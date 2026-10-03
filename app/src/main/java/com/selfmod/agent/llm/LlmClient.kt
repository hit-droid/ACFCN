package com.selfmod.agent.llm

import com.selfmod.agent.offline.native.LocalLlmEngine
import com.selfmod.agent.util.JsonUtil
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LlmClient(
    private val client: OkHttpClient = defaultClient(),
    private val onDevice: LocalLlmEngine? = null,
) {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val inFlight = AtomicReference<Call?>(null)
    private val httpAbort = AtomicBoolean(false)

    fun cancel() {
        httpAbort.set(true)
        onDevice?.cancel()
        inFlight.get()?.cancel()
    }

    fun clearAbort() {
        httpAbort.set(false)
        onDevice?.clearAbort()
    }

    fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        onDelta: ((String) -> Unit)? = null,
        cancelled: () -> Boolean = { false },
    ): ChatResult {
        if (config.kind == LlmConfig.KIND_ONDEVICE) {
            return chatOnDevice(config, messages, onDelta, cancelled)
        }
        val useNative = tools.isNotEmpty() && config.supportsNativeTools
        val toolList = if (useNative) tools else emptyList()
        return try {
            chatOpenAi(config, messages, toolList, onDelta, cancelled)
        } catch (e: LlmException) {
            if (e.code == 499) throw e
            if (config.isLocalHost() && e.code in listOf(404, 405, 400)) {
                runCatching { chatOllama(config, messages, onDelta, cancelled) }
                    .recoverCatching { chatLlamaCpp(config, messages, onDelta, cancelled) }
                    .getOrElse { throw e }
            } else {
                throw e
            }
        }
    }

    private fun chatOnDevice(
        config: LlmConfig,
        messages: List<ChatMessage>,
        onDelta: ((String) -> Unit)?,
        cancelled: () -> Boolean,
    ): ChatResult {
        val engine = onDevice ?: throw LlmException(503, "端侧推理引擎不可用")
        if (cancelled()) throw LlmException(499, "cancelled")
        if (!engine.isLoaded()) throw LlmException(503, "端侧模型未加载，请在离线页加载模型")
        // M9: `tool` results are folded into marked user turns instead of being
        // silently dropped (OnDevicePrompts keeps every message).
        val pairs = OnDevicePrompts.fold(messages)
        val sb = StringBuilder()
        val code = engine.chat(
            messages = pairs,
            maxTokens = config.maxTokens,
            temperature = config.temperature.toFloat(),
            onToken = object : LocalLlmEngine.TokenCallback {
                override fun onToken(piece: String): Boolean {
                    sb.append(piece)
                    onDelta?.invoke(piece)
                    return !cancelled()
                }
            },
        )
        if (code == -98 || cancelled()) {
            throw LlmException(499, "cancelled")
        }
        if (code == -99) {
            if (sb.isNotEmpty()) {
                return ChatResult(content = sb.toString(), raw = sb.toString(), finishReason = "timeout")
            }
            throw LlmException(
                504,
                "端侧推理超时（长时间没有新 token）。多半是模型太大、内存不足导致系统在换页。" +
                    "请换更小的模型（如 1.5B/1B 的 Q4_K_M），或关闭其他 App 后重试。",
            )
        }
        if (code < 0) {
            throw LlmException(
                502,
                "端侧推理失败（code=$code）：可能模型格式不支持或内存不足。试试更小的量化模型。",
            )
        }
        if (sb.isEmpty()) {
            throw LlmException(502, "端侧模型没有产生任何输出，请检查模型文件是否完整。")
        }
        return ChatResult(content = sb.toString(), raw = sb.toString(), finishReason = "stop")
    }

    private fun chatOpenAi(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: ((String) -> Unit)?,
        cancelled: () -> Boolean,
    ): ChatResult {
        val streaming = onDelta != null
        val body = buildBody(config, messages, tools, stream = streaming).toString()
        val req = request(config, config.baseUrl.trimEnd('/') + "/chat/completions", body)
        return executeCall(config, req, cancelled) { resp ->
            val rawHead = if (streaming) "" else resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val raw = if (streaming) resp.body?.string().orEmpty() else rawHead
                throw LlmException(resp.code, "LLM HTTP ${resp.code}: ${raw.take(800)}")
            }
            val result = if (streaming) {
                readSse(resp.body!!.source(), onDelta, cancelled)
            } else {
                parseChatResponse(rawHead)
            }
            attachParsedTools(result, tools)
        }
    }

    private fun chatOllama(
        config: LlmConfig,
        messages: List<ChatMessage>,
        onDelta: ((String) -> Unit)?,
        cancelled: () -> Boolean,
    ): ChatResult {
        val root = config.baseUrl.trimEnd('/').removeSuffix("/v1").removeSuffix("/api")
        val streaming = onDelta != null
        val msgs = JSONArray()
        messages.forEach { m ->
            msgs.put(JSONObject().put("role", ollamaRole(m.role)).put("content", m.content))
        }
        val body = JSONObject()
            .put("model", config.model.ifBlank { "llama3.2" })
            .put("stream", streaming)
            .put("messages", msgs)
            .put(
                "options",
                JSONObject()
                    .put("temperature", config.temperature)
                    .put("num_predict", config.maxTokens),
            )
            .toString()
        val req = request(config, "$root/api/chat", body)
        return executeCall(config, req, cancelled) { resp ->
            val raw = if (!streaming) resp.body?.string().orEmpty() else ""
            if (!resp.isSuccessful) {
                val err = if (streaming) resp.body?.string().orEmpty() else raw
                throw LlmException(resp.code, "Ollama HTTP ${resp.code}: ${err.take(400)}")
            }
            if (streaming) {
                val assembler = StreamAssembler()
                val source = resp.body!!.source()
                while (!source.exhausted()) {
                    if (aborted(cancelled)) throw LlmException(499, "cancelled")
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val piece = obj.optJSONObject("message")?.optString("content").orEmpty()
                    if (piece.isNotEmpty()) {
                        assembler.applyJson(
                            JSONObject()
                                .put(
                                    "choices",
                                    JSONArray().put(
                                        JSONObject().put("delta", JSONObject().put("content", piece)),
                                    ),
                                )
                                .toString(),
                        )
                        onDelta.invoke(piece)
                    }
                    if (obj.optBoolean("done")) break
                }
                attachParsedTools(assembler.result(), emptyList())
            } else {
                val obj = JSONObject(raw)
                val content = obj.optJSONObject("message")?.optString("content").orEmpty()
                attachParsedTools(ChatResult(content = content, raw = raw), emptyList())
            }
        }
    }

    private fun chatLlamaCpp(
        config: LlmConfig,
        messages: List<ChatMessage>,
        onDelta: ((String) -> Unit)?,
        cancelled: () -> Boolean,
    ): ChatResult {
        val root = config.baseUrl.trimEnd('/').removeSuffix("/v1")
        val prompt = messages.joinToString("\n") { "${it.role}: ${it.content}" } + "\nassistant:"
        val streaming = onDelta != null
        val body = JSONObject()
            .put("prompt", prompt)
            .put("n_predict", config.maxTokens)
            .put("temperature", config.temperature)
            .put("stream", streaming)
            .toString()
        val req = request(config, "$root/completion", body)
        return executeCall(config, req, cancelled) { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw LlmException(resp.code, "llama.cpp HTTP ${resp.code}: ${err.take(400)}")
            }
            if (streaming) {
                attachParsedTools(readSse(resp.body!!.source(), onDelta, cancelled), emptyList())
            } else {
                val raw = resp.body?.string().orEmpty()
                val obj = JSONObject(raw)
                val content = obj.optString("content").ifBlank { obj.optString("response") }
                attachParsedTools(ChatResult(content = content, raw = raw), emptyList())
            }
        }
    }

    private fun readSse(
        source: okio.BufferedSource,
        onDelta: ((String) -> Unit)?,
        cancelled: () -> Boolean,
    ): ChatResult {
        val assembler = StreamAssembler()
        while (!source.exhausted()) {
            if (aborted(cancelled)) throw LlmException(499, "cancelled")
            val line = source.readUtf8Line() ?: break
            val piece = assembler.applyLine(line)
            if (piece != null) onDelta?.invoke(piece)
            if (line.trim() == "data: [DONE]") break
        }
        return assembler.result()
    }

    private fun attachParsedTools(result: ChatResult, tools: List<ToolSpec>): ChatResult {
        if (result.toolCalls.isEmpty() && (tools.isNotEmpty() || result.content.isNotBlank())) {
            val parsed = ToolCallParser.parse(result.content)
            if (parsed.isNotEmpty()) {
                return result.copy(
                    content = ToolCallParser.stripMarkup(result.content),
                    toolCalls = parsed,
                    finishReason = "tool_calls",
                )
            }
        }
        return result
    }

    private fun request(config: LlmConfig, url: String, body: String): Request {
        val b = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(json))
        config.authHeader()?.let { b.addHeader("Authorization", it) }
        return b.build()
    }

    private fun ollamaRole(role: String): String = when (role) {
        "tool" -> "user"
        else -> role
    }

    private fun buildBody(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        stream: Boolean,
    ): JSONObject {
        val root = JSONObject()
        root.put("model", config.model)
        root.put("temperature", config.temperature)
        root.put("max_tokens", config.maxTokens)
        root.put("stream", stream)

        val msgs = JSONArray()
        messages.forEach { m ->
            val obj = JSONObject()
            obj.put("role", m.role)
            obj.put("content", m.content)
            if (m.name != null) obj.put("name", m.name)
            if (m.toolCallId != null) obj.put("tool_call_id", m.toolCallId)
            if (m.toolCalls.isNotEmpty()) {
                val arr = JSONArray()
                m.toolCalls.forEach { tc ->
                    arr.put(JSONObject().apply {
                        put("id", tc.id)
                        put("type", tc.type)
                        put("function", JSONObject().apply {
                            put("name", tc.function.name)
                            put("arguments", tc.function.arguments)
                        })
                    })
                }
                obj.put("tool_calls", arr)
            }
            msgs.put(obj)
        }
        root.put("messages", msgs)

        if (tools.isNotEmpty()) {
            val ta = JSONArray()
            tools.forEach { t ->
                ta.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", JsonUtil.obj(t.parameters))
                    })
                })
            }
            root.put("tools", ta)
        }
        return root
    }

    private fun parseChatResponse(raw: String): ChatResult {
        val obj = JsonUtil.obj(raw)
        val choice = obj.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException(500, "No choices in response: $raw")
        val msg = choice.optJSONObject("message")
        val content = msg?.optString("content") ?: ""
        val calls = ArrayList<ToolCall>()
        msg?.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function")
                calls += ToolCall(
                    id = tc.optString("id", "call_$i"),
                    type = tc.optString("type", "function"),
                    function = ToolFunction(
                        name = fn?.optString("name").orEmpty(),
                        arguments = fn?.optString("arguments", "{}").orEmpty(),
                    ),
                )
            }
        }
        return ChatResult(
            content = content,
            toolCalls = calls,
            raw = raw,
            finishReason = choice.optString("finish_reason", "stop"),
        )
    }

    private fun aborted(cancelled: () -> Boolean): Boolean = httpAbort.get() || cancelled()

    private fun httpClient(config: LlmConfig): OkHttpClient {
        val sec = config.timeoutSeconds.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        return client.newBuilder()
            .connectTimeout(sec.coerceAtMost(30), TimeUnit.SECONDS)
            .readTimeout(sec, TimeUnit.SECONDS)
            .writeTimeout(sec.coerceAtMost(60), TimeUnit.SECONDS)
            .callTimeout(sec, TimeUnit.SECONDS)
            .build()
    }

    private fun <T> executeCall(
        config: LlmConfig,
        req: Request,
        cancelled: () -> Boolean,
        body: (Response) -> T,
    ): T {
        val call = httpClient(config).newCall(req)
        inFlight.set(call)
        try {
            if (aborted(cancelled)) {
                call.cancel()
                throw LlmException(499, "cancelled")
            }
            call.execute().use { resp ->
                if (aborted(cancelled)) throw LlmException(499, "cancelled")
                return body(resp)
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            if (aborted(cancelled)) {
                throw LlmException(499, "cancelled")
            }
            if (isTimeout(e)) {
                throw LlmException(
                    504,
                    "请求超时（${config.timeoutSeconds.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)}s）。请提高超时，或检查网络/本机服务是否卡住。",
                )
            }
            if (call.isCanceled()) {
                throw LlmException(499, "cancelled")
            }
            throw LlmException(502, "网络错误：${e.message}")
        } finally {
            inFlight.compareAndSet(call, null)
            if (aborted(cancelled)) call.cancel()
        }
    }

    companion object {
        private const val MIN_TIMEOUT_SEC = 5L
        private const val MAX_TIMEOUT_SEC = 600L

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        internal fun isTimeout(e: Throwable): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is SocketTimeoutException) return true
                if (cur is InterruptedIOException && cur.message.orEmpty().contains("timeout", ignoreCase = true)) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }
    }
}

class LlmException(val code: Int, message: String) : RuntimeException(message)
