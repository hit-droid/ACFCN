cmake_minimum_required(VERSION 3.22.1)
project("acfcn_llm")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pull llama.cpp at configure time so our repo stays clean (no giant submodule).
include(FetchContent)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Build the CPU backend with the ARMv8.2 dotprod + fp16 kernels. The NDK
# toolchain defaults to the plain armv8-a baseline, which lacks SDOT/UDOT and
# makes quantized (Q4_K) matmul several times slower. These extensions are
# present on every Cortex-A75/A55-era core and newer (2017+), and ggml gates its
# optimized kernels on __ARM_FEATURE_DOTPROD, which -march turns on.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# OpenCL backend for Adreno GPU offload. Off by default: the backend needs
# OpenCL headers + an ICD loader staged in the NDK sysroot (CI wires this up,
# see .github/workflows/*.yml via scripts/setup-opencl-ndk.sh). Without that
# setup the flag stays OFF and the engine is CPU-only, exactly as before.
#
# Enable with the ACFCN_OPENCL=true environment variable, e.g.:
#   ACFCN_OPENCL=true ./gradlew :app:assembleDebug
# (AGP passes env vars through to the CMake invocation.)
option(ACFCN_ENABLE_OPENCL "Enable the OpenCL (Adreno GPU) backend" OFF)
if(DEFINED ENV{ACFCN_OPENCL} AND NOT "$ENV{ACFCN_OPENCL}" STREQUAL "")
    set(ACFCN_ENABLE_OPENCL "$ENV{ACFCN_OPENCL}" CACHE BOOL "Enable the OpenCL (Adreno GPU) backend" FORCE)
endif()

if(ACFCN_ENABLE_OPENCL)
    message(STATUS "ACFCN: OpenCL (Adreno GPU) backend enabled")
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    # Qualcomm's Adreno-optimized matmul kernels ship in the backend and are the
    # whole reason this path is faster than the generic CPU/SIMD kernels.
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    # Embed the kernels in the .so so nothing has to be compiled on device.
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
endif()

# Vulkan backend (Adreno GPU offload). Preferred over OpenCL when both are
# built: llama.cpp's Vulkan backend is markedly faster on mobile Adreno
# (subgroup tiling + cooperative matrix on Gen6+), it is the path MNN uses to
# reach its decode speed, and unlike the OpenCL ICD hack it needs no runtime
# wrapper — Android ships the Khronos Vulkan loader system-wide. Shaders are
# SPIRV-compiled at build time by the host glslc and embedded in the .so.
#
# Enable with the ACFCN_VULKAN=true environment variable, e.g.:
#   ACFCN_VULKAN=true ./gradlew :app:assembleDebug
# Host build deps (all in CI): glslc + SPIRV-Headers (apt: glslc spirv-headers)
# plus the staged Vulkan-HPP headers (scripts/setup-vulkan-ndk.sh). Linking
# uses the NDK's own per-API-level libvulkan.so stub; the one 1.1 symbol
# ggml-vulkan references directly (vkGetPhysicalDeviceFeatures2) is provided
# by vulkan_compat.cpp so the build also links for API 26/27 targets.
option(ACFCN_ENABLE_VULKAN "Enable the Vulkan (Adreno GPU) backend" OFF)
if(DEFINED ENV{ACFCN_VULKAN} AND NOT "$ENV{ACFCN_VULKAN}" STREQUAL "")
    set(ACFCN_ENABLE_VULKAN "$ENV{ACFCN_VULKAN}" CACHE BOOL "Enable the Vulkan (Adreno GPU) backend" FORCE)
endif()

if(ACFCN_ENABLE_VULKAN)
    message(STATUS "ACFCN: Vulkan (Adreno GPU) backend enabled")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # The NDK toolchain re-roots find_package to the sysroot
    # (CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY), which hides the host-installed
    # SPIRV-Headers CMake config that ggml-vulkan needs. Let package lookups
    # see the host too; SPIRV-Headers still resolves on the host below.
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE NEVER)
    # Pin ggml-vulkan to the NDK sysroot's Vulkan headers. scripts/setup-vulkan-ndk.sh
    # stages a version-consistent Vulkan-Headers set (vulkan.hpp + vulkan_core.h at
    # the SAME VK_HEADER_VERSION) into the sysroot; resolving there — instead of the
    # host libvulkan-dev copy that find_package would otherwise pick under
    # CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=NEVER — keeps the pair consistent regardless
    # of what the runner images ship. A runner refresh pushed libvulkan-dev's
    # vulkan.hpp to 341 while the NDK's vulkan_core.h stayed at 275, which broke the
    # whole build with "Wrong VK_HEADER_VERSION!"; forcing the sysroot include makes
    # that impossible to recur. CMAKE_SYSROOT is provided by the NDK toolchain file.
    if(DEFINED CMAKE_SYSROOT AND EXISTS "${CMAKE_SYSROOT}/usr/include/vulkan/vulkan.hpp")
      set(Vulkan_INCLUDE_DIR "${CMAKE_SYSROOT}/usr/include" CACHE PATH "Vulkan headers (NDK sysroot, version-matched)" FORCE)
    endif()
endif()

# ARM KleidiAI GEMM kernels (CPU). This is the CPU-side lever that brings
# llama.cpp's quantized matmul within reach of MNN's hand-tuned NEON GEMM on
# Cortex-A — the remaining gap after q8_0 KV + CPU flash attention. Opt-in for
# now: GGML_CPU_KLEIDIAI pulls the ARM-software/kleidiai release tarball at
# configure time (needs an AArch64 target + CMake >= 3.16, both satisfied by the
# NDK r27 toolchain) and must be validated against NDK on-device before we flip
# it on by default. Enable with the ACFCN_KLEIDIAI environment variable, e.g.:
#   ACFCN_KLEIDIAI=true ./gradlew :app:assembleDebug
option(ACFCN_ENABLE_KLEIDIAI "Enable ARM KleidiAI CPU GEMM kernels" OFF)
if(DEFINED ENV{ACFCN_KLEIDIAI} AND NOT "$ENV{ACFCN_KLEIDIAI}" STREQUAL "")
    set(ACFCN_ENABLE_KLEIDIAI "$ENV{ACFCN_KLEIDIAI}" CACHE BOOL "Enable ARM KleidiAI CPU GEMM kernels" FORCE)
endif()

if(ACFCN_ENABLE_KLEIDIAI)
    message(STATUS "ACFCN: ARM KleidiAI CPU GEMM kernels enabled")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# Pinned to master @ 74bca3950054eed1795f525cb9cf06bfe796492d (2026-10-25),
# 230 commits ahead of the previous b11640 (2026-10-15). Highlights for the
# Adreno decode/prefill path (the "match MNN speed" line):
#   * opencl: cooperative-k dp4a GEMM for q4_0 / q4_K / q6_K (#30741) plus
#     dp4a vector-load-activation variants (#30753) and small-batch GEMM
#     variants (#30850) — the decode/prefill matmul work on Adreno 740
#   * opencl: fix OOB tail access for the Adreno noshuffle GEMV kernels
#     (#30421, the decode GEMV path) and apply FA softcap before the mask
#     (#30546); rope program build fix for newer Clang (#30893), which the
#     NDK r27+ Clang in CI needs
#   * vulkan: ggml src1 precision support (#30656) and concurrent first
#     device init fix (#29766)
# API compatibility for every llama_/ggml_ symbol acfcn_llm.cpp uses was
# verified symbol-by-symbol against this commit's llama.h / ggml.h /
# ggml-backend.h; the only visible changes are llama_sampler_chain_add
# returning void instead of bool (we ignore the return value) and
# llama_model_params.use_mlock being removed (we do not use it).
# llama_batch_get_one is unchanged. llama.cpp switched to semver tags
# (v0.7.0 = 2026-10-15); b11640 was the last b-tag, so this pins the SHA.
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        74bca3950054eed1795f525cb9cf06bfe796492d
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# ggml-vulkan called std::aligned_alloc() up to b11640, which bionic only
# exports from API 28 — with minSdk 26 the build died with "reference to
# unresolved using declaration". Upstream replaced it with posix_memalign
# (#30828), so this shim is now a harmless no-op safety net: nothing in the
# backends we build references ::aligned_alloc any more, but keeping the
# force-include costs nothing and protects against a regression. The
# implementation is in vulkan_compat.cpp (same pattern as the
# vkGetPhysicalDeviceFeatures2 shim below).
if(TARGET ggml-vulkan)
    target_compile_options(ggml-vulkan PRIVATE
        "-include${CMAKE_CURRENT_SOURCE_DIR}/android_aligned_alloc_compat.h")
endif()

add_library(acfcn_llm SHARED
    acfcn_llm.cpp
)

if(ACFCN_ENABLE_OPENCL)
    # Only the JNI bridge needs to know whether a GPU backend was built in; the
    # GGML_* defines do not reliably propagate across the llama/ggml targets.
    target_compile_definitions(acfcn_llm PRIVATE ACFCN_USE_OPENCL)
endif()

if(ACFCN_ENABLE_VULKAN)
    target_compile_definitions(acfcn_llm PRIVATE ACFCN_USE_VULKAN)
    # ggml-vulkan references vkGetPhysicalDeviceFeatures2 (Vulkan 1.1) as a
    # direct extern symbol, which the NDK's API 26/27 libvulkan stubs do not
    # export. The shim provides it locally (runtime-forwards to the system
    # loader when present), so no full-symbol link stub is needed.
    target_sources(acfcn_llm PRIVATE vulkan_compat.cpp)
endif()

target_link_libraries(acfcn_llm
    llama
    android
    log
)

# ---------------------------------------------------------------------------
# MNN backend bridge (acfcn_mnn.so)
#
# Links against MNN 3.6.1's prebuilt Android .so files that ship in
# app/src/main/jniLibs/arm64-v8a (libllm.so + libMNN*.so + libc++_shared.so).
# No MNN source is compiled here: the official release libs already carry the
# OpenCL/Vulkan backends tuned for Adreno, which is the whole point of this
# path. The JNI bridge only implements the chat/load/abort contract.
# ---------------------------------------------------------------------------

# The prebuilt libs live next to CMakeLists under ../jniLibs/<abi>.
set(ACFCN_MNN_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a)

foreach(_lib libllm libMNN libMNN_Express)
    add_library(${_lib} SHARED IMPORTED)
    set_target_properties(${_lib} PROPERTIES
        IMPORTED_LOCATION ${ACFCN_MNN_LIB_DIR}/${_lib}.so
        IMPORTED_SONAME ${_lib}.so
    )
endforeach()

add_library(acfcn_mnn SHARED
    mnn_llm.cpp
)

target_include_directories(acfcn_mnn PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/mnn/include
)

target_link_libraries(acfcn_mnn
    libllm
    libMNN
    libMNN_Express
    android
    log
)
