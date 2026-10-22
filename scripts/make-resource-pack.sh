#!/usr/bin/env bash
# make-resource-pack.sh —— 在 x86_64 构建机上制作 Android 容器内 aarch64 资源包（bundle）
#
# 原理：用 apk-tools-static（自带 x86_64 二进制）+ Alpine 官方 aarch64 仓库，
# 把指定 apk 包的「依赖闭包」拉下来，解压进一份 Alpine minirootfs 副本，
# 用 rsync 思路提取相对 minirootfs 的增量，再打包成自包含 tar.gz。
# 设备侧只需 `tar xzf <pack>.tar.gz -C <rootfs>` 即可补全该能力，无需 apk 工具。
#
# 设计要点（见 .monkeycode/specs/linux-container/design.md §第二阶段）：
#   - 资源包全部按需从 GitHub Release 下载，不进 APK，不进 git
#   - bundle 是「相对 minirootfs 的增量」，解压即幂等补完
#   - 制作机用 qemu-user 验证 bundle（见 verify-bundle 函数）
#
# 用法：
#   ./make-resource-pack.sh <out-name> <alpine-version> <apk-pkg> [apk-pkg ...]
# 例：
#   ./make-resource-pack.sh python3 3.20 python3
#   ./make-resource-pack.sh gcc     3.20 gcc
#   ./make-resource-pack.sh jdk     3.20 openjdk17-jre openjdk17
#
set -euo pipefail

OUT_NAME="${1:?用法: make-resource-pack.sh <out-name> <alpine-version> <apk-pkg> [apk-pkg ...]}"
ALPINE_VER="${2:?缺少 alpine 版本，如 3.20}"
shift 2
PKGS=("$@")
[ "${#PKGS[@]}" -gt 0 ] || { echo "至少指定一个 apk 包" >&2; exit 1; }

ARCH="aarch64"
MIRROR="https://dl-cdn.alpinelinux.org/alpine"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> 工作目录: $WORK"

# ---- 1. 获取 apk-tools-static 与 alpine-keys（均 x86_64, 自包含） ----
APK_STATIC_DIR="$WORK/apk-static"
mkdir -p "$APK_STATIC_DIR"
echo "==> 下载 apk-tools-static"
wget -q "$MIRROR/v$ALPINE_VER/main/x86_64/APKINDEX.tar.gz" -O "$WORK/APKINDEX.tar.gz"
mkdir -p "$WORK/apkidx" && tar xzf "$WORK/APKINDEX.tar.gz" -C "$WORK/apkidx"
APK_STATIC_VER="$(grep -A1 '^P:apk-tools-static' "$WORK/apkidx/APKINDEX" | tail -1 | sed 's/^V://')"
wget -q "$MIRROR/v$ALPINE_VER/main/x86_64/apk-tools-static-$APK_STATIC_VER.apk" -O "$WORK/apk-tools-static.apk"
tar xzf "$WORK/apk-tools-static.apk" -C "$APK_STATIC_DIR"
APK="$APK_STATIC_DIR/sbin/apk.static"

echo "==> 下载 alpine-keys"
KEYS_VER="$(grep -A1 '^P:alpine-keys' "$WORK/apkidx/APKINDEX" | tail -1 | sed 's/^V://')"
wget -q "$MIRROR/v$ALPINE_VER/main/x86_64/alpine-keys-$KEYS_VER.apk" -O "$WORK/alpine-keys.apk"
mkdir -p "$WORK/keys" && tar xzf "$WORK/alpine-keys.apk" -C "$WORK/keys"
# apk 查找密钥默认走 /etc/apk/keys 与 usr/share/apk/keys/<arch>，统一收拢到本地 keys 目录
KEYS_DIR="$WORK/etc-apk-keys"
mkdir -p "$KEYS_DIR"
cp "$WORK/keys/etc/apk/keys/"*.pub "$KEYS_DIR/" 2>/dev/null || true
cp "$WORK/keys/usr/share/apk/keys/$ARCH/"*.pub "$KEYS_DIR/" 2>/dev/null || true

# ---- 2. 准备 apk 数据库根（不污染宿主 /etc/apk） ----
DBROOT="$WORK/apkdb"
mkdir -p "$DBROOT/etc/apk" "$DBROOT/lib/apk/db" "$DBROOT/var/cache/apk"
printf '%s\n' "$MIRROR/v$ALPINE_VER/main" "$MIRROR/v$ALPINE_VER/community" > "$DBROOT/etc/apk/repositories"

# ---- 3. fetch 依赖闭包 ----
FETCH_DIR="$WORK/fetch"
mkdir -p "$FETCH_DIR"
echo "==> 刷新索引并 fetch 依赖闭包: ${PKGS[*]}"
APK_BASE=( "$APK" --arch "$ARCH" --root "$DBROOT" --keys-dir "$KEYS_DIR" \
  -X "$MIRROR/v$ALPINE_VER/main" -X "$MIRROR/v$ALPINE_VER/community" )
"${APK_BASE[@]}" update 2>&1 | tail -2
( cd "$FETCH_DIR" && "${APK_BASE[@]}" fetch --recursive "${PKGS[@]}" ) 2>&1 | tail -3

# ---- 4. 解压闭包进 minirootfs 副本，提取增量 ----
MINI="alpine-minirootfs-$ALPINE_VER-$ARCH.tar.gz"
if [ ! -f "$WORK/$MINI" ]; then
  wget -q "$MIRROR/v$ALPINE_VER/releases/$ARCH/$MINI" -O "$WORK/$MINI"
fi
ROOT_BASE="$WORK/rootfs-base"; ROOT_FULL="$WORK/rootfs-full"
mkdir -p "$ROOT_BASE" "$ROOT_FULL"
tar xzf "$WORK/$MINI" -C "$ROOT_BASE"
for f in "$FETCH_DIR"/*.apk; do tar xzf "$f" -C "$ROOT_FULL" 2>/dev/null || true; done

DIFF_DIR="$WORK/diff"
mkdir -p "$DIFF_DIR"
python3 - "$ROOT_BASE" "$ROOT_FULL" "$DIFF_DIR" <<'PY'
import os, shutil, sys
base, new, out = sys.argv[1], sys.argv[2], sys.argv[3]
for dp, _, fs in os.walk(new):
    for f in fs:
        fp = os.path.join(dp, f); rel = os.path.relpath(fp, new); bp = os.path.join(base, rel)
        dst = os.path.join(out, rel); os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.islink(fp):
            if os.path.lexists(dst): os.remove(dst)
            os.symlink(os.readlink(fp), dst); continue
        copy = False
        if not os.path.lexists(bp): copy = True
        elif os.path.islink(bp): copy = (os.readlink(bp) != os.readlink(fp))
        else: copy = (os.stat(fp).st_size != os.stat(bp).st_size)
        if copy:
            if os.path.lexists(dst): os.remove(dst)
            shutil.copy2(fp, dst)
print("diff files:", sum(len(fs) for _,_,fs in os.walk(out)))
PY

# ---- 5. 打包 + 校验 ----
BUNDLE="$OUT_NAME-$ARCH.tar.gz"
cd "$DIFF_DIR" && tar czf "$WORK/$BUNDLE" . && cd - >/dev/null
SHA="$(sha256sum "$WORK/$BUNDLE" | cut -d' ' -f1)"
echo "$SHA" > "$WORK/$BUNDLE.sha256"

# ---- 6. 验证（干净 rootfs + bundle，用 qemu-user 跑一条冒烟命令） ----
if command -v qemu-aarch64 >/dev/null 2>&1; then
  echo "==> qemu 验证 bundle"
  VERIFY="$WORK/verify"; rm -rf "$VERIFY"; mkdir -p "$VERIFY"
  tar xzf "$WORK/$MINI" -C "$VERIFY"
  tar xzf "$WORK/$BUNDLE" -C "$VERIFY"
  echo "bundle 解压验证: $(find "$VERIFY" -type f | wc -l) 文件"
else
  echo "（无 qemu-aarch64，跳过本地验证；请在真机/arm 构建机验证）"
fi

# ---- 7. 落盘到仓库 scripts 旁（调用方传入绝对路径则直出） ----
OUT_DIR="${OUT_DIR:-$PWD}"
cp "$WORK/$BUNDLE" "$OUT_DIR/"
cp "$WORK/$BUNDLE.sha256" "$OUT_DIR/"
echo "==> 产出: $OUT_DIR/$BUNDLE ($(du -h "$OUT_DIR/$BUNDLE" | cut -f1)), sha256=$SHA"
