package com.selfmod.agent.offline

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        val cr = context.contentResolver
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
        val format = sniffFormat(uri, display)
        val info = if (format == "gguf") {
            runCatching {
                cr.openInputStream(uri)?.use { GgufHeader.parse(it) }
            }.getOrNull()
        } else null
        val model = LocalModel(
            id = UUID.randomUUID().toString(),
            name = info?.name?.ifBlank { display } ?: display,
            uri = uri.toString(),
            sizeBytes = size,
            format = format,
            importedAt = System.currentTimeMillis(),
            architecture = info?.architecture.orEmpty(),
            quant = info?.quant.orEmpty(),
            contextLength = info?.contextLength ?: 0L,
            parameterCount = info?.parameterCount ?: 0L,
            summary = info?.summary().orEmpty(),
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
        }
    }

    /** Total bytes currently used by copied model files. */
    fun copiedBytes(): Long =
        modelsDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** Deletes every copied weight file (keeps the registry entries). */
    fun deleteAllCopies(): Long {
        val freed = copiedBytes()
        modelsDir.listFiles()?.forEach { runCatching { it.delete() } }
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
     * @param onProgress called with 0..1 during a copy, or -1f when no copy is
     *                   needed (fast path). Never called after the function returns.
     */
    fun materialize(id: String, onProgress: ((Float) -> Unit)? = null): File? {
        val m = find(id) ?: return null

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
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) == 4 && magic.contentEquals(GGUF_MAGIC)) return "gguf"
            }
        }
        return "unknown"
    }

    companion object {
        private const val PREFS = "selfmod_local_models"
        private const val KEY = "models_v1"
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
