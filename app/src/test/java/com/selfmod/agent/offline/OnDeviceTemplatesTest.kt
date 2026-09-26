package com.selfmod.agent.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceTemplatesTest {

    @Test
    fun mapsGemmaJinjaToName() {
        val jinja = "{{ bos_token }}{% if messages[0]['role'] == 'system' %}" +
            "{{ raise_exception('System role not supported') }}{% endif %}" +
            "{{ '<start_of_turn>' + role }}"
        assertEquals("gemma", OnDeviceTemplates.resolve(jinja))
    }

    @Test
    fun mapsChatmlAndLlama3() {
        assertEquals("chatml", OnDeviceTemplates.resolve("<|im_start|>user"))
        assertEquals("llama3", OnDeviceTemplates.resolve("<|start_header_id|>user"))
        assertEquals("gemma", OnDeviceTemplates.resolve("gemma"))
        assertEquals("chatml", OnDeviceTemplates.resolve(""))
    }

    @Test
    fun foldsGemmaSystemIntoFirstUser() {
        val folded = OnDeviceTemplates.foldSystem(
            listOf(
                "system" to "You are ACFCN.",
                "user" to "Hello",
                "assistant" to "Hi",
                "user" to "Again",
            ),
            "gemma",
        )
        assertEquals(3, folded.size)
        assertEquals("user", folded[0].first)
        assertEquals("You are ACFCN.\n\nHello", folded[0].second)
        assertEquals("assistant" to "Hi", folded[1])
        assertEquals("user" to "Again", folded[2])
    }

    @Test
    fun leavesChatmlSystemUntouched() {
        val msgs = listOf("system" to "sys", "user" to "hi")
        assertEquals(msgs, OnDeviceTemplates.foldSystem(msgs, "chatml"))
    }
}
