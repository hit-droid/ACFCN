package com.selfmod.agent.container

import android.content.Context
import com.selfmod.agent.util.Diagnostics
import java.io.File
import java.security.MessageDigest

/**
 * Installs the bundled container assets into app-private storage on first run
 * (or when the bundled asset hashes change). Fail-closed: a rootfs whose sha256
 * does not match the manifest leaves the container unusable with a clear reason
 * — same integrity posture as the L10 plugin dex verification.
 *
 * The proot runtime is *not* handled here: it lives in `jniLibs/arm64-v8a`
 * (`libproot.so` / `libproot-loader.so`) and is executed straight from
 * `ApplicationInfo.nativeLibraryDir` — see [ContainerPaths].
 */
class RootfsInstaller(private val context: Context) {

    sealed class State {
        data object NotInstalled : State()
        data class Working(val message: String) : State()
        data class Ready(val rootfsDir: File, val workspaceDir: File) : State()
        data class Failed(val reason: String) : State()
    }

    /**
     * Install if needed. [onState] is invoked at least once with a terminal
     * state ([State.Ready] or [State.Failed]). Blocking — call from a worker
     * thread.
     */
    fun installIfNeeded(onState: (State) -> Unit) {
        val files = context.filesDir
        val rootfs = ContainerPaths.rootfsDir(files)
        val marker = ContainerPaths.installedMarker(files)
        val expected = expectedMarkerLine()
        if (expected != null && rootfs.isDirectory && marker.exists() && marker.readText().trim() == expected) {
            onState(State.Ready(rootfs, ContainerPaths.workspaceDir(files)))
            return
        }
        onState(State.Working("正在准备 Linux 容器…"))
        // Wipe a stale install (keeps workspace/ — that one holds agent data).
        runCatching {
            ContainerPaths.rootfsDir(files).deleteRecursively()
        }
        ContainerPaths.rootfsDir(files).mkdirs()
        ContainerPaths.workspaceDir(files).mkdirs()

        // 1. verify the bundled rootfs against the manifest before touching storage
        onState(State.Working("正在校验容器镜像…"))
        val actualSha = context.assets.open(ContainerPaths.ASSET_ROOTFS).use { sha256Hex(it) }
        val expectedSha = manifestSha()
        if (expectedSha != null && actualSha != expectedSha) {
            val reason = "rootfs 校验失败（期望 $expectedSha，实际 $actualSha）"
            Diagnostics.log(TAG, reason)
            onState(State.Failed(reason))
            return
        }

        // 2. unpack
        onState(State.Working("正在解压容器镜像…"))
        val extract = context.assets.open(ContainerPaths.ASSET_ROOTFS).use { raw ->
            java.util.zip.GZIPInputStream(raw).use { gz ->
                TarExtractor(rootfs).extract(gz)
            }
        }
        when (extract) {
            is TarExtractor.Result.Rejected -> {
                val reason = "rootfs 包含非法条目：${extract.entry}（${extract.reason}）"
                Diagnostics.log(TAG, reason)
                onState(State.Failed(reason))
                return
            }
            is TarExtractor.Result.Done ->
                Diagnostics.log(TAG, "rootfs unpacked: files=${extract.fileCount} skipped=${extract.skipped}")
        }
        // Alpine is merged-/usr: /bin is a symlink to usr/bin — File.exists() follows
        // symlinks so either location proves the shell is in place.
        if (!File(rootfs, "bin/sh").exists() && !File(rootfs, "usr/bin/sh").exists()) {
            val reason = "rootfs 解压后缺少 shell（/bin/sh 不存在）"
            Diagnostics.log(TAG, reason)
            onState(State.Failed(reason))
            return
        }

        // 3. mark installed
        marker.writeText("rootfs-sha256=$actualSha version=$MARKER_VERSION\n")
        onState(State.Ready(rootfs, ContainerPaths.workspaceDir(files)))
    }

    private fun expectedMarkerLine(): String? {
        val rootfsSha = manifestSha() ?: return null
        return "rootfs-sha256=$rootfsSha version=$MARKER_VERSION"
    }

    private fun manifestSha(): String? = runCatching {
        context.assets.open(ContainerPaths.ASSET_ROOTFS_SHA).bufferedReader().useLines { lines ->
            lines.firstOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        }
    }.getOrNull()

    private fun sha256Hex(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "container"

        /** Bumped whenever the install procedure changes in an incompatible way. */
        const val MARKER_VERSION = 2
    }
}
