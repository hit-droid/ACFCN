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
     * @param nCtx context window (tokens). Larger = more RAM. 1024 is safer on 6GB phones.
     * @param nThreads worker threads; pass 0 to auto-detect.
     * @param nBatch prompt-eval chunk size. Smaller = less RAM / slower prefill.
     */
    fun load(
        modelFile: File,
        nCtx: Int = 1024,
        nThreads: Int = 0,
        nBatch: Int = 64,
        onProgress: LoadCallback? = null,
    ): Boolean {
        if (!modelFile.exists()) {
            com.selfmod.agent.util.Diagnostics.log("engine", "load: 文件不存在 ${modelFile.absolutePath}")
            return false
        }
        val threads = if (nThreads > 0) nThreads else defaultThreads()
        com.selfmod.agent.util.Diagnostics.log(
            "engine",
            "load: ${modelFile.name} size=${modelFile.length() / (1024 * 1024)}MB " +
                "nCtx=$nCtx nBatch=$nBatch threads=$threads exists=${modelFile.canRead()}",
        )
        val t0 = System.currentTimeMillis()
        loaded = nativeInit(modelFile.absolutePath, nCtx, threads, nBatch, onProgress)
        com.selfmod.agent.util.Diagnostics.log(
            "engine",
            "load 返回 $loaded，耗时 ${System.currentTimeMillis() - t0}ms",
        )
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
        if (!isLoaded()) {
            com.selfmod.agent.util.Diagnostics.log("engine", "chat: 未加载")
            return -1
        }
        val rawTmpl = nativeChatTemplate()
        val tmpl = com.selfmod.agent.offline.OnDeviceTemplates.resolve(rawTmpl)
        val folded = com.selfmod.agent.offline.OnDeviceTemplates.foldSystem(messages, tmpl)
        val useRoles = folded.map { it.first }.toTypedArray()
        val useContents = folded.map { it.second }.toTypedArray()
        com.selfmod.agent.util.Diagnostics.log(
            "engine",
            "chat: resolved=$tmpl msgs=${folded.size} (raw=${messages.size}) maxTokens=$maxTokens idleTimeout=${timeoutMs}ms",
        )
        val t0 = System.currentTimeMillis()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val lastTokenAt = java.util.concurrent.atomic.AtomicLong(t0)
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val wrapped = object : TokenCallback {
            override fun onToken(piece: String): Boolean {
                if (aborted.get()) return false
                lastTokenAt.set(System.currentTimeMillis())
                if (first.compareAndSet(true, false)) {
                    com.selfmod.agent.util.Diagnostics.log(
                        "engine", "chat: 首 token 耗时 ${System.currentTimeMillis() - t0}ms",
                    )
                }
                return onToken?.onToken(piece) ?: true
            }
        }
        val code = runWithIdleWatchdog(timeoutMs, lastTokenAt, aborted) {
            nativeChat(tmpl, useRoles, useContents, maxTokens, temperature, topK, topP, wrapped)
        }
        com.selfmod.agent.util.Diagnostics.log(
            "engine", "chat: 返回 $code，总耗时 ${System.currentTimeMillis() - t0}ms",
        )
        return code
    }

    /**
     * Runs native work on a worker. Times out only after [idleMs] with no new
     * tokens (prefill + decode). First token on a 6GB phone can take 30–90s;
     * once tokens start flowing we keep waiting.
     */
    private fun runWithIdleWatchdog(
        idleMs: Long,
        lastTokenAt: java.util.concurrent.atomic.AtomicLong,
        aborted: java.util.concurrent.atomic.AtomicBoolean,
        block: () -> Int,
    ): Int {
        val result = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val worker = Thread {
            runCatching { result.set(block()) }
        }
        worker.isDaemon = true
        worker.start()
        val hardCap = (idleMs * 8).coerceAtMost(10 * 60_000L)
        val started = System.currentTimeMillis()
        while (worker.isAlive) {
            worker.join(2_000)
            if (!worker.isAlive) break
            val idle = System.currentTimeMillis() - lastTokenAt.get()
            val elapsed = System.currentTimeMillis() - started
            if (idle >= idleMs || elapsed >= hardCap) {
                aborted.set(true)
                com.selfmod.agent.util.Diagnostics.log(
                    "engine",
                    "chat: 空闲超时 idle=${idle}ms elapsed=${elapsed}ms（请 native 尽快停，引擎保持加载）",
                )
                return -99
            }
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
        nBatch: Int,
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
