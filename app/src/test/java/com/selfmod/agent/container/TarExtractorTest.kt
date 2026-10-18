package com.selfmod.agent.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The extractor unpacks a 3.9MB rootfs the app ships as its foundation — and
 * it is the only thing standing between a tampered asset and the app's private
 * storage. These tests build tar streams byte-by-byte (no commons-compress on
 * the classpath by design) and pin the security-relevant behaviour.
 */
class TarExtractorTest {

    private lateinit var dest: File

    @Before fun setUp() { dest = File(System.getProperty("java.io.tmpdir"), "acfcn-tar-${System.nanoTime()}") }
    @After fun tearDown() { dest.deleteRecursively() }

    // ------------------------------------------------------------ tar builder

    private class Entry(
        val name: String,
        val typeFlag: Char = '0',
        val content: String = "",
        val linkName: String = "",
        val longName: Boolean = false,
    )

    /** Serializes [entries] into a ustar stream (GNU LongName where asked). */
    private fun tar(vararg entries: Entry): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        entries.forEach { e ->
            if (e.longName) {
                // GNU LongName: an 'L' entry whose payload is the real name.
                val nameBytes = e.name.toByteArray()
                out.write(header("././@LongLink", 'L', nameBytes.size.toLong()))
                out.write(nameBytes)
                out.write(padding(nameBytes.size))
            }
            val contentBytes = e.content.toByteArray()
            out.write(header(e.name, e.typeFlag, contentBytes.size.toLong(), e.linkName))
            out.write(contentBytes)
            out.write(padding(contentBytes.size))
        }
        out.write(ByteArray(1024))   // end-of-archive
        return out.toByteArray()
    }

    private fun header(name: String, typeFlag: Char, size: Long, linkName: String = ""): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0, 0, minOf(name.length, 100))
        linkName.toByteArray().copyInto(h, 157, 0, minOf(linkName.length, 100))
        "0000644\u0000".toByteArray().copyInto(h, 100)
        // size as 11 octal digits + NUL
        size.toString(8).padStart(11, '0').toByteArray().copyInto(h, 124)
        "ustar\u0000".toByteArray().copyInto(h, 257)
        "00".toByteArray().copyInto(h, 263)
        h[156] = typeFlag.code.toByte()
        // checksum: header with chksum field as spaces
        for (i in 148..155) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xFF)
        "%06o\u0000 ".format(sum).toByteArray().copyInto(h, 148)
        return h
    }

    private fun padding(size: Int): ByteArray {
        val pad = (512 - (size % 512)) % 512
        return ByteArray(pad)
    }

    private fun extract(vararg entries: Entry): TarExtractor.Result =
        TarExtractor(dest).extract(ByteArrayInputStream(tar(*entries)))

    // ----------------------------------------------------------------- tests

    @Test
    fun `plain file and directory round-trip`() {
        val r = extract(
            Entry("bin/", typeFlag = '5'),
            Entry("bin/hello", content = "#!/bin/sh\necho hi\n"),
        )
        assertTrue(r is TarExtractor.Result.Done)
        assertEquals(2, (r as TarExtractor.Result.Done).fileCount)
        assertTrue(File(dest, "bin").isDirectory)
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "bin/hello").readText())
    }

    @Test
    fun `dot-slash prefixed names land correctly`() {
        val r = extract(Entry("./etc/hostname", content = "localhost\n"))
        assertTrue(r is TarExtractor.Result.Done)
        assertEquals("localhost\n", File(dest, "etc/hostname").readText())
    }

    @Test
    fun `parent-directory escape is refused`() {
        val r = extract(Entry("evil.txt", content = "pwned"), Entry("../outside.txt", content = "pwned"))
        assertTrue("expected Rejected, got $r", r is TarExtractor.Result.Rejected)
        assertEquals("../outside.txt", (r as TarExtractor.Result.Rejected).entry)
        assertFalse(File(dest.parentFile, "outside.txt").exists())
    }

    @Test
    fun `absolute path entries are rejected, not unpacked`() {
        val r = extract(Entry("/etc/passwd", content = "root:x:0:0:"))
        assertTrue("expected Rejected, got $r", r is TarExtractor.Result.Rejected)
        assertFalse(File(dest, "etc/passwd").exists())
    }

    @Test
    fun `symlinks are created`() {
        val r = extract(Entry("bin", typeFlag = '2', linkName = "usr/bin"))
        assertTrue(r is TarExtractor.Result.Done)
        val link = File(dest, "bin")
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertEquals("usr/bin", java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `GNU LongName entries unpack under the full name`() {
        val long = "a".repeat(150) + ".txt"
        val r = extract(Entry(long, content = "long-name-ok", longName = true))
        assertTrue(r is TarExtractor.Result.Done)
        assertEquals("long-name-ok", File(dest, long).readText())
    }

    @Test
    fun `device and fifo entries are skipped`() {
        val r = extract(
            Entry("dev/null", typeFlag = '3'),
            Entry("dev/pipe", typeFlag = '6'),
        )
        assertTrue(r is TarExtractor.Result.Done)
        assertEquals(2, (r as TarExtractor.Result.Done).skipped)
        assertFalse(File(dest, "dev/null").exists())
    }

    @Test
    fun `empty stream is a clean no-op`() {
        val r = TarExtractor(dest).extract(ByteArrayInputStream(ByteArray(0)))
        assertTrue(r is TarExtractor.Result.Done)
        assertEquals(0, (r as TarExtractor.Result.Done).fileCount)
    }

    @Test
    fun `torn stream stops early but keeps unpacked files`() {
        val full = tar(Entry("ok.txt", content = "kept"))
        val torn = full.copyOfRange(0, full.size - 600)   // chop the tail
        val r = TarExtractor(dest).extract(ByteArrayInputStream(torn))
        assertTrue(r is TarExtractor.Result.Done)
        assertEquals("kept", File(dest, "ok.txt").readText())
    }
}
