package com.selfmod.agent.offline.native

import com.selfmod.agent.offline.LocalModel
import java.io.File

/**
 * Picks the on-device backend for a model and exposes it behind the single
 * [OnDeviceEngine] the app talks to.
 *
 * Two engines ship side by side and are not interchangeable at the file level:
 *  - GGUF goes to llama.cpp ([LocalLlmEngine]), which reads a single file and
 *    can offload layers to the Adreno GPU through OpenCL;
 *  - MNN models are a *directory* and go to MNN ([MnnLlmEngine]), which runs the
 *    graph through MNN's own OpenCL backend.
 *
 * Only one is loaded at a time: they mmap gigabytes of the same RAM, so keeping
 * both resident would just push the smaller one out to swap. Loading a model of
 * the other format unloads the current one first.
 */
class OnDeviceEngines(
    private val llama: LocalLlmEngine,
    private val mnn: MnnLlmEngine,
) {
    /** Which format the currently loaded model came from, or null when idle. */
    @Volatile
    var loadedFormat: String? = null
        private set

    /** The engine that currently holds a model, or null. */
    val active: OnDeviceEngine?
        get() = when {
            llama.isLoaded() -> llama
            mnn.isLoaded() -> mnn
            else -> null
        }

    fun isLoaded(): Boolean = active != null

    /**
     * Loads [model] into the engine matching its format.
     *
     * @return true on success. A format this app cannot run locally returns
     *         false immediately instead of failing deep inside a native load.
     */
    fun load(
        model: LocalModel,
        path: File,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        onProgress: OnDeviceEngine.LoadCallback? = null,
    ): Boolean {
        val format = model.format.lowercase()
        val ok = when (format) {
            "gguf", "ggml" -> {
                unloadAll()
                llama.load(path, nCtx = nCtx, nThreads = nThreads, nBatch = nBatch, onProgress = onProgress)
            }
            "mnn" -> {
                unloadAll()
                mnn.load(path, nCtx = nCtx, nThreads = nThreads, nBatch = nBatch, onProgress = onProgress)
            }
            else -> {
                com.selfmod.agent.util.Diagnostics.log(
                    "engine",
                    "load: 格式 '$format' 不支持端侧加载（仅 gguf/mnn）",
                )
                false
            }
        }
        loadedFormat = if (ok) format else null
        return ok
    }

    fun unloadAll() {
        runCatching { llama.unload() }
        runCatching { mnn.unload() }
        loadedFormat = null
    }

    fun cancel() {
        runCatching { llama.cancel() }
        runCatching { mnn.cancel() }
    }

    fun clearAbort() {
        runCatching { llama.clearAbort() }
        runCatching { mnn.clearAbort() }
    }
}
