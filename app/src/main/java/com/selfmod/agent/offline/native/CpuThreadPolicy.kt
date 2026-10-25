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
}
