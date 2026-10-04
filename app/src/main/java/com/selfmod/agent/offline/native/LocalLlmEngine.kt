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

    // M13: single-flight load/unload gate. The native layer serializes on its
    // own mutex, but overlapping Kotlin load() calls used to free each other's
    // freshly loaded models (multi-GB mmap thrash) and could commit a stale
    // `loaded` flag after an unload had freed the engine.
    private val lifecycle = EngineLifecycle(loadedSetter = { loaded = it })

    fun isLoaded(): Boolean = loaded && ensureLoaded() && nativeIsReady()

    /**
     * @param nCtx context window (tokens). Larger = more RAM. 1024 is safer on 6GB phones.
     * @param nThreads worker threads; pass 0 to auto-detect.
     * @param nBatch prompt-eval chunk size. Smaller = less RAM / slower prefill.
     * @return false when another load is already in flight.
     */
    fun load(
        modelFile: File,
        nCtx: Int = 1024,
        nThreads: Int = 0,
        nBatch: Int = 64,
        onProgress: LoadCallback? = null,
    ): Boolean {
        val token = lifecycle.beginLoad() ?: run {
            com.selfmod.agent.util.Diagnostics.log("engine", "load: 已有加载在进行，忽略本次请求")
            return false
        }
        var attempted = false
        var ok = false
        try {
            if (!ensureLoaded()) {
                com.selfmod.agent.util.Diagnostics.log("engine", "load: native 库不可用")
                return false
            }
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
            attempted = true
            ok = nativeInit(modelFile.absolutePath, nCtx, threads, nBatch, onProgress)
            com.selfmod.agent.util.Diagnostics.log(
                "engine",
                "load 返回 $ok，耗时 ${System.currentTimeMillis() - t0}ms",
            )
            return ok
        } finally {
            lifecycle.endLoad(token, ok, attempted)
        }
    }

    fun unload() {
        // withUnload: exclusive access + version bump, so an in-flight load's
        // late commit is suppressed; nativeFree itself waits for the native
        // mutex, i.e. a load in progress finishes first, then is freed.
        lifecycle.withUnload {
            if (!ensureLoaded()) return@withUnload
            nativeCancel()
            nativeFree()
        }
    }

    /** Abort in-flight load or generate. Safe to call from any thread. */
    fun cancel() {
        if (!ensureLoaded()) return
        nativeCancel()
    }

    /** Drop a leftover abort so the next request can start. */
    fun clearAbort() {
        if (!ensureLoaded()) return
        nativeClearAbort()
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
        val code = IdleWatchdog.run(timeoutMs, lastTokenAt, aborted, { nativeCancel() }) {
            nativeChat(tmpl, useRoles, useContents, maxTokens, temperature, topK, topP, wrapped)
        }
        com.selfmod.agent.util.Diagnostics.log(
            "engine", "chat: 返回 $code，总耗时 ${System.currentTimeMillis() - t0}ms",
        )
        return code
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
    private external fun nativeCancel()
    private external fun nativeClearAbort()
    private external fun nativeIsReady(): Boolean
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

        /** M14 — tell the native signal handler where to write last_crash.txt. */
        fun setNativeCrashDir(dir: String) {
            if (!ensureLoaded()) return
            runCatching { nativeSetCrashDir(dir) }
        }

        private external fun nativeSetCrashDir(crashDir: String)

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
