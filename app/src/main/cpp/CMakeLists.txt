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
    # see the host too; Vulkan/OpenCL themselves still resolve inside the
    # sysroot (the host has no Vulkan dev package).
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE NEVER)
endif()

# b11640 = commit 3e390af18de1131dda27913c00511725b4274c8a (2026-10-15),
# over the previous b11179 (2026-09-25) for:
#   * #30215 vulkan: L2-friendly workgroup scheduling for the cm2 mulmat —
#     the decode-time matmul on the target device's Adreno 740
#   * #29639 quantized-KV (q4_0/q8_0) flash attention support on Vulkan,
#     which our default q8_0 KV + FA path depends on
#   * Adreno OpenCL fixes (#30176 compile fix, #30041 xmem OOB) and several
#     Vulkan FA correctness fixes (#29591 / #29988 / #30003)
# API compatibility for every llama_/ggml_ symbol acfcn_llm.cpp uses was
# verified symbol-by-symbol against this tag's llama.h / ggml.h /
# ggml-backend.h; the llama_batch API churn (#29622) does not touch
# llama_batch_get_one, our only usage.
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b11640
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# ggml-vulkan (llama.cpp >= ~b11640) calls std::aligned_alloc(), which
# bionic only exports from API 28 — with minSdk 26 the build dies with
# "reference to unresolved using declaration". Force-include a shim that
# declares ::aligned_alloc for API < 28; the implementation is in
# vulkan_compat.cpp (same pattern as the vkGetPhysicalDeviceFeatures2
# shim below). Applied surgically to ggml-vulkan only — it is the sole
# user of aligned_alloc in the backends we build (ggml-rpc / ggml-sycl
# are not enabled).
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
