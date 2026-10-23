package com.selfmod.agent.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * Exercises [PackManager] end-to-end on the JVM: the tarball is built in-test,
 * the download is a seam that copies bytes (or corrupts them), and the rootfs
 * is a temp dir with a fake `.installed` marker — no Android, no network.
 */
class PackManagerTest {

    private lateinit var filesDir: File
    private lateinit var rootfsDir: File
    private lateinit var marker: File

    private val downloads = AtomicInteger()

    private fun fixturePack(
        id: String = "fixture",
        sha256: String? = null,
        provides: List<String> = listOf("fixture-bin"),
    ) = ResourcePack(
        id = id,
        displayName = "Fixture ($id)",
        url = "https://example.invalid/$id-aarch64.tar.gz",
        sha256 = sha256,
        sizeBytes = 0,
        provides = provides,
    )

    @Before
    fun setUp() {
        filesDir = File(System.getProperty("java.io.tmpdir"), "acfcn-pack-${System.nanoTime()}")
        rootfsDir = ContainerPaths.rootfsDir(filesDir)
        marker = ContainerPaths.installedMarker(filesDir)
        downloads.set(0)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    // ------------------------------------------------------------ tar builder

    private class Entry(
        val name: String,
        val typeFlag: Char = '0',
        val content: String = "",
    )

    private fun tarGz(vararg entries: Entry): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach { e ->
            val content = e.content.toByteArray()
            tar.write(header(e.name, e.typeFlag, content.size.toLong()))
            tar.write(content)
            tar.write(padding(content.size))
        }
        tar.write(ByteArray(1024))   // end-of-archive
        return ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(tar.toByteArray()) }
        }.toByteArray()
    }

    private fun header(name: String, typeFlag: Char, size: Long): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0, 0, minOf(name.length, 100))
        "0000644\u0000".toByteArray().copyInto(h, 100)
        size.toString(8).padStart(11, '0').toByteArray().copyInto(h, 124)
        "ustar\u0000".toByteArray().copyInto(h, 257)
        "00".toByteArray().copyInto(h, 263)
        h[156] = typeFlag.code.toByte()
        for (i in 148..155) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xFF)
        "%06o\u0000 ".format(sum).toByteArray().copyInto(h, 148)
        return h
    }

    private fun padding(size: Int): ByteArray = ByteArray((512 - (size % 512)) % 512)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- helpers

    /** Writes the container's installed-rootfs marker with the given sha. */
    private fun writeMarker(sha: String) {
        marker.parentFile?.mkdirs()
        marker.writeText("rootfs-sha256=$sha version=1\n")
    }

    private fun stateFile(): File = File(filesDir, "linux/packs/state.json")

    private fun packManager(
        vararg packs: ResourcePack,
        bytes: ByteArray? = null,
        ensureRootfs: Boolean = true,
    ) = PackManager(
        filesDir = filesDir,
        catalog = packs.toList(),
        download = { _, dest, _ ->
            downloads.incrementAndGet()
            if (bytes != null) dest.writeBytes(bytes)
        },
        ensureRootfs = { ensureRootfs },
    )

    // ----------------------------------------------------------------- tests

    @Test
    fun `install extracts, records state and reports the post-install hook`() {
        val tarball = tarGz(
            Entry("usr/", typeFlag = '5'),
            Entry("usr/bin/fixture-bin", content = "#!/bin/sh\necho fixture\n"),
            Entry("etc/fixture.conf", content = "key=value\n"),
            Entry("usr/lib/fixture.post-install.sh", content = "#!/bin/sh\nldconfig\n"),
        )
        val sha = sha256Hex(tarball)
        val pack = fixturePack(sha256 = sha)
        writeMarker("rootfs-sha-1")

        val pm = packManager(pack, bytes = tarball)
        val result = pm.install("fixture")

        assertTrue("expected Done, got $result", result is PackManager.Result.Done)
        result as PackManager.Result.Done
        assertEquals(4, result.fileCount)
        assertEquals(tarball.size.toLong(), result.downloadedBytes)
        assertEquals("/usr/lib/fixture.post-install.sh", result.postInstallScript)

        // Files really landed in the rootfs.
        assertEquals("#!/bin/sh\necho fixture\n", File(rootfsDir, "usr/bin/fixture-bin").readText())
        assertEquals("key=value\n", File(rootfsDir, "etc/fixture.conf").readText())

        // State persisted and reflected by list().
        assertTrue(stateFile().exists())
        val status = pm.list().single()
        assertTrue(status.isInstalled)
        assertNotNull(status.installed)
        assertEquals(sha, status.installed!!.sha256)
        assertEquals(4, status.installed!!.files.size)
        assertTrue(pm.isInstalled("fixture"))
    }

    @Test
    fun `install replaces a busybox applet symlink without corrupting busybox`() {
        // Real device layout: minirootfs ships /usr/bin/strings as a busybox
        // applet symlink; the gcc pack brings the real binary at the same path.
        val rootBin = File(rootfsDir, "bin").apply { mkdirs() }
        File(rootBin, "busybox").writeText("BUSYBOX-BINARY")
        File(rootfsDir, "usr/bin").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(rootfsDir, "usr/bin/strings").toPath(),
            File(rootBin, "busybox").toPath(),
        )

        val tarball = tarGz(Entry("usr/bin/strings", content = "REAL-STRINGS"))
        val pm = packManager(fixturePack(sha256 = sha256Hex(tarball)), bytes = tarball)
        writeMarker("rootfs-sha-1")

        assertTrue(pm.install("fixture") is PackManager.Result.Done)
        val replaced = File(rootfsDir, "usr/bin/strings")
        assertFalse(java.nio.file.Files.isSymbolicLink(replaced.toPath()))
        assertEquals("REAL-STRINGS", replaced.readText())
        // busybox itself must be untouched — writing through the symlink
        // would brick the container's shell.
        assertEquals("BUSYBOX-BINARY", File(rootBin, "busybox").readText())
    }

    @Test
    fun `install fails closed on sha mismatch and extracts nothing`() {        val tarball = tarGz(Entry("usr/bin/fixture-bin", content = "ok"))
        val sha = sha256Hex(tarball + byteArrayOf(0))   // pinned sha ≠ actual bytes
        writeMarker("rootfs-sha-1")

        val pm = packManager(fixturePack(sha256 = sha), bytes = tarball)
        val result = pm.install("fixture")

        assertTrue("expected Failed, got $result", result is PackManager.Result.Failed)
        assertTrue((result as PackManager.Result.Failed).reason.contains("sha256"))
        assertFalse(File(rootfsDir, "usr/bin/fixture-bin").exists())
        assertFalse(stateFile().exists())
        assertFalse(pm.isInstalled("fixture"))
    }

    @Test
    fun `install refuses a pack that is not published yet`() {
        writeMarker("rootfs-sha-1")
        val pm = packManager(fixturePack(sha256 = null), bytes = tarGz())
        val result = pm.install("fixture")
        assertTrue("expected Failed, got $result", result is PackManager.Result.Failed)
        assertTrue((result as PackManager.Result.Failed).reason.contains("尚未发布"))
        assertEquals(0, downloads.get())
    }

    @Test
    fun `install refuses an unknown pack id`() {
        writeMarker("rootfs-sha-1")
        val pm = packManager(fixturePack(), bytes = tarGz())
        val result = pm.install("nope")
        assertTrue(result is PackManager.Result.Failed)
        assertTrue((result as PackManager.Result.Failed).reason.startsWith("未知资源包"))
    }

    @Test
    fun `install refuses when the container rootfs is unavailable`() {
        val tarball = tarGz(Entry("usr/bin/fixture-bin", content = "ok"))
        writeMarker("rootfs-sha-1")
        val pm = packManager(fixturePack(sha256 = sha256Hex(tarball)), bytes = tarball, ensureRootfs = false)
        val result = pm.install("fixture")
        assertTrue(result is PackManager.Result.Failed)
        assertTrue((result as PackManager.Result.Failed).reason.contains("rootfs 不可用"))
        assertEquals(0, downloads.get())
    }

    @Test
    fun `re-install with the same pinned sha is a no-op that skips the download`() {
        val tarball = tarGz(Entry("usr/bin/fixture-bin", content = "ok"))
        val pm = packManager(fixturePack(sha256 = sha256Hex(tarball)), bytes = tarball)
        writeMarker("rootfs-sha-1")

        assertTrue(pm.install("fixture") is PackManager.Result.Done)
        assertEquals(1, downloads.get())

        val again = pm.install("fixture")
        assertTrue("expected Done, got $again", again is PackManager.Result.Done)
        assertEquals(1, downloads.get())   // no second download
    }

    @Test
    fun `records are dropped when the rootfs is re-installed under a new sha`() {
        val tarball = tarGz(Entry("usr/bin/fixture-bin", content = "ok"))
        val pm = packManager(fixturePack(sha256 = sha256Hex(tarball)), bytes = tarball)
        writeMarker("rootfs-sha-1")
        assertTrue(pm.install("fixture") is PackManager.Result.Done)
        assertTrue(pm.isInstalled("fixture"))

        // App update → rootfs asset hash changed → fresh rootfs, our files gone.
        writeMarker("rootfs-sha-2")
        assertFalse("stale records must not survive a rootfs reinstall", pm.isInstalled("fixture"))
        assertTrue(pm.list().single().installed == null)
    }

    @Test
    fun `remove keeps files claimed by another installed pack and prunes empty dirs`() {
        val packA = tarGz(
            Entry("usr/bin/a", content = "A"),
            Entry("usr/lib/libshared.so", content = "shared"),
            Entry("opt/only-a/file", content = "A-only"),
        )
        val packB = tarGz(
            Entry("usr/bin/b", content = "B"),
            Entry("usr/lib/libshared.so", content = "shared"),
        )
        val a = fixturePack(id = "pack-a", sha256 = sha256Hex(packA))
        val b = fixturePack(id = "pack-b", sha256 = sha256Hex(packB))
        writeMarker("rootfs-sha-1")

        // Each pack gets its own tarball via a per-id lookup in the seam.
        val pmPerId = PackManager(
            filesDir = filesDir,
            catalog = listOf(a, b),
            download = { url, dest, _ ->
                downloads.incrementAndGet()
                dest.writeBytes(if (url.contains("pack-a")) packA else packB)
            },
            ensureRootfs = { true },
        )
        pmPerId.install("pack-a")
        pmPerId.install("pack-b")
        assertEquals(2, downloads.get())

        val removed = pmPerId.remove("pack-a")
        assertTrue("expected Done, got $removed", removed is PackManager.Result.Done)
        removed as PackManager.Result.Done
        assertEquals(2, removed.fileCount)   // usr/bin/a + opt/only-a/file

        // Exclusive file gone, shared file kept, sibling pack's file kept.
        assertFalse(File(rootfsDir, "usr/bin/a").exists())
        assertTrue(File(rootfsDir, "usr/lib/libshared.so").exists())
        assertTrue(File(rootfsDir, "usr/bin/b").exists())

        // Directories left empty by the removal are pruned.
        assertFalse("opt/ should have been pruned", File(rootfsDir, "opt").exists())

        // State: pack-a record dropped, pack-b intact.
        assertFalse(pmPerId.isInstalled("pack-a"))
        assertTrue(pmPerId.isInstalled("pack-b"))
        assertNull(stateFile().let { org.json.JSONObject(it.readText()).optJSONObject("packs")?.optJSONObject("pack-a") })
    }

    @Test
    fun `remove of a pack that was never installed fails`() {
        writeMarker("rootfs-sha-1")
        val pm = packManager(fixturePack(), bytes = tarGz())
        val result = pm.remove("fixture")
        assertTrue(result is PackManager.Result.Failed)
        assertTrue((result as PackManager.Result.Failed).reason.contains("未安装"))
    }

    @Test
    fun `catalog aliases resolve to the canonical pack`() {
        val tarball = tarGz(Entry("usr/bin/fixture-bin", content = "ok"))
        val pm = packManager(fixturePack(id = "python3", sha256 = sha256Hex(tarball)), bytes = tarball)
        writeMarker("rootfs-sha-1")
        assertTrue(pm.install("py") is PackManager.Result.Done)
        assertTrue(pm.isInstalled("python3"))
    }
}
