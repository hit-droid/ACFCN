package com.selfmod.agent.script

/**
 * The only surface a user/agent-authored JavaScript script may touch.
 * Implementations bridge to Android runtime services (HTTP, LLM, file store,
 * plugin loader, UI). Keeping this narrow is what makes "self-modifying code"
 * safe: a buggy or hostile script cannot escape the sandbox.
 */
interface ScriptApi {
    fun log(msg: String)
    fun toast(msg: String)

    fun httpGet(url: String, headers: String = "{}"): String
    fun httpPost(url: String, body: String, headers: String = "{}"): String

    fun llm(prompt: String): String
    fun llmChat(messagesJson: String): String

    fun readScript(name: String): String?
    fun writeScript(name: String, content: String): Boolean
    fun listScripts(): String                       // JSON array of names

    fun loadPlugin(name: String): Boolean
    fun listPlugins(): String                        // JSON array of names

    fun memoryGet(key: String): String?
    fun memorySet(key: String, value: String)

    fun uiNotify(action: String, payload: String)
    fun now(): Long
}

/** Wraps another api and records each log line via [onLog]. */
class LoggingScriptApi(
    private val inner: ScriptApi,
    private val onLog: (String) -> Unit,
) : ScriptApi {
    override fun log(msg: String) { onLog(msg); inner.log(msg) }
    override fun toast(msg: String) { onLog("toast: $msg"); inner.toast(msg) }
    override fun httpGet(url: String, headers: String): String =
        inner.httpGet(url, headers).also { onLog("HTTP GET $url -> ${it.take(120)}") }
    override fun httpPost(url: String, body: String, headers: String): String =
        inner.httpPost(url, body, headers).also { onLog("HTTP POST $url -> ${it.take(120)}") }
    override fun llm(prompt: String): String =
        inner.llm(prompt).also { onLog("LLM ask -> ${it.take(120)}") }
    override fun llmChat(messagesJson: String): String =
        inner.llmChat(messagesJson).also { onLog("LLM chat -> ${it.take(120)}") }
    override fun readScript(name: String): String? = inner.readScript(name)
    override fun writeScript(name: String, content: String): Boolean =
        inner.writeScript(name, content).also { ok -> onLog("writeScript $name -> $ok (${content.length}c)") }
    override fun listScripts(): String = inner.listScripts()
    override fun loadPlugin(name: String): Boolean =
        inner.loadPlugin(name).also { ok -> onLog("loadPlugin $name -> $ok") }
    override fun listPlugins(): String = inner.listPlugins()
    override fun memoryGet(key: String): String? = inner.memoryGet(key)
    override fun memorySet(key: String, value: String) {
        inner.memorySet(key, value); onLog("store.set $key")
    }
    override fun uiNotify(action: String, payload: String) {
        inner.uiNotify(action, payload); onLog("ui.notify $action")
    }
    override fun now(): Long = inner.now()
}
