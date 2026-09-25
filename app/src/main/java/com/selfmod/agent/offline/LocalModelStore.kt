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
        persist(list().filter { it.id != id })
    }

    fun find(id: String): LocalModel? = list().firstOrNull { it.id == id }

    fun modelsDir(): File = modelsDir

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
