package com.selfmod.agent.offline.native

import java.io.File

/**
 * On-device MNN inference. The MNN runtime is the prebuilt 3.6.1 Android build
 * (libllm.so + libMNN*.so) shipped in jniLibs; this class is the JNI wrapper.
 *
 * Mirrors [LocalLlmEngine] so the app layer can hold either engine behind the
 * same chat()/cancel()/load() contract.
 */
class MnnLlmEngine : OnDeviceEngine {

    interface TokenCallback : OnDeviceEngine.TokenCallback
    interface LoadCallback : OnDeviceEngine.LoadCallback

    @Volatile
    private var loaded = false

    override fun isLoaded(): Boolean = loaded && ensureLoaded() && nativeIsReady()

    /**
     * @param modelDir a directory containing config.json + llm.mnn + llm.mnn.weight +
     *                 tokenizer.mtok + llm_config.json.
     */
    fun load(
        modelDir: File,
        nCtx: Int = 2048,
        nThreads: Int = 0,
        nBatch: Int = 128,
        onProgress: LoadCallback? = null,
    ): Boolean {
        if (!ensureLoaded()) {
            com.selfmod.agent.util.Diagnostics.log("mnn", "load: native 库不可用")
            return false
        }
        val cfg = modelDir.resolve("config.json").takeIf { it.exists() }
            ?: modelDir.resolve("llm_config.json")
        if (!cfg.exists()) {
            com.selfmod.agent.util.Diagnostics.log("mnn", "load: 找不到 config.json / llm_config.json 于 ${modelDir.absolutePath}")
            return false
        }
        val threads = if (nThreads > 0) nThreads else defaultThreads()
        com.selfmod.agent.util.Diagnostics.log(
            "mnn",
            "load: ${modelDir.name} ctx=$nCtx nBatch=$nBatch threads=$threads",
        )
        onProgress?.onProgress(0.1f)
        val t0 = System.currentTimeMillis()
        val ok = nativeInit(modelDir.absolutePath, nCtx, threads, nBatch, onProgress)
        com.selfmod.agent.util.Diagnostics.log(
            "mnn", "load 返回 $ok，耗时 ${System.currentTimeMillis() - t0}ms",
        )
        if (ok) loaded = true
        runCatching { nativeLoadInfo() }
            .getOrDefault("")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { com.selfmod.agent.util.Diagnostics.log("mnn", it) }
        return ok
    }

    override fun unload() {
        if (!ensureLoaded()) return
        // Signal first so an in-flight decode unwinds at its next step, then
        // free. nativeFree() refuses while a generation is in flight, so retry
        // briefly rather than leaving a multi-hundred-MB model resident.
        nativeCancel()
        repeat(20) {
            nativeFree()
            if (!nativeIsReady()) {
                loaded = false
                return
            }
            Thread.sleep(100)
        }
        loaded = false
        com.selfmod.agent.util.Diagnostics.log("mnn", "unload: 生成未在超时内结束，模型仍驻留")
    }

    override fun cancel() {
        if (!ensureLoaded()) return
        nativeCancel()
    }

    override fun clearAbort() {
        if (!ensureLoaded()) return
        nativeClearAbort()
    }

    /**
     * @param messages (role, content) pairs; MNN applies the jinja chat_template from
     *                 config.json internally, so no template folding happens here.
     * @return generated token count, or -1 (not loaded) / -98 (cancelled).
     */
    override fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        onToken: TokenCallback?,
    ): Int {
        if (!isLoaded()) {
            com.selfmod.agent.util.Diagnostics.log("mnn", "chat: 未加载")
            return -1
        }
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        com.selfmod.agent.util.Diagnostics.log(
            "mnn",
            "chat: msgs=${messages.size} maxTokens=$maxTokens temp=$temperature topK=$topK",
        )
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val t0 = System.currentTimeMillis()
        val lastTokenAt = java.util.concurrent.atomic.AtomicLong(t0)
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val firstTokenAt = java.util.concurrent.atomic.AtomicLong(0L)
        val wrapped = object : TokenCallback {
            override fun onToken(piece: String): Boolean {
                if (aborted.get()) return false
                lastTokenAt.set(System.currentTimeMillis())
                emitted.incrementAndGet()
                if (first.compareAndSet(true, false)) {
                    val now = System.currentTimeMillis()
                    firstTokenAt.set(now)
                    com.selfmod.agent.util.Diagnostics.log("mnn", "chat: 首 token 耗时 ${now - t0}ms")
                }
                return onToken?.onToken(piece) ?: true
            }
        }
        // MNN has no native USER_CANCEL; the abort flag is polled between
        // decode steps natively, and the watchdog aborts through nativeCancel().
        val code = IdleWatchdog.run(120_000, lastTokenAt, aborted, { nativeCancel() }) {
            nativeChat(roles, contents, maxTokens, temperature, topK, topP, wrapped)
        }
        val n = emitted.get()
        com.selfmod.agent.util.Diagnostics.log(
            "mnn", "chat: 返回 $code，总耗时 ${System.currentTimeMillis() - t0}ms，输出 $n 段",
        )
        return code
    }

    override fun contextSize(): Int = nativeContextSize()
    override fun chatTemplate(): String = if (loaded) nativeChatTemplate() else ""
    override fun lastChatStats(): String =
        if (loaded) runCatching { nativeLastChatStats() }.getOrDefault("") else ""

    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 8)
    }

    private external fun nativeInit(
        modelDir: String, nCtx: Int, nThreads: Int, nBatch: Int,
        progressCallback: LoadCallback?,
    ): Boolean
    private external fun nativeFree()
    private external fun nativeCancel()
    private external fun nativeClearAbort()
    private external fun nativeIsReady(): Boolean
    private external fun nativeChat(
        roles: Array<String>, contents: Array<String>,
        maxTokens: Int, temperature: Float, topK: Int, topP: Float,
        callback: TokenCallback?,
    ): Int
    private external fun nativeChatTemplate(): String
    private external fun nativeContextSize(): Int
    private external fun nativeLastChatStats(): String
    private external fun nativeLoadInfo(): String

    companion object {
        @Volatile
        private var libLoaded = false

        fun ensureLoaded(): Boolean {
            if (libLoaded) return true
            return synchronized(this) {
                if (libLoaded) return@synchronized true
                libLoaded = runCatching { System.loadLibrary("acfcn_mnn") }.isSuccess
                libLoaded
            }
        }
    }
}
