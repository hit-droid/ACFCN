package com.selfmod.agent.offline.native

/**
 * An [OnDeviceEngine] that forwards to whichever engine currently holds a
 * model ([OnDeviceEngines.active]).
 *
 * [com.selfmod.agent.llm.LlmClient] is built once, at app start, long before a
 * model is chosen — and the backend is not known until the user picks one. This
 * adapter closes that gap: the client keeps a single stable reference, and each
 * call lands on the engine that is actually loaded.
 *
 * Calls made with nothing loaded return the same "not loaded" values the real
 * engines use, so the client's existing error handling is unchanged.
 */
class FormatRoutingEngine(
    private val engines: OnDeviceEngines,
) : OnDeviceEngine {

    override fun isLoaded(): Boolean = engines.isLoaded()

    /** Unloads both: only one is ever loaded, and this keeps the path obvious. */
    override fun unload() = engines.unloadAll()

    override fun cancel() = engines.cancel()

    override fun clearAbort() = engines.clearAbort()

    override fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        onToken: OnDeviceEngine.TokenCallback?,
    ): Int {
        val engine = engines.active ?: return -1
        return engine.chat(messages, maxTokens, temperature, topK, topP, onToken)
    }

    /** The loaded model's context window; 0 when idle. */
    override fun contextSize(): Int = engines.active?.contextSize() ?: 0

    override fun chatTemplate(): String = engines.active?.chatTemplate().orEmpty()

    override fun lastChatStats(): String = engines.active?.lastChatStats().orEmpty()
}
