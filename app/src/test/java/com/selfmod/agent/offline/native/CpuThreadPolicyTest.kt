package com.selfmod.agent.offline.native

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuThreadPolicyTest {

    @Test
    fun mnnOpenClHintIsSixtyEight() {
        assertEquals(68, CpuThreadPolicy.MNN_OPENCL_THREAD_NUM)
    }
}
