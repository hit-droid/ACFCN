package com.selfmod.agent.offline

import org.json.JSONObject

/**
 * Validates an MNN on-device model directory.
 *
 * An MNN LLM model is a *directory*, not a single file — unlike GGUF. The
 * official taobao-mnn `*-MNN` repos ship:
 *
 * ```
 * config.json        -- LlmConfig: jinja chat_template, llm_model/llm_weight/
 *                       tokenizer_file paths, tie_embeddings, eos, ...
 * llm.mnn            -- the graph
 * llm.mnn.weight     -- the weights (hundreds of MB .. GB)
 * llm_config.json    -- runtime knobs (backend_type, thread_num, ...)
 * tokenizer.mtok     -- tokenizer
 * ```
 *
 * `MNN::Transformer::Llm::createLLM(path)` takes the path to `config.json`, and
 * resolves every other file relative to that directory. So the only hard
 * requirement is config.json plus whatever it names; a model missing its
 * weights would fail deep inside MNN with a much worse error, which is why we
 * check up front.
 */
object MnnModelDir {

    /** config.json names its files via these keys; defaults mirror MNN. */
    private const val KEY_MODEL = "llm_model"
    private const val KEY_WEIGHT = "llm_weight"
    private const val KEY_TOKENIZER = "tokenizer_file"
    private const val KEY_CONFIG = "llm_config"

    private const val DEFAULT_MODEL = "llm.mnn"
    private const val DEFAULT_WEIGHT = "llm.mnn.weight"
    private const val DEFAULT_TOKENIZER = "tokenizer.mtok"
    private const val DEFAULT_CONFIG = "llm_config.json"

    /** Files every MNN LLM directory must contain, best-effort. */
    private val REQUIRED = listOf(DEFAULT_MODEL, DEFAULT_WEIGHT, DEFAULT_TOKENIZER)

    /** True when [dir] looks like an MNN LLM model directory (has config.json). */
    fun looksLikeModelDir(names: Collection<String>): Boolean =
        names.any { it == "config.json" } &&
            names.any { it.endsWith(".mnn") || it.endsWith(".mnn.weight") }

    /**
     * Parse the model's config.json and report what is present / missing.
     *
     * @param dirNames the file names inside the model directory
     * @param configJson the contents of config.json (nullable if unreadable)
     */
    fun inspect(dirNames: Collection<String>, configJson: String?): MnnModelInfo {
        val present = dirNames.toSet()
        val cfg = configJson?.let { runCatching { JSONObject(it) }.getOrNull() }

        val model = cfg?.optString(KEY_MODEL)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        val weight = cfg?.optString(KEY_WEIGHT)?.takeIf { it.isNotBlank() } ?: DEFAULT_WEIGHT
        val tokenizer = cfg?.optString(KEY_TOKENIZER)?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKENIZER
        val runtimeConfig = cfg?.optString(KEY_CONFIG)?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIG

        val wanted = linkedSetOf(model, weight, tokenizer)
        val missing = wanted.filter { it !in present }.toList()

        val chatTemplate = cfg
            ?.optJSONObject("jinja")
            ?.optString("chat_template")
            ?.takeIf { it.isNotBlank() }
        val modelType = cfg?.optString("model_type")?.takeIf { it.isNotBlank() }.orEmpty()
        val eos = cfg?.optJSONObject("jinja")?.optString("eos")?.takeIf { it.isNotBlank() }.orEmpty()
        val layerNums = cfg?.optInt("layer_nums", 0) ?: 0
        val hiddenSize = cfg?.optInt("hidden_size", 0) ?: 0

        return MnnModelInfo(
            modelFile = model,
            weightFile = weight,
            tokenizerFile = tokenizer,
            runtimeConfigFile = runtimeConfig.takeIf { it in present },
            missingFiles = missing,
            modelType = modelType,
            eosToken = eos,
            layerNums = layerNums,
            hiddenSize = hiddenSize,
            hasChatTemplate = chatTemplate != null,
        )
    }

    /** The file `createLLM` must be pointed at, relative to the model dir. */
    fun configPath(): String = "config.json"

    /** Human-readable problem description, or null when the directory is usable. */
    fun problem(info: MnnModelInfo): String? = when {
        info.missingFiles.isEmpty() -> null
        else -> "MNN 模型不完整，缺少：" + info.missingFiles.joinToString(", ") +
            "。请确认解压的是完整模型目录（含 config.json 与 .mnn 权重）。"
    }

    /** Candidate relative names that indicate an MNN model when browsing a zip. */
    fun isModelMarker(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base == "config.json" || base == DEFAULT_WEIGHT
    }
}

data class MnnModelInfo(
    val modelFile: String,
    val weightFile: String,
    val tokenizerFile: String,
    /** llm_config.json if the model ships one (runtime knobs). */
    val runtimeConfigFile: String?,
    /** Files named by config.json that are absent from the directory. */
    val missingFiles: List<String>,
    val modelType: String,
    val eosToken: String,
    val layerNums: Int,
    val hiddenSize: Int,
    val hasChatTemplate: Boolean,
) {
    fun isUsable(): Boolean = missingFiles.isEmpty()

    fun summary(): String = buildString {
        append("MNN")
        if (modelType.isNotBlank()) append(" · ").append(modelType)
        if (layerNums > 0) append(" · ").append(layerNums).append(" 层")
        if (hiddenSize > 0) append(" · hidden ").append(hiddenSize)
        if (hasChatTemplate) append(" · 内置模板")
    }
}
