package com.selfmod.agent.offline.native

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuThreadPolicyTest {

    @Test
    fun mnnOpenClHintIsSixtyEight() {
        assertEquals(68, CpuThreadPolicy.MNN_OPENCL_THREAD_NUM)
    }

    @Test
    fun bigClusterSizeDrivesThreadCount() {
        assertEquals(4, CpuThreadPolicy.resolveThreads(cores = 8, suggested = 4))
        assertEquals(2, CpuThreadPolicy.resolveThreads(cores = 8, suggested = 2))
        assertEquals(3, CpuThreadPolicy.resolveThreads(cores = 8, suggested = 3))
    }

    @Test
    fun detectionFailureFallsBackToTunedDefaultNotFloor() {
        // OEM blocks cpu_capacity: suggested=0 must yield 4, not 2.
        assertEquals(4, CpuThreadPolicy.resolveThreads(cores = 8, suggested = 0))
        assertEquals(4, CpuThreadPolicy.resolveThreads(cores = 6, suggested = 0))
    }

    @Test
    fun restrictedCpusetStillWins() {
        assertEquals(2, CpuThreadPolicy.resolveThreads(cores = 2, suggested = 0))
        assertEquals(2, CpuThreadPolicy.resolveThreads(cores = 2, suggested = 8))
    }

    @Test
    fun threadCountStaysWithinBounds() {
        assertEquals(4, CpuThreadPolicy.resolveThreads(cores = 16, suggested = 16))
        assertEquals(2, CpuThreadPolicy.resolveThreads(cores = 1, suggested = 1))
    }
}
