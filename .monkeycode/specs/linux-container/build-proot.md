# proot NDK 交叉编译复现指南

`build-proot.sh` 一键复现 `app/src/main/jniLibs/arm64-v8a/` 下的两个产物。在干净 Ubuntu 22.04 + NDK 27.0.12077973 环境验证通过。

## 前置条件

```bash
# 1. NDK（本仓库构建用的同一套）
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.0.12077973

# 2. gawk —— 必须。系统默认 mawk 没有 strtonum，proot 的 loader-info.awk 会直接失败
sudo apt-get install -y gawk
sudo update-alternatives --set awk /usr/bin/gawk

# 3. 源码准备（网络受限时的替代源见下）
#    - talloc 2.4.2: https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz
#    - proot: Termux 分支源码（github.com/termux/proot），需带 ashmem_memfd.c 的 <string.h> 补丁
```

## 运行

```bash
bash build-proot.sh
# 产物：/tmp/proot-out/libproot.so + libproot-out/libproot-loader.so
# 校验：file 应显示 "ELF 64-bit LSB pie executable, ARM aarch64, interpreter /system/bin/linker64"
```

脚本假设源码分别在 `/tmp/talloc` 和 `/tmp/proot-build/src`（ Termux proot 源码树）。
talloc 的 `samba-team/talloc` GitHub 仓库已 404，请从 samba.org FTP 取官方 tarball。

## 三个 NDK 踩坑（脚本内已处理，记录备查）

1. **mawk vs gawk**：proot 构建系统 `readelf -s loader/loader | awk -f loader/loader-info.awk`
   用了 gawk 专有的 `strtonum()`。mawk 下报 `function strtonum never defined`，
   `make loader/loader-info.c` 失败。装 gawk 并切默认 awk 即可。

2. **llvm-objdump 架构解析**：GNUmakefile 的 `OBJIFY` 宏用
   `objdump -f cli/cli.o | grep architecture | awk '{print $2}'` 推导 `--binary-architecture`。
   llvm-objdump 输出 `architecture: UNKNOWN!`（ELF 头里没有单独架构字符串），
   GNU objdump 才输出 `architecture: aarch64`。结果是 `objcopy: architecture UNKNOWN! unknown`。
   脚本直接 patch GNUmakefile 硬编码 `elf64-littleaarch64` / `aarch64`。

3. **工具链变量**：GNUmakefile 用 `OBJCOPY ?= $(CROSS_COMPILE)objcopy` 等 `?=` 变量。
   只 export `CC/AR/LD` 不够——`OBJCOPY/OBJDUMP` 会落到系统 GNU binutils，
   然后踩坑 2 的错误格式。脚本显式 export 全套 llvm-* 工具。

## 产物说明

| 文件 | 大小 | 形态 | 去向 |
| --- | --- | --- | --- |
| libproot.so | ~285KB | PIE，动态链接（仅 libc/libdl，bionic 自带） | jniLibs/arm64-v8a/，经 ProcessBuilder exec |
| libproot-loader.so | ~5.3KB | 静态 ELF | jniLibs/arm64-v8a/，经 PROOT_LOADER 环境变量指定 |

命名必须是 `lib*.so`：Android 10+ 只允许 exec `nativeLibraryDir`（APK 提取目录）
下的文件，应用私有可写目录 exec 会被 SELinux/W^X 拒绝。assets 里放二进制再拷贝到
filesDir 执行的方案在新系统上不可行。

## 验证状态

- x86 构建机：`file`/`readelf` 结构校验 + qemu-aarch64 冒烟（ELF 头部正确，因缺
  Android linker64 无法完整启动——环境限制，非二进制问题）。
- 真机：待设备验证（`linux_exec` 首跑 + 5 项集成清单见 design.md §8）。
