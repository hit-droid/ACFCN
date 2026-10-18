package com.selfmod.agent.offline.native

import java.io.File

/**
 * On-device MNN inference. The MNN runtime is the prebuilt 3.6.1 Android build
 * (libllm.so + libMNN*.so) shipped in jniLibs; this class is the JNI wrapper.
 *
 * Mirrors [LocalLlmEngine] so the app layer can hold either engine behind the
 * same chat()/cancel()/load() contract.
 */
class MnnLlmEngine {

    interface TokenCallback {
        fun onToken(piece: String): Boolean
    }

    interface LoadCallback {
        fun onProgress(progress: Float)
    }

    @Volatile
    private var loaded = false

    fun isLoaded(): Boolean = loaded && ensureLoaded() && nativeIsReady()

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

    fun unload() {
        if (!ensureLoaded()) return
        nativeCancel()
        nativeFree()
        loaded = false
    }

    fun cancel() {
        if (!ensureLoaded()) return
        nativeCancel()
    }

    fun clearAbort() {
        if (!ensureLoaded()) return
        nativeClearAbort()
    }

    /**
     * @param messages (role, content) pairs; MNN applies the jinja chat_template from
     *                 config.json internally, so no template folding happens here.
     * @return the number of generated tokens, or -1 (engine missing), -98 (user cancel).
     */
    fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        onToken: TokenCallback? = null,
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
        val wrapped = object : TokenCallback {
            override fun onToken(piece: String): Boolean = onToken?.onToken(piece) ?: true
        }
        val code = nativeChat(roles, contents, maxTokens, temperature, topK, topP, wrapped)
        val totalMs = ""
        com.selfmod.agent.util.Diagnostics.log("mnn", "chat: 返回 $code")
        return code
    }

    fun contextSize(): Int = nativeContextSize()
    fun chatTemplate(): String = nativeChatTemplate()
    fun lastChatStats(): String = if (loaded) runCatching { nativeLastChatStats() }.getOrDefault("") else ""

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
