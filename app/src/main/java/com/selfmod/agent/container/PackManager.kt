package com.selfmod.agent.container

import com.selfmod.agent.util.Diagnostics
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Installs, lists and removes container toolchain packs (see [ResourcePack]).
 *
 * A pack is a tar.gz diff against the bundled minirootfs; installing is
 * "download → verify sha256 (fail closed) → extract over the rootfs → record
 * state". Removal is reference-counted: files still claimed by another
 * installed pack are kept.
 *
 * All methods are blocking — call from a worker thread (the agent loop runs
 * tools on `Dispatchers.IO`).
 *
 * @param filesDir app-private files dir (host of `linux/rootfs`, `linux/packs`).
 * @param catalog available packs; defaults to [ResourcePacks.ALL] and exists
 *        so tests can inject a published fixture pack.
 * @param download seam for the tarball fetch; the default streams via OkHttp
 *        while hashing, so a corrupt transfer is refused before extraction.
 * @param ensureRootfs makes sure the container rootfs exists before a pack is
 *        extracted into it; returns false when the rootfs is unusable.
 */
class PackManager(
    private val filesDir: File,
    private val catalog: List<ResourcePack> = ResourcePacks.ALL,
    private val download: (String, File, (Long, Long) -> Unit) -> Unit = ::httpDownload,
    private val ensureRootfs: () -> Boolean = { false },
) {

    /** Per-pack install record (state.json entry). */
    data class Installed(
        val sha256: String,
        val sizeBytes: Long,
        val installedAtMs: Long,
        val files: List<String>,
    )

    /** Catalog entry joined with install state, for listings. */
    data class PackStatus(
        val pack: ResourcePack,
        val installed: Installed?,
    ) {
        val isInstalled: Boolean get() = installed != null
    }

    sealed class Result {
        data class Done(
            val pack: ResourcePack,
            val fileCount: Int,
            val downloadedBytes: Long,
            /** Path (container-absolute) of a post-install script the bundle carried, if any. */
            val postInstallScript: String?,
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    private val packsDir: File get() = File(filesDir, "linux/packs")
    private val stateFile: File get() = File(packsDir, "state.json")
    private val rootfsDir: File get() = ContainerPaths.rootfsDir(filesDir)

    // ------------------------------------------------------------------ list

    /** Catalog with install state; stale records (rootfs was re-installed) are dropped. */
    @Synchronized
    fun list(): List<PackStatus> {
        val state = loadState()
        return catalog.map { pack ->
            PackStatus(pack, state.optJSONObject(PACKS)?.optJSONObject(pack.id)?.let { decode(it) })
        }
    }

    fun isInstalled(packId: String): Boolean =
        list().firstOrNull { it.pack.id == packId }?.isInstalled == true

    // --------------------------------------------------------------- install

    /**
     * Download and install [packId]. Idempotent: an already-installed pack with
     * the same pinned sha256 is a no-op success.
     */
    fun install(packId: String, onProgress: (String) -> Unit = {}): Result {
        val pack = catalog.resolve(packId)
            ?: return Result.Failed("未知资源包: $packId（可用: ${catalog.joinToString { it.id }}）")
        val sha = pack.sha256
            ?: return Result.Failed("资源包 ${pack.id} 尚未发布（无固定 sha256），暂不可安装")

        synchronized(this) {
            val state = loadState()
            val prev = state.optJSONObject(PACKS)?.optJSONObject(pack.id)
            if (prev != null && prev.optString("sha256") == sha) {
                return Result.Done(pack, prev.optJSONArray("files")?.length() ?: 0, prev.optLong("size"), null)
            }
            if (!ensureRootfs()) {
                return Result.Failed("容器 rootfs 不可用，无法安装资源包（先成功执行一次 linux_exec）")
            }

            packsDir.mkdirs()
            val tmp = File(packsDir, "${pack.id}.tar.gz.tmp")
            runCatching { tmp.delete() }

            onProgress("正在下载 ${pack.displayName}…")
            try {
                download(pack.url, tmp) { done, total ->
                    if (total > 0) onProgress("下载中 ${done * 100 / total}%（${done / 1048576}/${total / 1048576} MiB）")
                }
            } catch (e: Exception) {
                tmp.delete()
                return Result.Failed("下载失败: ${e.message ?: e.javaClass.simpleName}")
            }

            onProgress("正在校验完整性…")
            val actualSha = sha256Hex(tmp)
            if (actualSha != sha) {
                tmp.delete()
                return Result.Failed("sha256 校验失败（期望 $sha，实际 $actualSha）——拒绝安装")
            }
            val size = tmp.length()

            onProgress("正在解压进容器…")
            val entries = mutableListOf<String>()
            val extract = GZIPInputStream(tmp.inputStream().buffered()).use { gz ->
                TarExtractor(rootfsDir) { name -> entries.add(name) }.extract(gz)
            }
            when (extract) {
                is TarExtractor.Result.Rejected -> {
                    tmp.delete()
                    return Result.Failed("资源包含非法条目: ${extract.entry}（${extract.reason}）")
                }
                is TarExtractor.Result.Done -> Unit
            }
            tmp.delete()

            val record = JSONObject().apply {
                put("sha256", sha)
                put("size", size)
                put("installed_at", System.currentTimeMillis())
                put("files", org.json.JSONArray(entries))
            }
            state.getJSONObject(PACKS).put(pack.id, record)
            saveState(state)

            // A bundle may carry its own post-install hook (triggers the apk
            // world would have run: ldconfig, ca-certificates, …). The caller
            // executes it inside the container.
            val postInstall = entries.firstOrNull { it.endsWith(".post-install.sh") }
                ?.let { "/${it.removePrefix("./")}" }

            Diagnostics.log(TAG, "pack installed: ${pack.id} files=${extract.fileCount} bytes=$size")
            return Result.Done(pack, extract.fileCount, size, postInstall)
        }
    }

    // ---------------------------------------------------------------- remove

    /**
     * Remove [packId]: delete the files it installed that no other installed
     * pack claims, then prune the directories they leave empty. The state
     * record is dropped last.
     */
    fun remove(packId: String): Result {
        val pack = catalog.resolve(packId)
            ?: return Result.Failed("未知资源包: $packId")
        synchronized(this) {
            val state = loadState()
            val record = state.optJSONObject(PACKS)?.optJSONObject(pack.id)
                ?: return Result.Failed("资源包 ${pack.id} 未安装")
            val files = record.optJSONArray("files")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            // Files shared with other installed packs must survive.
            val claimed = mutableSetOf<String>()
            state.getJSONObject(PACKS).keys().forEach { key ->
                if (key == pack.id) return@forEach
                val arr = state.getJSONObject(PACKS).optJSONObject(key)?.optJSONArray("files") ?: return@forEach
                for (i in 0 until arr.length()) claimed.add(arr.getString(i))
            }

            var deleted = 0
            files.sortedByDescending { it.count { c -> c == '/' } }.forEach { rel ->
                if (rel in claimed) return@forEach
                val f = File(rootfsDir, rel)
                val existed = f.exists() || isSymlink(f)
                if (existed && runCatching { f.delete() }.getOrDefault(false)) deleted++
            }
            pruneEmptyDirs(rootfsDir)

            state.getJSONObject(PACKS).remove(pack.id)
            saveState(state)
            Diagnostics.log(TAG, "pack removed: ${pack.id} deleted=$deleted kept_shared=${files.size - deleted}")
            return Result.Done(pack, deleted, record.optLong("size"), null)
        }
    }

    // ----------------------------------------------------------------- state

    /**
     * State is keyed to the installed rootfs: when the rootfs marker's sha
     * changes (fresh install after an app update), everything we extracted is
     * gone, so the records are stale and get dropped.
     *
     * Deliberately uncached: the marker can change under us at any time (the
     * rootfs installer re-extracts it when the bundled asset hash changes), and
     * none of the callers are hot enough for a re-read to matter.
     */
    private fun loadState(): JSONObject {
        val rootfsSha = ContainerPaths.installedMarker(filesDir).takeIf { it.exists() }
            ?.readText()?.lineSequence()
            ?.firstOrNull { it.startsWith("rootfs-sha256=") }
            ?.substringAfter('=')
            ?.trim()
        val state = runCatching {
            stateFile.takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()

        val recordedSha = state.optString(ROOTFS_SHA)
        if (rootfsSha == null || recordedSha != rootfsSha) {
            // Fresh/unknown rootfs: nothing we installed can be trusted.
            state.remove(PACKS)
        }
        if (state.optJSONObject(PACKS) == null) state.put(PACKS, JSONObject())
        if (rootfsSha != null) state.put(ROOTFS_SHA, rootfsSha) else state.remove(ROOTFS_SHA)
        return state
    }

    private fun saveState(state: JSONObject) {
        runCatching { packsDir.mkdirs(); stateFile.writeText(state.toString()) }
            .onFailure { Diagnostics.log(TAG, "state write failed: ${it.message}") }
    }

    private fun decode(o: JSONObject) = Installed(
        sha256 = o.optString("sha256"),
        sizeBytes = o.optLong("size"),
        installedAtMs = o.optLong("installed_at"),
        files = o.optJSONArray("files")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList(),
    )

    private fun pruneEmptyDirs(root: File) {
        // Deepest-first so parents become empty by the time we reach them.
        root.walkBottomUp().forEach { f ->
            if (f != root && f.isDirectory && f.listFiles()?.isEmpty() == true) f.delete()
        }
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { f.toPath().let { java.nio.file.Files.isSymbolicLink(it) } }.getOrDefault(false)

    private fun sha256Hex(file: File): String =
        file.inputStream().buffered().use { sha256Hex(it) }

    private fun sha256Hex(input: InputStream): String {
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
        private const val TAG = "container-pack"
        private const val PACKS = "packs"
        private const val ROOTFS_SHA = "rootfs_sha256"

        /** Sanity cap: packs are tens of MiB; anything past this is a bug or abuse. */
        private const val MAX_PACK_BYTES = 256L * 1024 * 1024

        /**
         * Default downloader: OkHttp streaming into [dest] while reporting
         * progress. Follows GitHub asset redirects. Fails closed on
         * non-200, oversized bodies and short streams.
         */
        private fun httpDownload(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.code != 200) throw java.io.IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("empty body")
                val total = body.contentLength()
                if (total > MAX_PACK_BYTES) throw java.io.IOException("包体超过 ${MAX_PACK_BYTES / 1048576}MiB 上限")
                var done = 0L
                val buf = ByteArray(64 * 1024)
                dest.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > MAX_PACK_BYTES) throw java.io.IOException("包体超过 ${MAX_PACK_BYTES / 1048576}MiB 上限")
                            out.write(buf, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
                if (total > 0 && done != total) throw java.io.IOException("下载不完整（$done/$total）")
            }
        }
    }
}
