#!/usr/bin/env bash
#
# Stage a version-consistent set of Vulkan headers (C core + C++ binding) into
# the NDK sysroot so llama.cpp's Vulkan backend (GGML_VULKAN=ON) compiles.
#
# Why this is needed, and why a naive copy breaks the build
# ----------------------------------------------------------
# NDK 27 (27.0.12077973) ships BOTH the C++ binding vulkan.hpp AND the C core
# vulkan_core.h in its sysroot — and they are on DIFFERENT VK_HEADER_VERSION
# values (vulkan.hpp at 341, vulkan_core.h at 275 on that NDK). ggml-vulkan
# compiles vulkan.hpp, whose static_assert demands
#   VK_HEADER_VERSION == <its own version>
# and then #includes vulkan_core.h. If the .hpp and the .h disagree, the build
# dies with "Wrong VK_HEADER_VERSION!".
#
# Copying the host libvulkan-dev vulkan.hpp (also 341) into the sysroot without
# also syncing vulkan_core.h to 341 reproduces exactly that skew: the host
# package ships vulkan.hpp but NOT vulkan_core.h, so the compiler falls back to
# the NDK's vulkan_core.h (275) and fails 275 == 341. That regression broke the
# whole CI/release build line (see PR #86 reverting the earlier Khronos-staging
# fix). This script restores and hardens that fix.
#
# The fix: derive the target version from the vulkan.hpp that will actually be
# compiled, then stage the FULL Vulkan-Headers (both .h and .hpp) for that exact
# tag from Khronos into the sysroot, overwriting the NDK's mismatched pair. The
# resulting vulkan.hpp + vulkan_core.h are always the same version. A post-stage
# assert fails fast if they ever drift apart again, instead of blowing up 10
# minutes into the native compile.
#
# Host-side build deps (tools only):
#   apt install glslc spirv-headers libvulkan-dev
# glslc (host) compiles the .comp shaders to SPIRV at build time; SPIRV-Headers
# and libvulkan-dev are only consulted to learn the target header version.
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

# --- Host build deps (tools only) --------------------------------------------
command -v glslc >/dev/null 2>&1 || {
  echo "glslc not found. Install: apt install glslc (or add it to PATH)" >&2; exit 1; }
[ -f /usr/include/spirv/unified1/spirv.hpp ] || {
  echo "spirv.hpp not found. Install: apt install spirv-headers" >&2; exit 1; }
echo "glslc:    $(command -v glslc) ($(glslc --version | head -1))"

# --- Determine the Vulkan header version to stage ----------------------------
# Prefer the version declared by the vulkan.hpp that ggml-vulkan will compile
# (the NDK's bundled copy first, then the host libvulkan-dev copy). Fall back to
# the NDK's vulkan_core.h only if no vulkan.hpp is available (older NDKs that
# ship C headers alone).
VK_INC="$SYSROOT/usr/include/vulkan"
mkdir -p "$VK_INC"
# Staging root: the whole Vulkan-Headers include/ tree (vulkan/ AND vk_video/)
# is laid down here so every transitive #include resolves against usr/include.
VK_STAGE="$SYSROOT/usr/include"
VK_VER=""
for cand in "$VK_INC/vulkan.hpp" /usr/include/vulkan/vulkan.hpp; do
  if [ -f "$cand" ]; then
    VK_VER=$(grep -oE 'VK_HEADER_VERSION[[:space:]]*==[[:space:]]*[0-9]+' "$cand" \
              | grep -oE '[0-9]+' | tail -1)
    [ -n "$VK_VER" ] && break
  fi
done
if [ -z "$VK_VER" ]; then
  VK_VER=$(grep -oE '#define[[:space:]]+VK_HEADER_VERSION[[:space:]]+[0-9]+' \
            "$VK_INC/vulkan_core.h" | grep -oE '[0-9]+' | head -1)
fi
[ -n "$VK_VER" ] || { echo "Could not determine VK_HEADER_VERSION from NDK/NDK-host Vulkan headers" >&2; exit 1; }

# Resolve the Khronos Vulkan-Headers tag for this header version. The repo tags
# follow the API version (v1.3.<ver> on the 1.3 line, v1.4.<ver> on the 1.4
# line); a given VK_HEADER_VERSION patch number lands on whichever major line
# introduced it (e.g. 275 -> v1.3.275, 341 -> v1.4.341). Try the known prefixes
# and keep the first tag that actually exists.
VK_TAG=""
for cand in "v1.3.$VK_VER" "v1.4.$VK_VER" "vulkan-sdk-1.4.$VK_VER.0"; do
  if curl -fsSL -o /dev/null "https://api.github.com/repos/KhronosGroup/Vulkan-Headers/git/refs/tags/$cand" 2>/dev/null; then
    VK_TAG="$cand"
    break
  fi
done
[ -n "$VK_TAG" ] || { echo "No Vulkan-Headers tag found for VK_HEADER_VERSION $VK_VER" >&2; exit 1; }
echo "Target VK_HEADER_VERSION: $VK_VER  -> staging Vulkan-Headers $VK_TAG"

# --- Stage the full, version-consistent Vulkan-Headers set -------------------
# Both the .hpp (C++ binding) and the .h (C core: vulkan_core.h, vulkan.h, the
# platform- specific vulkan_*.h) come from the SAME tag, so the static_assert in
# vulkan.hpp and the VK_HEADER_VERSION in vulkan_core.h can never disagree.
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
    if p.startswith('include/') and (p.endswith('.hpp') or p.endswith('.h')):
        # Keep the path relative to include/ so subdirectories (e.g. vk_video/)
        # are preserved when staged — vulkan_core.h #includes them as
        # "vk_video/vulkan_video_codec_av1std.h", which resolves against the
        # include root (usr/include), NOT usr/include/vulkan/.
        print(p[len('include/'):])
") )
fi
if [ "${#HPP_FILES[@]}" -eq 0 ]; then
  echo "Vulkan-Headers tree API unavailable; using known file list for $VK_TAG"
  HPP_FILES=( vulkan/vulkan.hpp vulkan/vulkan_core.h vulkan/vulkan.h vulkan/vulkan_directfb.h \
    vulkan/vulkan_xcb.h vulkan/vulkan_wayland.h vulkan/vulkan_xlib.h vulkan/vulkan_mir.h \
    vulkan/vulkan_screen.h vulkan/vulkan_fuchsia.h vulkan/vulkan_ggp.h vulkan/vulkan_macos.h \
    vulkan/vulkan_vi.h vulkan/vulkan_win32.h vulkan/vulkan_enums.hpp \
    vulkan/vulkan_extension_inspection.hpp vulkan/vulkan_format_traits.hpp vulkan/vulkan_funcs.hpp \
    vulkan/vulkan_handles.hpp vulkan/vulkan_hash.hpp vulkan/vulkan_hpp_macros.hpp vulkan/vulkan_raii.hpp \
    vulkan/vulkan_shared.hpp vulkan/vulkan_static_assertions.hpp vulkan/vulkan_structs.hpp \
    vulkan/vulkan_to_string.hpp vulkan/vulkan_video.hpp \
    vk_video/vulkan_video_codec_av1std.h vk_video/vulkan_video_codec_av1std_decode.h \
    vk_video/vulkan_video_codec_h264std.h vk_video/vulkan_video_codec_h264std_decode.h \
    vk_video/vulkan_video_codec_h265std.h vk_video/vulkan_video_codec_h265std_decode.h \
    vk_video/vulkan_video_codec_h264std_encode.h vk_video/vulkan_video_codec_h264std_encode_read.h \
    vk_video/vulkan_video_codec_h265std_encode.h vk_video/vulkan_video_codec_h265std_encode_read.h \
    vk_video/vulkan_video_codec_gop_structure.h vk_video/vulkan_video_codec_gop_structure_read.h \
    vk_video/vulkan_video_codec_av1std_encode.h vk_video/vulkan_video_codec_av1std_encode_read.h )
fi

for rel in "${HPP_FILES[@]}"; do
  url="https://raw.githubusercontent.com/KhronosGroup/Vulkan-Headers/$VK_TAG/include/$rel"
  dst="$VK_STAGE/$rel"
  mkdir -p "$(dirname "$dst")"
  curl -fsSL "$url" -o "$dst" \
    || { echo "Failed to download $url" >&2; exit 1; }
done

# --- Verify the staged pair is internally consistent (fail fast) ------------
STAGED_HPP=$(grep -oE 'VK_HEADER_VERSION[[:space:]]*==[[:space:]]*[0-9]+' \
             "$VK_STAGE/vulkan/vulkan.hpp" | grep -oE '[0-9]+' | tail -1)
STAGED_CORE=$(grep -oE '#define[[:space:]]+VK_HEADER_VERSION[[:space:]]+[0-9]+' \
             "$VK_STAGE/vulkan/vulkan_core.h" | grep -oE '[0-9]+' | head -1)
if [ "$STAGED_HPP" != "$STAGED_CORE" ]; then
  echo "ERROR: staged vulkan.hpp ($STAGED_HPP) and vulkan_core.h ($STAGED_CORE) disagree" >&2
  echo "ERROR: Vulkan build would fail with 'Wrong VK_HEADER_VERSION!'" >&2
  exit 1
fi
echo "Staged Vulkan headers -> $VK_STAGE (consistent at VK_HEADER_VERSION $STAGED_CORE)"

# --- Stage SPIRV-Headers (host) ----------------------------------------------
# ggml-vulkan includes spirv/unified1/spirv.hpp directly (for the shader
# reflection/SPIR-V constants used at pipeline setup time).
cp -r /usr/include/spirv "$SYSROOT/usr/include/"
echo "Staged SPIRV-Headers -> $SYSROOT/usr/include/spirv"

echo "Vulkan NDK setup complete. Build with ACFCN_VULKAN=true."
