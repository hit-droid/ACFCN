package com.selfmod.agent.llm

import org.json.JSONObject

/**
 * M2 — JSON sampling knobs for each remote chat dialect.
 *
 * Kept as a pure helper so the field set can be unit-tested without standing
 * up an HTTP client. [LlmClient] copies these keys into the request body.
 */
object SamplingPayload {

    fun openAi(config: LlmConfig): JSONObject {
        val root = JSONObject()
            .put("temperature", config.temperature)
            .put("max_tokens", config.maxTokens)
            .put("top_p", config.topP)
            .put("presence_penalty", config.presencePenalty)
            .put("frequency_penalty", config.frequencyPenalty)
            .putSeed(config)
        // top_k is not in the OpenAI Chat Completions schema; api.openai.com
        // rejects unknown fields. Local OpenAI-compat servers (Ollama / LM
        // Studio / llama.cpp) accept it, so only attach it there.
        if (config.isLocalHost() || config.kind == LlmConfig.KIND_LOCAL || config.kind == LlmConfig.KIND_OFFLINE) {
            root.put("top_k", config.topK)
        }
        return root
    }

    fun ollama(config: LlmConfig): JSONObject = JSONObject()
        .put("temperature", config.temperature)
        .put("num_predict", config.maxTokens)
        .put("top_p", config.topP)
        .put("top_k", config.topK)
        .put("repeat_penalty", config.repeatPenalty)
        .put("presence_penalty", config.presencePenalty)
        .put("frequency_penalty", config.frequencyPenalty)
        .putSeed(config)

    fun llamaCpp(config: LlmConfig): JSONObject = JSONObject()
        .put("temperature", config.temperature)
        .put("n_predict", config.maxTokens)
        .put("top_p", config.topP)
        .put("top_k", config.topK)
        .put("repeat_penalty", config.repeatPenalty)
        .put("presence_penalty", config.presencePenalty)
        .put("frequency_penalty", config.frequencyPenalty)
        .putSeed(config)

    private fun JSONObject.putSeed(config: LlmConfig): JSONObject {
        if (config.seed >= 0) put("seed", config.seed)
        return this
    }
}
