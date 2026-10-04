package com.selfmod.agent.agent

import com.selfmod.agent.llm.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2: `coerceArgs` used to spray plain text into `input`, `url` AND `code` at the
 * same time, so a tool whose schema named none of those keys (`read_script`,
 * `get_memory`, `ui_notify`, `write_script`, …) never saw the argument at all, and
 * `execute_js` silently got the text three times over.
 *
 * The tool's own schema now decides which single field the text goes into.
 */
class CoerceArgsTest {

    /** Registry under test: records exactly what the executor was handed. */
    private class Harness {
        var seen: String? = null
        val registry = ToolRegistry()

        fun add(parameters: String, name: String = "t") {
            registry.register(
                ToolDef(ToolSpec(name = name, description = "d", parameters = parameters)) { args ->
                    seen = args
                    ToolRegistry.okJson("ran")
                }
            )
        }

        fun call(raw: String, name: String = "t"): String = registry.invoke(name, raw)

        /** The arguments the executor actually received, parsed. */
        fun got(): JSONObject {
            val s = seen ?: error("executor was never called")
            return JSONObject(s)
        }
    }

    private val urlOnly =
        """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""

    @Test
    fun `text goes to the declared field only, not input url code`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
        h.call("hello")
        val o = h.got()
        assertEquals("hello", o.getString("name"))
        assertEquals("only the declared field is filled", 1, o.length())
        assertFalse(o.has("input"))
        assertFalse(o.has("url"))
        assertFalse(o.has("code"))
    }

    @Test
    fun `regression - read_script style tool no longer loses the argument`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
        // Before: {"input":..,"url":..,"code":..} -> the executor's getString("name")
        // threw, and the model only ever saw "tool error: ...not found" with no hint
        // that its own argument had simply landed in the wrong key.
        h.call("my_script")
        assertEquals("my_script", h.got().getString("name"))
    }

    @Test
    fun `required field wins over an earlier optional one`() {
        val h = Harness()
        h.add(
            """{"type":"object","properties":{"payload":{"type":"string"},"action":{"type":"string"}},"required":["action"]}"""
        )
        // ui_notify's real shape: the action label is required, the payload is not.
        h.call("show_panel")
        val o = h.got()
        assertEquals("show_panel", o.getString("action"))
        assertFalse("payload must not be invented", o.has("payload"))
    }

    @Test
    fun `when several fields are required, declaration order decides`() {
        val h = Harness()
        h.add(
            """{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"}},"required":["value","key"]}"""
        )
        h.call("alpha")
        // `key` is declared first, so it is the one the schema "asks for" first —
        // matching read_script/write_script/get_memory, whose first declared field
        // is the one their executor reads unconditionally.
        assertEquals("alpha", h.got().getString("key"))
        assertEquals(1, h.got().length())
    }

    @Test
    fun `first declared field wins when nothing is required`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{"action":{"type":"string"},"payload":{"type":"string"}},"required":[]}""")
        h.call("refresh")
        val o = h.got()
        assertEquals("refresh", o.getString("action"))
        assertFalse(o.has("payload"))
    }

    @Test
    fun `declaration order is read even when a description sits between key and type`() {
        val h = Harness()
        h.add(
            """{"type":"object","properties":{"code":{"type":"string","description":"JS source"},"name":{"type":"string"}},"required":["code"]}"""
        )
        // execute_js' real schema: `code` is declared first *and* required.
        h.call("api.log('hi')")
        val o = h.got()
        assertEquals("api.log('hi')", o.getString("code"))
        assertFalse(o.has("name"))
    }

    @Test
    fun `integer field is filled as a number so getInt reads it back`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{"index":{"type":"integer"}},"required":["index"]}""")
        h.call("3")
        val o = h.got()
        assertFalse("written as a number, not a string", o.get("index") is String)
        assertEquals(3, o.getInt("index"))
    }

    @Test
    fun `integer field that cannot be parsed keeps the original text`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{"index":{"type":"integer"}},"required":["index"]}""")
        h.call("third")
        // Better to hand the executor the text it can report on than to invent a 0.
        assertEquals("third", h.got().getString("index"))
    }

    @Test
    fun `boolean field is filled as a real boolean`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{"load":{"type":"boolean"}},"required":["load"]}""")
        h.call("true")
        assertEquals(true, h.got().getBoolean("load"))
    }

    @Test
    fun `tool with no declared fields gets an empty object`() {
        val h = Harness()
        h.add("""{"type":"object","properties":{}}""")
        h.call("ignored")
        // browser_snapshot / list_scripts / offline_status read nothing: invented keys
        // would only give a malformed-args tool something to misread.
        assertEquals("{}", h.seen)
    }

    @Test
    fun `unparseable schema falls back to the old spray rather than failing the call`() {
        val h = Harness()
        h.add("not json at all")
        h.call("value")
        val o = h.got()
        assertEquals("value", o.getString("input"))
        assertEquals("value", o.getString("url"))
        assertEquals("value", o.getString("code"))
    }

    @Test
    fun `schema with a non-object properties shape still finds the declared field`() {
        val h = Harness()
        // type written as an array (union) — the scanner's strict pattern misses it,
        // so the JSONObject key list takes over instead of dropping the argument.
        h.add("""{"type":"object","properties":{"query":{"type":["string","null"]}},"required":["query"]}""")
        h.call("kittens")
        assertEquals("kittens", h.got().getString("query"))
    }

    @Test
    fun `json object arguments pass through untouched`() {
        val h = Harness()
        h.add(urlOnly)
        h.call("""{"url":"http://x","extra":1}""")
        assertTrue(h.seen!!.contains("extra"))
        assertEquals("http://x", h.got().getString("url"))
    }

    @Test
    fun `json array arguments pass through untouched`() {
        val h = Harness()
        h.add(urlOnly)
        h.call("""["a","b"]""")
        assertEquals("""["a","b"]""", h.seen)
    }

    @Test
    fun `blank argument is an empty object whatever the schema says`() {
        val h = Harness()
        h.add(urlOnly)
        h.call("   ")
        assertEquals("{}", h.seen)
    }

    @Test
    fun `surrounding whitespace is trimmed before the text lands in the field`() {
        val h = Harness()
        h.add(urlOnly)
        h.call("  http://example.com  ")
        assertEquals("http://example.com", h.got().getString("url"))
    }

    @Test
    fun `unknown tool still reports the error without reaching coercion`() {
        val h = Harness()
        h.add(urlOnly)
        val out = JSONObject(h.call("text", name = "missing"))
        assertFalse(out.getBoolean("ok"))
        assertTrue(out.getString("error").contains("unknown tool"))
    }

    @Test
    fun `every shipping tool spec still parses to a usable field`() {
        // Guards against the scanner silently missing a schema in Tools.kt: for each
        // real spec that declares at least one field, a plain-text call must land in
        // exactly one of that spec's own declared fields.
        val specs = listOf(
            """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript source to execute"},"name":{"type":"string","description":"Optional filename for error reporting"}},"required":["code"]}""",
            """{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"}},"required":["name","content"]}""",
            """{"type":"object","properties":{"name":{"type":"string"},"action":{"type":"string"},"payload":{"type":"string"}},"required":["name","action"]}""",
            """{"type":"object","properties":{"name":{"type":"string"},"entry":{"type":"string"},"dex_b64":{"type":"string"},"load":{"type":"boolean"}},"required":["name","entry","dex_b64"]}""",
            """{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"}},"required":["key","value"]}""",
            """{"type":"object","properties":{"url":{"type":"string"},"body":{"type":"string"},"headers":{"type":"string"}},"required":["url","body"]}""",
            """{"type":"object","properties":{"action":{"type":"string"},"payload":{"type":"string"}},"required":["action"]}""",
            """{"type":"object","properties":{"index":{"type":"integer"},"text":{"type":"string"}},"required":["index","text"]}""",
            """{"type":"object","properties":{"direction":{"type":"string"}},"required":[]}""",
        )
        specs.forEach { parameters ->
            val h = Harness()
            h.add(parameters)
            h.call("text")
            val o = h.got()
            assertEquals("one field filled for $parameters", 1, o.length())
            val key = o.keys().next()
            val declared = JSONObject(parameters).getJSONObject("properties")
            assertTrue("$key not declared in $parameters", declared.has(key))
        }
    }
}
