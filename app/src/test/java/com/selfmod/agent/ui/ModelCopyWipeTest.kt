package com.selfmod.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L9: the wipe rules that used to be buried inside a ViewModel that cannot be
 * constructed off-device.
 */
class ModelCopyWipeTest {

    @Test
    fun refusesWhileEngineIsLoading() {
        assertEquals(
            "端侧模型正在加载，请等待完成后再删除副本",
            ModelCopyWipe.refusal(engineBusy = true, generating = false),
        )
    }

    @Test
    fun refusesWhileAgentIsGenerating() {
        assertEquals(
            "智能体正在使用端侧模型，请先停止对话再删除副本",
            ModelCopyWipe.refusal(engineBusy = false, generating = true),
        )
    }

    @Test
    fun loadRefusalWinsOverGeneration() {
        // A half-written copy is the more dangerous of the two states.
        assertTrue(
            ModelCopyWipe.refusal(engineBusy = true, generating = true)!!
                .contains("正在加载"),
        )
    }

    @Test
    fun allowsWipeWhenIdle() {
        assertNull(ModelCopyWipe.refusal(engineBusy = false, generating = false))
    }

    @Test
    fun engineStatusNoLongerClaimsALoadedModel() {
        // The bug: _engineReady flipped to false while the status line still read
        // "已加载：xxx", so the panel contradicted itself.
        assertTrue(ModelCopyWipe.AFTER_ENGINE.contains("已卸载"))
        assertTrue(!ModelCopyWipe.AFTER_ENGINE.startsWith("已加载"))
    }

    @Test
    fun messageReportsFreedBytesAndKeepsRegistryHint() {
        // human() is binary-based: 3 GiB, not 3 GB decimal.
        val withCopies = ModelCopyWipe.message(freed = 3L * 1_073_741_824L, copied = 2)
        assertTrue(withCopies.contains("3.00 GB"))
        assertTrue(withCopies.contains("2 个模型"))

        val none = ModelCopyWipe.message(freed = 2L * 1_048_576L, copied = 0)
        assertTrue(none.contains("2.0 MB"))
        assertTrue(!none.contains("个模型"))
    }

    @Test
    fun failureMessageKeepsTheReason() {
        assertEquals("删除模型副本失败：ENOSPC", ModelCopyWipe.failure("ENOSPC"))
        assertTrue(ModelCopyWipe.failure(null).startsWith("删除模型副本失败："))
    }
}
