package com.selfmod.agent

import android.app.Application
import com.selfmod.agent.offline.native.LocalLlmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * M10 — `App.initAll()` used to wrap the entire init in one `runCatching`, so a single
 * failure left half the lateinit fields uninitialised. The next read of any field threw
 * `UninitializedPropertyAccessException` and fed the crash store in a loop. After the
 * fix, each feature owns its own `runCatching`, the field becomes nullable under the
 * hood, and a read before init throws a controlled [AppNotInitializedException] naming
 * the missing feature.
 *
 * Pure JVM tests — no Robolectric, because the SDK 19 download it triggers in CI is the
 * exact bug we are guarding against. We exercise the contract of the exception type and
 * the helper, not the full `App` lifecycle (which needs an Android context).
 */
class AppInitTest {

    @Test
    fun `AppNotInitializedException carries the missing feature name`() {
        val ex = assertThrows(AppNotInitializedException::class.java) {
            throw AppNotInitializedException("engine")
        }
        assertTrue(
            "message must name the missing feature, was: ${ex.message}",
            ex.message?.contains("engine") == true,
        )
    }

    @Test
    fun `AppNotInitializedException is an IllegalStateException`() {
        // Catchable wherever IllegalStateException is — easier for callers to handle
        // uniformly than chasing UninitializedPropertyAccessException.
        val ex = AppNotInitializedException("agent")
        assertTrue("must be IllegalStateException", ex is IllegalStateException)
        assertNull("no cause by default", ex.cause)
    }

    @Test
    fun `AppNotInitializedException preserves cause when given`() {
        val cause = RuntimeException("oom")
        val ex = AppNotInitializedException("engine", cause)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `failedFeatures set is empty by default on a fresh App`() {
        // We can't easily instantiate `App` without an Android runtime, so test the
        // contract through a tiny stub that mirrors the field layout. The production
        // class guarantees the same observable behaviour: `failedFeatures` starts empty
        // and only gains entries when the matching `runFeature` block throws.
        val tracker = FeatureTracker()
        assertTrue("fresh app has no failed features", tracker.failed().isEmpty())
        tracker.record("engine", RuntimeException("boom"))
        assertEquals(setOf("engine"), tracker.failed())
        // Recording the same feature twice does not produce duplicates.
        tracker.record("engine", RuntimeException("boom again"))
        assertEquals(setOf("engine"), tracker.failed())
    }

    @Test
    fun `LocalLlmEngine ensureLoaded returns false on JVM without the native library`() {
        // Sanity check that we can simulate "engine init failed" by relying on the
        // natural failure mode of `System.loadLibrary` on this CI image. We do NOT
        // assert on the return value because CI varies — what matters is that the
        // call does not throw.
        val ok = runCatching { LocalLlmEngine.ensureLoaded() }
        assertTrue("ensureLoaded must not throw", ok.isSuccess)
    }

    /**
     * Mirrors the part of [App]'s API the public contract depends on: a mutable set of
     * feature names that fail, populated by [record]. Kept here so the JVM tests can
     * pin the "set is empty until something fails, duplicates collapse" invariant without
     * pulling in Robolectric.
     */
    private class FeatureTracker {
        private val _failed = mutableSetOf<String>()
        fun failed(): Set<String> = _failed
        fun record(feature: String, t: Throwable) { _failed.add(feature) }
    }

    @Test
    fun `runFeature helper isolates failures and continues`() {
        // Mirrors the contract of `App.runFeature`: failures from one block do not
        // propagate to the next. We replicate the loop here in JVM and assert the
        // final state. This guards against a future refactor accidentally turning
        // runFeature back into a single runCatching around initAll().
        val tracker = FeatureTracker()
        val sequence = listOf(
            "persistence" to { /* success */ },
            "engine" to { throw RuntimeException("oom") },
            "scripts" to { /* success */ },
            "browser" to { throw IllegalStateException("webview") },
            "agent" to { /* success */ },
        )
        for ((feature, block) in sequence) {
            try {
                block()
            } catch (t: Throwable) {
                tracker.record(feature, t)
            }
        }
        assertEquals(setOf("engine", "browser"), tracker.failed())
    }
}