package com.selfmod.agent.offline

/** A curated small model the user can grab and import for on-device use. */
data class RecommendedModel(
    val name: String,
    val params: String,
    val quant: String,
    val sizeLabel: String,
    val ramHint: String,
    val url: String,
    val note: String,
)

object RecommendedModels {
    /**
     * Only small enough models to realistically run on phones (arm64, CPU).
     * Links point to stable Hugging Face repos.
     */
    val ALL: List<RecommendedModel> = listOf(
        RecommendedModel(
            name = "Qwen2.5 1.5B Instruct",
            params = "1.5B",
            quant = "Q4_K_M",
            sizeLabel = "约 1.1 GB",
            ramHint = "需 ~2 GB 内存",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            note = "入门首选，速度快，中文可用",
        ),
        RecommendedModel(
            name = "Qwen2.5 3B Instruct",
            params = "3B",
            quant = "Q4_K_M",
            sizeLabel = "约 2.0 GB",
            ramHint = "需 ~3 GB 内存",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF",
            note = "质量更好，中端机可用",
        ),
        RecommendedModel(
            name = "Llama 3.2 1B Instruct",
            params = "1B",
            quant = "Q4_K_M",
            sizeLabel = "约 0.8 GB",
            ramHint = "需 ~1.5 GB 内存",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF",
            note = "最省资源，英文强",
        ),
        RecommendedModel(
            name = "Gemma 2 2B Instruct",
            params = "2B",
            quant = "Q4_K_M",
            sizeLabel = "约 1.6 GB",
            ramHint = "需 ~2.5 GB 内存",
            url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF",
            note = "Google 出品，均衡",
        ),
        RecommendedModel(
            name = "Phi-3.5 Mini Instruct",
            params = "3.8B",
            quant = "Q4_K_M",
            sizeLabel = "约 2.4 GB",
            ramHint = "需 ~4 GB 内存",
            url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF",
            note = "推理能力强，需较好机型",
        ),
    )
}
