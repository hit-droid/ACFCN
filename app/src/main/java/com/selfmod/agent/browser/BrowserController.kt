package com.selfmod.agent.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared in-app browser. The user drives it from the Browser tab; the agent
 * drives it through tools. Both sides talk to the same WebView.
 *
 * Snapshot / click / type follow the browser-use pattern: label interactive
 * nodes with an index, then act by index.
 */
class BrowserController(
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val _url = MutableStateFlow("about:blank")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _lastSnapshot = MutableStateFlow("")
    val lastSnapshot: StateFlow<String> = _lastSnapshot.asStateFlow()

    private val _desktop = MutableStateFlow(false)
    val desktop: StateFlow<Boolean> = _desktop.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _canBack = MutableStateFlow(false)
    val canBack: StateFlow<Boolean> = _canBack.asStateFlow()

    private val _agentPointer = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Last element the agent clicked: (index, x, y) for on-page highlight. */
    val agentPointer: StateFlow<Pair<Int, Int>?> = _agentPointer.asStateFlow()

    @Volatile private var webView: WebView? = null
    private val pageLatch = AtomicReference<CountDownLatch?>(null)

    /**
     * Latches an agent action is currently blocked on. [abortWaits] releases
     * them so "stop" returns immediately instead of waiting out a timeout.
     */
    private val pendingWaits = java.util.concurrent.CopyOnWriteArrayList<CountDownLatch>()

    /** Bumped on every navigation; snapshot ids are stamped with it (H7). */
    private val pageEpoch = AtomicLong(0)

    /** Refuses to act on element ids the model was never shown (H7). */
    private val guard = SnapshotGuard()

    fun attach(wv: WebView) {
        if (webView === wv) return
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.userAgentString = if (_desktop.value) DESKTOP_UA else wv.settings.userAgentString
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                _url.value = url
                _loading.value = true
                _canBack.value = view.canGoBack()
                // Any navigation invalidates the element ids handed out by the
                // previous snapshot (H7).
                pageEpoch.incrementAndGet()
                if (url.startsWith("http")) {
                    _history.value = (listOf(url) + _history.value.filter { it != url }).take(50)
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                _url.value = url
                _loading.value = false
                _progress.value = 100
                _canBack.value = view.canGoBack()
                pageLatch.getAndSet(null)?.countDown()
                injectAgentOverlay()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                _title.value = title.orEmpty()
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _progress.value = newProgress
                _loading.value = newProgress < 100
            }
        }
        wv.setDownloadListener { url, _, _, _, _ ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                intent.setData(android.net.Uri.parse(url))
                wv.context.startActivity(intent)
            }
        }
        if (_url.value == "about:blank" || _url.value.isBlank()) {
            wv.loadUrl(HOME)
        }
    }

    /** Draws a small pulsing marker where the agent last acted. */
    private fun injectAgentOverlay() {
        runCatching {
            eval(
                """
                (function(){
                  if (document.getElementById('__acfcn_overlay')) return 'ok';
                  var d=document.createElement('div');
                  d.id='__acfcn_overlay';
                  d.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #4F8EF7;border-radius:6px;box-shadow:0 0 0 3px rgba(79,142,247,.25);transition:all .2s;display:none';
                  document.body.appendChild(d);
                  window.__acfcnMark=function(el){
                    if(!el){d.style.display='none';return;}
                    var r=el.getBoundingClientRect();
                    d.style.display='block';
                    d.style.left=(r.left-2)+'px';d.style.top=(r.top-2)+'px';
                    d.style.width=r.width+'px';d.style.height=r.height+'px';
                  };
                  return 'ok';
                })()
                """.trimIndent(),
            )
        }
    }

    fun detach(wv: WebView) {
        if (webView === wv) {
            webView = null
            guard.invalidate()
        }
    }

    fun isAttached(): Boolean = webView != null

    /**
     * Starts a load and returns immediately. This is what the UI (address bar,
     * history list, model download links) should use — no thread is blocked, so
     * a slow or hanging page can never stall the caller.
     */
    fun open(raw: String): String {
        val url = normalizeUrl(raw)
        onMain {
            _url.value = url
            _loading.value = true
            webView?.loadUrl(url)
        }
        return "navigating url=$url"
    }

    /**
     * Agent-facing navigation: load, wait for the page to settle, and hand back
     * a snapshot so the model has something to act on.
     *
     * The waits here are registered in [pendingWaits] and interruptible, so
     * [abortWaits] or a thread interrupt releases them at once instead of
     * burning the full timeout (H6).
     */
    fun navigate(raw: String): String {
        val url = normalizeUrl(raw)
        if (isMain()) return open(url)
        val latch = CountDownLatch(1)
        pageLatch.set(latch)
        onMain {
            _url.value = url
            _loading.value = true
            webView?.loadUrl(url) ?: run {
                latch.countDown()
            }
        }
        val loaded = awaitSignal(latch, NAV_TIMEOUT_MS)
        // "Stop" interrupts this thread, so an abort must not be reported as a
        // slow page — return before spending time on a snapshot nobody reads.
        if (Thread.currentThread().isInterrupted) return "cancelled url=$url"
        if (!awaitSettled(PAGE_SETTLE_MS)) return "cancelled url=$url"
        val snap = snapshot()
        return buildString {
            append(if (loaded) "loaded" else "timeout")
            append(" url=").append(_url.value)
            append(" title=").append(_title.value)
            append('\n').append(snap.take(3500))
        }
    }

    /** Release every agent action that is currently blocked waiting on the page. */
    fun abortWaits() {
        pendingWaits.forEach { it.countDown() }
    }

    private fun awaitSignal(latch: CountDownLatch, timeoutMs: Long): Boolean {
        pendingWaits.add(latch)
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pendingWaits.remove(latch)
        }
    }

    /** Quiet period after a page settles; returns false if interrupted (H6). */
    private fun awaitSettled(ms: Long): Boolean {
        if (ms <= 0) return true
        val latch = CountDownLatch(1)
        pendingWaits.add(latch)
        val timer = java.util.Timer("browser-settle", true)
        return try {
            timer.schedule(object : java.util.TimerTask() {
                override fun run() { latch.countDown() }
            }, ms)
            // Bounded even if the timer somehow never fires; abortWaits() releases
            // it early on stop.
            latch.await(ms + SETTLE_MAX_WAIT_MS, TimeUnit.MILLISECONDS)
            !Thread.currentThread().isInterrupted
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            timer.cancel()
            pendingWaits.remove(latch)
        }
    }

    fun goBack(): String {
        onMain {
            if (webView?.canGoBack() == true) webView?.goBack()
        }
        if (!isMain()) awaitSettled(PAGE_SETTLE_MS)
        return "back url=${_url.value} title=${_title.value}"
    }

    /** Returns true if the WebView handled the back press (i.e. there was history). */
    fun consumeHistoryBack(): Boolean {
        val can = onMainSync(false) { webView?.canGoBack() == true }
        if (can) {
            onMain { webView?.goBack() }
            return true
        }
        return false
    }

    fun canGoBack(): Boolean = onMainSync(false) { webView?.canGoBack() == true }

    fun canGoForward(): Boolean = onMainSync(false) { webView?.canGoForward() == true }

    fun goForward(): String {
        onMain {
            if (webView?.canGoForward() == true) webView?.goForward()
        }
        if (!isMain()) awaitSettled(PAGE_SETTLE_MS)
        return "forward url=${_url.value} title=${_title.value}"
    }

    fun reload(): String {
        onMain { webView?.reload() }
        if (!isMain()) awaitSettled(PAGE_SETTLE_MS)
        return "reload url=${_url.value}"
    }

    /**
     * Index the page and hand out element ids the agent can act on.
     *
     * Each element's fingerprint is recorded so [click] / [type] can detect that
     * the element at that index is no longer the one that was described (H7),
     * instead of blindly re-numbering the page and acting on a shifted index.
     */
    fun snapshot(): String {
        if (webView == null) return "ERROR: browser not attached (open the 浏览器 tab once)"
        // Capture the epoch *before* the eval: if the page navigates while we are
        // indexing it, the ids we are about to hand out are already invalid and
        // the staleness check must reject them.
        val epochBefore = pageEpoch.get()
        val raw = eval(SNAPSHOT_JS)
        _lastSnapshot.value = raw
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.take(4000)
        val els = parsed.optJSONArray("elements") ?: JSONArray()
        val fps = LinkedHashMap<Int, String>(els.length())
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            fps[e.optInt("i")] = e.optString("fp")
        }
        guard.record(epochBefore, fps)
        return buildString {
            append("url=").append(parsed.optString("url")).append('\n')
            append("title=").append(parsed.optString("title")).append('\n')
            append("text=").append(parsed.optString("text").take(1200)).append('\n')
            append("elements:\n")
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                append("#").append(e.optInt("i")).append(" ")
                append(e.optString("tag")).append(" ")
                val t = e.optString("text")
                if (t.isNotBlank()) append('"').append(t.take(80)).append('"').append(' ')
                val href = e.optString("href")
                if (href.isNotBlank()) append(href.take(80))
                append('\n')
            }
        }
    }

    /** Non-null message explaining why ids from the last snapshot can't be used. */
    private fun staleIndexReason(index: Int): String? {
        if (webView == null) return "ERROR: browser not attached (open the 浏览器 tab once)"
        return guard.reason(index, pageEpoch.get())
    }

    /** Resolve element by index, refusing to act on a drifted one (H7). */
    private fun actionJs(index: Int, tail: String): String {
        val want = JSONObject.quote(guard.fingerprintOf(index).orEmpty())
        // Single pass, so page-controlled text can never be re-scanned for a
        // later placeholder and smuggle extra JS into the template.
        val args = mapOf(
            "__INDEX__" to index.toString(),
            "__FP__" to want,
            "__TAIL__" to tail,
        )
        return PLACEHOLDER_REGEX.replace(ACTION_JS_TEMPLATE) { args[it.value].orEmpty() }
    }

    fun click(index: Int): String {
        staleIndexReason(index)?.let { return it }
        val r = eval(actionJs(index, "el.click(); return 'clicked #$index '+el.tagName;"))
        if (!isMain()) awaitSettled(CLICK_SETTLE_MS)
        return r
    }

    fun type(index: Int, text: String): String {
        staleIndexReason(index)?.let { return it }
        val tail = "var q=__TEXT__;" +
            "if ('value' in el) { el.value = q; } else { el.textContent = q; }" +
            "el.dispatchEvent(new Event('input', {bubbles:true}));" +
            "el.dispatchEvent(new Event('change', {bubbles:true}));" +
            "return 'typed $index';"
        return eval(actionJs(index, tail.replace("__TEXT__", JSONObject.quote(text))))
    }

    fun extractText(): String = eval(EXTRACT_JS).take(8000)

    fun evalJs(code: String): String = eval(code).take(8000)

    fun scroll(direction: String): String {
        val dy = when (direction.lowercase()) {
            "up" -> -800
            "top" -> 0
            else -> 800
        }
        val js = if (direction.equals("top", true)) {
            "window.scrollTo(0,0); 'scrolled top'"
        } else {
            "window.scrollBy(0,$dy); 'scrolled $direction'"
        }
        return eval(js)
    }

    fun setDesktop(on: Boolean) {
        _desktop.value = on
        onMain {
            val wv = webView ?: return@onMain
            wv.settings.userAgentString = if (on) DESKTOP_UA else WebSettings.getDefaultUserAgent(wv.context)
            wv.reload()
        }
    }

    fun currentUrl(): String = _url.value
    fun currentTitle(): String = _title.value

    private fun eval(js: String, timeoutMs: Long = 12_000): String {
        val wv = webView ?: return "ERROR: browser not attached (open the 浏览器 tab once)"
        val latch = CountDownLatch(1)
        val box = arrayOf("null")
        main.post {
            try {
                wv.evaluateJavascript(js) { r ->
                    box[0] = unescapeJs(r)
                    latch.countDown()
                }
            } catch (t: Throwable) {
                box[0] = "ERROR: ${t.message}"
                latch.countDown()
            }
        }
        if (!awaitSignal(latch, timeoutMs)) return "ERROR: js timeout"
        return box[0]
    }

    private fun unescapeJs(result: String?): String {
        if (result == null || result == "null") return ""
        return runCatching {
            val v = JSONTokener(result).nextValue()
            v?.toString() ?: result
        }.getOrDefault(result)
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
        if (t.contains(" ") || !t.contains(".")) {
            return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        return "https://$t"
    }

    private fun isMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun onMain(block: () -> Unit) {
        if (isMain()) block()
        else main.post(block)
    }

    private fun <T> onMainSync(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Any>(1)
        var ran = false
        // If the block throws or the main thread never picks it up, fall back to
        // [default] — previously this returned `box[0] as T`, i.e. an NPE for a
        // non-nullable T (M11).
        runCatching {
            main.post {
                runCatching {
                    box[0] = block()
                    ran = true
                }
                latch.countDown()
            }
            awaitSignal(latch, MAIN_SYNC_TIMEOUT_MS)
        }
        if (!ran) return default
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    companion object {
        const val HOME = "https://duckduckgo.com"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private const val NAV_TIMEOUT_MS = 25_000L
        private const val PAGE_SETTLE_MS = 350L
        private const val CLICK_SETTLE_MS = 400L
        private const val SETTLE_MAX_WAIT_MS = 5_000L
        private const val MAIN_SYNC_TIMEOUT_MS = 8_000L

        /** Placeholders substituted into [ACTION_JS_TEMPLATE] in one pass. */
        private val PLACEHOLDER_REGEX = Regex("__INDEX__|__FP__|__TAIL__")

        /**
         * Stable-ish identity of an element, so [click] / [type] can tell whether
         * the node behind an index is still the one [snapshot] described (H7).
         */
        private const val FP_JS =
            "function fp(e){if(!e)return'';return[e.tagName,(e.id||''),'|',(e.className&&e.className.toString?e.className.toString():''),'|'," +
                "(e.innerText||e.value||e.getAttribute('aria-label')||e.placeholder||'').replace(/\\s+/g,' ').trim().slice(0,60),'|'," +
                "(e.getAttribute&&(e.getAttribute('href')||e.getAttribute('type'))||'')].join('');}"

        private val SNAPSHOT_JS = """
            (function(){
              $FP_JS
              var els=[].slice.call(document.querySelectorAll('a,button,input,textarea,select,[onclick],[role="button"],[role="link"]));
              var out=[];
              els.slice(0,70).forEach(function(el,i){
                el.setAttribute('data-agent-id', String(i));
                var text=(el.innerText||el.value||el.getAttribute('aria-label')||el.placeholder||'').replace(/\s+/g,' ').trim();
                out.push({i:i, tag:el.tagName, type:el.type||'', text:text.slice(0,80), href:el.href||el.getAttribute('href')||'', fp:fp(el)});
              });
              var body=(document.body && document.body.innerText || '').replace(/\s+/g,' ').trim().slice(0,1500);
              return JSON.stringify({url:location.href, title:document.title, text:body, elements:out});
            })()
        """.trimIndent()

        private val ACTION_JS_TEMPLATE = """
            (function(){
              $FP_JS
              var el=document.querySelector('[data-agent-id="__INDEX__"]');
              if(!el) return 'STALE: index __INDEX__ is gone from the page — call browser_snapshot again';
              var want=__FP__;
              if (want && fp(el)!==want) return 'STALE: index __INDEX__ is now a different element — call browser_snapshot again';
              try { if (window.__acfcnMark) window.__acfcnMark(el); } catch(e){}
              el.focus();
              __TAIL__
            })()
        """.trimIndent()

        private val EXTRACT_JS = """
            (function(){
              return (document.body && document.body.innerText || '').replace(/\s+/g,' ').trim().slice(0,8000);
            })()
        """.trimIndent()
    }
}
