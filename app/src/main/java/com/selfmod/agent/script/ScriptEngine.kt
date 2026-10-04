package com.selfmod.agent.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Result of running a script. error != null means the script threw. */
data class ScriptResult(
    val value: Any?,
    val logs: List<String>,
    val error: String?,
    val stdout: String,
)

/** Thrown out of the interpreter when a script burns its wall-clock budget (L11). */
internal class ScriptTimeoutException(message: String) : EvaluatorException(message)

/**
 * Context that watches its own instruction count and gives up once the wall
 * clock passes [deadlineMs]. Rhino only invokes [observeInstructionCount] in
 * interpreter mode when an observer threshold is set (measured on 1.7.14),
 * which is exactly how [ScriptEngine] configures it.
 */
internal class BudgetedContext : Context() {
    @Volatile var deadlineMs: Long = 0L

    init {
        // Without a threshold the interpreter never calls observeInstructionCount,
        // so the deadline below would never be checked (verified empirically).
        instructionObserverThreshold = ScriptEngine.INSTRUCTION_OBSERVER_THRESHOLD
    }

    override fun observeInstructionCount(instructionCount: Int) {
        if (deadlineMs != 0L && System.currentTimeMillis() > deadlineMs) {
            throw ScriptTimeoutException(
                "script exceeded its time limit (likely an infinite loop) " +
                    "at $instructionCount instructions",
            )
        }
    }
}

/**
 * Thin wrapper around Mozilla Rhino. Uses `initSafeStandardObjects` so scripts
 * cannot escape to arbitrary Java classes via reflection — every capability the
 * script can touch must come through the [ScriptApi] object exposed as `api`.
 */
class ScriptEngine {

    private object Factory : ContextFactory() {
        override fun makeContext(): Context = BudgetedContext()
    }

    @Synchronized
    fun run(
        script: String,
        api: ScriptApi,
        fileName: String = "user_script.js",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ScriptResult {
        val logs = ArrayList<String>()
        val collectingApi = LoggingScriptApi(api) { line -> logs += line }
        return try {
            val value = Factory.call { cx ->
                cx.optimizationLevel = -1            // interpreter mode; portable on Android
                cx.languageVersion = Context.VERSION_ES6
                // Bound recursion too: a stack-overflow loop never lets the observer
                // run, so a wall-clock deadline alone cannot stop runaway recursion (L11).
                cx.maximumInterpreterStackDepth = MAX_INTERPRETER_STACK_DEPTH
                val ctx = cx as BudgetedContext
                ctx.deadlineMs = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L

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

                ctx.evaluateString(scope, prelude + "\n" + script, fileName, 1, null)
            }
            ScriptResult(value = value, logs = logs, error = null, stdout = logs.joinToString("\n"))
        } catch (t: Throwable) {
            val error = if (t is ScriptTimeoutException) "TIMEOUT: ${t.message}" else t.message ?: t.toString()
            ScriptResult(value = null, logs = logs, error = error, stdout = logs.joinToString("\n"))
        }
    }

    companion object {
        /** A script may not hog the agent thread for longer than this. */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Poll the clock roughly every 20k instructions — cheap and responsive enough. */
        const val INSTRUCTION_OBSERVER_THRESHOLD = 20_000

        /** Rhino's own default is 1500; keep a firm cap so recursion fails fast, not hangs. */
        const val MAX_INTERPRETER_STACK_DEPTH = 1500
    }
}
