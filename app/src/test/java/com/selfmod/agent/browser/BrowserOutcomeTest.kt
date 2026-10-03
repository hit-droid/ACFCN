package com.selfmod.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * L3: the shape the model sees. These run on the real strings BrowserController
 * produces today, so the envelope cannot drift from the browser without failing here.
 */
class BrowserOutcomeTest {

    private fun env(raw: String) = JSONObject(BrowserOutcome.classify(raw).envelope())

    @Test
    fun navigateSuccessKeepsUrlTitleAndSnapshot() {
        val raw = "loaded url=https://example.com title=Example Domain\ntext=\n#1 A \"Go\""
        val o = env(raw)
        assertTrue(o.getBoolean("ok"))
        assertEquals("loaded", o.getString("status"))
        assertEquals("https://example.com", o.getString("url"))
        assertEquals("Example Domain", o.getString("title"))
        assertTrue(o.getString("data").contains("#1 A"))
    }

    @Test
    fun titleWithSpacesIsNotTruncatedAtTheFirstWord() {
        val o = env("loaded url=https://a.b title=One Two Three")
        assertEquals("One Two Three", o.getString("title"))
    }

    @Test
    fun timeoutIsAFailureButKeepsThePartialPage() {
        val o = env("timeout url=https://a.b title=Part\ntext=half a page")
        assertFalse(o.getBoolean("ok"))
        assertEquals("timeout", o.getString("status"))
        assertTrue(o.getString("error").contains("25s"))
        assertEquals("text=half a page", o.getString("data"))
    }

    @Test
    fun cancelIsReportedAsCancelledNotAsSlowPage() {
        val o = env("cancelled url=https://a.b")
        assertFalse(o.getBoolean("ok"))
        assertEquals("cancelled", o.getString("status"))
    }

    @Test
    fun staleIndexRefusalBecomesAProperError() {
        // The H7 guard refuses with "ERROR: …" — it must not read like page content.
        val o = env("ERROR: page changed since your last snapshot — call browser_snapshot again")
        assertFalse(o.getBoolean("ok"))
        assertEquals("error", o.getString("status"))
        assertTrue(o.getString("error").contains("browser_snapshot again"))
    }

    @Test
    fun staleFromTheJsTemplateIsAlsoAnError() {
        // ACTION_JS_TEMPLATE refuses with "STALE: …" before touching the page.
        val o = env("STALE: index 3 is now a different element — call browser_snapshot again")
        assertFalse(o.getBoolean("ok"))
        assertEquals("error", o.getString("status"))
        assertTrue(o.getString("error").contains("browser_snapshot again"))
    }

    @Test
    fun navigatingHeaderIsReportedAsSuccessWithTheUrl() {
        // On the main thread navigate() short-circuits to open() and says "navigating".
        val o = env("navigating url=https://a.b")
        assertTrue(o.getBoolean("ok"))
        assertEquals("navigating", o.getString("status"))
        assertEquals("https://a.b", o.getString("url"))
    }

    @Test
    fun detachedBrowserIsAnError() {
        val o = env("ERROR: browser detached")
        assertFalse(o.getBoolean("ok"))
        assertEquals("browser detached", o.getString("error"))
    }

    @Test
    fun actionRepliesAreOkWithTheJsEchoAsData() {
        val o = env("clicked #3 A")
        assertTrue(o.getBoolean("ok"))
        assertEquals("ok", o.getString("status"))
        assertEquals("clicked #3 A", o.getString("data"))

        val t = env("typed 7")
        assertTrue(t.getBoolean("ok"))
        assertEquals("typed 7", t.getString("data"))
    }

    @Test
    fun emptyResultIsStillValidJson() {
        val o = env("")
        assertTrue(o.getBoolean("ok"))
        assertFalse(o.has("data"))
    }

    @Test
    fun pageTextCannotFakeTheEnvelope() {
        // A page whose body literally starts with "ERROR: " must stay *data*, not
        // be re-interpreted as a browser failure.
        val o = env("loaded url=https://a.b title=T\nERROR: this is page text")
        assertTrue(o.getBoolean("ok"))
        assertTrue(o.getString("data").startsWith("ERROR: this is page text"))
    }

    @Test
    fun statusFieldIsPresentOnEveryReply() {
        for (raw in listOf(
            "loaded url=a title=b", "timeout url=a title=b", "cancelled url=a",
            "ERROR: nope", "typed 1", "", "back url=https://a.b title=X",
        )) {
            val o = env(raw)
            assertTrue("missing status for <$raw>", o.has("status"))
            assertTrue("missing ok for <$raw>", o.has("ok"))
        }
    }
}
