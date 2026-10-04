package com.selfmod.agent.offline.native

import java.util.concurrent.locks.ReentrantLock

/**
 * Single-flight gate for engine load/unload with an ordered loaded-flag (M13).
 *
 * The native layer serializes on its own mutex, but the Kotlin side let two
 * load() calls run back-to-back: the second silently freed the first's model
 * right after the first reported success — multi-GB mmap loads thrashing each
 * other — and the `loaded` flag could commit true after an interleaved unload
 * had already freed the engine. This gate:
 *  - rejects a load while another one is in flight;
 *  - version-guards flag commits: a load that finished before an interleaved
 *    unload still commits; one that lost the race does not;
 *  - never commits on pre-native early returns (bad path, missing file), so a
 *    rejected request does not flip `loaded` off while a good model is live.
 */
class EngineLifecycle(
    private val loadedSetter: (Boolean) -> Unit,
) {
    private val lock = ReentrantLock()
    private var loading = false
    private var generation = 0L

    /** @return null when another load is in flight, else a generation token. */
    fun beginLoad(): Long? {
        lock.lock()
        try {
            if (loading) return null
            loading = true
            return generation
        } finally {
            lock.unlock()
        }
    }

    /** Clears the in-flight mark; commits the flag only if [attempted] and no unload interleaved. */
    fun endLoad(token: Long, ok: Boolean, attempted: Boolean) {
        lock.lock()
        try {
            loading = false
            if (attempted && token == generation) loadedSetter(ok)
        } finally {
            lock.unlock()
        }
    }

    /** Runs [block] under exclusive access; an unload always ends unloaded. */
    fun <T> withUnload(block: () -> T): T {
        lock.lock()
        try {
            generation++
            try {
                return block()
            } finally {
                loadedSetter(false)
            }
        } finally {
            lock.unlock()
        }
    }
}
