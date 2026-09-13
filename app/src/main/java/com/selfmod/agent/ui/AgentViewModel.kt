package com.selfmod.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfmod.agent.App
import com.selfmod.agent.UiEvent
import com.selfmod.agent.agent.AgentStep
import com.selfmod.agent.llm.ChatMessage
import com.selfmod.agent.llm.LlmConfig
import com.selfmod.agent.repo.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One rendered line in the agent trace. */
data class TraceEntry(
    val ts: Long,
    val kind: String,        // thought | action | observation | answer | error | started | user
    val title: String,
    val body: String,
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val agent = app.agent

    private val history = mutableListOf<ChatMessage>()
    private val _trace = MutableStateFlow<List<TraceEntry>>(emptyList())
    val trace: StateFlow<List<TraceEntry>> = _trace.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast = _toast.asSharedFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        reset()
    }

    fun onInputTextChange(t: String) { _inputText.value = t }

    fun send() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _busy.value) return
        push(TraceEntry(System.currentTimeMillis(), "user", "你", text))
        _inputText.value = ""
        _busy.value = true
        viewModelScope.launch {
            try {
                agent.run(history, text) { step -> push(step.toTrace()) }
            } catch (e: Throwable) {
                push(TraceEntry(System.currentTimeMillis(), "error", "异常", e.message ?: e.toString()))
            } finally {
                _busy.value = false
            }
        }
    }

    fun reset() {
        history.clear()
        history.add(ChatMessage("system", agent.systemPrompt()))
        _trace.value = emptyList()
    }

    fun config(): LlmConfig = app.settings.llmConfig()
    fun setConfig(cfg: LlmConfig) {
        app.settings.setLlmConfig(cfg)
        // System prompt doesn't change with config, so no reset needed.
    }

    fun repo() = app.repo
    fun plugins() = app.plugins
    fun settings() = app.settings
    val uiEventsFlow get() = app.uiEvents

    fun ingestUiEvent(e: UiEvent) {
        push(TraceEntry(System.currentTimeMillis(), "ui", "UI事件: ${e.action}", e.payload))
    }

    // --------------------------- Scripts -----------------------------

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

    // --------------------------- Plugins ----------------------------

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
        _trace.value = _trace.value + entry
    }

    private fun AgentStep.toTrace(): TraceEntry = when (this) {
        AgentStep.Started -> TraceEntry(System.currentTimeMillis(), "started", "开始", "")
        is AgentStep.Thought -> TraceEntry(System.currentTimeMillis(), "thought", "思考", text)
        is AgentStep.Action -> TraceEntry(System.currentTimeMillis(), "action", "行动: $tool", args)
        is AgentStep.Observation -> TraceEntry(System.currentTimeMillis(), "observation", "观察", result)
        is AgentStep.Answer -> TraceEntry(System.currentTimeMillis(), "answer", "答复", text)
        is AgentStep.Error -> TraceEntry(System.currentTimeMillis(), "error", "错误", message)
    }
}
