package com.selfmod.agent.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnModelDirTest {

    private val fullDir = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "llm_config.json",
        "tokenizer.mtok",
    )

    private val configJson = """
        {
          "model_type": "qwen2",
          "hidden_size": 1536,
          "layer_nums": 28,
          "jinja": {
            "chat_template": "{%- for m in messages %}{{ m.content }}{%- endfor %}",
            "eos": "<|im_end|>"
          }
        }
    """.trimIndent()

    @Test
    fun recognisesModelDirectory() {
        assertTrue(MnnModelDir.looksLikeModelDir(fullDir))
        // config.json alone is not enough.
        assertFalse(MnnModelDir.looksLikeModelDir(listOf("config.json")))
        assertFalse(MnnModelDir.looksLikeModelDir(listOf("tokenizer.mtok")))
    }

    @Test
    fun parsesCompleteModel() {
        val info = MnnModelDir.inspect(fullDir, configJson)
        assertTrue(info.isUsable())
        assertTrue(info.missingFiles.isEmpty())
        assertEquals("qwen2", info.modelType)
        assertEquals(28, info.layerNums)
        assertEquals(1536, info.hiddenSize)
        assertEquals("<|im_end|>", info.eosToken)
        assertTrue(info.hasChatTemplate)
        assertEquals("llm_config.json", info.runtimeConfigFile)
        assertNull(MnnModelDir.problem(info))
    }

    @Test
    fun reportsMissingWeights() {
        // The exact case that would otherwise fail deep inside MNN load().
        val info = MnnModelDir.inspect(listOf("config.json", "llm.mnn", "tokenizer.mtok"), configJson)
        assertFalse(info.isUsable())
        assertTrue(info.missingFiles.contains("llm.mnn.weight"))
        val problem = MnnModelDir.problem(info)
        assertNotNull(problem)
        assertTrue(problem!!.contains("llm.mnn.weight"))
    }

    @Test
    fun honoursCustomFileNamesFromConfig() {
        val cfg = """{"llm_model":"model.mnn","llm_weight":"model.mnn.weight","tokenizer_file":"tok.mtok"}"""
        val info = MnnModelDir.inspect(
            listOf("config.json", "model.mnn", "model.mnn.weight", "tok.mtok"),
            cfg,
        )
        assertTrue(info.isUsable())
        assertEquals("model.mnn", info.modelFile)
        assertEquals("model.mnn.weight", info.weightFile)
        assertEquals("tok.mtok", info.tokenizerFile)
        // config.json did not name llm_config.json and none is present.
        assertNull(info.runtimeConfigFile)
    }

    @Test
    fun toleratesUnreadableConfig() {
        // A malformed config.json must not crash import; defaults are assumed.
        val info = MnnModelDir.inspect(fullDir, "{ not json")
        assertEquals("llm.mnn", info.modelFile)
        assertEquals("llm.mnn.weight", info.weightFile)
        assertFalse(info.hasChatTemplate)
        assertTrue(info.isUsable())
    }

    @Test
    fun toleratesNullConfig() {
        val info = MnnModelDir.inspect(fullDir, null)
        assertTrue(info.isUsable())
        assertEquals("", info.modelType)
    }

    @Test
    fun summaryMentionsKeyFacts() {
        val s = MnnModelDir.inspect(fullDir, configJson).summary()
        assertTrue(s.contains("MNN"))
        assertTrue(s.contains("qwen2"))
        assertTrue(s.contains("28"))
        assertTrue(s.contains("模板"))
    }

    @Test
    fun pointsCreateLlmAtConfigJson() {
        assertEquals("config.json", MnnModelDir.configPath())
    }

    @Test
    fun detectsZipMarkers() {
        assertTrue(MnnModelDir.isModelMarker("Qwen2.5-1.5B-MNN/config.json"))
        assertTrue(MnnModelDir.isModelMarker("Qwen2.5-1.5B-MNN/llm.mnn.weight"))
        assertFalse(MnnModelDir.isModelMarker("Qwen2.5-1.5B-MNN/llm.mnn"))
        assertFalse(MnnModelDir.isModelMarker("README.md"))
    }
}
