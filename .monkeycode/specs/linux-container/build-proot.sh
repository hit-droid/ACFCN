#!/bin/bash
set -e
NDK=/opt/android-sdk/ndk/27.0.12077973
API=26
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export CC="$TOOLCHAIN/bin/aarch64-linux-android$API-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android$API-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export LD="$TOOLCHAIN/bin/ld.lld"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
export READELF="$TOOLCHAIN/bin/llvm-readelf"
export CFLAGS="-O2 -fPIC -fPIE"
export LDFLAGS="-pie"
OUT=/tmp/proot-out
rm -rf "$OUT" && mkdir -p "$OUT"

echo "=== [1/3] talloc static ==="
cd /tmp/talloc
rm -rf /tmp/talloc-build && mkdir -p /tmp/talloc-build
cat > /tmp/talloc-cross-answers.txt << 'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "aarch64"
Checking uname release type: "5.4.0"
Checking uname version type: "#1 SMP PREEMPT"
Checking getconf LFS_CFLAGS: NO
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: NO
Checking for HAVE_SECURE_MKSTEMP: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_AIX: NO
Checking for HAVE_IFACE_IFCONF: NO
Checking for HAVE_IFACE_IFREQ: NO
Checking for HAVE_LITTLE_ENDIAN - runtime: OK
Checking for HAVE_BIG_ENDIAN - runtime: NO
rpath library support: OK
-Wl,--version-script support: FAIL
EOF
./configure --prefix=/tmp/talloc-build --cross-compile --cross-answers=/tmp/talloc-cross-answers.txt --disable-python --without-gettext > /tmp/talloc-configure.log 2>&1 || { tail -30 /tmp/talloc-configure.log; exit 1; }
make -j$(nproc) > /tmp/talloc-make.log 2>&1 || { tail -30 /tmp/talloc-make.log; exit 1; }
make install > /tmp/talloc-install.log 2>&1 || { tail -20 /tmp/talloc-install.log; exit 1; }
ls /tmp/talloc/bin/default/talloc.c.*.o > /tmp/talloc-objs.txt
"$AR" rcs /tmp/talloc-build/lib/libtalloc.a $(cat /tmp/talloc-objs.txt)
echo "talloc.a: $(ls -la /tmp/talloc-build/lib/libtalloc.a | awk '{print $5}') bytes"

echo "=== [1.5/3] patch GNUmakefile for llvm tools ==="
cd /tmp/proot-build/src
# 两个 NDK 适配补丁（详见 build-proot.md）：
# 1) OBJIFY 宏依赖 objdump 解析架构，llvm-objdump 输出 "architecture: UNKNOWN!" —— 硬编码 aarch64
# 2) gawk 的 strtonum（loader-info.awk 需要）——由本脚本运行环境保证（apt install gawk）
python3 - <<'PATCH_EOF'
p = 'GNUmakefile'
s = open(p).read()
old = """		--output-target=`env LANG=C $(OBJDUMP) -f cli/cli.o |				\\
			grep 'file format' | awk '{print $$4}'`				\\
		--binary-architecture `env LANG=C $(OBJDUMP) -f cli/cli.o |		\\
				grep architecture | cut -f 1 -d , | awk '{print $$2}'`	\\"""
new = """		--output-target=elf64-littleaarch64					\\
		--binary-architecture=aarch64						\\"""
if old in s:
    open(p,'w').write(s.replace(old, new))
    print("OBJIFY patched")
else:
    print("OBJIFY already patched or pattern not found — skipping")
PATCH_EOF

echo "=== [2/3] proot loader ==="
cd /tmp/proot-build/src
make -B -j$(nproc) CC="$CC" CFLAGS="$CFLAGS" V=1 loader/loader > /tmp/proot-loader.log 2>&1 || { tail -30 /tmp/proot-loader.log; exit 1; }
cp loader/loader "$OUT/libproot-loader.so"

echo "=== [3/3] proot ==="
make clean > /dev/null 2>&1 || true
export CFLAGS="$CFLAGS -I/tmp/talloc-build/include"
export LDFLAGS="$LDFLAGS /tmp/talloc-build/lib/libtalloc.a"
make -j$(nproc) CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" V=1 > /tmp/proot-make.log 2>&1 || { tail -40 /tmp/proot-make.log; exit 1; }
cp proot "$OUT/libproot.so"
"$STRIP" "$OUT/libproot.so" "$OUT/libproot-loader.so"
echo "=== 产物 ==="
ls -la "$OUT/"
file "$OUT/libproot.so" | cut -d: -f2
file "$OUT/libproot-loader.so" | cut -d: -f2
