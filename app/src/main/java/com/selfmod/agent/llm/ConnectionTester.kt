package com.selfmod.agent.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ConnectionTestResult(
    val ok: Boolean,
    val latencyMs: Long,
    val message: String,
    val models: List<String> = emptyList(),
    val sample: String = "",
    val httpCode: Int = 0,
)

data class ProbeHit(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val httpCode: Int,
    val latencyMs: Long,
)

/**
 * One-tap connectivity checks for OpenAI-compatible endpoints, plus a scan of
 * common local inference ports (Ollama / LM Studio / llama.cpp / Jan).
 */
class ConnectionTester {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .writeTimeout(800, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun test(config: LlmConfig): ConnectionTestResult {
        val t0 = System.currentTimeMillis()
        val models = runCatching { listModels(config) }.getOrDefault(emptyList())
        val chat = runCatching { pingChat(config) }
        val latency = System.currentTimeMillis() - t0
        if (chat.isFailure) {
            val err = chat.exceptionOrNull()?.message ?: "unknown"
            val stillOk = models.isNotEmpty()
            return ConnectionTestResult(
                ok = stillOk,
                latencyMs = latency,
                message = if (stillOk) "已列出模型，但试聊失败：$err" else "连接失败：$err",
                models = models,
                httpCode = 0,
            )
        }
        val sample = chat.getOrNull().orEmpty()
        return ConnectionTestResult(
            ok = true,
            latencyMs = latency,
            message = "连接成功 · ${latency}ms · 模型 ${config.model.ifBlank { "(未指定)" }}",
            models = models,
            sample = sample,
            httpCode = 200,
        )
    }

    fun listModels(config: LlmConfig): List<String> {
        val client = clientFor(config)
        val base = config.baseUrl.trimEnd('/')
        val root = base.removeSuffix("/v1")
        val urls = listOf("$base/models", "$root/api/tags", "$root/v1/models")
        var lastErr: Throwable? = null
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).get().apply { applyAuth(config) }.build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastErr = RuntimeException("HTTP ${resp.code} ${raw.take(200)}")
                        return@use
                    }
                    val parsed = parseModelList(raw)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        if (lastErr != null) throw lastErr!!
        return emptyList()
    }

    fun probeLocal(): List<ProbeHit> {
        val hits = ArrayList<ProbeHit>()
        for (c in LOCAL_CANDIDATES) {
            val t0 = System.currentTimeMillis()
            val url = c.baseUrl.trimEnd('/') + "/models"
            try {
                val req = Request.Builder().url(url).get()
                    .addHeader("Accept", "application/json")
                    .build()
                probeHttp.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val latency = System.currentTimeMillis() - t0
                    if (resp.code in 200..499) {
                        val models = if (resp.isSuccessful) parseModelList(raw) else emptyList()
                        hits += ProbeHit(c.name, c.baseUrl, models, resp.code, latency)
                    }
                }
            } catch (_: Throwable) {
                // endpoint not listening
            }
        }
        return hits
    }

    private fun pingChat(config: LlmConfig): String {
        val client = clientFor(config)
        val body = JSONObject()
            .put("model", config.model.ifBlank { "local" })
            .put("temperature", 0)
            .put("max_tokens", 32)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply with the single word PONG."),
                ),
            )
        val req = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Accept", "application/json")
            .apply { applyAuth(config) }
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${raw.take(400)}")
            }
            val obj = JSONObject(raw)
            return obj.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .ifBlank { raw.take(200) }
        }
    }

    private fun parseModelList(raw: String): List<String> {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        obj.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) out += id
            }
        }
        obj.optJSONArray("models")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val n = m.optString("name").ifBlank { m.optString("model") }
                if (n.isNotBlank()) out += n
            }
        }
        return out.toList()
    }

    private fun Request.Builder.applyAuth(config: LlmConfig): Request.Builder {
        config.authHeader()?.let { addHeader("Authorization", it) }
        return this
    }

    private fun clientFor(config: LlmConfig): OkHttpClient {
        val sec = config.timeoutSeconds.coerceIn(5, 180)
        return OkHttpClient.Builder()
            .connectTimeout(sec.coerceAtMost(20), TimeUnit.SECONDS)
            .readTimeout(sec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        private val LOCAL_CANDIDATES = listOf(
            ProbeHit("Ollama", "http://127.0.0.1:11434/v1", emptyList(), 0, 0),
            ProbeHit("Ollama (模拟器宿主)", "http://10.0.2.2:11434/v1", emptyList(), 0, 0),
            ProbeHit("LM Studio", "http://127.0.0.1:1234/v1", emptyList(), 0, 0),
            ProbeHit("llama.cpp", "http://127.0.0.1:8080/v1", emptyList(), 0, 0),
            ProbeHit("Jan", "http://127.0.0.1:1337/v1", emptyList(), 0, 0),
            ProbeHit("vLLM / LocalAI", "http://127.0.0.1:8000/v1", emptyList(), 0, 0),
        ).map { Candidate(it.name, it.baseUrl) }
    }

    private data class Candidate(val name: String, val baseUrl: String)
}
