package com.selfmod.agent.llm

/** Configuration for an OpenAI-compatible chat completions endpoint.
 *  Defaults target the GLM (智谱) public endpoint. Works with OpenAI,
 *  DeepSeek, Moonshot, and other compatible providers by changing baseUrl. */
data class LlmConfig(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-4-plus",
    val temperature: Double = 0.6,
    val maxTokens: Int = 2048,
    val timeoutSeconds: Long = 120,
) {
    companion object {
        val DEFAULT = LlmConfig()
        // A few ready presets the UI can switch between.
        val PRESETS = listOf(
            "GLM (智谱)" to DEFAULT,
            "DeepSeek" to LlmConfig(
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
            ),
            "Moonshot (Kimi)" to LlmConfig(
                baseUrl = "https://api.moonshot.cn/v1",
                model = "moonshot-v1-32k",
            ),
            "OpenAI" to LlmConfig(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
            ),
        )
    }
}
