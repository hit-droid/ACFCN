package com.selfmod.agent

import android.app.Application
import android.util.Log

/**
 * Installs a global uncaught-exception handler. On crash we persist a report;
 * [MainActivity] checks for it on the next launch and shows a friendly screen
 * with copy/share. We still delegate to the previous handler so the system
 * logs the crash and kills the process normally.
 */
class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            Log.e("ACFCN", "UNCAUGHT on ${thread.name}", throwable)
            CrashStoreHolder.store?.save(throwable)
        }
        previous?.uncaughtException(thread, throwable)
    }

    companion object {
        fun install(store: com.selfmod.agent.util.CrashStore) {
            CrashStoreHolder.store = store
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            if (prev is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(prev))
        }
    }
}

object CrashStoreHolder {
    @Volatile
    var store: com.selfmod.agent.util.CrashStore? = null
}
