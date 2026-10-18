#!/usr/bin/env bash
#
# Stage the Khronos OpenCL headers, a self-built OpenCL ICD loader (link-time
# only) and a forwarding wrapper (runtime libOpenCL.so) so the NDK can compile
# llama.cpp's OpenCL backend (GGML_OPENCL=ON) and the APK can actually reach
# the device's vendor GPU driver.
#
# Android does not expose libOpenCL.so through the public NDK. The shipped
# runtime library is NOT the Khronos ICD loader: Adreno/Mali vendor drivers
# are plain complete OpenCL implementations that do not export the loader's
# ICD entry point (clIcdGetPlatformIDsKHR), so the loader sees 0 devices on
# many phones. Instead app/src/main/cpp/opencl_wrapper.c dlopen()s the vendor
# driver by absolute path and forwards the exact symbols libacfcn_llm.so
# needs, with OPENCL_x.y version tags (opencl_wrapper.map) matching what
# libacfcn was linked against. The Khronos loader is still placed in the NDK
# sysroot so -lOpenCL resolves at link time. This script:
#   1. installs the OpenCL headers into the NDK sysroot,
#   2. builds libOpenCL.so (ICD loader) for the target ABI,
#   3. drops it into the NDK sysroot (linker resolves -lOpenCL),
#   4. compiles opencl_wrapper.c into app/src/main/jniLibs/<abi>/libOpenCL.so
#      (what AGP packs and what the linker loads at runtime).
#
# The app then reaches the device's Adreno driver through the standard ICD
# mechanism (see OPENCL_VENDOR_PATH handling in acfcn_llm.cpp).
#
# Usage: scripts/setup-opencl-ndk.sh [abi]   (default abi: arm64-v8a)
#
# Requires: git, cmake (>=3.16), ninja, a matching Android NDK in
# ANDROID_NDK_HOME / ANDROID_NDK_ROOT / $ANDROID_HOME/ndk/<version>.
set -euo pipefail

ABI="${1:-arm64-v8a}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-26}"

OPENCL_HEADERS_TAG="v2024.10.24"
OPENCL_ICD_LOADER_TAG="v2024.10.24"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$ABI" in
  arm64-v8a) ANDROID_ABI_TRIPLE="aarch64-linux-android" ;;
  armeabi-v7a) ANDROID_ABI_TRIPLE="armv7a-linux-androideabi" ;;
  x86_64) ANDROID_ABI_TRIPLE="x86_64-linux-android" ;;
  x86) ANDROID_ABI_TRIPLE="i686-linux-android" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
esac

# --- Locate the NDK ---------------------------------------------------------
# Precedence: explicit ANDROID_NDK_HOME/ANDROID_NDK_ROOT > ANDROID_NDK_VERSION
# under $ANDROID_HOME/ndk > newest NDK found. The build pins ndkVersion in
# app/build.gradle.kts, so callers (e.g. CI, where several NDKs are preinstalled)
# should pass ANDROID_NDK_HOME to guarantee we match it.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$SDK" ] || { echo "Neither ANDROID_NDK_HOME nor ANDROID_HOME is set." >&2; exit 1; }
  if [ -n "${ANDROID_NDK_VERSION:-}" ]; then
    NDK="$SDK/ndk/$ANDROID_NDK_VERSION"
  else
    NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  fi
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "Could not locate an Android NDK (tried '$NDK')." >&2; exit 1; }

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) echo "Unsupported host: $(uname -s)" >&2; exit 1 ;;
esac

SYSROOT="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -d "$SYSROOT" ] || { echo "NDK sysroot not found: $SYSROOT" >&2; exit 1; }
[ -f "$TOOLCHAIN" ] || { echo "NDK toolchain file not found: $TOOLCHAIN" >&2; exit 1; }

echo "NDK:         $NDK"
echo "Sysroot:     $SYSROOT"
echo "ABI:         $ABI ($ANDROID_ABI_TRIPLE)"
echo "Platform:    android-$ANDROID_PLATFORM"

WORK_DIR="$(mktemp -d)"
echo "Work dir:    $WORK_DIR"
trap 'echo "Leaving $WORK_DIR in place (no cleanup)."' EXIT

# --- 1. OpenCL headers into the sysroot -------------------------------------
git clone --depth 1 --branch "$OPENCL_HEADERS_TAG" \
  https://github.com/KhronosGroup/OpenCL-Headers "$WORK_DIR/OpenCL-Headers"
cp -r "$WORK_DIR/OpenCL-Headers/CL" "$SYSROOT/usr/include/"
echo "Installed OpenCL headers -> $SYSROOT/usr/include/CL"

# --- 2. Build the ICD loader for the target ABI -----------------------------
git clone --depth 1 --branch "$OPENCL_ICD_LOADER_TAG" \
  https://github.com/KhronosGroup/OpenCL-ICD-Loader "$WORK_DIR/OpenCL-ICD-Loader"

cmake -S "$WORK_DIR/OpenCL-ICD-Loader" -B "$WORK_DIR/OpenCL-ICD-Loader/build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DOPENCL_ICD_LOADER_HEADERS_DIR="$SYSROOT/usr/include" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_PLATFORM"
# The loader is pure C, so the default (static) STL keeps libOpenCL.so
# self-contained: no extra libc++_shared.so DT_NEEDED in the APK.
cmake --build "$WORK_DIR/OpenCL-ICD-Loader/build" --target OpenCL

BUILT_SO="$(find "$WORK_DIR/OpenCL-ICD-Loader/build" -name 'libOpenCL.so' | head -1)"
[ -n "$BUILT_SO" ] || { echo "libOpenCL.so was not produced." >&2; exit 1; }

# --- 3. Stage into the sysroot and into jniLibs for packaging ---------------
SYSROOT_LIB_DIR="$SYSROOT/usr/lib/$ANDROID_ABI_TRIPLE"
if [ "$ABI" = "armeabi-v7a" ]; then
  SYSROOT_LIB_DIR="$SYSROOT/usr/lib/arm-linux-androideabi"
fi
mkdir -p "$SYSROOT_LIB_DIR"
cp "$BUILT_SO" "$SYSROOT_LIB_DIR/libOpenCL.so"
echo "Installed libOpenCL.so -> $SYSROOT_LIB_DIR/libOpenCL.so (link-time only)"

# --- 4. Build the forwarding wrapper, shipped as the runtime libOpenCL.so ---
# The Khronos ICD loader cannot see Adreno/Mali drivers (they do not export
# the ICD entry point), so at runtime we pack opencl_wrapper.c instead: it
# dlopen()s the vendor driver by absolute path and forwards the symbols
# libacfcn_llm.so imports, version-tagged via opencl_wrapper.map to keep the
# DT_NEEDED verneed check happy (llama.cpp links against OPENCL_x.y versions).
CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/${ANDROID_ABI_TRIPLE}${ANDROID_PLATFORM}-clang"
if [ ! -x "$CLANG" ]; then
  for c in "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/${ANDROID_ABI_TRIPLE}"*-clang; do
    [ -x "$c" ] && CLANG="$c" && break
  done
fi
[ -x "$CLANG" ] || { echo "Cannot find NDK clang for $ABI." >&2; exit 1; }

JNI_LIBS_DIR="$REPO_ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$JNI_LIBS_DIR"
"$CLANG" --target="${ANDROID_ABI_TRIPLE}${ANDROID_PLATFORM}" \
  --sysroot="$SYSROOT" \
  -shared -fPIC -O2 -DNDEBUG \
  -I "$SYSROOT/usr/include" \
  -Wl,--version-script="$REPO_ROOT/app/src/main/cpp/opencl_wrapper.map" \
  -Wl,-soname,libOpenCL.so \
  -o "$JNI_LIBS_DIR/libOpenCL.so" \
  "$REPO_ROOT/app/src/main/cpp/opencl_wrapper.c" \
  -llog -ldl
echo "Built OpenCL forwarding wrapper -> $JNI_LIBS_DIR/libOpenCL.so"

echo "OpenCL NDK setup complete. Build with -Pacfcn.opencl=true."
