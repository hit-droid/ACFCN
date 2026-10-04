package com.selfmod.agent.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodeRepositoryPluginIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = CodeRepository(
        scriptsDir = tmp.newFolder("scripts"),
        pluginsDir = tmp.newFolder("plugins"),
        versionsDir = tmp.newFolder("versions"),
    )

    @Test
    fun installPluginRecordsDigestAndVerifyPasses() {
        val r = repo()
        val dex = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val ok = r.installPlugin("greet", dex, "com.example.Greet")

        assertTrue(ok)
        assertNotNull(r.pluginDigest("greet"))
        assertNull(r.verifyPluginIntegrity("greet"))
    }

    @Test
    fun tamperedDexFailsIntegrityCheck() {
        val r = repo()
        val dex = ByteArray(256) { it.toByte() }
        r.installPlugin("calc", dex, "com.example.Calc")

        // Mutate the on-disk dex after install.
        val dexFile = File(r.pluginsDir(), "calc.dex")
        dexFile.writeBytes(ByteArray(256) { 0xEE.toByte() })

        val issue = r.verifyPluginIntegrity("calc")
        assertNotNull(issue)
        assertTrue(issue!!.contains("integrity check failed"))
    }

    @Test
    fun missingDigestIsReported() {
        val r = repo()
        // Write a dex without installing (no digest recorded).
        File(r.pluginsDir(), "orphan.dex").writeBytes(byteArrayOf(1, 2, 3))

        val issue = r.verifyPluginIntegrity("orphan")
        assertNotNull(issue)
        assertTrue(issue!!.contains("no recorded integrity digest"))
    }

    @Test
    fun missingDexIsReported() {
        val r = repo()

        val issue = r.verifyPluginIntegrity("ghost")
        assertNotNull(issue)
        assertTrue(issue!!.contains("dex missing"))
    }

    @Test
    fun deletePluginRemovesDigest() {
        val r = repo()
        r.installPlugin("bye", byteArrayOf(9, 8, 7), "com.example.Bye")

        assertTrue(r.deletePlugin("bye"))

        assertNull(r.pluginDigest("bye"))
        assertNull(r.pluginEntry("bye"))
        assertTrue(!File(r.pluginsDir(), "bye.dex").exists())
    }

    @Test
    fun reinstallAfterTamperRestoresIntegrity() {
        val r = repo()
        val original = byteArrayOf(1, 2, 3, 4, 5, 6)
        r.installPlugin("fix", original, "com.example.Fix")

        // Tamper, verify failure, then reinstall the original bytes.
        File(r.pluginsDir(), "fix.dex").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()))
        assertNotNull(r.verifyPluginIntegrity("fix"))

        r.installPlugin("fix", original, "com.example.Fix")
        assertNull(r.verifyPluginIntegrity("fix"))
        assertEquals("com.example.Fix", r.pluginEntry("fix"))
    }
}
