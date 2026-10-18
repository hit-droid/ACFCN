package com.selfmod.agent.container

import android.content.Context
import com.selfmod.agent.util.Diagnostics
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the container lifecycle for the whole app: one-time rootfs install
 * (idempotent, hash-pinned) and per-call [ContainerSession] creation.
 *
 * Thread-safe. [exec] blocks — call it from a background dispatcher (the agent
 * loop already runs tools on `Dispatchers.IO`). [cancelCurrent] lets the agent
 * stop button abort a running command.
 */
class ContainerManager(private val context: Context) {

    private val installer = RootfsInstaller(context)

    /** Null until the first successful [ensureReady]; reset on install failure. */
    private val readyDirs = AtomicReference<Pair<File, File>?>(null)

    @Volatile private var currentSession: ContainerSession? = null
    private val sessionLock = Any()

    /**
     * Install the rootfs if it is not installed yet. Returns the ready dirs or
     * a human-readable failure reason. Blocking.
     */
    fun ensureReady(onProgress: (String) -> Unit = {}): Result {
        readyDirs.get()?.let { (rootfs, workspace) -> return Result.Ready(rootfs, workspace) }
        synchronized(this) {
            readyDirs.get()?.let { (rootfs, workspace) -> return Result.Ready(rootfs, workspace) }
            var outcome: Result = Result.Failed("未初始化")
            installer.installIfNeeded { state ->
                when (state) {
                    is RootfsInstaller.State.Working -> onProgress(state.message)
                    is RootfsInstaller.State.Ready -> {
                        readyDirs.set(state.rootfsDir to state.workspaceDir)
                        outcome = Result.Ready(state.rootfsDir, state.workspaceDir)
                    }
                    is RootfsInstaller.State.Failed -> {
                        readyDirs.set(null)
                        outcome = Result.Failed(state.reason)
                    }
                    is RootfsInstaller.State.NotInstalled -> Unit
                }
            }
            return outcome
        }
    }

    /**
     * Run [command] in the container, installing the rootfs first if needed.
     * Returns a structured result; spawn/integrity problems come back as
     * [ContainerSession.Result.Status.SPAWN_FAILED] or an error message.
     */
    fun exec(
        command: String,
        cwd: String = ContainerSession.DEFAULT_CWD,
        timeoutMs: Long = ContainerSession.DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap(),
        onProgress: (String) -> Unit = {},
    ): ContainerSession.Result {
        val dirs = when (val r = ensureReady(onProgress)) {
            is Result.Ready -> r
            is Result.Failed -> return ContainerSession.Result(
                status = ContainerSession.Result.Status.SPAWN_FAILED,
                exitCode = -1,
                stdout = "",
                stderr = r.reason,
                truncated = false,
                durationMs = 0,
            )
        }
        if (!ContainerPaths.prootExecutable(context).exists()) {
            return ContainerSession.Result(
                status = ContainerSession.Result.Status.SPAWN_FAILED,
                exitCode = -1,
                stdout = "",
                stderr = "容器运行时缺失（nativeLibraryDir 中没有 libproot.so）",
                truncated = false,
                durationMs = 0,
            )
        }
        val safeEnv = env.filterKeys { it !in FORBIDDEN_ENV_KEYS }
        val session = ContainerSession(
            prootPath = ContainerPaths.prootExecutable(context),
            rootfsDir = dirs.rootfsDir,
            workspaceHostDir = dirs.workspaceDir,
            loaderPath = ContainerPaths.prootLoader(context),
        )
        synchronized(sessionLock) { currentSession = session }
        return try {
            session.exec(command, cwd, timeoutMs, safeEnv)
        } finally {
            synchronized(sessionLock) { currentSession = null }
        }
    }

    /** Abort the in-flight command (agent stop button). No-op when idle. */
    fun cancelCurrent() {
        synchronized(sessionLock) { currentSession }?.cancel()
    }

    sealed class Result {
        data class Ready(val rootfsDir: File, val workspaceDir: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    companion object {
        private const val TAG = "container"

        /**
         * Environment keys the model must never control: they steer the proot
         * runtime itself (loader path) or the platform linker. Everything else
         * (PATH additions, LANG, tool-specific vars) is allowed to pass through.
         */
        private val FORBIDDEN_ENV_KEYS = setOf("PROOT_LOADER", "LD_PRELOAD", "LD_LIBRARY_PATH")

        init {
            Diagnostics.log(TAG, "ContainerManager class loaded")
        }
    }
}
