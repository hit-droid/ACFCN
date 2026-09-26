package com.selfmod.agent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun offlinePromptIncludesReact() {
        val s = PromptTemplates.system(listOf("browser_open", "execute_js"), offline = true, localEndpoint = true)
        assertTrue(s.contains("<tool_call>"))
        assertTrue(s.contains("Action:"))
        assertTrue(s.contains("browser_open"))
        assertTrue(s.contains("离线"))
    }

    @Test
    fun cloudPromptListsTools() {
        val s = PromptTemplates.system(listOf("http_get"), offline = false, localEndpoint = false)
        assertTrue(s.contains("http_get"))
        assertTrue(s.contains("思考-行动-观察"))
    }

    @Test
    fun onDevicePromptIsShort() {
        val s = PromptTemplates.systemOnDevice(listOf("browser_open", "execute_js"))
        assertTrue(s.length < 400)
        assertTrue(s.contains("Action:"))
        assertTrue(s.contains("browser_open"))
    }
}
