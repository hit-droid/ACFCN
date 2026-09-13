package com.selfmod.agent.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Result of running a script. error != null means the script threw. */
data class ScriptResult(
    val value: Any?,
    val logs: List<String>,
    val error: String?,
    val stdout: String,
)

/**
 * Thin wrapper around Mozilla Rhino. Uses `initSafeStandardObjects` so scripts
 * cannot escape to arbitrary Java classes via reflection — every capability the
 * script can touch must come through the [ScriptApi] object exposed as `api`.
 */
class ScriptEngine {

    @Synchronized
    fun run(
        script: String,
        api: ScriptApi,
        fileName: String = "user_script.js",
    ): ScriptResult {
        val logs = ArrayList<String>()
        val collectingApi = LoggingScriptApi(api) { line -> logs += line }
        val ctx = Context.enter()
        ctx.optimizationLevel = -1            // interpreter mode; portable on Android
        ctx.languageVersion = Context.VERSION_ES6
        try {
            val scope: Scriptable = ctx.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "api", Context.javaToJS(collectingApi, scope))

            // Convenience prelude so scripts can just call log(...) instead of api.log(...).
            val prelude = """
                var log = function() { api.log(Array.prototype.slice.call(arguments).join(' ')); };
                var toast = function(m) { api.toast(m); };
                var http = { get: function(u,h){return api.httpGet(u,h||'{}');},
                             post: function(u,b,h){return api.httpPost(u,b,h||'{}');} };
                var llm = { ask: function(p){return api.llm(p);},
                            chat: function(m){return api.llmChat(m);} };
                var store = { get: function(k){return api.memoryGet(k);},
                              set: function(k,v){api.memorySet(k,v);} };
                var scripts = { read: function(n){return api.readScript(n);},
                                write: function(n,c){return api.writeScript(n,c);},
                                list: function(){return JSON.parse(api.listScripts());} };
                var plugins = { load: function(n){return api.loadPlugin(n);},
                                list: function(){return JSON.parse(api.listPlugins());} };
                var ui = { notify: function(a,p){api.uiNotify(a,p||'{}');} };
                var now = function(){return api.now();};
            """.trimIndent()

            val full = prelude + "\n" + script
            val result = ctx.evaluateString(scope, full, fileName, 1, null)
            val out = logs.joinToString("\n")
            return ScriptResult(value = result, logs = logs, error = null, stdout = out)
        } catch (t: Throwable) {
            val out = logs.joinToString("\n")
            return ScriptResult(value = null, logs = logs, error = t.message ?: t.toString(), stdout = out)
        } finally {
            Context.exit()
        }
    }
}
