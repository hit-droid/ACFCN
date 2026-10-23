package com.selfmod.agent.container

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal POSIX `tar` reader — just enough to unpack an Alpine minirootfs
 * (regular files, directories, symlinks, GNU long names). Zero dependencies:
 * the app has no commons-compress and the container must be able to install
 * itself before any other tooling exists.
 *
 * Security posture: entries whose name escapes the destination directory
 * (`../`, absolute paths) are refused; device/FIFO/hardlink entries are
 * skipped. Permissions are applied best-effort (Android's app-private storage
 * ignores most of them anyway).
 */
class TarExtractor(
    private val dest: File,
    /**
     * Optional per-entry callback invoked with the normalized (relative) name
     * of every successfully written entry, in archive order. Used by
     * [PackManager] to record what a pack installed so it can be removed
     * again. Null (the default) keeps the rootfs install path allocation-free.
     */
    private val entrySink: ((String) -> Unit)? = null,
) {

    sealed class Result {
        data class Done(val fileCount: Int, val skipped: Int) : Result()
        data class Rejected(val entry: String, val reason: String) : Result()
    }

    /**
     * Unpack a (optionally gzip-compressed) tar stream into [dest].
     * The caller owns closing [input].
     */
    fun extract(input: InputStream): Result {
        dest.mkdirs()
        var files = 0
        var skipped = 0
        var pendingName: String? = null   // GNU LongName/LongLink payload
        val header = ByteArray(512)
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (!readFully(input, header)) {
                return Result.Done(files, skipped)   // clean EOF (all-zero block)
            }
            if (header.allZeros()) {
                // Two consecutive zero blocks end the archive; a single one may be
                // followed by padding. Treat one zero block as end-of-archive.
                return Result.Done(files, skipped)
            }
            if (!checksumOk(header)) {
                return Result.Done(files, skipped)   // torn stream: stop, keep what we have
            }
            val name = pendingName ?: readString(header, 0, 100)
            pendingName = null
            val size = readOctal(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = readString(header, 157, 100)
            val mode = readOctal(header, 100, 8)

            when (typeFlag) {
                'L' -> {   // GNU LongName: next header's name is this entry's payload
                    pendingName = readStringPayload(input, size, buf)
                    skipPadding(input, size)
                    continue
                }
                'K' -> {   // GNU LongLink — link targets we do not follow anyway
                    skipPadding(input, size)
                    continue
                }
            }

            // Fail closed on anything that escapes the destination: a rootfs
            // tarball with `../` or absolute-path entries is tampered or
            // malformed, and unpacking it partially is worse than not at all.
            if (isEscapingName(name)) {
                return Result.Rejected(name, "路径越出安装目录")
            }
            val entryName = normalize(name, linkName, typeFlag)
            if (entryName == null) {
                skipped++
                skipPadding(input, size)
                continue
            }
            val target = File(dest, entryName)
            if (!isInside(dest, target)) {
                return Result.Rejected(name, "路径越出安装目录")
            }
            when (typeFlag) {
                '0', ' ' -> {
                    target.parentFile?.mkdirs()
                    // The minirootfs reserves busybox applet names as symlinks
                    // (/usr/bin/strings -> /bin/busybox). A pack that ships the
                    // real binary must REPLACE that symlink — following it
                    // would write the payload into busybox itself and brick
                    // the container's shell.
                    if (isSymlink(target)) target.delete()
                    target.outputStream().use { out -> copyExact(input, out, size, buf) }
                    applyMode(target, mode)
                    files++
                    entrySink?.invoke(entryName)
                }
                '5' -> {
                    if (isSymlink(target)) target.delete()
                    target.mkdirs()
                    applyMode(target, mode)
                    files++
                    entrySink?.invoke(entryName)
                }
                '2' -> {   // symlink — Alpine's merged-/usr layout (/bin -> usr/bin) needs these
                    target.parentFile?.mkdirs()
                    val ok = runCatching {
                        // exists() is false for a dangling link; isSymlink()
                        // covers both so the replacement always lands.
                        if (target.exists() || isSymlink(target)) target.delete()
                        java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                    }.isSuccess
                    if (ok) { files++; entrySink?.invoke(entryName) } else skipped++
                }
                '1' -> skipped++   // hardlink — rootfs tarballs use plain files
                else -> {
                    skipPadding(input, size)
                    skipped++      // '3','4','6','7': devices, fifos — never unpack
                }
            }
            if (typeFlag == '0' || typeFlag == ' ') skipPadding(input, size)        }
    }

    // ---------------------------------------------------------------- helpers

    private fun ByteArray.allZeros(): Boolean = none { it != 0.toByte() }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var n = 0
        while (n < into.size) {
            val r = input.read(into, n, into.size - n)
            if (r < 0) return n == 0   // partial read = torn stream; zero at boundary = clean EOF
            n += r
        }
        return true
    }

    private fun copyExact(input: InputStream, out: OutputStream, size: Long, buf: ByteArray) {
        var left = size
        while (left > 0) {
            val want = if (left < buf.size) left.toInt() else buf.size
            val r = input.read(buf, 0, want)
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) {
            val skip = ByteArray(pad)
            readFully(input, skip)
        }
    }

    private fun readStringPayload(input: InputStream, size: Long, buf: ByteArray): String {
        val payload = ByteArray(size.toInt())
        readFully(input, payload)
        return payload.toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    /** ustar checksum: header bytes summed with the checksum field read as spaces. */
    private fun checksumOk(header: ByteArray): Boolean {
        var stored = 0L
        runCatching {
            // The field is "6 octal digits, NUL, space" per POSIX — plain trim()
            // leaves the NUL behind and every real tarball would fail to verify.
            stored = header.copyOfRange(148, 156).toString(Charsets.UTF_8)
                .trim('\u0000', ' ').toLongOrNull(8) ?: 0L
        }
        if (stored == 0L) return false
        var sum = 0L
        for (i in header.indices) sum += (if (i in 148..155) ' '.code.toByte() else header[i]).toLong() and 0xFF
        return sum == stored || (sum and 0xFFFF) == stored
    }

    private fun readString(header: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = offset + len
        while (end < limit && header[end] != 0.toByte()) end++
        return header.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, len: Int): Long {
        val s = readString(header, offset, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }

    private fun applyMode(file: File, mode: Long) {
        if (mode == 0L) return
        // 0o7777-ish; Android app storage ignores owner bits, apply the rest best-effort.
        val bits = (mode and 0x1FF).toInt()
        runCatching {
            val ok = file.setReadable(true) and file.setWritable(true)
            if (bits and 0x49 != 0 && ok) file.setExecutable(true)
        }
    }

    /** Names that try to escape the destination directory are refused outright. */
    private fun isEscapingName(name: String): Boolean {
        val n = name.removePrefix("./").trimEnd('/')
        return n.startsWith("/") || n.startsWith("../") || n == ".." || n.contains("/../")
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun normalize(name: String, linkName: String, typeFlag: Char): String? {
        val n = name.removePrefix("./").trimEnd('/')
        if (n.isEmpty()) return null
        return n
    }

    private fun isInside(root: File, target: File): Boolean =
        runCatching {
            val rootPath = root.canonicalPath
            val targetPath = target.canonicalPath
            targetPath == rootPath || targetPath.startsWith("$rootPath/")
        }.getOrDefault(false)
}
