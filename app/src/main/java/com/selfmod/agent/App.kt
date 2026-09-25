package com.selfmod.agent

import android.app.Application
import android.util.Log
import com.selfmod.agent.agent.AgentCore
import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.llm.ConnectionTester
import com.selfmod.agent.llm.LlmClient
import com.selfmod.agent.offline.LocalModelStore
import com.selfmod.agent.offline.native.LocalLlmEngine
import com.selfmod.agent.plugin.PluginRegistry
import com.selfmod.agent.repo.CodeRepository
import com.selfmod.agent.script.ScriptApi
import com.selfmod.agent.script.ScriptEngine
import com.selfmod.agent.script.ScriptHost
import com.selfmod.agent.store.SessionStore
import com.selfmod.agent.store.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/** A structured event pushed from agent/scripts/tools to the UI layer. */
data class UiEvent(val action: String, val payload: String)

private const val TAG = "ACFCN"

class App : Application() {
    lateinit var settings: SettingsStore
    lateinit var repo: CodeRepository
    lateinit var plugins: PluginRegistry
    lateinit var llmClient: LlmClient
    lateinit var scriptEngine: ScriptEngine
    lateinit var scriptHost: ScriptApi
    lateinit var agent: AgentCore
    lateinit var browser: BrowserController
    lateinit var models: LocalModelStore
    lateinit var tester: ConnectionTester
    lateinit var sessions: SessionStore
    lateinit var engine: LocalLlmEngine

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
    val uiEvents = _uiEvents.asSharedFlow()

    private val uiNotifier: (String, String) -> Unit = { action, payload ->
        _uiEvents.tryEmit(UiEvent(action, payload))
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { initAll() }.onFailure {
            Log.e(TAG, "App init FAILED — app will start but features may be broken", it)
        }
    }

    private fun initAll() {
        val files = filesDir
        settings = SettingsStore(this)
        repo = CodeRepository(
            scriptsDir = File(files, "scripts"),
            pluginsDir = File(files, "plugins"),
            versionsDir = File(files, "versions"),
        )
        runCatching { repo.importAssetScript(this, "hello.js", "hello") }
        runCatching { repo.importAssetScript(this, "demo.js", "demo") }
        runCatching { repo.importAssetScript(this, "offline_agent.js", "offline_agent") }
        plugins = PluginRegistry(repo, File(files, "odex"))
        engine = LocalLlmEngine()
        LocalLlmEngine.ensureLoaded()
        llmClient = LlmClient(onDevice = engine)
        scriptEngine = ScriptEngine()
        browser = BrowserController()
        models = LocalModelStore(this)
        tester = ConnectionTester()
        sessions = SessionStore(this)
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
            browser = browser,
            models = models,
        )
        Log.i(TAG, "App initialized OK")
    }

    fun uiNotifier(): (String, String) -> Unit = uiNotifier
}
