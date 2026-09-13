package com.selfmod.agent

import android.app.Application
import com.selfmod.agent.agent.AgentCore
import com.selfmod.agent.llm.LlmClient
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.script.ScriptApi
import com.selfmod.agent.script.ScriptEngine
import com.selfmod.agent.script.ScriptHost
import com.selfmod.agent.store.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/** A structured event pushed from agent/scripts/tools to the UI layer. */
data class UiEvent(val action: String, val payload: String)

/**
 * Application-wide singletons. Wiring everything here (rather than a DI
 * framework) keeps the codebase small and easy to read — the whole point of
 * the app is "everything visible and editable".
 */
class App : Application() {
    lateinit var settings: SettingsStore
    lateinit var repo: CodeRepository
    lateinit var plugins: PluginRegistry
    lateinit var llmClient: LlmClient
    lateinit var scriptEngine: ScriptEngine
    lateinit var scriptHost: ScriptApi
    lateinit var agent: AgentCore

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
    val uiEvents = _uiEvents.asSharedFlow()

    private val uiNotifier: (String, String) -> Unit = { action, payload ->
        _uiEvents.tryEmit(UiEvent(action, payload))
    }

    override fun onCreate() {
        super.onCreate()
        val files = filesDir
        settings = SettingsStore(this)
        repo = CodeRepository(
            scriptsDir = File(files, "scripts"),
            pluginsDir = File(files, "plugins"),
            versionsDir = File(files, "versions"),
        )
        // First-run bootstrap: seed built-in example scripts.
        runCatching { repo.importAssetScript(this, "hello.js", "hello") }
        runCatching { repo.importAssetScript(this, "demo.js", "demo") }
        plugins = PluginRegistry(repo, File(files, "odex"))
        llmClient = LlmClient()
        scriptEngine = ScriptEngine()
        scriptHost = ScriptHost(
            appContext = this,
            repo = repo,
            plugins = plugins,
            settings = settings,
            llmClient = llmClient,
            uiNotifier = uiNotifier,
        )
        agent = AgentCore(
            llmClient = llmClient,
            settings = settings,
            scriptEngine = scriptEngine,
            scriptHost = scriptHost,
            repo = repo,
            plugins = plugins,
            uiNotifier = uiNotifier,
        )
    }

    fun uiNotifier(): (String, String) -> Unit = uiNotifier
}
