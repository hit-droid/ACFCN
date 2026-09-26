package com.selfmod.agent.offline.native

import java.io.File

/**
 * On-device GGUF inference via llama.cpp (JNI). CPU only, arm64-v8a.
 *
 * Load a model once, then [generate] / [chat] stream decoded pieces through
 * [onToken]; return false from [onToken] to cancel generation.
 */
class LocalLlmEngine {

    interface TokenCallback {
        /** @return true to continue, false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    interface LoadCallback {
        /** @param progress 0.0 .. 1.0 */
        fun onProgress(progress: Float)
    }

    @Volatile
    private var loaded = false

    fun isLoaded(): Boolean = loaded && nativeIsReady()

    /**
     * @param nCtx context window (tokens). Larger = more RAM. 2048 is a safe default.
     * @param nThreads worker threads; pass 0 to auto-detect.
     */
    fun load(
        modelFile: File,
        nCtx: Int = 2048,
        nThreads: Int = 0,
        onProgress: LoadCallback? = null,
    ): Boolean {
        if (!modelFile.exists()) return false
        val threads = if (nThreads > 0) nThreads else defaultThreads()
        loaded = nativeInit(modelFile.absolutePath, nCtx, threads, onProgress)
        return loaded
    }

    fun unload() {
        if (loaded) {
            nativeFree()
            loaded = false
        }
    }

    /** Raw completion. Returns number of tokens emitted, negative on error. */
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        onToken: TokenCallback? = null,
    ): Int {
        if (!isLoaded()) return -1
        return nativeGenerate(prompt, maxTokens, temperature, topK, topP, onToken)
    }

    /** Chat completion using the model's built-in chat template. */
    fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        onToken: TokenCallback? = null,
        timeoutMs: Long = 120_000,
    ): Int {
        if (!isLoaded()) return -1
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        val tmpl = nativeChatTemplate()
        return runWithWatchdog(timeoutMs) {
            nativeChat(tmpl, roles, contents, maxTokens, temperature, topK, topP, onToken)
        }
    }

    /**
     * Runs native work on a worker thread and aborts (returns -99) if it takes
     * longer than [timeoutMs]. Prevents a stuck native decode from freezing the
     * caller forever.
     */
    private fun runWithWatchdog(timeoutMs: Long, block: () -> Int): Int {
        val result = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val worker = Thread {
            runCatching { result.set(block()) }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            // Cannot safely interrupt native code; report timeout and let it die
            // with the process. The engine is marked unusable to avoid reuse.
            loaded = false
            return -99
        }
        return result.get()
    }

    fun contextSize(): Int = nativeContextSize()

    /** The chat template the model reports (falls back to "chatml"). */
    fun chatTemplate(): String = if (loaded) nativeChatTemplate() else ""

    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 6)
    }

    private external fun nativeInit(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        progressCallback: LoadCallback?,
    ): Boolean
    private external fun nativeFree()
    private external fun nativeIsReady(): Boolean
    private external fun nativeGenerate(
        prompt: String, maxTokens: Int, temperature: Float, topK: Int,
        topP: Float, callback: TokenCallback?,
    ): Int
    private external fun nativeChat(
        template: String, roles: Array<String>, contents: Array<String>,
        maxTokens: Int, temperature: Float, topK: Int, topP: Float,
        callback: TokenCallback?,
    ): Int
    private external fun nativeChatTemplate(): String
    private external fun nativeContextSize(): Int

    companion object {
        @Volatile
        private var libLoaded = false

        fun ensureLoaded(): Boolean {
            if (libLoaded) return true
            return synchronized(this) {
                if (libLoaded) return@synchronized true
                libLoaded = runCatching { System.loadLibrary("acfcn_llm") }.isSuccess
                libLoaded
            }
        }
    }
}
