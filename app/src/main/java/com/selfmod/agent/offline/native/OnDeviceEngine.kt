package com.selfmod.agent.offline.native

/**
 * The contract the app layer relies on for on-device inference, shared by the
 * llama.cpp/GGUF engine ([LocalLlmEngine]) and the MNN engine ([MnnLlmEngine]).
 *
 * Callers ([com.selfmod.agent.llm.LlmClient], the offline screen) only ever see
 * this interface, so which backend runs is decided once, by the model's format,
 * instead of being baked into every call site.
 */
interface OnDeviceEngine {

    interface TokenCallback {
        /** @return true to continue, false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    interface LoadCallback {
        /** @param progress 0.0 .. 1.0 */
        fun onProgress(progress: Float)
    }

    /** True when a model is loaded and the native engine reports ready. */
    fun isLoaded(): Boolean

    /** Free the loaded model. Safe to call when nothing is loaded. */
    fun unload()

    /** Abort an in-flight generation. Safe to call from any thread. */
    fun cancel()

    /** Drop a leftover abort so the next request can start. */
    fun clearAbort()

    /**
     * Chat completion using the model's own chat template.
     *
     * @param messages (role, content) pairs, oldest first.
     * @return number of generated tokens; negative on error (-98 cancelled,
     *         -99 idle timeout, -5 context overflow, -1 not loaded).
     */
    fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        onToken: TokenCallback? = null,
    ): Int

    /** Context window in tokens. */
    fun contextSize(): Int

    /** The chat template family the engine reports, or "" when not loaded. */
    fun chatTemplate(): String

    /** One-line perf summary for the diagnostics log; "" when unavailable. */
    fun lastChatStats(): String
}
