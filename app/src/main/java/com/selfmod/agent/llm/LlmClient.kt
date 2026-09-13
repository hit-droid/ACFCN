package com.selfmod.agent.llm

import com.selfmod.agent.util.JsonUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Synchronous OpenAI-compatible /chat/completions client.
 *  Callers are expected to dispatch off the main thread. */
class LlmClient(
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
    ): ChatResult {
        val body = buildBody(config, messages, tools).toString()
        val req = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LlmException(
                    code = resp.code,
                    message = "LLM HTTP ${resp.code}: ${raw.take(800)}",
                )
            }
            return parseChatResponse(raw)
        }
    }

    private fun buildBody(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): JSONObject {
        val root = JSONObject()
        root.put("model", config.model)
        root.put("temperature", config.temperature)
        root.put("max_tokens", config.maxTokens)
        root.put("stream", false)

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
                        // parameters is a JSON-schema string; embed as object
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

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

class LlmException(val code: Int, message: String) : RuntimeException(message)
