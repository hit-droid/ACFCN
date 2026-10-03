package com.selfmod.agent.agent

import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.browser.BrowserOutcome
import com.selfmod.agent.llm.ToolSpec
import com.selfmod.agent.offline.LocalModelStore
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.script.ScriptApi
import com.selfmod.agent.script.ScriptEngine
import com.selfmod.agent.store.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every capability the agent may use on the device is exposed here as a [ToolDef].
 * The set is intentionally small and auditable: run JS, edit & version scripts,
 * manage dex plugins, persist memory, fetch URLs, push UI updates.
 */
object Tools {

    fun all(
        scriptEngine: ScriptEngine,
        scriptHost: ScriptApi,
        repo: CodeRepository,
        plugins: PluginRegistry,
        settings: SettingsStore,
        uiNotifier: (String, String) -> Unit,
        browser: BrowserController,
        models: LocalModelStore,
    ): List<ToolDef> = listOf(
        executeJs(scriptEngine, scriptHost),
        readScript(repo),
        writeScript(repo),
        listScripts(repo),
        deleteScript(repo),
        listVersions(repo),
        rollback(repo),
        loadPlugin(plugins),
        listPlugins(plugins),
        invokePlugin(plugins),
        unloadPlugin(plugins),
        installPlugin(repo, plugins),
        getMemory(settings),
        setMemory(settings),
        listMemory(settings),
        httpGet(scriptHost),
        httpPost(scriptHost),
        uiNotify(uiNotifier),
        browserOpen(browser),
        browserSnapshot(browser),
        browserClick(browser),
        browserType(browser),
        browserExtract(browser),
        browserScroll(browser),
        browserEval(browser),
        browserBack(browser),
        listLocalModels(models),
        offlineStatus(settings),
    )

    // ----------------------------------------------------------------- JS

    private fun executeJs(engine: ScriptEngine, host: ScriptApi) = ToolDef(
        spec = ToolSpec(
            name = "execute_js",
            description = "Run JavaScript now inside the app sandbox. Globals available: api, log, toast, http{get,post}, llm{ask,chat}, store{get,set}, scripts{read,write,list}, plugins{load,list}, ui{notify}, now(). Use this to think, compute, fetch data, and to push live changes to the app. Return value becomes the tool result.",
            parameters = """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript source to execute"},"name":{"type":"string","description":"Optional filename for error reporting"}},"required":["code"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val code = o.optString("code").ifBlank { o.optString("input") }
            if (code.isBlank()) {
                ToolRegistry.errJson("code required")
            } else {
                val name = o.optString("name", "agent.js")
                val res = engine.run(code, host, name)
                val out = JSONObject()
                out.put("value", res.value?.toString() ?: "null")
                out.put("logs", res.logs.joinToString("\n").take(2000))
                out.put("error", res.error ?: JSONObject.NULL)
                out.toString()
            }
        },
    )

    // ------------------------------------------------------------- scripts

    private fun readScript(repo: CodeRepository) = ToolDef(
        spec = ToolSpec(
            name = "read_script",
            description = "Read the current body of a stored JavaScript script by name.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
        run = { args ->
            val name = JSONObject(args).getString("name")
            if (!repo.scriptExists(name)) ToolRegistry.errJson("no such script: $name")
            else ToolRegistry.okJson("loaded", mapOf("name" to name, "content" to repo.readScript(name)))
        },
    )

    private fun writeScript(repo: CodeRepository) = ToolDef(
        spec = ToolSpec(
            name = "write_script",
            description = "Create or overwrite a stored script. The previous version is automatically snapshotted and can be rolled back. The script is NOT auto-executed — use execute_js to run it.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"}},"required":["name","content"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val name = o.getString("name")
            val content = o.getString("content")
            val versionId = repo.writeScript(name, content)
            ToolRegistry.okJson("saved", mapOf("name" to name, "version" to versionId, "bytes" to content.length))
        },
    )

    private fun listScripts(repo: CodeRepository) = ToolDef(
        spec = ToolSpec(
            name = "list_scripts",
            description = "List names of all stored scripts.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = { JSONArray(repo.listScripts()).toString() },
    )

    private fun deleteScript(repo: CodeRepository) = ToolDef(
        spec = ToolSpec(
            name = "delete_script",
            description = "Permanently delete a stored script (versions are kept).",
            parameters = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
        run = { args ->
            val name = JSONObject(args).getString("name")
            ToolRegistry.okJson("deleted", mapOf("name" to name, "ok" to repo.deleteScript(name)))
        },
    )

    private fun listVersions(repo: CodeRepository) = ToolDef(
        spec = ToolSpec(
            name = "list_versions",
            description = "List prior saved versions of a script (newest first).",
            parameters = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
        run = { args ->
            val name = JSONObject(args).getString("name")
            val arr = JSONArray()
            repo.listVersions(name).forEach { v ->
                arr.put(JSONObject().apply {
                    put("id", v.id); put("ts", v.timestamp); put("bytes", v.sizeBytes)
                })
            }
            arr.toString()
        },
    )

    private fun rollback(repo: CodeRepository) = ToolDef(
        spec = ToolSpec(
            name = "rollback",
            description = "Restore a script to a prior version. Use list_versions to get version ids.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"},"version":{"type":"string"}},"required":["name","version"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val name = o.getString("name")
            val ver = o.getString("version")
            val content = repo.rollback(name, ver)
            ToolRegistry.okJson("rolled back", mapOf("name" to name, "version" to ver, "bytes" to content.length))
        },
    )

    // ------------------------------------------------------------- plugins

    private fun loadPlugin(plugins: PluginRegistry) = ToolDef(
        spec = ToolSpec(
            name = "load_plugin",
            description = "Load (or return cached) a dex plugin by name. Returns its descriptor.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
        run = { args ->
            val name = JSONObject(args).getString("name")
            runCatching { plugins.load(name) }
                .fold(
                    onSuccess = { p -> ToolRegistry.okJson("loaded", mapOf("id" to p.id, "version" to p.version, "describe" to p.describe())) },
                    onFailure = { ToolRegistry.errJson(it.message ?: it.toString()) },
                )
        },
    )

    private fun listPlugins(plugins: PluginRegistry) = ToolDef(
        spec = ToolSpec(
            name = "list_plugins",
            description = "List installed dex plugins (by name) and which are currently loaded.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = {
            val o = JSONObject()
            o.put("installed", JSONArray(plugins.available()))
            o.put("loaded", JSONArray(plugins.loaded()))
            o.toString()
        },
    )

    private fun invokePlugin(plugins: PluginRegistry) = ToolDef(
        spec = ToolSpec(
            name = "invoke_plugin",
            description = "Invoke an action on a loaded dex plugin. payload must be a JSON string.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"},"action":{"type":"string"},"payload":{"type":"string"}},"required":["name","action"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val name = o.getString("name")
            val action = o.getString("action")
            val payload = o.optString("payload", "{}")
            val p = plugins.get(name)
            if (p == null) {
                ToolRegistry.errJson("plugin not loaded: $name (call load_plugin first)")
            } else {
                runCatching { p.invoke(action, payload) }
                    .getOrElse { ToolRegistry.errJson(it.message ?: it.toString()) }
            }
        },
    )

    private fun unloadPlugin(plugins: PluginRegistry) = ToolDef(
        spec = ToolSpec(
            name = "unload_plugin",
            description = "Unload a previously loaded dex plugin so the next load uses a fresh classloader.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
        run = { args ->
            val name = JSONObject(args).getString("name")
            plugins.unload(name)
            ToolRegistry.okJson("unloaded", mapOf("name" to name))
        },
    )

    private fun installPlugin(repo: CodeRepository, plugins: PluginRegistry) = ToolDef(
        spec = ToolSpec(
            name = "install_plugin",
            description = "Install a dex plugin from a base64 payload, then optionally load it. entry is the fully-qualified Java class implementing SelfModPlugin.",
            parameters = """{"type":"object","properties":{"name":{"type":"string"},"entry":{"type":"string"},"dex_b64":{"type":"string"},"load":{"type":"boolean"}},"required":["name","entry","dex_b64"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val name = o.getString("name")
            val entry = o.getString("entry")
            val b64 = o.getString("dex_b64")
            val load = o.optBoolean("load", true)
            val bytes = runCatching {
                android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            }
            if (bytes.isFailure) {
                ToolRegistry.errJson("invalid base64: ${bytes.exceptionOrNull()?.message}")
            } else {
                repo.installPlugin(name, bytes.getOrNull()!!, entry)
                if (load) plugins.load(name)
                ToolRegistry.okJson("installed", mapOf("name" to name, "entry" to entry, "loaded" to load))
            }
        },
    )

    // -------------------------------------------------------------- memory

    private fun getMemory(settings: SettingsStore) = ToolDef(
        spec = ToolSpec(
            name = "get_memory",
            description = "Read a value from the agent's persistent key-value memory.",
            parameters = """{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""",
        ),
        run = { args ->
            val k = JSONObject(args).getString("key")
            val v = settings.memoryGet(k)
            ToolRegistry.okJson("read", mapOf("key" to k, "value" to v, "exists" to (v != null)))
        },
    )

    private fun setMemory(settings: SettingsStore) = ToolDef(
        spec = ToolSpec(
            name = "set_memory",
            description = "Write a value to the agent's persistent key-value memory (survives restarts).",
            parameters = """{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"}},"required":["key","value"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            settings.memorySet(o.getString("key"), o.getString("value"))
            ToolRegistry.okJson("stored", mapOf("key" to o.getString("key")))
        },
    )

    private fun listMemory(settings: SettingsStore) = ToolDef(
        spec = ToolSpec(
            name = "list_memory",
            description = "List all keys currently held in persistent memory.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = { JSONArray(settings.memoryKeys()).toString() },
    )

    // --------------------------------------------------------------- http

    private fun httpGet(host: ScriptApi) = ToolDef(
        spec = ToolSpec(
            name = "http_get",
            description = "Perform an HTTP GET. headers is a JSON object of header->value.",
            parameters = """{"type":"object","properties":{"url":{"type":"string"},"headers":{"type":"string"}},"required":["url"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val url = o.getString("url")
            val headers = o.optString("headers", "{}")
            ToolRegistry.okJson("done", mapOf("status" to "ok", "body" to host.httpGet(url, headers).take(8000)))
        },
    )

    private fun httpPost(host: ScriptApi) = ToolDef(
        spec = ToolSpec(
            name = "http_post",
            description = "Perform an HTTP POST with a string body. headers is a JSON object.",
            parameters = """{"type":"object","properties":{"url":{"type":"string"},"body":{"type":"string"},"headers":{"type":"string"}},"required":["url","body"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val url = o.getString("url")
            val body = o.getString("body")
            val headers = o.optString("headers", "{}")
            ToolRegistry.okJson("done", mapOf("status" to "ok", "body" to host.httpPost(url, body, headers).take(8000)))
        },
    )

    // ------------------------------------------------------------------ ui

    private fun uiNotify(notifier: (String, String) -> Unit) = ToolDef(
        spec = ToolSpec(
            name = "ui_notify",
            description = "Push a structured update to the app UI. action is a label like 'show_panel' or 'toast'; payload is a JSON string with whatever the UI handler expects.",
            parameters = """{"type":"object","properties":{"action":{"type":"string"},"payload":{"type":"string"}},"required":["action"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val action = o.getString("action")
            val payload = o.optString("payload", "{}")
            notifier(action, payload)
            ToolRegistry.okJson("sent", mapOf("action" to action))
        },
    )

    // ----------------------------------------------------------- browser

    /**
     * L3: browser replies used to be free-form text (`loaded url=…`, `clicked #3 A`,
     * `typed 3`, a bare page dump, `ERROR: …`), so the model had to guess which shape
     * it was looking at and a stale-index refusal read like page content. Everything
     * the browser tools return now goes through this one envelope.
     */
    private fun browserResult(raw: String) = BrowserOutcome.classify(raw).envelope()

    private fun browserOpen(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_open",
            description = "Navigate the in-app browser to a URL or search query. User and agent share the same WebView. " +
                "Returns {ok, status:loaded|navigating|timeout|cancelled|error, url, title, data:<snapshot>}.",
            parameters = """{"type":"object","properties":{"url":{"type":"string","description":"URL or search query"}},"required":["url"]}""",
        ),
        run = { args ->
            val url = JSONObject(args).optString("url").ifBlank {
                JSONObject(args).optString("input")
            }
            if (url.isBlank()) ToolRegistry.errJson("url required")
            else browserResult(browser.navigate(url))
        },
    )

    private fun browserSnapshot(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_snapshot",
            description = "Take a snapshot of the current page: URL, title, readable text, and numbered interactive elements. " +
                "Use the index with browser_click / browser_type. Returns {ok, status, data:<snapshot>}.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = { browserResult(browser.snapshot()) },
    )

    private fun browserClick(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_click",
            description = "Click an interactive element by its snapshot index. " +
                "A drifted index fails with ok=false — take a fresh browser_snapshot instead of retrying.",
            parameters = """{"type":"object","properties":{"index":{"type":"integer"}},"required":["index"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val idx = if (o.has("index")) o.getInt("index") else o.optInt("input", -1)
            if (idx < 0) ToolRegistry.errJson("index required") else browserResult(browser.click(idx))
        },
    )

    private fun browserType(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_type",
            description = "Type text into an input/textarea identified by snapshot index. " +
                "A drifted index fails with ok=false — take a fresh browser_snapshot instead of retrying.",
            parameters = """{"type":"object","properties":{"index":{"type":"integer"},"text":{"type":"string"}},"required":["index","text"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            browserResult(browser.type(o.getInt("index"), o.optString("text")))
        },
    )

    private fun browserExtract(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_extract",
            description = "Extract visible text of the current page. Returns {ok, status, data:<text>}.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = { browserResult(browser.extractText()) },
    )

    private fun browserScroll(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_scroll",
            description = "Scroll the page. direction is down, up, or top.",
            parameters = """{"type":"object","properties":{"direction":{"type":"string"}},"required":[]}""",
        ),
        run = { args ->
            val dir = JSONObject(args).optString("direction", "down")
            browserResult(browser.scroll(dir.ifBlank { "down" }))
        },
    )

    private fun browserEval(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_eval",
            description = "Run JavaScript in the current page. Returns {ok, status, data:<js result>}.",
            parameters = """{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}""",
        ),
        run = { args ->
            val o = JSONObject(args)
            val code = o.optString("code").ifBlank { o.optString("input") }
            browserResult(browser.evalJs(code))
        },
    )

    private fun browserBack(browser: BrowserController) = ToolDef(
        spec = ToolSpec(
            name = "browser_back",
            description = "Go back in the in-app browser history. Returns {ok, status:back, url, title}.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = { browserResult(browser.goBack()) },
    )

    private fun listLocalModels(models: LocalModelStore) = ToolDef(
        spec = ToolSpec(
            name = "list_local_models",
            description = "List GGUF/ONNX models the user imported for offline use.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = {
            val arr = JSONArray()
            models.list().forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id); put("name", m.name)
                    put("format", m.format); put("size", m.sizeLabel())
                    put("architecture", m.architecture); put("quant", m.quant)
                    put("context", m.contextLength); put("summary", m.summary)
                })
            }
            arr.toString()
        },
    )

    private fun offlineStatus(settings: SettingsStore) = ToolDef(
        spec = ToolSpec(
            name = "offline_status",
            description = "Report whether offline mode is on and which LLM endpoint is active.",
            parameters = """{"type":"object","properties":{}}""",
        ),
        run = {
            val cfg = settings.llmConfig()
            JSONObject().apply {
                put("offline_mode", settings.offlineMode())
                put("kind", cfg.kind)
                put("baseUrl", cfg.baseUrl)
                put("model", cfg.model)
                put("local", cfg.isLocalHost())
                put("usable", cfg.isUsable())
            }.toString()
        },
    )
}
