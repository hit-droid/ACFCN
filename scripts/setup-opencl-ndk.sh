#!/usr/bin/env bash
#
# Stage the Khronos OpenCL headers and a self-built OpenCL ICD loader so the
# NDK can compile llama.cpp's OpenCL backend (GGML_OPENCL=ON).
#
# Android does not expose libOpenCL.so through the public NDK, so an app that
# uses OpenCL must ship its own ICD loader inside the APK. This script:
#   1. installs the OpenCL headers into the NDK sysroot,
#   2. builds libOpenCL.so for arm64-v8a (against the NDK toolchain),
#   3. drops libOpenCL.so both into the NDK sysroot (so the linker resolves
#      -lOpenCL) and into app/src/main/jniLibs/arm64-v8a (so AGP packs it).
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
ANDROID_PLATFORM="${ANDROID_PLATFORM:-28}"

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
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$SDK" ] || { echo "Neither ANDROID_NDK_HOME nor ANDROID_HOME is set." >&2; exit 1; }
  NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "Could not locate an Android NDK." >&2; exit 1; }

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
echo "Installed libOpenCL.so -> $SYSROOT_LIB_DIR/libOpenCL.so"

JNI_LIBS_DIR="$REPO_ROOT/app/src/main/jniLibs/$ABI"
mkdir -p "$JNI_LIBS_DIR"
cp "$BUILT_SO" "$JNI_LIBS_DIR/libOpenCL.so"
echo "Installed libOpenCL.so -> $JNI_LIBS_DIR/libOpenCL.so"

echo "OpenCL NDK setup complete. Build with -Pacfcn.opencl=true."
