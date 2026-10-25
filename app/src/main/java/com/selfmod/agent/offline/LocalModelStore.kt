package com.selfmod.agent.offline

import android.content.Context
import com.selfmod.agent.agent.HttpGuard
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class LocalModel(
    val id: String,
    val name: String,
    val uri: String,
    val sizeBytes: Long,
    val format: String,
    val importedAt: Long,
    val architecture: String = "",
    val quant: String = "",
    val contextLength: Long = 0L,
    val parameterCount: Long = 0L,
    val summary: String = "",
) {
    fun sizeLabel(): String = when {
        sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
        sizeBytes >= 1_000_000 -> "%.1f MB".format(sizeBytes / 1_000_000.0)
        sizeBytes >= 1_000 -> "%.0f KB".format(sizeBytes / 1_000.0)
        else -> "$sizeBytes B"
    }
}

/**
 * Registry of imported on-device model files (GGUF / ONNX / etc.).
 * Files stay as persistable content URIs — we never copy multi-GB weights.
 * Inference is performed by a local OpenAI-compatible runtime (Ollama,
 * llama.cpp server, LM Studio) that the app points at.
 */
class LocalModelStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cr = context.applicationContext.contentResolver
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    fun list(): List<LocalModel> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<LocalModel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += LocalModel(
                id = o.optString("id"),
                name = o.optString("name"),
                uri = o.optString("uri"),
                sizeBytes = o.optLong("sizeBytes"),
                format = o.optString("format", "unknown"),
                importedAt = o.optLong("importedAt"),
                architecture = o.optString("architecture"),
                quant = o.optString("quant"),
                contextLength = o.optLong("contextLength"),
                parameterCount = o.optLong("parameterCount"),
                summary = o.optString("summary"),
            )
        }
        return out.sortedByDescending { it.importedAt }
    }

    fun importUri(uri: Uri): LocalModel {
        runCatching {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var display = uri.lastPathSegment?.substringAfterLast('/') ?: "model"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) display = c.getString(nameIdx) ?: display
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        return registerModel(uri, display, size)
    }

    /**
     * Registers a model file that already sits on disk (P2-7). Unlike [importUri]
     * it takes a real [File] and stores a `file://` URI, so the file stays where it
     * is and [materialize] can read it directly without copying.
     */
    fun importFile(file: File): LocalModel {
        val uri = Uri.fromFile(file)
        return registerModel(uri, file.name, file.length())
    }

    /**
     * Streams a model file from a URL into app storage, sniffs its format + metadata,
     * and registers it like [importUri]. Honours the SSRF guard (so a model can't be
     * fetched from a cloud-metadata / loopback sink), reports progress, and optionally
     * verifies a SHA-256 (passing [expectedSha256] disables the silent-corruption path).
     *
     * @param onProgress called with (fraction 0..1, downloadedBytes, totalBytes). When
     *                  the server omits Content-Length, fraction is -1f (indeterminate).
     */
    @Throws(IOException::class)
    fun download(
        url: String,
        displayName: String,
        expectedSha256: String? = null,
        onProgress: ((Float, Long, Long) -> Unit)? = null,
    ): LocalModel {
        when (val v = HttpGuard.check(url, offlineMode = true)) {
            is HttpGuard.Verdict.Deny -> throw IOException("SSRF 拦截：无法下载（${v.reason}）")
            is HttpGuard.Verdict.Allow -> { /* proceed */ }
        }
        val dest = File(modelsDir, sanitize(displayName))
        val part = File(modelsDir, sanitize(displayName) + ".part")
        part.delete()
        val digest = if (!expectedSha256.isNullOrBlank()) MessageDigest.getInstance("SHA-256") else null
        var total = -1L
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "ACFCN/2.24")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                part.delete()
                throw IOException("HTTP $code：下载失败")
            }
            total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        read += n
                        if (onProgress != null) {
                            onProgress(
                                if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f,
                                read,
                                total,
                            )
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
        if (digest != null) {
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex.lowercase() != expectedSha256!!.lowercase()) {
                part.delete()
                throw IOException("SHA-256 校验失败：期望 ${expectedSha256?.take(12)}… 实际 ${hex.take(12)}…")
            }
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("下载落盘失败")
        }
        return registerModel(Uri.fromFile(dest), displayName, dest.length())
    }

    /**
     * Shared model-construction path: sniff the format, parse GGUF / MNN metadata,
     * build the [LocalModel], de-dup by URI, persist, and return it. Used by both
     * [importUri] (content URI) and [download] / [importFile] (file URI).
     */
    private fun registerModel(uri: Uri, display: String, size: Long): LocalModel {
        val format = sniffFormat(uri, display)
        val info = if (format == "gguf") {
            runCatching { cr.openInputStream(uri)?.use { GgufHeader.parse(it) } }.getOrNull()
        } else null
        // An MNN model is a directory (or a zip of one), so peek inside the
        // archive for config.json — otherwise the list can only say "zip".
        val mnnInfo = if (format == "mnn") {
            runCatching { readMnnInfo(uri, display) }.getOrNull()
        } else null
        val model = LocalModel(
            id = UUID.randomUUID().toString(),
            name = info?.name?.takeIf { it.isNotBlank() }
                ?: mnnInfo?.first?.takeIf { it.isNotBlank() }
                ?: display,
            uri = uri.toString(),
            sizeBytes = size,
            format = format,
            importedAt = System.currentTimeMillis(),
            architecture = info?.architecture?.takeIf { it.isNotBlank() }
                ?: mnnInfo?.second?.modelType.orEmpty(),
            quant = info?.quant.orEmpty(),
            contextLength = info?.contextLength ?: 0L,
            parameterCount = info?.parameterCount ?: 0L,
            summary = info?.summary()?.takeIf { it.isNotBlank() }
                ?: mnnInfo?.second?.summary().orEmpty(),
        )
        val all = list().toMutableList()
        all.removeAll { it.uri == model.uri }
        all.add(0, model)
        persist(all)
        return model
    }

    fun remove(id: String) {
        val m = find(id)
        persist(list().filter { it.id != id })
        // Also delete the copied weight file (and any partial) to reclaim space.
        m?.let {
            runCatching { File(modelsDir, sanitize(it.name)).delete() }
            runCatching { File(modelsDir, sanitize(it.name) + ".part").delete() }
            // MNN bundles are extracted directories (plus a staging dir).
            runCatching { File(modelsDir, "mnn-" + sanitize(it.id)).deleteRecursively() }
            runCatching { File(modelsDir, "mnn-" + sanitize(it.id) + ".part").deleteRecursively() }
        }
    }

    /** Total bytes currently used by copied model files and extracted MNN dirs. */
    fun copiedBytes(): Long =
        modelsDir.listFiles()?.sumOf { f ->
            if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else f.length()
        } ?: 0L

    /** Deletes every copied weight file and extracted MNN dir (keeps the registry). */
    fun deleteAllCopies(): Long {
        val freed = copiedBytes()
        modelsDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        return freed
    }

    fun find(id: String): LocalModel? = list().firstOrNull { it.id == id }

    fun modelsDir(): File = modelsDir

    /**
     * Returns a real filesystem path llama.cpp can open.
     *
     * Strategy: (1) if the content URI already points at a readable real file
     * (common for Downloads / Documents via `_data`), use it directly — no copy,
     * no extra disk. (2) otherwise copy into app storage, reporting progress.
     *
     * For an MNN model this returns the model *directory* instead (MNN resolves
     * config.json's siblings relative to it); see [materializeMnn].
     *
     * @param onProgress called with 0..1 during a copy, or -1f when no copy is
     *                   needed (fast path). Never called after the function returns.
     */
    fun materialize(id: String, onProgress: ((Float) -> Unit)? = null): File? {
        val m = find(id) ?: return null
        if (m.format == "mnn") return materializeMnn(m, onProgress)

        // 1) Try to resolve a real path from the content URI (no copy).
        resolveRealPath(Uri.parse(m.uri))?.let { p ->
            val f = File(p)
            if (f.exists() && f.canRead()) {
                onProgress?.invoke(-1f)
                return f
            }
        }

        // 2) Fall back to copying into app-private storage.
        val dest = File(modelsDir, sanitize(m.name))
        if (dest.exists() && dest.length() == m.sizeBytes && dest.length() > 0) {
            onProgress?.invoke(1f)
            return dest
        }
        return runCatching {
            val total = if (m.sizeBytes > 0) m.sizeBytes else -1L
            val part = File(modelsDir, sanitize(m.name) + ".part")
            val input = context.contentResolver.openInputStream(Uri.parse(m.uri)) ?: return null
            input.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        // Throttle UI updates to ~every 16 MB.
                        if (onProgress != null && total > 0 && copied - lastEmit >= (16L shl 20)) {
                            lastEmit = copied
                            onProgress(copied.toFloat() / total.toFloat())
                        }
                    }
                    out.flush()
                }
            }
            // Only publish the final file once the copy fully succeeded.
            if (part.renameTo(dest)) {
                onProgress?.invoke(1f)
                dest
            } else {
                part.delete()
                null
            }
        }.onFailure {
            File(modelsDir, sanitize(m.name) + ".part").delete()
        }.getOrNull()
    }

    /**
     * Extracts an MNN model bundle into a directory and returns that directory.
     *
     * MNN needs all of config.json / llm.mnn / llm.mnn.weight / tokenizer.mtok
     * on disk as siblings, so unlike GGUF we cannot read it from the content URI
     * — it has to be unpacked. Extraction lands in `<models>/mnn-<id>/` via a
     * staging dir that is only renamed into place once every entry is written,
     * so a cancelled extract never leaves a half-model behind that MNN would
     * then try to load.
     */
    private fun materializeMnn(m: LocalModel, onProgress: ((Float) -> Unit)?): File? {
        val dest = File(modelsDir, "mnn-" + sanitize(m.id))
        if (File(dest, MnnModelDir.configPath()).exists()) {
            onProgress?.invoke(-1f)
            return dest
        }
        val staging = File(modelsDir, "mnn-" + sanitize(m.id) + ".part")
        return runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            val stream = context.contentResolver.openInputStream(Uri.parse(m.uri))
                ?: return@runCatching null
            val total = if (m.sizeBytes > 0) m.sizeBytes.toDouble() else -1.0
            var read = 0L
            var lastEmit = 0L
            stream.use { ins ->
                java.util.zip.ZipInputStream(ins).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // Guard against zip-slip: only ever write inside staging.
                        val target = File(staging, name).canonicalFile
                        if (!target.path.startsWith(staging.canonicalFile.path)) {
                            throw java.io.IOException("zip 条目越界：$name")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                val buf = ByteArray(1 shl 20)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    read += n
                                    // Throttle UI updates to ~every 16 MB.
                                    if (onProgress != null && total > 0 && read - lastEmit >= (16L shl 20)) {
                                        lastEmit = read
                                        onProgress((read / total).toFloat().coerceIn(0f, 0.99f))
                                    }
                                }
                                out.flush()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            // HF bundles nest everything in one top-level folder; lift it so the
            // model files sit directly in `dest` as MNN expects.
            val children = staging.listFiles().orEmpty()
            val root = if (children.size == 1 && children[0].isDirectory) children[0] else staging
            if (!File(root, MnnModelDir.configPath()).exists()) {
                throw java.io.IOException("压缩包里没有 config.json，不像是 MNN 模型")
            }
            dest.deleteRecursively()
            if (!root.renameTo(dest)) {
                root.copyRecursively(dest, overwrite = true)
                root.deleteRecursively()
            }
            onProgress?.invoke(1f)
            dest
        }.onFailure {
            staging.deleteRecursively()
        }.getOrNull()
    }

    /**
     * Best-effort: resolve a content:// URI to a real file path via the provider's _data column.
     */
    private fun resolveRealPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("_data")
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    /**
     * L13 — the single source of truth for "what real path would [materialize]
     * actually open for this model", *without* copying. This keeps [materialize]
     * and [localPathFor] consistent: if a real provider path is directly readable
     * we report it; otherwise the existing copied file in app storage is reported.
     * A caller asking "is this model loadable locally" gets the same answer as
     * one that actually materializes.
     */
    fun loadablePathFor(id: String): String? {
        val m = find(id) ?: return null
        // Real provider path takes precedence (matches materialize step 1).
        resolveRealPath(Uri.parse(m.uri))?.let { p ->
            val f = File(p)
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        // MNN models are unpacked into a per-id directory, not a named file.
        if (m.format == "mnn") {
            val dir = File(modelsDir, "mnn-" + sanitize(m.id))
            return if (File(dir, MnnModelDir.configPath()).exists()) dir.absolutePath else null
        }
        // Fall back to an already-copied file (matches materialize step 2).
        val candidate = File(modelsDir, sanitize(m.name))
        return if (candidate.exists()) candidate.absolutePath else null
    }

    /**
     * Path of the on-device *copy* (app storage) of this model, if one exists.
     * Does NOT return a real provider path — this is specifically "is there a
     * copy we can delete / that occupies app storage". Prefer [loadablePathFor]
     * when asking whether the model can be loaded.
     */
    fun localPathFor(id: String): String? {
        val m = find(id) ?: return null
        if (m.format == "mnn") {
            val dir = File(modelsDir, "mnn-" + sanitize(m.id))
            return if (dir.exists()) dir.absolutePath else null
        }
        val candidate = File(modelsDir, sanitize(m.name))
        return if (candidate.exists()) candidate.absolutePath else null
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)

    private fun persist(models: List<LocalModel>) {
        val arr = JSONArray()
        models.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("uri", m.uri)
                put("sizeBytes", m.sizeBytes)
                put("format", m.format)
                put("importedAt", m.importedAt)
                put("architecture", m.architecture)
                put("quant", m.quant)
                put("contextLength", m.contextLength)
                put("parameterCount", m.parameterCount)
                put("summary", m.summary)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun sniffFormat(uri: Uri, name: String): String {
        val lower = name.lowercase()
        when {
            lower.endsWith(".gguf") -> return "gguf"
            lower.endsWith(".ggml") -> return "ggml"
            lower.endsWith(".onnx") -> return "onnx"
            lower.endsWith(".bin") -> return "bin"
            lower.endsWith(".safetensors") -> return "safetensors"
            lower.endsWith(".mnn") || lower.endsWith(".mnn.weight") -> return "mnn"
        }
        // A zip could be an MNN model bundle; look for the marker entries.
        if (lower.endsWith(".zip")) {
            val marker = runCatching { zipEntryNames(uri).any { MnnModelDir.isModelMarker(it) } }
                .getOrDefault(false)
            if (marker) return "mnn"
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) == 4 && magic.contentEquals(GGUF_MAGIC)) return "gguf"
            }
        }
        return "unknown"
    }

    /** Lists entry names in a zip without extracting it. */
    private fun zipEntryNames(uri: Uri): List<String> {
        val out = ArrayList<String>()
        val stream = context.contentResolver.openInputStream(uri) ?: return out
        stream.use { ins ->
            java.util.zip.ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    out.add(e.name)
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        return out
    }

    /**
     * Best-effort description of an MNN model: its display name and the parsed
     * [MnnModelInfo]. Works for both a zipped bundle and a loose directory-like
     * selection (a single .mnn/.mnn.weight whose siblings we cannot see).
     */
    private fun readMnnInfo(uri: Uri, display: String): Pair<String, MnnModelInfo> {
        val names = runCatching { zipEntryNames(uri) }.getOrDefault(emptyList())
        if (names.isEmpty()) {
            // Loose file: we only know the one name the picker handed us.
            val info = MnnModelDir.inspect(listOf(display), null)
            val stem = display.substringBeforeLast('.')
            return stem to info
        }
        // Common prefix directory: HF bundles wrap everything in one folder.
        val top = names.filter { !it.endsWith("/") }
        val prefix = top.map { it.substringBeforeLast('/', "") }
            .distinct()
            .singleOrNull()
            .orEmpty()
        val baseNames = top.map { it.removePrefix(if (prefix.isEmpty()) "" else "$prefix/") }
        val configJson = readZipEntry(uri, if (prefix.isEmpty()) "config.json" else "$prefix/config.json")
        val info = MnnModelDir.inspect(baseNames, configJson)
        val folderName = prefix.substringAfterLast('/').ifBlank { display.substringBeforeLast('.') }
        return folderName to info
    }

    /** Reads one entry's text out of a zip, or null. */
    private fun readZipEntry(uri: Uri, wanted: String): String? = runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        stream.use { ins ->
            val zip = java.util.zip.ZipInputStream(ins)
            zip.use { z ->
                var found: String? = null
                while (found == null) {
                    val e = z.nextEntry ?: break
                    if (e.name == wanted) {
                        found = z.readBytes().toString(Charsets.UTF_8)
                    } else {
                        z.closeEntry()
                    }
                }
                found
            }
        }
    }.getOrNull()

    companion object {
        private const val PREFS = "selfmod_local_models"
        private const val KEY = "models_v1"
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
