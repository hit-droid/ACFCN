package com.selfmod.agent.script

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.LlmClient
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.store.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Concrete [ScriptApi] backed by Android services. */
class ScriptHost(
    private val appContext: Context,
    private val repo: CodeRepository,
    private val plugins: PluginRegistry,
    private val settings: SettingsStore,
    private val llmClient: LlmClient,
    private val httpClient: OkHttpClient = defaultHttp(),
    private val uiNotifier: (String, String) -> Unit = { _, _ -> },
) : ScriptApi {

    private val main = Handler(Looper.getMainLooper())

    override fun log(msg: String) { /* collected by LoggingScriptApi wrapper */ }

    override fun toast(msg: String) {
        main.post { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun httpGet(url: String, headers: String): String {
        val req = Request.Builder().url(url).apply {
            parseHeaders(headers).forEach { (k, v) -> addHeader(k, v) }
        }.get().build()
        httpClient.newCall(req).execute().use { r -> return r.body?.string().orEmpty() }
    }

    override fun httpPost(url: String, body: String, headers: String): String {
        val mt = "application/json".toMediaType()
        val req = Request.Builder().url(url).apply {
            parseHeaders(headers).forEach { (k, v) -> addHeader(k, v) }
        }.post(body.toRequestBody(mt)).build()
        httpClient.newCall(req).execute().use { r -> return r.body?.string().orEmpty() }
    }

    override fun llm(prompt: String): String {
        val r = llmClient.chat(settings.llmConfig(), listOf(ChatMessage("user", prompt)))
        return r.content
    }

    override fun llmChat(messagesJson: String): String {
        val arr = JSONArray(messagesJson)
        val msgs = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            msgs += ChatMessage(role = o.optString("role"), content = o.optString("content"))
        }
        return llmClient.chat(settings.llmConfig(), msgs).content
    }

    override fun readScript(name: String): String? = runCatching { repo.readScript(name) }.getOrNull()
    override fun writeScript(name: String, content: String): Boolean =
        runCatching { repo.writeScript(name, content) }.isSuccess
    override fun listScripts(): String = JSONArray(repo.listScripts()).toString()

    override fun loadPlugin(name: String): Boolean =
        runCatching { plugins.load(name); true }.getOrDefault(false)
    override fun listPlugins(): String = JSONArray(plugins.available()).toString()

    override fun memoryGet(key: String): String? = settings.memoryGet(key)
    override fun memorySet(key: String, value: String) = settings.memorySet(key, value)

    override fun uiNotify(action: String, payload: String) = uiNotifier(action, payload)
    override fun now(): Long = System.currentTimeMillis()

    private fun parseHeaders(s: String): List<Pair<String, String>> {
        if (s.isBlank()) return emptyList()
        val o = JSONObject(s)
        val out = ArrayList<Pair<String, String>>()
        for (k in o.keys()) out += k to o.optString(k)
        return out
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
