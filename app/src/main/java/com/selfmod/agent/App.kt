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
import com.selfmod.agent.util.CrashStore
import com.selfmod.agent.util.StorageStats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/** A structured event pushed from agent/scripts/tools to the UI layer. */
data class UiEvent(val action: String, val payload: String)

/**
 * L16 — the old `MutableSharedFlow(extraBufferCapacity = 32)` lost events two different
 * ways, silently both times:
 *
 * 1. replay = 0, and the only collector lives in `AgentScreen`'s `LaunchedEffect`. Switch
 *    to another tab and nothing is subscribed: every event fired from then on is gone.
 * 2. Once the 32-slot buffer is full `tryEmit` just returns false — the caller (script
 *    host, agent tools) never looks at it, so there is not even a log line.
 *
 * A buffered `Channel` keeps the backlog for the next collector instead. Overflow policy is
 * SUSPEND so `trySend` can actually report a full queue; the newest event is then dropped
 * and counted, and the UI turns the count into a visible line rather than swallowing it.
 */
internal class UiEventQueue(capacity: Int = UI_QUEUE_CAPACITY) {
    private val channel = Channel<UiEvent>(capacity = capacity)
    private val _dropped = MutableStateFlow(0)

    /** Events waiting for the screen to come back; single-consumer by design. */
    val events: Flow<UiEvent> = channel.receiveAsFlow()

    /** How many offers were refused since the last acknowledge. */
    val dropped: StateFlow<Int> = _dropped.asStateFlow()

    /** Never suspends — this is called from script/agent threads as well as the UI. */
    fun offer(action: String, payload: String): Boolean {
        val ok = channel.trySend(UiEvent(action, payload)).isSuccess
        // Offers come from whatever thread the script/agent runs on, so the counter
        // is bumped with CAS, not read-modify-write.
        if (!ok) {
            while (true) {
                val n = _dropped.value
                if (_dropped.compareAndSet(n, n + 1)) break
            }
        }
        return ok
    }

    /** Called after the drop count has been shown, so it is reported exactly once. */
    fun acknowledgeDropped(): Int {
        var n = _dropped.value
        while (n != 0 && !_dropped.compareAndSet(n, 0)) n = _dropped.value
        return n
    }

    companion object {
        /** Same order of magnitude as before, but now it holds instead of dropping. */
        const val UI_QUEUE_CAPACITY = 64
    }
}

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
    lateinit var crashStore: CrashStore
    lateinit var storage: StorageStats

    internal val uiQueue = UiEventQueue()
    val uiEvents: Flow<UiEvent> = uiQueue.events

    private val uiNotifier: (String, String) -> Unit = { action, payload ->
        uiQueue.offer(action, payload)
    }

    override fun onCreate() {
        super.onCreate()
        crashStore = CrashStore(this)
        storage = StorageStats(this)
        runCatching { CrashHandler.install(crashStore) }
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
