package com.selfmod.agent.store

import android.content.Context
import com.selfmod.agent.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Multi-session store behaviour: isolation, ordering, deletion, legacy
 * migration, and path-safety of session ids.
 *
 * Robolectric (real filesDir) — excluded from local runs by default like
 * AgentCoreTest; CI runs the full suite.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {

    private fun store(): SessionStore = SessionStore(RuntimeEnvironment.getApplication() as Context)

    private fun sessionsDir(): File =
        File((RuntimeEnvironment.getApplication() as Context).filesDir, "sessions")

    @Test
    fun savesAndLoadsPerSession() {
        val s = store()
        val a = s.newSessionId()
        val b = s.newSessionId()
        s.saveSession(a, listOf(ChatMessage("user", "会话A的问题")))
        s.saveSession(b, listOf(ChatMessage("user", "会话B的问题"), ChatMessage("assistant", "B的答复")))

        assertEquals(listOf("user", "assistant"), s.loadSession(b).map { it.role })
        assertEquals("会话B的问题", s.loadSession(b).first().content)
        assertEquals(1, s.loadSession(a).size)
    }

    @Test
    fun listsNewestFirstWithDerivedTitles() {
        val s = store()
        val a = s.newSessionId()
        val b = s.newSessionId()
        s.saveSession(a, listOf(ChatMessage("user", "旧会话")))
        Thread.sleep(5) // updatedAt is millisecond-granular; make the order unambiguous
        s.saveSession(b, listOf(ChatMessage("user", "新会话")))

        val list = s.listSessions()
        assertEquals(2, list.size)
        assertEquals("新会话", list[0].title)
        assertEquals("旧会话", list[1].title)
        assertEquals(1, list[0].messageCount)
    }

    @Test
    fun deleteRemovesFileAndIndexRow() {
        val s = store()
        val a = s.newSessionId()
        s.saveSession(a, listOf(ChatMessage("user", "待删除")))
        assertTrue(File(sessionsDir(), "$a.json").exists())

        s.deleteSession(a)

        assertFalse(File(sessionsDir(), "$a.json").exists())
        assertTrue(s.listSessions().isEmpty())
        assertTrue(s.loadSession(a).isEmpty())
    }

    @Test
    fun deleteAllWipesEverything() {
        val s = store()
        s.saveSession(s.newSessionId(), listOf(ChatMessage("user", "一")))
        s.saveSession(s.newSessionId(), listOf(ChatMessage("user", "二")))

        s.deleteAll()

        assertTrue(s.listSessions().isEmpty())
        assertTrue(sessionsDir().listFiles()?.none { it.name.endsWith(".json") } ?: true)
    }

    @Test
    fun rejectsUnsafeIds() {
        val s = store()
        // A session id becomes a filename; path traversal must not escape the dir.
        s.saveSession("../../escape", listOf(ChatMessage("user", "x")))
        assertTrue(s.listSessions().isEmpty())
        assertTrue(s.loadSession("../../escape").isEmpty())
        assertFalse(File(sessionsDir().parentFile, "escape.json").exists())
    }

    @Test
    fun unknownSessionLoadsEmpty() {
        val s = store()
        assertTrue(s.loadSession("does-not-exist").isEmpty())
        assertFalse(s.sessionFileExists("does-not-exist"))
    }

    @Test
    fun migratesLegacyHistoryOnce() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        ctx.getSharedPreferences("selfmod_session", Context.MODE_PRIVATE)
            .edit()
            .putString("history_v1", SessionCodec.encode(listOf(ChatMessage("user", "老版本唯一会话"))))
            .apply()

        val s = store() // migration runs in init

        val list = s.listSessions()
        assertEquals(1, list.size)
        assertEquals(SessionStore.LEGACY_SESSION_ID, list[0].id)
        assertEquals("老版本唯一会话", list[0].title)
        assertEquals(1, s.loadSession(SessionStore.LEGACY_SESSION_ID).size)

        // Second store instance must not duplicate it.
        val s2 = store()
        assertEquals(1, s2.listSessions().size)
    }

    @Test
    fun deriveTitleFromFirstUserMessage() {
        val title = SessionStore.deriveTitle(
            listOf(ChatMessage("system", "s"), ChatMessage("user", "第一行\n第二行")),
        )
        assertEquals("第一行 第二行", title)
    }

    @Test
    fun deriveTitleFallsBackWhenNoUserMessage() {
        assertEquals("新会话", SessionStore.deriveTitle(listOf(ChatMessage("assistant", "hi"))))
        assertEquals("新会话", SessionStore.deriveTitle(emptyList()))
    }
}
