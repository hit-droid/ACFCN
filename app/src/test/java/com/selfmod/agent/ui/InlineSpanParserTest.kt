package com.selfmod.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5: what matters for a chat bubble is (1) no markdown markers leak into the visible
 * text and (2) a link carries its URL. Both are asserted on the span list, which is
 * the exact input the renderer turns into an AnnotatedString.
 */
class InlineSpanParserTest {

    private fun visible(text: String): String =
        InlineSpanParser.parse(text).joinToString("") { it.text }

    @Test
    fun linkKeepsLabelAndCarriesUrl() {
        val spans = InlineSpanParser.parse("看 [文档](https://example.com/docs) 吧")
        assertEquals("看 文档 吧", spans.joinToString("") { it.text })
        val link = spans.single { it.url != null }
        assertEquals("https://example.com/docs", link.url)
        assertEquals("文档", link.text)
    }

    @Test
    fun unterminatedParenStillYieldsTheUrlStreamedSoFar() {
        val spans = InlineSpanParser.parse("[ACFCN](https://example.com")
        assertEquals("ACFCN", visible("[ACFCN](https://example.com"))
        assertEquals("https://example.com", spans.single().url)
    }

    @Test
    fun halfTypedBoldDoesNotShowAsterisks() {
        // Streaming tail: `**重点` arrives before the closing pair.
        assertEquals("重点", visible("**重点"))
        assertEquals(InlineKind.Bold, InlineSpanParser.parse("**重点").single().kind)
        // And once it closes, the text is unchanged.
        assertEquals("重点", visible("**重点**"))
    }

    @Test
    fun halfTypedCodeDoesNotShowBackticks() {
        assertEquals("npm i", visible("`npm i"))
        assertEquals("npm i", visible("`npm i`"))
    }

    @Test
    fun plainMarkersWithoutClosingStayLiteral() {
        assertEquals("3*4", visible("3*4"))
        assertEquals("[not a link", visible("[not a link"))
        assertEquals("a ] (b", visible("a ] (b"))
    }

    @Test
    fun emphasisStillWorks() {
        val spans = InlineSpanParser.parse("**粗** 和 *斜* 和 `码`")
        assertEquals("粗 和 斜 和 码", visible("**粗** 和 *斜* 和 `码`"))
        assertEquals(
            listOf(
                InlineKind.Bold to "粗",
                InlineKind.Plain to " 和 ",
                InlineKind.Italic to "斜",
                InlineKind.Plain to " 和 ",
                InlineKind.Code to "码",
            ),
            spans.map { it.kind to it.text },
        )
    }

    @Test
    fun linkInsideBoldKeepsBothStyleAndUrl() {
        val spans = InlineSpanParser.parse("**[点我](https://a.b)**")
        assertEquals("点我", visible("**[点我](https://a.b)**"))
        assertEquals(InlineKind.Bold, spans.single().kind)
        assertEquals("https://a.b", spans.single().url)
    }

    @Test
    fun adjacentRunsWithSameStyleAreMerged() {
        val spans = InlineSpanParser.parse("abc[链接](https://a.b)def")
        assertEquals(3, spans.size)
        assertEquals("abc", spans[0].text)
        assertEquals("链接", spans[1].text)
        assertEquals("def", spans[2].text)
        assertNull(spans[0].url)
        assertNull(spans[2].url)
    }

    @Test
    fun emptyLabelIsNotTreatedAsLink() {
        assertEquals("[](https://a.b)", visible("[](https://a.b)"))
    }

    @Test
    fun urlWithBalancedParensKeepsItsOwn() {
        val spans = InlineSpanParser.parse("[w](https://en.wikipedia.org/wiki/A_(b))")
        assertEquals("w", visible("[w](https://en.wikipedia.org/wiki/A_(b))"))
        assertEquals("https://en.wikipedia.org/wiki/A_(b)", spans.single().url)
    }

    @Test
    fun blankUrlIsNotTreatedAsLink() {
        val spans = InlineSpanParser.parse("[label](   )")
        assertEquals("[label](   )", visible("[label](   )"))
        assertTrue(spans.none { it.url != null })
    }
}
