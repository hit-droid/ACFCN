#!/usr/bin/env bash
#
# Stage the Vulkan C++ headers into the NDK sysroot so the NDK can compile
# llama.cpp's Vulkan backend (GGML_VULKAN=ON).
#
# NDK 27 ships only the C headers (vulkan/vulkan.h + vulkan_core.h), but
# ggml-vulkan is written against Vulkan-HPP (vulkan/vulkan.hpp). The C++ binding
# headers are NOT bundled with the NDK and must be staged. We fetch them
# version-matched to the NDK's VK_HEADER_VERSION (see the staging step below),
# because the host libvulkan-dev package drifts across runner images and would
# otherwise mismatch the NDK's vulkan_core.h. SPIRV-Headers
# (spirv/unified1/spirv.hpp) come from the host package:
#
#   apt install glslc spirv-headers
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
# glslc + SPIRV-Headers are host-side tools/data the build needs directly.
# The Vulkan C++ bindings (vulkan.hpp + components) are fetched below, pinned
# to the NDK's header version, so we no longer depend on the host
# libvulkan-dev's drifting copy.
command -v glslc >/dev/null 2>&1 || {
  echo "glslc not found. Install: apt install glslc (or add it to PATH)" >&2; exit 1; }
[ -f /usr/include/spirv/unified1/spirv.hpp ] || {
  echo "spirv.hpp not found. Install: apt install spirv-headers" >&2; exit 1; }
echo "glslc:    $(command -v glslc) ($(glslc --version | head -1))"

# --- Stage Vulkan-HPP (version-matched to the NDK) ----------------------------
# The NDK ships only the C headers (vulkan.h + vulkan_core.h) and pins
# VK_HEADER_VERSION there; the C++ bindings (vulkan.hpp + its component
# headers) are NOT bundled with the NDK. Copying them from the host
# libvulkan-dev package used to work, but that package's version drifts with
# the runner image: a newer vulkan.hpp (e.g. VK_HEADER_VERSION 341) mixed with
# the NDK's vulkan_core.h (275) fails the build with "Wrong VK_HEADER_VERSION!".
# So we fetch the EXACT matching C++ bindings from Khronos Vulkan-Headers at
# the tag that pairs with the NDK's VK_HEADER_VERSION, overwriting any host
# copy. This keeps the HPP set and the C headers on the same header version
# regardless of what libvulkan-dev the runner happens to provide.
VK_INC="$SYSROOT/usr/include/vulkan"
mkdir -p "$VK_INC"

VK_VER=$(grep -oE '#define[[:space:]]+VK_HEADER_VERSION[[:space:]]+[0-9]+' \
          "$SYSROOT/usr/include/vulkan/vulkan_core.h" \
          | grep -oE '[0-9]+' | head -1)
[ -n "$VK_VER" ] || {
  echo "Could not read VK_HEADER_VERSION from NDK vulkan_core.h" >&2; exit 1; }
VK_TAG="v1.3.$VK_VER"
echo "NDK VK_HEADER_VERSION: $VK_VER  -> staging Vulkan-Hpp from tag $VK_TAG"

# Resolve the exact set of .hpp files at that tag (filenames grow across
# versions), preferring the GitHub tree API; fall back to a known list for
# v1.3.275 if the API is unreachable.
HPP_FILES=()
if TREE=$(curl -fsSL "https://api.github.com/repos/KhronosGroup/Vulkan-Headers/git/trees/$VK_TAG?recursive=1" 2>/dev/null); then
  HPP_FILES=( $(printf '%s' "$TREE" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for t in d.get('tree', []):
    p = t.get('path', '')
    if p.startswith('include/vulkan/') and p.endswith('.hpp'):
        print(p.split('/')[-1])
") )
fi
if [ "${#HPP_FILES[@]}" -eq 0 ]; then
  echo "Vulkan-Hpp tree API unavailable; using known file list for $VK_TAG"
  HPP_FILES=( vulkan.hpp vulkan_enums.hpp vulkan_extension_inspection.hpp \
              vulkan_format_traits.hpp vulkan_funcs.hpp vulkan_handles.hpp \
              vulkan_hash.hpp vulkan_hpp_macros.hpp vulkan_raii.hpp \
              vulkan_shared.hpp vulkan_static_assertions.hpp vulkan_structs.hpp \
              vulkan_to_string.hpp vulkan_video.hpp )
fi

for base in "${HPP_FILES[@]}"; do
  url="https://raw.githubusercontent.com/KhronosGroup/Vulkan-Headers/$VK_TAG/include/vulkan/$base"
  curl -fsSL "$url" -o "$VK_INC/$base" \
    || { echo "Failed to download $url" >&2; exit 1; }
done
echo "Staged Vulkan-Hpp ($VK_VER) -> $VK_INC (${#HPP_FILES[@]} headers)"

# --- Stage SPIRV-Headers ------------------------------------------------------
# ggml-vulkan includes spirv/unified1/spirv.hpp directly (for the shader
# reflection/SPIR-V constants used at pipeline setup time).
cp -r /usr/include/spirv "$SYSROOT/usr/include/"
echo "Staged SPIRV-Headers -> $SYSROOT/usr/include/spirv"

echo "Vulkan NDK setup complete. Build with ACFCN_VULKAN=true."
