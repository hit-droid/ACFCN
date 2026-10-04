package com.selfmod.agent.offline.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class EngineLifecycleTest {

    private val commits = ArrayList<Boolean>()
    private val lc = EngineLifecycle(loadedSetter = { commits.add(it) })

    @Test
    fun rejectsSecondLoadWhileInFlight() {
        val t1 = lc.beginLoad()
        assertNotNull(t1)
        assertNull("加载进行中应拒绝第二个 load", lc.beginLoad())
        lc.endLoad(t1!!, ok = true, attempted = true)
        assertNotNull("结束后应放行下一个 load", lc.beginLoad())
    }

    @Test
    fun successfulLoadCommitsTrue() {
        val t = lc.beginLoad()!!
        lc.endLoad(t, ok = true, attempted = true)
        assertEquals(listOf(true), commits)
    }

    @Test
    fun failedNativeAttemptCommitsFalse() {
        val t = lc.beginLoad()!!
        lc.endLoad(t, ok = false, attempted = true)
        assertEquals(listOf(false), commits)
    }

    @Test
    fun earlyReturnDoesNotTouchLoadedFlag() {
        val t = lc.beginLoad()!!
        lc.endLoad(t, ok = false, attempted = false)
        assertFalse("早退（文件不存在等）不应改动 loaded", commits.isNotEmpty())
    }

    @Test
    fun unloadDuringLoadSuppressesStaleCommit() {
        val t = lc.beginLoad()!!
        // unload interleaves while the native load is still in flight
        lc.withUnload { /* nativeFree 等待 native 锁 */ }
        lc.endLoad(t, ok = true, attempted = true)
        // 只应看到 unload 的 false，load 的 true 被 stale token 拦下
        assertEquals(listOf(false), commits)
    }

    @Test
    fun unloadAlwaysClearsFlagEvenWhenBlockThrows() {
        lc.beginLoad()
        runCatching { lc.withUnload<Unit> { throw IllegalStateException("boom") } }
        assertEquals(listOf(false), commits)
    }

    @Test
    fun generationTicksAllowNextLoadToCommit() {
        val t1 = lc.beginLoad()!!
        lc.withUnload { }
        // 旧 load 的 finally 此时才跑到（nativeInit 已返回、卸载已完成）；
        // token 已过期，不提交，但会解除 in-flight 占用
        lc.endLoad(t1, ok = true, attempted = true)
        val t2 = lc.beginLoad()!!
        lc.endLoad(t2, ok = true, attempted = true)
        // false(unload) + true(new load)；旧 load 的 true 被拦下
        assertEquals(listOf(false, true), commits)
    }

    @Test
    fun loadRejectedWhileOldLoadStillWrappingUp() {
        val t1 = lc.beginLoad()!!
        lc.withUnload { }
        // 旧 load 的 endLoad 还没跑：新 load 仍应被拒，防止两个 native load 排队
        assertNull("收尾期的新 load 应被拒绝", lc.beginLoad())
        lc.endLoad(t1, ok = true, attempted = true)
        assertNotNull(lc.beginLoad())
    }
}
