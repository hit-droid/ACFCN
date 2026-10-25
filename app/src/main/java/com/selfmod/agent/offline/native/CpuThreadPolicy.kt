package com.selfmod.agent.offline.native

/**
 * Shared thread-count constants for on-device engines.
 *
 * MNN OpenCL `thread_num` is a compute-unit hint, not a pthread count.
 * llama.cpp thread policy lives in [LocalLlmEngine] / native affinity.
 */
object CpuThreadPolicy {
    /** Official Adreno OpenCL path: CL_DEVICE_MAX_COMPUTE_UNITS-style hint. */
    const val MNN_OPENCL_THREAD_NUM = 68

    /** Upper bound for llama.cpp decode workers; measured optimum on Adreno. */
    const val MAX_DEFAULT_THREADS = 4

    /** Floor so a restricted cpuset still gets a working pool. */
    const val MIN_DEFAULT_THREADS = 2

    /**
     * Resolve the llama.cpp worker count.
     *
     * [suggested] is the big-cluster size reported by native (already
     * intersected with the process's allowed CPU set); 0 means detection was
     * unavailable. Detection failure must not silently halve throughput: fall
     * back to [MAX_DEFAULT_THREADS] instead of the floor.
     */
    fun resolveThreads(cores: Int, suggested: Int): Int {
        val base = if (suggested > 0) minOf(cores, suggested) else minOf(cores, MAX_DEFAULT_THREADS)
        return base.coerceIn(MIN_DEFAULT_THREADS, MAX_DEFAULT_THREADS)
    }
}
