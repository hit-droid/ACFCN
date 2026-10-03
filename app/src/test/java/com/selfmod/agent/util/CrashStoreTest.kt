package com.selfmod.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

class CrashStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveWritesPrimaryAndReadReturnsIt() {
        val primary = tmp.newFolder("files")
        val store = newStore(primary)
        assertTrue(store.save(RuntimeException("boom")))
        val report = store.read()
        assertTrue(report!!.contains("boom"))
        assertTrue(report.contains("ACFCN crash report"))
        assertTrue(File(primary, CrashStore.FILE_NAME).isFile)
        assertFalse(File(primary, CrashStore.TMP_NAME).exists())
    }

    @Test
    fun saveFallsBackWhenPrimaryIsNotADirectory() {
        val primary = tmp.newFile("files-not-dir")
        val fallback = tmp.newFolder("cache")
        val logs = ArrayList<String>()
        val store = newStore(primary, listOf(fallback), logs)
        assertTrue(store.save(IllegalStateException("disk-full-primary")))
        assertNull(File(fallback.parentFile, "files-not-dir/${CrashStore.FILE_NAME}").takeIf { it.isFile })
        val report = store.read()
        assertTrue(report!!.contains("disk-full-primary"))
        assertTrue(File(fallback, CrashStore.FILE_NAME).isFile)
        assertTrue(logs.any { it.contains("write failed") })
    }

    @Test
    fun saveReturnsFalseWhenEveryLocationFails() {
        val primary = tmp.newFile("bad-primary")
        val fallback = tmp.newFile("bad-fallback")
        val logs = ArrayList<String>()
        val store = newStore(primary, listOf(fallback), logs)
        assertFalse(store.save(RuntimeException("nowhere")))
        assertNull(store.read())
        assertTrue(logs.any { it.contains("all 2 locations failed") })
    }

    @Test
    fun readPrefersPrimaryOverFallback() {
        val primary = tmp.newFolder("files")
        val fallback = tmp.newFolder("cache")
        File(primary, CrashStore.FILE_NAME).writeText("from-primary")
        File(fallback, CrashStore.FILE_NAME).writeText("from-fallback")
        val store = newStore(primary, listOf(fallback))
        assertEquals("from-primary", store.read())
    }

    @Test
    fun readUsesFallbackWhenPrimaryMissing() {
        val primary = tmp.newFolder("files")
        val fallback = tmp.newFolder("cache")
        File(fallback, CrashStore.FILE_NAME).writeText("only-cache")
        val store = newStore(primary, listOf(fallback))
        assertEquals("only-cache", store.read())
    }

    @Test
    fun clearRemovesPrimaryAndFallback() {
        val primary = tmp.newFolder("files")
        val fallback = tmp.newFolder("cache")
        File(primary, CrashStore.FILE_NAME).writeText("a")
        File(fallback, CrashStore.FILE_NAME).writeText("b")
        File(primary, CrashStore.TMP_NAME).writeText("tmp")
        val store = newStore(primary, listOf(fallback))
        store.clear()
        assertFalse(File(primary, CrashStore.FILE_NAME).exists())
        assertFalse(File(fallback, CrashStore.FILE_NAME).exists())
        assertFalse(File(primary, CrashStore.TMP_NAME).exists())
        assertNull(store.read())
    }

    private fun newStore(
        primary: File,
        fallbacks: List<File> = emptyList(),
        logs: MutableList<String>? = null,
    ): CrashStore = CrashStore(
        primaryDir = primary,
        fallbackDirs = fallbacks,
        deviceInfo = { "device: test" },
        clock = { Date(0) },
        logger = { msg, _ -> logs?.add(msg) },
    )
}
