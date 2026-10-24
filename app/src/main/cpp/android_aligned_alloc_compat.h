// Force-include shim for the ggml-vulkan backend on old Android API levels.
//
// Android's bionic only exports aligned_alloc() from API 28 (Android 9).
// llama.cpp's ggml-vulkan (new portable_aligned_malloc() since ~b11640)
// calls std::aligned_alloc(), which is a using-declaration of
// ::aligned_alloc guarded by _LIBCPP_USING_IF_EXISTS — with our minSdk 26
// the platform header declares no such symbol and the compile fails with
// "reference to unresolved using declaration". Declaring ::aligned_alloc
// ourselves (this header is force-included before any other include, so it
// precedes libc++'s <cstdlib>) makes the using declaration resolve.
//
// The implementation for API < 28 lives in vulkan_compat.cpp (posix_memalign
// based). On API 28+ this header is a no-op and the platform symbol is used.
//
// Wired up in CMakeLists.txt via target_compile_options(ggml-vulkan ...).

#ifndef ACFCN_ANDROID_ALIGNED_ALLOC_COMPAT_H
#define ACFCN_ANDROID_ALIGNED_ALLOC_COMPAT_H

#if defined(__ANDROID_API__) && __ANDROID_API__ < 28

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif
void *aligned_alloc(size_t alignment, size_t size);
#ifdef __cplusplus
}
#endif

#endif // __ANDROID_API__ < 28

#endif // ACFCN_ANDROID_ALIGNED_ALLOC_COMPAT_H
