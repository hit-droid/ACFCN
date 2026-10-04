package com.selfmod.agent.llm

/**
 * OpenAI-compatible chat endpoint. Works with cloud providers, LAN/localhost
 * servers (Ollama, LM Studio, llama.cpp), and imported offline models that
 * are served through one of those local runtimes.
 */
data class LlmConfig(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-4-plus",
    val temperature: Double = 0.6,
    // L1: 2048 at on-device speeds (5-15 tok/s) means minutes of generation
    // per answer; 512 keeps replies snappy. Users can raise it in config.
    val maxTokens: Int = 512,
    val timeoutSeconds: Long = 120,
    val kind: String = KIND_CLOUD,
    val profileName: String = "",
    val supportsNativeTools: Boolean = true,
    val onDeviceModelPath: String = "",
    val onDeviceContext: Int = 2048,
) {
    fun host(): String = runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("")

    fun isLocalHost(): Boolean {
        val h = host().lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "0.0.0.0" || h == "10.0.2.2" ||
            h.endsWith(".local") || h.endsWith(".lan")
        ) return true
        // 用 isSiteLocalAddress 正确识别 10/8、172.16/12、192.168/16、127/8 等私网段，
        // 避免字符串前缀匹配漏掉 172.17–172.31 或误匹配主机名。
        return runCatching {
            val addr = java.net.InetAddress.getByName(h)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        }.getOrDefault(false)
    }

    fun isUsable(): Boolean {
        if (kind == KIND_ONDEVICE) return onDeviceModelPath.isNotBlank()
        if (baseUrl.isBlank()) return false
        if (isLocalHost() || kind == KIND_LOCAL || kind == KIND_OFFLINE) return true
        return apiKey.isNotBlank()
    }

    fun authHeader(): String? {
        val key = apiKey.trim()
        if (key.isEmpty() || key.equals("ollama", ignoreCase = true) || key == "none") return null
        return "Bearer $key"
    }

    companion object {
        const val KIND_CLOUD = "cloud"
        const val KIND_LOCAL = "local"
        const val KIND_OFFLINE = "offline"
        const val KIND_ONDEVICE = "ondevice"

        val DEFAULT = LlmConfig()

        val PRESETS: List<ProviderPreset> = listOf(
            ProviderPreset("glm", "GLM 智谱", "云端 · 国内直连", LlmConfig(
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                model = "glm-4-plus",
                kind = KIND_CLOUD,
                profileName = "GLM 智谱",
            )),
            ProviderPreset("deepseek", "DeepSeek", "云端 · OpenAI 兼容", LlmConfig(
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                kind = KIND_CLOUD,
                profileName = "DeepSeek",
            )),
            ProviderPreset("moonshot", "Kimi 月之暗面", "云端 · 长上下文", LlmConfig(
                baseUrl = "https://api.moonshot.cn/v1",
                model = "moonshot-v1-32k",
                kind = KIND_CLOUD,
                profileName = "Kimi 月之暗面",
            )),
            ProviderPreset("openai", "OpenAI", "云端 · gpt-4o-mini", LlmConfig(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                kind = KIND_CLOUD,
                profileName = "OpenAI",
            )),
            ProviderPreset("groq", "Groq", "云端 · 高速推理", LlmConfig(
                baseUrl = "https://api.groq.com/openai/v1",
                model = "llama-3.1-8b-instant",
                kind = KIND_CLOUD,
                profileName = "Groq",
            )),
            ProviderPreset("openrouter", "OpenRouter", "云端 · 多模型网关", LlmConfig(
                baseUrl = "https://openrouter.ai/api/v1",
                model = "openai/gpt-4o-mini",
                kind = KIND_CLOUD,
                profileName = "OpenRouter",
            )),
            ProviderPreset("siliconflow", "SiliconFlow", "云端 · 硅基流动", LlmConfig(
                baseUrl = "https://api.siliconflow.cn/v1",
                model = "Qwen/Qwen2.5-7B-Instruct",
                kind = KIND_CLOUD,
                profileName = "SiliconFlow",
            )),
            ProviderPreset("ollama", "Ollama", "本机 · 11434 · 无需 Key", LlmConfig(
                baseUrl = "http://127.0.0.1:11434/v1",
                apiKey = "ollama",
                model = "llama3.2",
                kind = KIND_LOCAL,
                profileName = "Ollama",
                supportsNativeTools = true,
            )),
            ProviderPreset("lmstudio", "LM Studio", "本机 · 1234", LlmConfig(
                baseUrl = "http://127.0.0.1:1234/v1",
                apiKey = "lm-studio",
                model = "local-model",
                kind = KIND_LOCAL,
                profileName = "LM Studio",
                supportsNativeTools = false,
            )),
            ProviderPreset("llamacpp", "llama.cpp", "本机 · 8080 · GGUF", LlmConfig(
                baseUrl = "http://127.0.0.1:8080/v1",
                apiKey = "",
                model = "local-gguf",
                kind = KIND_LOCAL,
                profileName = "llama.cpp",
                supportsNativeTools = false,
            )),
            ProviderPreset("jan", "Jan", "本机 · 1337", LlmConfig(
                baseUrl = "http://127.0.0.1:1337/v1",
                apiKey = "",
                model = "local-model",
                kind = KIND_LOCAL,
                profileName = "Jan",
                supportsNativeTools = false,
            )),
        )
    }
}

data class ProviderPreset(
    val id: String,
    val name: String,
    val hint: String,
    val config: LlmConfig,
)

data class SavedProfile(
    val id: String,
    val name: String,
    val config: LlmConfig,
    val lastOk: Boolean? = null,
    val lastLatencyMs: Long? = null,
    val lastCheckedAt: Long = 0L,
)
