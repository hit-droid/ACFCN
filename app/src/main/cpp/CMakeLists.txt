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

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b11179
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(acfcn_llm SHARED
    acfcn_llm.cpp
)

if(ACFCN_ENABLE_OPENCL)
    # Only the JNI bridge needs to know whether a GPU backend was built in; the
    # GGML_* defines do not reliably propagate across the llama/ggml targets.
    target_compile_definitions(acfcn_llm PRIVATE ACFCN_USE_OPENCL)
endif()

target_link_libraries(acfcn_llm
    llama
    android
    log
)
