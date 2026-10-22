#!/usr/bin/env bash
#
# Stage the Vulkan C++ headers into the NDK sysroot so the NDK can compile
# llama.cpp's Vulkan backend (GGML_VULKAN=ON).
#
# NDK 27 ships only the C headers (vulkan/vulkan.h + vulkan_core.h), but
# ggml-vulkan is written against Vulkan-HPP (vulkan/vulkan.hpp) and includes
# SPIRV-Headers directly (spirv/unified1/spirv.hpp). Both are pure header
# libraries that come from host packages:
#
#   apt install glslc spirv-headers libvulkan-dev
#
# glslc (host) compiles the .comp shaders to SPIRV at build time — required
# by ggml-vulkan's CMake (find_package(Vulkan COMPONENTS glslc)). This script
# only stages the headers; it does not install packages.
#
# Usage: scripts/setup-vulkan-ndk.sh [abi]   (default abi: arm64-v8a)

set -euo pipefail

ABI="${1:-arm64-v8a}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-26}"

case "$ABI" in
  arm64-v8a) ANDROID_ABI_TRIPLE="aarch64-linux-android" ;;
  armeabi-v7a) ANDROID_ABI_TRIPLE="armv7a-linux-androideabi" ;;
  x86_64) ANDROID_ABI_TRIPLE="x86_64-linux-android" ;;
  x86) ANDROID_ABI_TRIPLE="i686-linux-android" ;;
  *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
esac

# --- Locate the NDK (same precedence as setup-opencl-ndk.sh) -----------------
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
[ -d "$SYSROOT" ] || { echo "NDK sysroot not found: $SYSROOT" >&2; exit 1; }

echo "NDK:      $NDK"
echo "Sysroot:  $SYSROOT"
echo "ABI:      $ABI ($ANDROID_ABI_TRIPLE)"

# --- Check host deps ----------------------------------------------------------
command -v glslc >/dev/null 2>&1 || {
  echo "glslc not found. Install: apt install glslc (or add it to PATH)" >&2; exit 1; }
[ -f /usr/include/vulkan/vulkan.hpp ] || {
  echo "vulkan.hpp not found. Install: apt install libvulkan-dev" >&2; exit 1; }
[ -f /usr/include/spirv/unified1/spirv.hpp ] || {
  echo "spirv.hpp not found. Install: apt install spirv-headers" >&2; exit 1; }
echo "glslc:    $(command -v glslc) ($(glslc --version | head -1))"

# --- Stage Vulkan-HPP ---------------------------------------------------------
# Only the .hpp files: the C headers (vulkan.h, vulkan_core.h, ...) already
# ship with the NDK and must not be overwritten (they match the NDK's API
# level split).
VK_INC="$SYSROOT/usr/include/vulkan"
mkdir -p "$VK_INC"
for f in /usr/include/vulkan/*.hpp; do
  cp "$f" "$VK_INC/"
done
echo "Staged Vulkan-HPP -> $VK_INC ($(ls "$VK_INC"/*.hpp | wc -l) headers)"

# --- Stage SPIRV-Headers ------------------------------------------------------
# ggml-vulkan includes spirv/unified1/spirv.hpp directly (for the shader
# reflection/SPIR-V constants used at pipeline setup time).
cp -r /usr/include/spirv "$SYSROOT/usr/include/"
echo "Staged SPIRV-Headers -> $SYSROOT/usr/include/spirv"

echo "Vulkan NDK setup complete. Build with ACFCN_VULKAN=true."
