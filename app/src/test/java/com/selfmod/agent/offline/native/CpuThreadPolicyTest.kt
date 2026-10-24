package com.selfmod.agent.offline.native

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuThreadPolicyTest {

    @Test
    fun llamaCpuThreadsClampsToBigCoreWindow() {
        assertEquals(2, CpuThreadPolicy.llamaCpuThreads(1))
        assertEquals(4, CpuThreadPolicy.llamaCpuThreads(4))
        assertEquals(6, CpuThreadPolicy.llamaCpuThreads(8))
        assertEquals(6, CpuThreadPolicy.llamaCpuThreads(12))
    }

    @Test
    fun gpuOffloadCapsCpuWorkersAtTwo() {
        assertEquals(2, CpuThreadPolicy.llamaEffectiveThreads(requested = 6, gpuLayers = 99))
        assertEquals(2, CpuThreadPolicy.llamaEffectiveThreads(requested = 4, gpuLayers = 27))
        assertEquals(1, CpuThreadPolicy.llamaEffectiveThreads(requested = 1, gpuLayers = 99))
    }

    @Test
    fun cpuOnlyKeepsRequestedWorkers() {
        assertEquals(6, CpuThreadPolicy.llamaEffectiveThreads(requested = 6, gpuLayers = 0))
        assertEquals(4, CpuThreadPolicy.llamaEffectiveThreads(requested = 4, gpuLayers = 0))
    }

    @Test
    fun mnnOpenClHintIsSixtyEight() {
        assertEquals(68, CpuThreadPolicy.MNN_OPENCL_THREAD_NUM)
    }
}
