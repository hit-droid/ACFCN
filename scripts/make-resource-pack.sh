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
# alpine-version 用 minor（如 3.20）：apk 依赖闭包从 minor 分支解析（取最新
# patch 的包）。minirootfs 基线默认自动解析该 minor 下的最新 patch；用
# ROOTFS_VER=3.20.9 可固定到与 app 内置资产（ContainerPaths.ASSET_ROOTFS）
# 完全一致的版本，杜绝 musl 漂移。
#
# PACK_SMOKE_CMD：设置后在干净 rootfs+bundle 上用 qemu-aarch64 跑冒烟命令，
# 多条用 ;; 分隔（如 PACK_SMOKE_CMD='/usr/bin/gcc --version;;/usr/bin/g++ --version'）。
# 每条都是 guest 侧 argv：首 token 必须是 rootfs+bundle 内真实二进制的绝对路径。
# 注意：qemu-user 在本机构建机上无法嵌套 execve（binfmt 未注册时 guest 内
# 再 exec 动态链接程序必 ENOENT/ENOEXEC），故冒烟首 token 须为真实二进制
# 绝对路径，命令自身不能依赖二次 exec（gcc 调 cc1、sh 脚本调 java、经
# /usr/local/bin 包装调 java 均不可——这些只能在真机验证）。
# JDK launcher（路径含 /java-*-openjdk/bin/ 的）会自动预置 LD_LIBRARY_PATH，
# 规避 musl 下 launcher 的 putenv+重exec 自己（见 verify 段注释）。
# $VERIFY 占位符：冒烟命令中写 $VERIFY 会在构建期展开成 verify 树的宿主绝对
# 路径。仅 qemu 限制场景需要（见 6c 段注释：OpenJDK classpath 校验走 statx，
# qemu-user 8.2 不翻译它）；设备侧 proot 无此问题，正常写 guest 路径。

#
# PACK_EXTRAS：非 apk 构件，分号分隔，格式 <url>#<dest>[#<zip内路径模式...]。
# dest 决定语义：
#   - 以 / 结尾 = 目录：把 URL（zip）解压进去。第三字段省略=整包解压；
#     给出则只解压匹配模式（空格分隔，如 'android-14/d8 android-14/lib/d8.jar'）。
#     例：https://.../build-tools_r34-linux.zip#opt/d8/#android-14/d8 android-14/lib/d8.jar
#   - 不以 / 结尾 = 文件：把 URL 原样存成该文件。jar 这类「zip 的内容无用、
#     要的就是文件本身」的构件必须用这种（unzip 一个 jar 只会把 class 撒出来）。
#     例：https://.../kotlin-compiler-2.0.21.jar#opt/kotlinc/lib/kotlin-compiler-2.0.21.jar
#
# PACK_EXPECT：bundle 必须包含的构件断言（glob，分号分隔），解压验证时检查，
# 如 PACK_EXPECT='usr/libexec/gcc/*/*/cc1;usr/bin/as;usr/bin/ld'。
#
# PACK_POST_INSTALL：构建机上的一个 sh 脚本路径；会随 bundle 分发为
# usr/lib/acfcn-pack-post-install.sh，设备侧安装成功后由 linux_pack_install
# 工具在容器内以 /bin/sh 执行一次（用于建 PATH 包装、jar 改名等 rootfs 内
# 收尾动作）。脚本可识别 GUEST_ROOT 变量：构建机 qemu 预演时设为宿主 verify
# 树路径，设备侧为空。
#
# PACK_SKIP：闭包中不参与解压的包名（按 apk 文件名前缀匹配，分号分隔）。
# 用于剔除运行期不需要的硬依赖大体量包，如 PACK_SKIP='openjdk17-jmods'
# （jmods 仅 jlink 需要，javac/kotlinc/d8 均不碰）。
#
# PACK_KEEP=1：保留工作目录（含 verify 树）便于调试，默认退出时清理。
#
set -euo pipefail

OUT_NAME="${1:?用法: make-resource-pack.sh <out-name> <alpine-version> <apk-pkg> [apk-pkg ...]}"
ALPINE_VER="${2:?缺少 alpine 版本，如 3.20}"
shift 2
PKGS=("$@")
[ "${#PKGS[@]}" -gt 0 ] || { echo "至少指定一个 apk 包" >&2; exit 1; }

ARCH="aarch64"
# 默认官方 CDN；国内网络不通时换镜像（本沙箱实测，wget/HTTP1.1 口径）：
#   MIRROR=https://mirror.nju.edu.cn/alpine          44MB gcc 包 2.4s（≈18MB/s，最快）
#   MIRROR=https://mirrors.tuna.tsinghua.edu.cn/alpine  44MB 13s（≈3MB/s）
#   MIRROR=https://mirrors.aliyun.com/alpine         仅 HTTP/2 快（curl 4MB/s）；
#       apk.static/wget 走 HTTP/1.1 被限速到 ~22KB/s，44MB 要半小时——别用
#   （ustc 镜像本沙箱不可达；官方 CDN 曾整体 0B/s 不可达）
MIRROR="${MIRROR:-https://dl-cdn.alpinelinux.org/alpine}"

# 统一下载入口：wget -q 失败时不留任何线索（静默 exit 1，排查困难），
# 这里包一层，失败时明确报出 URL 与镜像建议。
dl() {
  # $1=URL $2=输出文件（缺省 "-" 即 stdout）
  wget -q --tries=3 --timeout=30 --waitretry=2 "$1" -O "${2:--}" \
    || { echo "下载失败: $1（检查网络，或换 MIRROR=https://mirrors.aliyun.com/alpine）" >&2; exit 1; }
}
WORK="$(mktemp -d)"
# PACK_KEEP=1：保留工作目录（含 verify 树）便于调试钩子/冒烟失败
trap '[ -n "${PACK_KEEP:-}" ] || rm -rf "$WORK"' EXIT

echo "==> 工作目录: $WORK"

# ---- 1. 获取 apk-tools-static 与 alpine-keys（均 x86_64, 自包含） ----
APK_STATIC_DIR="$WORK/apk-static"
mkdir -p "$APK_STATIC_DIR"
echo "==> 下载 apk-tools-static"
dl "$MIRROR/v$ALPINE_VER/main/x86_64/APKINDEX.tar.gz" "$WORK/APKINDEX.tar.gz"
mkdir -p "$WORK/apkidx" && tar xzf "$WORK/APKINDEX.tar.gz" -C "$WORK/apkidx"
APK_STATIC_VER="$(grep -A1 '^P:apk-tools-static' "$WORK/apkidx/APKINDEX" | tail -1 | sed 's/^V://')"
dl "$MIRROR/v$ALPINE_VER/main/x86_64/apk-tools-static-$APK_STATIC_VER.apk" "$WORK/apk-tools-static.apk"
tar xzf "$WORK/apk-tools-static.apk" -C "$APK_STATIC_DIR"
APK="$APK_STATIC_DIR/sbin/apk.static"

echo "==> 下载 alpine-keys"
KEYS_VER="$(grep -A1 '^P:alpine-keys' "$WORK/apkidx/APKINDEX" | tail -1 | sed 's/^V://')"
dl "$MIRROR/v$ALPINE_VER/main/x86_64/alpine-keys-$KEYS_VER.apk" "$WORK/alpine-keys.apk"
mkdir -p "$WORK/keys" && tar xzf "$WORK/alpine-keys.apk" -C "$WORK/keys"
# apk 查找密钥默认走 /etc/apk/keys 与 usr/share/apk/keys/<arch>，统一收拢到本地 keys 目录
KEYS_DIR="$WORK/etc-apk-keys"
mkdir -p "$KEYS_DIR"
cp "$WORK/keys/etc/apk/keys/"*.pub "$KEYS_DIR/" 2>/dev/null || true
cp "$WORK/keys/usr/share/apk/keys/$ARCH/"*.pub "$KEYS_DIR/" 2>/dev/null || true

# ---- 2. 准备 apk 数据库根（不污染宿主 /etc/apk） ----
# apk-tools 2.14 起数据库根必须显式含 state 文件，否则报
# "Unable to read database state: No such file or directory"。
DBROOT="$WORK/apkdb"
mkdir -p "$DBROOT/etc/apk" "$DBROOT/lib/apk/db" "$DBROOT/var/cache/apk"
: > "$DBROOT/etc/apk/world"
: > "$DBROOT/lib/apk/db/installed"
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
# minirootfs 基线：默认取 minor 下最新 patch；ROOTFS_VER 可显式固定
#（app 内置 3.20.9，见 app/src/main/java/.../container/ContainerPaths.kt）。
if [ -z "${ROOTFS_VER:-}" ]; then
  ROOTFS_VER="$(dl "$MIRROR/v$ALPINE_VER/releases/$ARCH/" \
    | grep -oP "alpine-minirootfs-\K$ALPINE_VER\.\d+(?=-$ARCH\.tar\.gz)" \
    | sort -V | tail -1)"
fi
[ -n "$ROOTFS_VER" ] || { echo "无法解析 minirootfs patch 版本（ROOTFS_VER 留空且索引解析失败）" >&2; exit 1; }
MINI="alpine-minirootfs-$ROOTFS_VER-$ARCH.tar.gz"
echo "==> minirootfs 基线: $MINI"
if [ ! -f "$WORK/$MINI" ]; then
  dl "$MIRROR/v$ALPINE_VER/releases/$ARCH/$MINI" "$WORK/$MINI"
fi
ROOT_BASE="$WORK/rootfs-base"; ROOT_FULL="$WORK/rootfs-full"
mkdir -p "$ROOT_BASE" "$ROOT_FULL"
tar xzf "$WORK/$MINI" -C "$ROOT_BASE"
for f in "$FETCH_DIR"/*.apk; do
  # PACK_SKIP：按包名前缀跳过解压（如 openjdk17-jmods 仅 jlink 需要）
  base="$(basename "$f")"
  skipped=0
  IFS=';' read -ra SKIPS <<< "${PACK_SKIP:-}"
  for g in "${SKIPS[@]}"; do
    [ -n "$g" ] || continue
    # shellcheck disable=SC2254
    case "$base" in $g-*) skipped=1; break ;; esac
  done
  if [ "$skipped" = 1 ]; then
    echo "    跳过解压（PACK_SKIP）: $base"
    continue
  fi
  # apk 包带 APK-TOOLS.checksum.SHA1 pax 扩展头，GNU/BSD tar 都会警告——预期内。
  tar xzf "$f" -C "$ROOT_FULL" 2>/dev/null || true
done

# ---- 4b. 非 apk 构件（kotlinc / d8 等平台无关 zip）----
if [ -n "${PACK_EXTRAS:-}" ]; then
  IFS=';' read -ra EXTRAS <<< "$PACK_EXTRAS"
  for spec in "${EXTRAS[@]}"; do
    [ -n "$spec" ] || continue
    url="${spec%%#*}"
    mid="${spec#*#}"
    dest="${mid%%#*}"
    prefix="${mid#*#}"
    if [ "$url" = "$spec" ] || [ -z "$dest" ]; then
      echo "PACK_EXTRAS 条目格式错误: $spec（应为 <url>#<dest>[#<zip内模式...>]）" >&2; exit 1
    fi
    case "$dest" in
      /*|..*|*/..*|*/.) echo "PACK_EXTRAS dest 必须是 rootfs 内相对路径: $dest" >&2; exit 1 ;;
    esac
    zip="$WORK/extras-$(basename "$url")"
    echo "==> 下载额外构件: $url"
    dl "$url" "$zip"
    case "$dest" in
      */)
        # 目录：解压 zip（第三字段省略=整包；给出=只取匹配模式）
        mkdir -p "$ROOT_FULL/$dest"
        if [ "$prefix" != "$dest" ]; then
          # shellcheck disable=SC2086
          unzip -q -o "$zip" $prefix -d "$ROOT_FULL/$dest" \
            || { echo "zip 内找不到模式: $prefix（$url）" >&2; exit 1; }
          echo "    选择性解压: $prefix -> $dest"
        else
          unzip -q -o "$zip" -d "$ROOT_FULL/$dest"
          echo "    整包解压 -> $dest"
        fi
        ;;
      *)
        # 文件：原样落盘（jar 的 zip 内容是 class 文件，解压即毁，必须整文件下载）
        mkdir -p "$ROOT_FULL/$(dirname "$dest")"
        cp "$zip" "$ROOT_FULL/$dest"
        echo "    原样下载 -> $dest"
        ;;
    esac
  done
fi

# ---- 4c. 安装后钩子（随 bundle 分发，设备侧在容器内执行一次）----
if [ -n "${PACK_POST_INSTALL:-}" ]; then
  [ -f "$PACK_POST_INSTALL" ] || { echo "PACK_POST_INSTALL 文件不存在: $PACK_POST_INSTALL" >&2; exit 1; }
  mkdir -p "$ROOT_FULL/usr/lib"
  cp "$PACK_POST_INSTALL" "$ROOT_FULL/usr/lib/acfcn-pack-post-install.sh"
  chmod 0755 "$ROOT_FULL/usr/lib/acfcn-pack-post-install.sh"
  echo "==> 已装入 post-install 钩子: usr/lib/acfcn-pack-post-install.sh"
fi

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
        elif os.path.islink(bp): copy = True   # 基线是 busybox applet 占位 symlink，真包带来规则文件——必须覆盖
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

# ---- 6. 验证（干净 rootfs + bundle，用 qemu-user 跑冒烟命令） ----
if command -v qemu-aarch64 >/dev/null 2>&1; then
  echo "==> qemu 验证 bundle"
  VERIFY="$WORK/verify"; rm -rf "$VERIFY"; mkdir -p "$VERIFY"
  tar xzf "$WORK/$MINI" -C "$VERIFY"
  tar xzf "$WORK/$BUNDLE" -C "$VERIFY"
  echo "bundle 解压验证: $(find "$VERIFY" -type f | wc -l) 文件"
  if [ -n "${PACK_EXPECT:-}" ]; then
    IFS=';' read -ra EXPECTS <<< "$PACK_EXPECT"
    for pat in "${EXPECTS[@]}"; do
      [ -n "$pat" ] || continue
      # shellcheck disable=SC2086
      if [ -z "$(ls -d $VERIFY/$pat 2>/dev/null)" ]; then
        echo "bundle 缺少预期构件: $pat" >&2; exit 1
      fi
    done
    echo "预期构件检查通过: $PACK_EXPECT"
  fi
  # ---- 6b. 预演设备侧 post-install 钩子 ----
  # 钩子在设备侧由 linux_pack_install 以 /bin/sh（busybox ash）在容器内执行；
  # 构建机上用 qemu+busybox 预演同一份脚本，GUEST_ROOT 指向宿主 verify 树：
  # 钩子里的外部命令（mv/chmod 等经 ash 嵌套 exec 落到宿主同名工具，参数是
  # 宿主路径故作用对象正确）与重定向（进程内 open，走 -L 翻译）都能正确作用
  # 于 verify 树——qemu -L 对已存在的宿主路径直通。构建期即可抓住钩子的
  # 路径笔误/语法错误。
  HOOK="$VERIFY/usr/lib/acfcn-pack-post-install.sh"
  if [ -f "$HOOK" ]; then
    echo "==> qemu 预演 post-install 钩子"
    ( cd "$VERIFY" && GUEST_ROOT="$VERIFY" qemu-aarch64 -L "$VERIFY" "$VERIFY/bin/busybox" sh "$HOOK" )
  fi

  # ---- 6c. qemu 冒烟（;; 分隔多条） ----
  if [ -n "${PACK_SMOKE_CMD:-}" ]; then
    # 每条都是 guest 侧 argv（eval 拆分，保留引号语义）：首 token 必须是
    # rootfs+bundle 内真实二进制的绝对路径——不能是 /bin/sh 这类绝对 symlink
    # （qemu -L 不翻译顶层 execve 路径），也不要指望 guest 内嵌套 exec 动态
    # 链接程序（execve 不走 -L 翻译，只会 ENOENT；所以包装脚本、gcc 调 cc1、
    # sh 脚本调 java 这类只能在真机验证）。
    # guest 侧 open/dlopen 的绝对路径由 -L 自动翻译（如 python import ssl
    # 的 dlopen("/usr/lib/libssl.so.3") 可正常命中）。
    IFS=';' read -ra SMOKES <<< "$PACK_SMOKE_CMD"
    for smoke in "${SMOKES[@]}"; do
      [ -n "$smoke" ] || continue
      # $VERIFY 构建期占位符：展开成 verify 树的宿主绝对路径。用于绕开
      # qemu-user 8.2 的一个限制——其 statx(2) case 直接拿 guest 原始路径
      # 调宿主 sys_statx()，不做 -L 前缀翻译（见 util/path.c 的 path() 只
      # 被 openat/fstatat/readlinkat 等 case 调用；statx 仅 ENOSYS 回退
      # 分支才翻译）。OpenJDK 17 在校验 classpath jar 时会调 statx（ strace
      # 实证：newfstatat 成功紧接着 statx 同路径 ENOENT），guest 路径必被
      # 判定为不存在 → ClassNotFound。宿主前缀路径无需翻译故一切正常。
      # 设备侧走 proot（ptrace 全 syscall 路径翻译，含 statx）无此限制，
      # wrapper 的 guest 路径通配 classpath 不受影响。
      smoke="${smoke//\$VERIFY/$VERIFY}"
      eval "set -- $smoke"
      case "$1" in
        /*) ;;
        *) echo "PACK_SMOKE_CMD 每条首 token 必须是绝对路径（如 /usr/bin/python3）" >&2; exit 1 ;;
      esac
      # JDK launcher 特判：musl 下 java_md.c 的 RequiresSetenv() 恒返回真，
      # launcher 会 putenv(LD_LIBRARY_PATH) 后 execve 重exec 自己，qemu 无
      # binfmt 时必 ENOEXEC。源码里有早退分支：LD_LIBRARY_PATH 已以期望前缀
      # （<jvmdir>:<jre>/lib:<jre>/../lib）开头则直接 return 不 re-exec。
      # 这里预置该前缀（用宿主前缀路径——qemu -L 对已存在宿主路径直通）。
      lp=""
      case "$1" in
        */java-*-openjdk/bin/*)
          jvmhome="${1%/bin/*}"
          lp="$VERIFY$jvmhome/lib/server:$VERIFY$jvmhome/lib:$VERIFY$jvmhome/../lib"
          ;;
      esac
      echo "==> qemu 冒烟: $smoke"
      if [ -n "$lp" ]; then
        ( cd "$VERIFY" && LD_LIBRARY_PATH="$lp${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" qemu-aarch64 -L "$VERIFY" "$VERIFY$1" "${@:2}" )
      else
        ( cd "$VERIFY" && qemu-aarch64 -L "$VERIFY" "$VERIFY$1" "${@:2}" )
      fi
    done
  fi
else
  echo "（无 qemu-aarch64，跳过本地验证；请在真机/arm 构建机验证）"
fi

# ---- 7. 落盘到仓库 scripts 旁（调用方传入绝对路径则直出） ----
OUT_DIR="${OUT_DIR:-$PWD}"
cp "$WORK/$BUNDLE" "$OUT_DIR/"
cp "$WORK/$BUNDLE.sha256" "$OUT_DIR/"
echo "==> 产出: $OUT_DIR/$BUNDLE ($(du -h "$OUT_DIR/$BUNDLE" | cut -f1)), sha256=$SHA"
