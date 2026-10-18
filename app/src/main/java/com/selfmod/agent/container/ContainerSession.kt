package com.selfmod.agent.container

import com.selfmod.agent.util.Diagnostics
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One command execution inside the container. The session owns the whole
 * process lifecycle: spawn, pipe draining (capped), timeout watchdog, cancel.
 *
 * ```
 * proot -R <rootfs> -b /proc -b /dev -b /sys -b <workspace>:/workspace
 *       --kill-on-exit --cwd=<cwd> /bin/sh -c <command>
 * ```
 *
 * `prootPath` points at `libproot.so` and `loaderPath` at `libproot-loader.so`,
 * both inside `ApplicationInfo.nativeLibraryDir` (see [ContainerPaths]).
 *
 * Not thread-safe; create one per execution.
 */
class ContainerSession(
    private val prootPath: File,
    private val rootfsDir: File,
    private val workspaceHostDir: File,
    private val loaderPath: File? = null,
) {

    data class Result(
        val status: Status,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val durationMs: Long,
    ) {
        enum class Status { OK, TIMEOUT, CANCELLED, SPAWN_FAILED }
    }

    @Volatile private var process: Process? = null
    private val timedOut = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /**
     * Run [command] through `/bin/sh -c` and block until it exits, times out or
     * is cancelled. Callers must be on a background thread.
     *
     * @param cwd      working directory inside the container (default `/workspace`)
     * @param timeoutMs hard timeout, clamped to [TIMEOUT_MIN_MS]..[TIMEOUT_MAX_MS]
     * @param env      extra environment variables layered over the container defaults
     */
    fun exec(
        command: String,
        cwd: String = DEFAULT_CWD,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap(),
    ): Result {
        val t0 = System.currentTimeMillis()
        val effectiveTimeout = timeoutMs.coerceIn(TIMEOUT_MIN_MS, TIMEOUT_MAX_MS)
        val cmd = command.trim()
        if (cmd.isEmpty()) {
            return Result(Result.Status.SPAWN_FAILED, -1, "", "command is empty", false, 0)
        }
        val pb = ProcessBuilder(
            prootPath.absolutePath,
            "-R", rootfsDir.absolutePath,
            "-b", "/proc",
            "-b", "/dev",
            "-b", "/sys",
            "-b", "${workspaceHostDir.absolutePath}:/workspace",
            "--kill-on-exit",
            "--cwd=$cwd",
            "/bin/sh", "-c", cmd,
        )
        pb.redirectErrorStream(false)
        val e = pb.environment()
        e["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        e["HOME"] = "/root"
        e["TERM"] = "dumb"
        e["LANG"] = "C.UTF-8"
        if (loaderPath != null) e["PROOT_LOADER"] = loaderPath.absolutePath
        env.forEach { (k, v) -> if (k.isNotBlank() && !k.contains('=') && k != "PROOT_LOADER") e[k] = v }

        val proc = try {
            pb.start().also { process = it }
        } catch (t: Throwable) {
            Diagnostics.log(TAG, "spawn failed: ${t.message}")
            return Result(
                Result.Status.SPAWN_FAILED, -1, "",
                t.message ?: t.toString(), false, System.currentTimeMillis() - t0,
            )
        }

        val stdoutBuf = CappedBuffer(STDOUT_CAP_BYTES)
        val stderrBuf = CappedBuffer(STDERR_CAP_BYTES)
        val outThread = Thread { drain(proc.inputStream, stdoutBuf) }.apply { isDaemon = true; start() }
        val errThread = Thread { drain(proc.errorStream, stderrBuf) }.apply { isDaemon = true; start() }

        val watchdog = Thread {
            runCatching { Thread.sleep(effectiveTimeout) }
            if (process?.isAlive == true) {
                timedOut.set(true)
                destroy(proc, gracefulFirst = true)
            }
        }.apply { isDaemon = true; start() }

        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        watchdog.interrupt()
        outThread.join(2_000)
        errThread.join(2_000)

        val status = when {
            cancelled.get() -> Result.Status.CANCELLED
            timedOut.get() -> Result.Status.TIMEOUT
            else -> Result.Status.OK
        }
        val duration = System.currentTimeMillis() - t0
        Diagnostics.log(TAG, "exec status=$status exit=$code ${duration}ms cmd=${cmd.take(120)}")
        return Result(
            status = status,
            exitCode = code,
            stdout = stdoutBuf.text(),
            stderr = stderrBuf.text(),
            truncated = stdoutBuf.truncated || stderrBuf.truncated,
            durationMs = duration,
        )
    }

    /** Request cancellation from any thread (agent stop button). */
    fun cancel() {
        cancelled.set(true)
        process?.let { destroy(it, gracefulFirst = true) }
    }

    private fun destroy(proc: Process, gracefulFirst: Boolean) {
        runCatching { proc.destroy() }
        if (!gracefulFirst) return
        // Give the tracee tree a moment to unwind (proot --kill-on-exit), then
        // make sure nothing survives.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && proc.isAlive) {
            runCatching { Thread.sleep(50) }
        }
        if (proc.isAlive) runCatching { proc.destroyForcibly() }
    }

    private fun drain(stream: java.io.InputStream, buf: CappedBuffer) {
        try {
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                buf.append(chunk, n)
            }
        } catch (_: Throwable) {
            // stream closed on destroy — expected
        } finally {
            runCatching { stream.close() }
        }
    }

    /** Pipe drain buffer that keeps at most [cap] bytes and flags truncation. */
    private class CappedBuffer(val cap: Int) {
        private val sb = StringBuilder()
        var truncated = false
            private set

        fun append(bytes: ByteArray, n: Int) {
            if (sb.length >= cap) {
                truncated = true
                return   // keep draining the pipe (prevents writer blocking) but drop data
            }
            val room = cap - sb.length
            val take = if (n <= room) n else room.also { truncated = true }
            sb.append(String(bytes, 0, take, Charsets.UTF_8))
        }

        fun text(): String = sb.toString()
    }

    companion object {
        const val TAG = "container"
        const val DEFAULT_CWD = "/workspace"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val TIMEOUT_MIN_MS = 1_000L
        const val TIMEOUT_MAX_MS = 600_000L
        const val STDOUT_CAP_BYTES = 256 * 1024
        const val STDERR_CAP_BYTES = 64 * 1024
    }
}
