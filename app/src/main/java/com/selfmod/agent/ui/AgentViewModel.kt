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
    val id: Long = nextId(),
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextId(): Long = counter.incrementAndGet()
    }
}

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
        // Release any browser action still blocked waiting for a page, so the
        // run's IO thread returns immediately instead of burning its timeout.
        app.browser.abortWaits()
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
        // Non-blocking: the UI must never wait on a page load (H6).
        app.browser.open(url)
    }

    /** Opens a URL in the shared in-app browser (used by model download pages). */
    fun openInBrowser(url: String) {
        _requestedTab.value = 1
        app.browser.open(url)
    }

    private val _requestedTab = MutableStateFlow(-1)
    val requestedTab: StateFlow<Int> = _requestedTab.asStateFlow()
    fun consumeRequestedTab(): Int {
        val t = _requestedTab.value
        _requestedTab.value = -1
        return t
    }

    private val _engineStatus = MutableStateFlow("")
    val engineStatus: StateFlow<String> = _engineStatus.asStateFlow()
    private val _engineBusy = MutableStateFlow(false)
    val engineBusy: StateFlow<Boolean> = _engineBusy.asStateFlow()
    private val _engineReady = MutableStateFlow(app.engine.isLoaded())
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    private val _engineProgress = MutableStateFlow(-1f)
    /** 0..1 while copying/loading, -1 when idle. */
    val engineProgress: StateFlow<Float> = _engineProgress.asStateFlow()

    /** Copies the GGUF into app storage and loads it into the on-device engine. */
    fun loadOnDevice(m: LocalModel) {
        if (_engineBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _engineBusy.value = true
            _engineStatus.value = "正在检查设备…"
            com.selfmod.agent.util.Diagnostics.log(
                "load", "开始加载 ${m.name} size=${m.sizeBytes / (1024 * 1024)}MB uri=${m.uri}",
            )
            try {
                app.engine.unload()
                _engineReady.value = false

                // Pre-flight checks. IMPORTANT: llama.cpp uses *native* memory (mmap),
                // so the Java heap limit is irrelevant. We check real system RAM and
                // free disk space (materialize() copies the file into app storage).
                val neededMb = m.sizeBytes / (1024L * 1024L)

                val freeDiskMb = freeDiskMb()
                // Copy needs roughly the model size on disk (plus slack).
                if (neededMb + 128 > freeDiskMb) {
                    _engineStatus.value =
                        "存储不足：复制该模型约需 ${neededMb}MB，剩余约 ${freeDiskMb}MB。" +
                            "请清理空间后重试。"
                    return@launch
                }

                val ram = ramSnapshotMb()
                // Llama keeps weights + KV cache + compute buffers. Estimate ~1.3x.
                val estimateMb = neededMb + neededMb / 3 + 256
                val warn = if (estimateMb > ram.first) {
                    "⚠ 内存偏紧（需约 ${estimateMb}MB，可用 ${ram.first}MB / 总 ${ram.second}MB），加载后可能卡顿。\n"
                } else ""

                _engineStatus.value = warn + "正在准备模型文件…"
                val file = app.models.materialize(m.id) { progress ->
                    if (progress < 0f) {
                        _engineProgress.value = -1f
                        _engineStatus.value = warn + "模型文件已在本地，无需复制。"
                    } else {
                        _engineProgress.value = progress
                        val pct = (progress * 100).toInt().coerceIn(0, 100)
                        _engineStatus.value = warn + "正在复制模型到 App 目录… $pct%"
                    }
                } ?: run {
                    _engineStatus.value = "无法读取模型文件"
                    com.selfmod.agent.util.Diagnostics.log("load", "materialize 返回 null")
                    return@launch
                }
                com.selfmod.agent.util.Diagnostics.log(
                    "load", "文件就绪 ${file.absolutePath} size=${file.length() / (1024 * 1024)}MB",
                )
                _engineProgress.value = -1f
                _engineStatus.value = warn + "正在加载到内存（mmap 映射，通常更快）…"
                // 6GB phones thrash with ctx=2048 + batch=512. Keep KV and
                // prefill buffers modest so the first token arrives in tens of
                // seconds instead of minutes of swapping.
                val totalRamMb = ram.second
                val ctxCap = when {
                    totalRamMb < 7000 -> 1024
                    totalRamMb < 11000 -> 2048
                    else -> 4096
                }
                val ctx = if (m.contextLength > 0) {
                    m.contextLength.toInt().coerceIn(512, ctxCap)
                } else ctxCap
                val nBatch = when {
                    totalRamMb < 7000 -> 32
                    totalRamMb < 11000 -> 64
                    else -> 128
                }
                val nThreads = when {
                    totalRamMb < 7000 -> 4
                    else -> 0
                }
                com.selfmod.agent.util.Diagnostics.log(
                    "load", "ram=${ram.first}/${ram.second}MB ctx=$ctx nBatch=$nBatch nThreads=$nThreads",
                )
                val ok = app.engine.load(
                    file,
                    nCtx = ctx,
                    nThreads = nThreads,
                    nBatch = nBatch,
                    onProgress = object : com.selfmod.agent.offline.native.LocalLlmEngine.LoadCallback {
                        override fun onProgress(progress: Float) {
                            _engineProgress.value = progress
                            val pct = (progress * 100).toInt().coerceIn(0, 100)
                            _engineStatus.value = warn + "正在加载模型… $pct%"
                        }
                    },
                )
                _engineProgress.value = -1f
                if (ok) {
                    _engineReady.value = true
                    _engineStatus.value = warn + "已加载：${m.name}（ctx $ctx）"
                    val cfg = app.settings.llmConfig().copy(
                        kind = LlmConfig.KIND_ONDEVICE,
                        model = m.name.substringBeforeLast('.'),
                        profileName = "端侧 · ${m.name.substringBeforeLast('.')}",
                        supportsNativeTools = false,
                        onDeviceModelPath = file.absolutePath,
                        onDeviceContext = ctx,
                    )
                    setConfig(cfg)
                    app.settings.setOfflineMode(true)
                    _offlineMode.value = true
                } else {
                    _engineStatus.value = "加载失败：模型格式不支持或内存不足（需 arm64 设备）"
                }
            } catch (e: Throwable) {
                _engineStatus.value = "加载异常：${e.message}"
            } finally {
                _engineBusy.value = false
                _engineProgress.value = -1f
            }
        }
    }

    private fun freeDiskMb(): Long {
        val dir = getApplication<android.app.Application>().filesDir
        val stat = android.os.StatFs(dir.absolutePath)
        return stat.availableBytes / (1024L * 1024L)
    }

    /** @return (availableMb, totalMb) of real physical RAM. */
    private fun ramSnapshotMb(): Pair<Long, Long> {
        val info = android.app.ActivityManager.MemoryInfo()
        val am = getApplication<android.app.Application>()
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getMemoryInfo(info)
        return (info.availMem / (1024L * 1024L)) to (info.totalMem / (1024L * 1024L))
    }

    fun unloadOnDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            app.engine.unload()
            _engineReady.value = false
            _engineStatus.value = "已卸载端侧模型"
        }
    }

    private val _selfTest = MutableStateFlow("")
    val selfTest: StateFlow<String> = _selfTest.asStateFlow()
    private val _selfTesting = MutableStateFlow(false)
    val selfTesting: StateFlow<Boolean> = _selfTesting.asStateFlow()

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        _diagnostics.value = com.selfmod.agent.util.Diagnostics.dump()
    }

    fun clearDiagnostics() {
        com.selfmod.agent.util.Diagnostics.clear()
        _diagnostics.value = "（暂无日志）"
    }

    // ---- storage ----

    fun storageUsage(): List<com.selfmod.agent.util.DirUsage> = app.storage.usage()
    fun storageTotal(): Long = app.storage.total()
    fun storageFree(): Long = app.storage.freeDisk()

    private val _storageMsg = MutableStateFlow("")
    val storageMsg: StateFlow<String> = _storageMsg.asStateFlow()

    fun clearCache() {
        val freed = app.storage.clearCache()
        _storageMsg.value = "已清理缓存，释放 ${com.selfmod.agent.util.StorageStats.human(freed)}"
    }

    fun clearModelCopies() {
        val freed = app.storage.clearModelCopies()
        app.engine.unload()
        _engineReady.value = false
        _storageMsg.value = "已删除模型副本，释放 ${com.selfmod.agent.util.StorageStats.human(freed)}"
    }

    fun clearScriptVersions() {
        val freed = app.storage.clearScriptVersions()
        _storageMsg.value = "已清理历史版本，释放 ${com.selfmod.agent.util.StorageStats.human(freed)}"
    }

    fun clearSession() {
        runCatching { app.sessions.clear() }
        _storageMsg.value = "已清空会话记录"
    }

    /** Runs a minimal on-device inference and reports diagnostics. */
    fun runEngineSelfTest() {
        if (_selfTesting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _selfTesting.value = true
            val sb = StringBuilder()
            try {
                if (!app.engine.isLoaded()) {
                    _selfTest.value = "未加载模型。请先在某个模型上点「在本机加载」。"
                    return@launch
                }
                sb.appendLine("引擎已加载 ✓")
                sb.appendLine("chat 模板: ${app.engine.chatTemplate()}")
                sb.appendLine("上下文: ${app.engine.contextSize()}")
                sb.appendLine()

                app.engine.clearAbort()
                val t0 = System.currentTimeMillis()
                val out = StringBuilder()
                val code = app.engine.chat(
                    messages = listOf("user" to "Say hello in one short sentence."),
                    maxTokens = 24,
                    temperature = 0.2f,
                    timeoutMs = 120_000,
                    onToken = object : com.selfmod.agent.offline.native.LocalLlmEngine.TokenCallback {
                        override fun onToken(piece: String): Boolean {
                            out.append(piece)
                            return true
                        }
                    },
                )
                val dt = System.currentTimeMillis() - t0
                sb.appendLine("推理返回码: $code")
                sb.appendLine("耗时: ${dt}ms")
                val text = out.toString()
                sb.appendLine("输出: ${text.ifBlank { "（空！模型没有产生任何 token）" }}")
                if (code == -99 && text.isNotBlank()) {
                    sb.appendLine("说明: 空闲看门狗触发，但已有部分输出，引擎仍保持加载。")
                }
            } catch (e: Throwable) {
                sb.appendLine("自检异常: ${e.message}")
            } finally {
                _selfTest.value = sb.toString()
                _selfTesting.value = false
            }
        }
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
            _trace.value = cur + TraceEntry(System.currentTimeMillis(), "stream", "", text)
        }
    }

    /** Removes a trailing live-stream bubble whose text equals the final message. */
    private fun dropRedundantStream(finalText: String) {
        val cur = _trace.value
        val last = cur.lastOrNull() ?: return
        if (last.kind == "stream" && last.body.trim() == finalText.trim()) {
            _trace.value = cur.dropLast(1)
        }
    }

    private fun AgentStep.toTrace(): TraceEntry {
        if (this is AgentStep.StreamDelta) {
            appendDelta(text)
            return TraceEntry(System.currentTimeMillis(), "delta", "", "")
        }
        return when (this) {
            AgentStep.Started -> TraceEntry(System.currentTimeMillis(), "started", "开始", "")
            is AgentStep.Thought -> {
                dropRedundantStream(text)
                streamBuffer.clear()
                TraceEntry(System.currentTimeMillis(), "thought", "思考", text)
            }
            is AgentStep.Action -> {
                streamBuffer.clear()
                TraceEntry(System.currentTimeMillis(), "action", tool, args)
            }
            is AgentStep.Observation -> TraceEntry(System.currentTimeMillis(), "observation", "观察", result)
            is AgentStep.Answer -> {
                dropRedundantStream(text)
                streamBuffer.clear()
                TraceEntry(System.currentTimeMillis(), "answer", "答复", text)
            }
            is AgentStep.Error -> {
                streamBuffer.clear()
                TraceEntry(System.currentTimeMillis(), "error", "错误", message)
            }
            is AgentStep.StreamDelta -> TraceEntry(System.currentTimeMillis(), "delta", "", "")
        }
    }
}
