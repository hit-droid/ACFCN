package com.selfmod.agent.browser

import org.json.JSONObject

/**
 * L3: every browser tool answers with the same envelope, whatever the underlying
 * JS or page returned.
 *
 * Before this, `browser_open` answered `loaded url=… title=…`, `browser_click`
 * answered the raw JS string `clicked #3 A`, `browser_type` answered `typed 3`,
 * `browser_extract` answered an unlabelled wall of page text, and a refused
 * action answered `ERROR: …`. The model had to guess which shape it was reading,
 * and a stale-index refusal (H7) looked like page content rather than a failure.
 *
 * [classify] maps any of those replies to a [Status]; [envelope] renders it. No
 * browser behaviour changes — the raw strings are still what
 * [BrowserController] returns, only the tools wrap them.
 */
class BrowserOutcome(
    val status: Status,
    /** Verb of an OK result ("loaded", "ok"), or the reason for a failed one. */
    val note: String,
    /** Payload the model still needs: snapshot, page text, JS result. */
    val body: String,
    val url: String = "",
    val title: String = "",
) {
    enum class Status { Ok, Timeout, Cancelled, Error }

    /** The same `ok` flag every other tool in this app uses. */
    val ok: Boolean get() = status == Status.Ok

    fun envelope(): String {
        val o = JSONObject()
        o.put("ok", ok)
        o.put("status", when (status) {
            Status.Ok -> note.ifBlank { "ok" }
            Status.Timeout -> "timeout"
            Status.Cancelled -> "cancelled"
            Status.Error -> "error"
        })
        if (url.isNotBlank()) o.put("url", url)
        if (title.isNotBlank()) o.put("title", title)
        if (!ok) o.put("error", errorText())
        // A timed-out page is still a page worth reading; never drop the body.
        if (body.isNotBlank()) o.put("data", body)
        return o.toString()
    }

    private fun errorText(): String = when (status) {
        Status.Timeout -> "页面 ${BrowserController.NAV_TIMEOUT_MS / 1000}s 内未加载完，data 里是已有的部分。"
        Status.Cancelled -> "已取消：用户点了停止，或浏览器已释放。"
        Status.Error -> note
        Status.Ok -> ""
    }

    companion object {
        /** Failure verbs the controller emits: a guard refusal (Java) and a drifted
         * index refusal (the JS template). Both mean "your last snapshot is dead". */
        private val FAILURE_PREFIXES = listOf("ERROR: ", "STALE: ")

        /** Header replies: `<verb> url=… [title=…]` followed by an optional body. */
        private val HEADER_PREFIXES = listOf(
            "loaded url=" to (Status.Ok to "loaded"),
            "navigating url=" to (Status.Ok to "navigating"),
            "back url=" to (Status.Ok to "back"),
            "timeout url=" to (Status.Timeout to "timeout"),
            "cancelled url=" to (Status.Cancelled to "cancelled"),
        )

        /** Maps a raw reply to a status; unrecognised shapes are OK payloads. */
        fun classify(raw: String): BrowserOutcome {
            for (p in FAILURE_PREFIXES) {
                if (raw.startsWith(p)) {
                    return BrowserOutcome(Status.Error, raw.removePrefix(p).trim(), "")
                }
            }
            for ((prefix, pair) in HEADER_PREFIXES) {
                if (raw.startsWith(prefix)) {
                    val (st, verb) = pair
                    val head = raw.substringBefore('\n')
                    val body = if (raw.contains('\n')) raw.substringAfter('\n') else ""
                    val (url, title) = headerFields(head.removePrefix(prefix))
                    return BrowserOutcome(st, verb, body, url, title)
                }
            }
            return BrowserOutcome(Status.Ok, "ok", raw)
        }

        /** `X title=Y` → Pair; the title may contain spaces so split on the marker. */
        private fun headerFields(head: String): Pair<String, String> {
            val marker = " title="
            val at = head.indexOf(marker)
            return if (at < 0) head.trim() to ""
            else head.substring(0, at).trim() to head.substring(at + marker.length).trim()
        }
    }
}
