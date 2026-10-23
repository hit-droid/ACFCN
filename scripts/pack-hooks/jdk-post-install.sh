#!/bin/sh
# jdk 资源包的安装后钩子。制作时由 make-resource-pack.sh 以
# PACK_POST_INSTALL=本文件 装进 bundle（usr/lib/acfcn-pack-post-install.sh），
# 设备侧由 linux_pack_install 工具在容器内以 /bin/sh 执行一次
# （best-effort：失败只记 note，不回滚已安装文件）。
#
# 背景：
#   - Alpine 的 openjdk 只把二进制装在 /usr/lib/jvm/java-17-openjdk/bin，
#     不在容器默认 PATH 上；
#   - kotlinc 用 Maven 的 jar 手工拼装到 /opt/kotlinc/lib（PACK_EXTRAS），
#     jar 名带版本号（kotlin-stdlib-2.0.21.jar）；
#   - d8 是 build-tools zip 选择性解压到 /opt/d8/android-<api>/。
# 这里统一在 /usr/local/bin 建一层包装脚本——用 exec 真实路径而不是符号链接，
# 避免 HotSpot 通过 /proc/self/exe 反推 JAVA_HOME 时受符号链接干扰。
#
# GUEST_ROOT：构建机 qemu 预演时由 make-resource-pack.sh 设为宿主 verify 树
# 路径（qemu -L 对已存在的宿主路径直通，故外部命令与重定向都能正确作用于
# verify 树）；设备侧不设（为空），路径即容器内绝对路径。
#
# 幂等：重复安装重复执行结果一致（覆盖写；改名只在目标缺失时做）。
set -eu

R="${GUEST_ROOT:-}"
JVM="$R/usr/lib/jvm/java-17-openjdk"
KLIB="$R/opt/kotlinc/lib"
BIN="$R/usr/local/bin"

# ---- 1. kotlin jar 改名成官方 kotlinc 布局的不带版本名 ----
# kotlin-home 探测（PathUtil.KotlinPathsFromHomeDirectory）按固定名
# lib/kotlin-stdlib.jar 等找 jar；Maven 下载来的是 kotlin-stdlib-2.0.21.jar，
# 不改名则 kotlinc 每次运行都报 "unable to find kotlin-stdlib.jar in the
# Kotlin home directory" 警告（-cp 通配符仍能加载，仅警告，但很烦）。
if [ -d "$KLIB" ]; then
    for name in kotlin-stdlib kotlin-reflect kotlin-script-runtime kotlin-compiler; do
        for f in "$KLIB/$name"-*.jar; do
            [ -e "$f" ] || continue
            [ -e "$KLIB/$name.jar" ] || mv "$f" "$KLIB/$name.jar"
        done
    done
fi

# ---- 2. JVM 工具 PATH 包装（bin 下全部，含 javac/jar/jshell/keytool 等）----
mkdir -p "$BIN"
if [ -d "$JVM/bin" ]; then
    for f in "$JVM"/bin/*; do
        [ -f "$f" ] || continue
        b="${f##*/}"
        printf '#!/bin/sh\nexec %s/bin/%s "$@"\n' "$JVM" "$b" > "$BIN/$b"
        chmod 0755 "$BIN/$b"
    done
fi

# ---- 3. kotlinc 包装 ----
# -Dkotlin.home 让编译器按官方布局找到 lib/kotlin-stdlib.jar（配合第 1 步
# 改名，消除上述警告并自动把 stdlib 加进编译 classpath）。
if [ -d "$KLIB" ]; then
    printf '#!/bin/sh\nexec %s/bin/java -Dkotlin.home=%s -cp "%s/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"\n' \
        "$JVM" "$R/opt/kotlinc" "$KLIB" > "$BIN/kotlinc"
    chmod 0755 "$BIN/kotlinc"
fi

# ---- 4. d8 包装 ----
# d8 上游脚本（#!/bin/bash，负责 -J 参数剥离与 -Xmx2G 默认值；busybox ash
# 不支持 declare -a，故 jdk 包必须带 bash）。脚本用 $0 反推 lib/ 目录，
# 包装成 exec 真实路径最稳。取第一个 android-<api> 目录。
d8dir=""
for d in "$R"/opt/d8/android-*; do
    [ -x "$d/d8" ] || continue
    d8dir="$d"
    break
done
if [ -n "$d8dir" ]; then
    printf '#!/bin/sh\nexec %s/d8 "$@"\n' "$d8dir" > "$BIN/d8"
    chmod 0755 "$BIN/d8"
fi

exit 0
