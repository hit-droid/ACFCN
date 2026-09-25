package com.selfmod.agent.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfmod.agent.App
import com.selfmod.agent.UiEvent
import com.selfmod.agent.agent.AgentStep
import com.selfmod.agent.browser.BrowserController
import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.ConnectionTestResult
import com.selfmod.agent.llm.LlmConfig
import com.selfmod.agent.llm.ProbeHit
import com.selfmod.agent.llm.SavedProfile
import com.selfmod.agent.offline.LocalModel
import com.selfmod.agent.repo.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TraceEntry(
    val ts: Long,
    val kind: String,
    val title: String,
    val body: String,
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val agent = app.agent

    private val history = mutableListOf<ChatMessage>()
    private val _trace = MutableStateFlow<List<TraceEntry>>(emptyList())
    val trace: StateFlow<List<TraceEntry>> = _trace.asStateFlow()

    private var runJob: Job? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastUserText = MutableStateFlow("")
    val lastUserText: StateFlow<String> = _lastUserText.asStateFlow()

    private val _showOnboarding = MutableStateFlow(!app.settings.onboardingDone())
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    private val _keysEncrypted = MutableStateFlow(app.settings.keysEncrypted)
    val keysEncrypted: StateFlow<Boolean> = _keysEncrypted.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast = _toast.asSharedFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _offlineMode = MutableStateFlow(app.settings.offlineMode())
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<ConnectionTestResult?>(null)
    val testResult: StateFlow<ConnectionTestResult?> = _testResult.asStateFlow()

    private val _listedModels = MutableStateFlow<List<String>>(emptyList())
    val listedModels: StateFlow<List<String>> = _listedModels.asStateFlow()

    private val _listingModels = MutableStateFlow(false)
    val listingModels: StateFlow<Boolean> = _listingModels.asStateFlow()

    private val _probeHits = MutableStateFlow<List<ProbeHit>>(emptyList())
    val probeHits: StateFlow<List<ProbeHit>> = _probeHits.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    init {
        reset()
        loadSession()
    }

    private fun loadSession() {
        runCatching {
            val saved = app.sessions.load()
            if (saved.isEmpty()) return
            history.addAll(saved)
            val restored = saved.filter { it.role == "user" || it.role == "assistant" }
                .map { m ->
                    val kind = if (m.role == "user") "user" else "answer"
                    val title = if (m.role == "user") "你" else "答复"
                    TraceEntry(System.currentTimeMillis(), kind, title, m.content)
                }
            _trace.value = restored
            _lastUserText.value = saved.lastOrNull { it.role == "user" }?.content.orEmpty()
        }
    }

    fun onInputTextChange(t: String) { _inputText.value = t }

    fun send() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _busy.value) return
        _lastUserText.value = text
        sendInternal(text)
    }

    fun stop() {
        agent.cancel()
        runJob?.cancel()
        runJob = null
        _busy.value = false
    }

    fun retryLast() {
        val text = _lastUserText.value.ifBlank {
            _trace.value.lastOrNull { it.kind == "user" }?.body.orEmpty()
        }
        if (text.isBlank() || _busy.value) return
        sendInternal(text)
    }

    private fun sendInternal(text: String) {
        if (_busy.value) return
        push(TraceEntry(System.currentTimeMillis(), "user", "你", text))
        _inputText.value = ""
        _busy.value = true
        runJob = viewModelScope.launch {
            try {
                if (history.none { it.role == "system" }) {
                    history.add(0, ChatMessage("system", agent.systemPrompt()))
                }
                agent.run(history, text) { step -> push(step.toTrace()) }
            } catch (e: Throwable) {
                push(TraceEntry(System.currentTimeMillis(), "error", "异常", e.message ?: e.toString()))
            } finally {
                _busy.value = false
                persistSession()
            }
        }
    }

    fun dismissOnboarding() {
        app.settings.setOnboardingDone(true)
        _showOnboarding.value = false
    }

    private fun persistSession() {
        runCatching { app.sessions.save(history) }
    }

    fun reset() {
        stop()
        history.clear()
        history.add(ChatMessage("system", agent.systemPrompt()))
        _trace.value = emptyList()
        _lastUserText.value = ""
        runCatching { app.sessions.clear() }
    }

    fun config(): LlmConfig = app.settings.llmConfig()
    fun setConfig(cfg: LlmConfig) {
        app.settings.setLlmConfig(cfg)
        history.removeAll { it.role == "system" }
        history.add(0, ChatMessage("system", agent.systemPrompt()))
    }

    fun repo() = app.repo
    fun plugins() = app.plugins
    fun settings() = app.settings
    fun browser(): BrowserController = app.browser
    val uiEventsFlow get() = app.uiEvents

    fun ingestUiEvent(e: UiEvent) {
        push(TraceEntry(System.currentTimeMillis(), "ui", "UI事件: ${e.action}", e.payload))
    }

    fun setOfflineMode(on: Boolean) {
        app.settings.setOfflineMode(on)
        _offlineMode.value = on
        history.removeAll { it.role == "system" }
        history.add(0, ChatMessage("system", agent.systemPrompt()))
    }

    fun testConnection() {
        if (_testing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _testing.value = true
            val r = runCatching { app.tester.test(app.settings.llmConfig()) }
                .getOrElse { ConnectionTestResult(false, 0, it.message ?: it.toString()) }
            _testResult.value = r
            if (r.models.isNotEmpty()) _listedModels.value = r.models
            _testing.value = false
        }
    }

    fun refreshModels() {
        if (_listingModels.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _listingModels.value = true
            val list = runCatching { app.tester.listModels(app.settings.llmConfig()) }
                .getOrDefault(emptyList())
            _listedModels.value = list
            _listingModels.value = false
        }
    }

    fun probeLocal() {
        if (_probing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _probing.value = true
            _probeHits.value = runCatching { app.tester.probeLocal() }.getOrDefault(emptyList())
            _probing.value = false
        }
    }

    fun applyLocalEndpoint(name: String, baseUrl: String, model: String) {
        val cfg = app.settings.llmConfig().copy(
            baseUrl = baseUrl,
            apiKey = "",
            model = model.ifBlank { app.settings.llmConfig().model },
            kind = LlmConfig.KIND_LOCAL,
            profileName = name,
            supportsNativeTools = name.contains("Ollama", ignoreCase = true),
        )
        setConfig(cfg)
        app.settings.setOfflineMode(true)
        _offlineMode.value = true
    }

    fun profiles(): List<SavedProfile> = app.settings.profiles()
    fun saveProfile(name: String) {
        val p = app.settings.saveCurrentAsProfile(name)
        app.settings.setActiveProfileId(p.id)
    }
    fun applyProfile(id: String) { app.settings.applyProfile(id) }
    fun deleteProfile(id: String) { app.settings.deleteProfile(id) }

    fun localModels(): List<LocalModel> = app.models.list()
    fun importModel(uri: Uri): LocalModel = app.models.importUri(uri)
    fun removeModel(id: String) { app.models.remove(id) }
    fun applyOfflineModel(m: LocalModel) {
        val stem = m.name.substringBeforeLast('.')
        val cfg = app.settings.llmConfig().copy(
            model = stem,
            kind = if (app.settings.llmConfig().isLocalHost()) LlmConfig.KIND_LOCAL else LlmConfig.KIND_OFFLINE,
            profileName = stem,
            supportsNativeTools = false,
        )
        setConfig(cfg)
        app.settings.setOfflineMode(true)
        _offlineMode.value = true
    }

    fun navigateBrowser(url: String) {
        viewModelScope.launch(Dispatchers.IO) { app.browser.navigate(url) }
    }

    data class ScriptRunResult(val logs: String, val error: String?, val value: String?)

    private val _scriptOutput = MutableStateFlow<ScriptRunResult?>(null)
    val scriptOutput: StateFlow<ScriptRunResult?> = _scriptOutput.asStateFlow()
    private val _scriptRunning = MutableStateFlow(false)
    val scriptRunning: StateFlow<Boolean> = _scriptRunning.asStateFlow()

    fun listScripts(): List<String> = app.repo.listScripts()
    fun scriptExists(name: String): Boolean = app.repo.scriptExists(name)
    fun readScript(name: String): String =
        if (app.repo.scriptExists(name)) app.repo.readScript(name) else ""
    fun saveScript(name: String, content: String): String = app.repo.writeScript(name, content)
    fun deleteScript(name: String) { app.repo.deleteScript(name) }
    fun versionsOf(name: String): List<Version> = app.repo.listVersions(name)
    fun rollbackScript(name: String, versionId: String): String = app.repo.rollback(name, versionId)

    fun runScript(code: String, name: String = "manual.js") {
        if (_scriptRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _scriptRunning.value = true
            try {
                val res = app.scriptEngine.run(code, app.scriptHost, name)
                _scriptOutput.value = ScriptRunResult(
                    logs = res.logs.joinToString("\n"),
                    error = res.error,
                    value = res.value?.toString(),
                )
            } catch (e: Throwable) {
                _scriptOutput.value = ScriptRunResult("", e.message ?: e.toString(), null)
            } finally {
                _scriptRunning.value = false
            }
        }
    }

    fun clearScriptOutput() { _scriptOutput.value = null }

    fun availablePlugins(): List<String> = app.plugins.available()
    fun loadedPlugins(): List<String> = app.plugins.loaded()
    fun loadPlugin(name: String) {
        runCatching { app.plugins.load(name) }
    }
    fun unloadPlugin(name: String) { app.plugins.unload(name) }
    fun deletePlugin(name: String) {
        app.plugins.unload(name)
        app.repo.deletePlugin(name)
    }

    private fun push(entry: TraceEntry) {
        if (entry.kind == "delta") return
        _trace.value = _trace.value + entry
    }

    private val streamBuffer = StringBuilder()

    private fun appendDelta(text: String) {
        streamBuffer.append(text)
        val cur = _trace.value
        val last = cur.lastOrNull()
        if (last != null && last.kind == "stream") {
            _trace.value = cur.dropLast(1) + last.copy(body = streamBuffer.toString())
        } else {
            streamBuffer.clear()
            streamBuffer.append(text)
            _trace.value = cur + TraceEntry(System.currentTimeMillis(), "stream", "流式", text)
        }
    }

    private fun AgentStep.toTrace(): TraceEntry = when (this) {
        AgentStep.Started -> TraceEntry(System.currentTimeMillis(), "started", "开始", "")
        is AgentStep.Thought -> {
            streamBuffer.clear()
            TraceEntry(System.currentTimeMillis(), "thought", "思考", text)
        }
        is AgentStep.Action -> {
            streamBuffer.clear()
            TraceEntry(System.currentTimeMillis(), "action", "行动: $tool", args)
        }
        is AgentStep.Observation -> TraceEntry(System.currentTimeMillis(), "observation", "观察", result)
        is AgentStep.Answer -> {
            streamBuffer.clear()
            TraceEntry(System.currentTimeMillis(), "answer", "答复", text)
        }
        is AgentStep.Error -> {
            streamBuffer.clear()
            TraceEntry(System.currentTimeMillis(), "error", "错误", message)
        }
        is AgentStep.StreamDelta -> {
            appendDelta(text)
            return TraceEntry(System.currentTimeMillis(), "delta", "", "")
        }
    }
}
