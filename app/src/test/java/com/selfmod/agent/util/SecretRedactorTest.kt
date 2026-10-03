package com.selfmod.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun masksAuthorizationHeader() {
        assertEquals(
            "Authorization: [REDACTED]",
            SecretRedactor.redact("Authorization: Bearer sk-abcdef1234567890"),
        )
    }

    @Test
    fun masksApiKeyPairsInVariousShapes() {
        assertEquals(
            "api_key=[REDACTED]",
            SecretRedactor.redact("api_key=ghp_abcdefghijklmnopqrst"),
        )
        assertEquals(
            "x-api-key: [REDACTED]",
            SecretRedactor.redact("x-api-key: gsk_abcdef12345678"),
        )
        assertEquals(
            "\"api-key\": \"[REDACTED]\"",
            SecretRedactor.redact("\"api-key\": \"sk-proj-abcdefgh1234\""),
        )
    }

    @Test
    fun masksProviderPrefixedKeys() {
        val out = SecretRedactor.redact(
            "Incorrect API key provided: sk-abc123def456ghi789. You can find at openai.com",
        )
        assertTrue("应脱敏 sk- 键", out.contains("[REDACTED]"))
        assertTrue(out.contains("[REDACTED]. You can find"))
    }

    @Test
    fun masksBareBearerAndGoogleKeys() {
        assertEquals(
            "headers {Bearer [REDACTED]}",
            SecretRedactor.redact("headers {Bearer eyJhbGciOiJIUzI1NiJ9x}"),
        )
        assertEquals(
            "key [REDACTED]",
            SecretRedactor.redact("key AIzaSyB1234567890abcd"),
        )
    }

    @Test
    fun leavesPlainTextAlone() {
        val text = """
            ACFCN crash report
            device: Xiaomi M2012K11AC
            android: 14 (API 34)
            java.lang.IllegalStateException: engine not loaded
                at com.selfmod.agent.offline.native.LocalLlmEngine.chat
        """.trimIndent()
        assertEquals(text, SecretRedactor.redact(text))
    }

    @Test
    fun shortTokensAreNotMangled() {
        assertEquals("Bearer abc", SecretRedactor.redact("Bearer abc"))
        assertEquals("task-123", SecretRedactor.redact("task-123"))
    }
}
