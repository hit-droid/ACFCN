package com.selfmod.agent.offline.native

/**
 * CPU / OpenCL worker counts for the two on-device engines.
 *
 * Native still has the last word (GPU offload clamps llama.cpp to
 * [GPU_CPU_THREADS]; MNN OpenCL ignores a CPU-style count and uses
 * [MNN_OPENCL_THREAD_NUM]), but the Kotlin side needs the same numbers
 * for logs and for the nThreads JNI argument.
 */
object CpuThreadPolicy {

    /** llama.cpp workers kept around once layers are on the GPU. */
    const val GPU_CPU_THREADS = 2

    /**
     * MNN OpenCL `thread_num` is a compute-unit hint, not a pthread count.
     * Official Adreno path uses 68.
     */
    const val MNN_OPENCL_THREAD_NUM = 68

    /**
     * llama.cpp CPU-only workers. Caps at 6 so a 8+ little-core phone does
     * not park Q4_K SDOT on the efficiency cluster.
     */
    fun llamaCpuThreads(availableProcessors: Int): Int =
        availableProcessors.coerceIn(2, 6)

    /**
     * Effective llama.cpp thread count after GPU offload is known.
     * [requested] is what Kotlin / debug.acfcn.threads asked for.
     */
    fun llamaEffectiveThreads(requested: Int, gpuLayers: Int): Int {
        val base = if (requested > 0) requested else llamaCpuThreads(4)
        return if (gpuLayers != 0) minOf(base, GPU_CPU_THREADS).coerceAtLeast(1)
        else base.coerceAtLeast(1)
    }
}
