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
import kotlinx.coroutines.flow.emptyFlow
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

/**
 * Thrown when a feature was not initialised (e.g. M10 — `App.initAll()` bailed out
 * partway). Replaces the old `UninitializedPropertyAccessException` whose message did
 * not even name the missing feature, and used to feed a crash loop.
 */
class AppNotInitializedException(feature: String, cause: Throwable? = null) :
    IllegalStateException("App feature '$feature' is not initialised", cause)

class App : Application() {
    // Backing fields. The public read-only properties below throw a controlled
    // [AppNotInitializedException] if a feature's init block failed, instead of the
    // previous [UninitializedPropertyAccessException] which had no feature name and
    // re-triggered the crash handler on every subsequent access.
    private var _settings: SettingsStore? = null
    private var _repo: CodeRepository? = null
    private var _plugins: PluginRegistry? = null
    private var _llmClient: LlmClient? = null
    private var _scriptEngine: ScriptEngine? = null
    private var _scriptHost: ScriptApi? = null
    private var _agent: AgentCore? = null
    private var _browser: BrowserController? = null
    private var _models: LocalModelStore? = null
    private var _tester: ConnectionTester? = null
    private var _sessions: SessionStore? = null
    private var _engine: LocalLlmEngine? = null
    private var _crashStore: CrashStore? = null
    private var _storage: StorageStats? = null

    // uiQueue is constructed eagerly and never fails in practice; guarded by a
    // nullable indirection so a future failure path can't take down every collector.
    internal var uiQueue: UiEventQueue? = UiEventQueue()
    val uiEvents: Flow<UiEvent> get() = uiQueue?.events ?: emptyFlow()

    val settings: SettingsStore get() = _settings ?: fail("settings")
    val repo: CodeRepository get() = _repo ?: fail("repo")
    val plugins: PluginRegistry get() = _plugins ?: fail("plugins")
    val llmClient: LlmClient get() = _llmClient ?: fail("llmClient")
    val scriptEngine: ScriptEngine get() = _scriptEngine ?: fail("scriptEngine")
    val scriptHost: ScriptApi get() = _scriptHost ?: fail("scriptHost")
    val agent: AgentCore get() = _agent ?: fail("agent")
    val browser: BrowserController get() = _browser ?: fail("browser")
    val models: LocalModelStore get() = _models ?: fail("models")
    val tester: ConnectionTester get() = _tester ?: fail("tester")
    val sessions: SessionStore get() = _sessions ?: fail("sessions")
    val engine: LocalLlmEngine get() = _engine ?: fail("engine")
    val crashStore: CrashStore get() = _crashStore ?: fail("crashStore")
    val storage: StorageStats get() = _storage ?: fail("storage")

    /** Names of features whose init block threw; surfaced by `AgentViewModel` to
     *  show the user what's broken instead of letting them tap a tab that crashes. */
    val failedFeatures: Set<String> get() = _failedFeatures
    private val _failedFeatures = mutableSetOf<String>()

    private val uiNotifier: (String, String) -> Unit = { action, payload ->
        uiQueue?.offer(action, payload)
    }

    override fun onCreate() {
        super.onCreate()
        // The two pieces that only need `Context` are constructed first so they are
        // always available — even if every later init step explodes.
        _crashStore = CrashStore(this)
        _storage = StorageStats(this)
        uiQueue = UiEventQueue()
        runCatching { CrashHandler.install(crashStore) }
        // Each feature block owns its own slot; failure on one feature is recorded
        // and contained so we never end up with a half-built singleton.
        initPersistence()
        initEngine()
        initScripts()
        initBrowser()
        initAgent()
    }

    private fun initPersistence() {
        runFeature("persistence") {
            val files = filesDir
            _settings = SettingsStore(this)
            _repo = CodeRepository(
                scriptsDir = File(files, "scripts"),
                pluginsDir = File(files, "plugins"),
                versionsDir = File(files, "versions"),
            )
            // Asset scripts are best-effort — missing assets are not a fatal init
            // error, just log and move on.
            runCatching { _repo?.importAssetScript(this, "hello.js", "hello") }
            runCatching { _repo?.importAssetScript(this, "demo.js", "demo") }
            runCatching { _repo?.importAssetScript(this, "offline_agent.js", "offline_agent") }
            _plugins = PluginRegistry(_repo!!, File(files, "odex"))
            _models = LocalModelStore(this)
            _tester = ConnectionTester()
            _sessions = SessionStore(this)
        }
    }

    private fun initEngine() {
        runFeature("engine") {
            _engine = LocalLlmEngine()
            LocalLlmEngine.ensureLoaded()
            _llmClient = LlmClient(onDevice = _engine!!)
        }
    }

    private fun initScripts() {
        runFeature("scripts") {
            _scriptEngine = ScriptEngine()
        }
    }

    private fun initBrowser() {
        runFeature("browser") {
            _browser = BrowserController()
        }
    }

    private fun initAgent() {
        // The agent depends on every preceding slot. If any dependency is null we
        // treat that as a hard skip — the user will see "agent unavailable" rather
        // than a half-built AgentCore that crashes on its first tool call.
        if (_settings == null || _repo == null || _plugins == null ||
            _llmClient == null || _scriptEngine == null || _browser == null ||
            _models == null
        ) {
            Log.w(TAG, "app_init agent skipped — missing dependency")
            _failedFeatures.add("agent")
            return
        }
        runFeature("agent") {
            _scriptHost = ScriptHost(
                appContext = this,
                repo = _repo!!,
                plugins = _plugins!!,
                settings = _settings!!,
                llmClient = _llmClient!!,
                uiNotifier = uiNotifier,
            )
            _agent = AgentCore(
                llmClient = _llmClient!!,
                settings = _settings!!,
                scriptEngine = _scriptEngine!!,
                scriptHost = _scriptHost!!,
                repo = _repo!!,
                plugins = _plugins!!,
                uiNotifier = uiNotifier,
                browser = _browser!!,
                models = _models!!,
            )
        }
    }

    private inline fun runFeature(feature: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "app_init $feature failed: ${t.javaClass.simpleName}: ${t.message}", t)
            _failedFeatures.add(feature)
            runCatching { _crashStore?.save(t) }
        }
    }

    private fun fail(feature: String): Nothing =
        throw AppNotInitializedException(feature)

    fun uiNotifier(): (String, String) -> Unit = uiNotifier
}