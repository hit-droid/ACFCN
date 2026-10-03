package com.selfmod.agent.ui

/**
 * Visible inline styles the chat renderer knows about. Links are not a style of
 * their own: a link contributes [InlineSpan.url] to whatever spans its label.
 */
internal enum class InlineKind { Plain, Bold, Italic, Code }

/**
 * One run of already-visible text. Unlike raw markdown this carries no markers, so
 * the caller can render it and assert the text is exactly what the user should see.
 */
internal data class InlineSpan(val text: String, val kind: InlineKind, val url: String? = null)

/**
 * Inline markdown subset used by [MarkdownText]: `[label](url)`, `**bold**`,
 * `` `code` ``, `*italic*`.
 *
 * Answer text arrives token by token, so an unterminated marker at the tail is the
 * normal case, not an error: `**bold` is rendered as bold text instead of flashing
 * the raw asterisks until the closing pair lands. A link whose `)` has not arrived
 * yet keeps growing its URL the same way.
 *
 * Deliberately not supported (renders literally, same as before): nested link
 * labels, emphasis inside a code span, heading/list syntax (see `MarkdownParser`).
 */
internal object InlineSpanParser {
    fun parse(text: String): List<InlineSpan> {
        val out = ArrayList<InlineSpan>()
        write(text, 0, text.length, null, InlineKind.Plain, out)
        return coalesce(out)
    }

    /** Joins runs a caller would render identically, so spans stay readable-sized. */
    private fun coalesce(spans: List<InlineSpan>): List<InlineSpan> {
        val out = ArrayList<InlineSpan>(spans.size)
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.kind == s.kind && last.url == s.url) {
                out[out.size - 1] = last.copy(text = last.text + s.text)
            } else {
                out += s
            }
        }
        return out
    }

    private fun write(
        text: String,
        start: Int,
        end: Int,
        url: String?,
        style: InlineKind,
        out: MutableList<InlineSpan>,
    ) {
        var i = start
        while (i < end) {
            if (text[i] == '[') {
                val link = matchLink(text, i, end)
                if (link != null) {
                    // The label keeps the surrounding style and gains the link URL.
                    write(text, link.labelStart, link.labelEnd, link.url, style, out)
                    i = link.after
                    continue
                }
                out += InlineSpan(text[i].toString(), style, url)
                i++
                continue
            }
            if (text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2).takeIf { it > i && it + 2 <= end }
                if (close != null) {
                    write(text, i + 2, close, url, InlineKind.Bold, out)
                    i = close + 2
                } else {
                    // Half-typed during streaming: bold everything that is left.
                    write(text, i + 2, end, url, InlineKind.Bold, out)
                    i = end
                }
                continue
            }
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1).takeIf { it > i && it < end }
                if (close != null) {
                    out += InlineSpan(text.substring(i + 1, close), InlineKind.Code, url)
                    i = close + 1
                } else {
                    out += InlineSpan(text.substring(i + 1, end), InlineKind.Code, url)
                    i = end
                }
                continue
            }
            val marker = text[i]
            if (marker == '*' || marker == '_') {
                val close = text.indexOf(marker, i + 1).takeIf { it > i + 1 && it < end }
                if (close != null) {
                    write(text, i + 1, close, url, InlineKind.Italic, out)
                    i = close + 1
                    continue
                }
                // A stray `*`/`_` (e.g. "3*4") stays literal.
            }
            var j = i + 1
            while (j < end && !isMarker(text[j])) j++
            out += InlineSpan(text.substring(i, j), style, url)
            i = j
        }
    }

    private fun isMarker(c: Char): Boolean = c == '[' || c == '`' || c == '*' || c == '_'

    private class Link(val labelStart: Int, val labelEnd: Int, val url: String, val after: Int)

    /** Matches `[label](url)` inside `[at, end)`; null when it is not a link. */
    private fun matchLink(text: String, at: Int, end: Int): Link? {
        val close = text.indexOf(']', at + 1).takeIf { it > at && it < end } ?: return null
        if (close + 1 >= end || text[close + 1] != '(') return null
        val open = close + 2
        val labelStart = at + 1
        if (labelStart == close) return null
        val paren = closingParen(text, open, end)
        // `)` may not have streamed in yet: the URL is whatever follows so far.
        val url = (if (paren >= 0) text.substring(open, paren) else text.substring(open, end)).trim()
        if (url.isEmpty()) return null
        return Link(labelStart, close, url, if (paren >= 0) paren + 1 else end)
    }

    /** First `)` that is not part of the URL, so `https://x/foo_(bar)` keeps its own parens. */
    private fun closingParen(text: String, from: Int, end: Int): Int {
        var depth = 0
        var i = from
        while (i < end) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    // A `(` we opened inside the URL means the next `)` closes that pair.
                    if (depth == 0 && (i == from || text[i - 1] != '(')) return i
                    if (depth > 0) depth--
                }
            }
            i++
        }
        return -1
    }
}
