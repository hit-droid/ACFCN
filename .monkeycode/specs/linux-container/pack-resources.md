# 资源包（Resource Pack）制作 — 踩坑与复现（pack-resources）

> 配套 `design.md` §10 | 负责：workbuddy | 2026-10-23
> 在 x86_64 构建机上制作 Android 容器内 aarch64 资源包（bundle）。python3 / gcc / jdk 三包已全部
> 落地并上传 GitHub Release `container-packs`，设备侧 PackManager 按需下载、sha256 强校验、解压即用。

## 0. 一句话原理

app 的 Linux 容器是 Alpine rootfs，`apk` 包是「ustar tar.gz + `.PKGINFO`」。`apk fetch --recursive`
只下载并解压闭包，**不执行**被装包的 aarch64 二进制，因此 x86 机就能完整制作；最终 bundle 用
`qemu-user` 在 x86 上模拟 arm64 跑冒烟命令验证即可。

**增量 diff**：把闭包全解压进 minirootfs 副本，与原始 minirootfs 做 diff 取增量——bundle 只含
新增/变更文件（python3 闭包 21 包 18MB → 增量 52MB → gzip 17MB）。

## 1. 制作机环境

- x86_64 Linux（本沙箱 Ubuntu 22.04）。
- **不需要** proot（ptrace 模拟在 qemu 下不可靠）。
- 需要 `qemu-user`（`apt-get install qemu-user`；提供 `qemu-aarch64`，binfmt 未激活，故显式 `-L <sysroot>` 调用）。
- 需要 Python3（diff 提取脚本用）。
- 全程离线工具链自包含：脚本自动下载 `apk-tools-static`(x86_64) + `alpine-keys`(x86_64，含 aarch64 公钥)。

## 2. 已落地的三个包

| 包 | 构件 | 大小 | sha256（前 8 位） | 冒烟验证 |
|---|---|---|---|---|
| python3 | python3 3.12 + ssl/ctypes/json/sqlite3 | 17,018,667 B | `765a2394` | import ssl,ctypes,json,sqlite3 ✅ |
| gcc | gcc 13 + g++ + binutils + make | 76,382,055 B | `90961400` | gcc/g++/as/ld/make --version ✅ + cc1→as→ld→运行深验 ✅ |
| jdk | OpenJDK 17 + kotlinc 2.0.21 + d8 8.2.2 | 145,847,099 B | `2ba0c35e` | java/javac/kotlinc/D8 ✅ |

完整 sha256 见 `app/.../container/ResourcePack.kt`（fail-closed pin，下载不符即拒）。

### 2.0 脚本环境变量全景（make-resource-pack.sh）

| 变量 | 作用 |
|---|---|
| `MIRROR` | Alpine 镜像。**强烈建议 `https://mirror.nju.edu.cn/alpine`**（见 §2.7 实测） |
| `ROOTFS_VER` | minirootfs 基线 patch。**必须固定 `3.20.9`**，与 app 内置资产（`ContainerPaths.ASSET_ROOTFS`）一致，杜绝 musl 漂移 |
| `OUT_DIR` | 产物输出目录（缺省脚本旁） |
| `PACK_SMOKE_CMD` | `;;` 分隔多条 guest 侧冒烟命令（见 §2.6） |
| `PACK_EXTRAS` | 非 apk 构件（jar/d8），双模式（见 §2.5） |
| `PACK_EXPECT` | bundle 必须包含的构件断言（glob，`;` 分隔） |
| `PACK_POST_INSTALL` | 设备侧安装后钩子脚本路径（随 bundle 分发为 `usr/lib/acfcn-pack-post-install.sh`，见 §2.10） |
| `PACK_SKIP` | 闭包中跳过解压的包名（如 `openjdk17-jmods` 仅 jlink 需要） |
| `PACK_KEEP` | 保留工作目录（含 verify 树）便于调试 |

## 2. 步骤与踩坑

### 2.1 apk-tools-static 版本号不能猜
- `dl-cdn.alpinelinux.org/.../main/x86_64/apk-tools-static-<ver>.apk` 的 `<ver>` 会随小版本变（实测 `2.14.0-r0` 已 404，实际 `2.14.4-r1`）。
- 先 `wget APKINDEX.tar.gz` → 解压 → `grep -A1 '^P:apk-tools-static' APKINDEX` 取 `V:` 行。

### 2.2 签名密钥必须单独给，且要 aarch64 那套
- apk-tools-static 解压后**不带** `/etc/apk/keys`。直接 `update` 报 `UNTRUSTED signature`。
- 下载 `alpine-keys-<ver>.apk` → 解压，取 `usr/share/apk/keys/aarch64/*.pub` **和** `etc/apk/keys/*.pub` 两套都收。
- 用 `apk.static --keys-dir <keys-dir>` 局部指定，**别污染宿主 `/etc/apk`**（沙箱是 root，污染会搞坏宿主的 apk）。

### 2.3 apk 数据库根要自建
- 裸跑报 `Unable to lock database: No such file or directory`。
- `mkdir -p <dbroot>/etc/apk <dbroot>/lib/apk/db <dbroot>/var/cache/apk`，写 `repositories` 指向 main+community。
- 全程 `--root <dbroot>`，不动宿主系统。

### 2.4 fetch 用 cd 进目录而非 `-o`
- `apk.static fetch --recursive <pkg>` 默认把 apk 下到**当前工作目录**。用 `( cd <dir> && apk ... fetch ... )` 最稳。
- `--recursive` 拉的是依赖闭包（python3 → 21 个 apk / 18MB；`gcc g++ make` → 18 包）。

### 2.5 增量 diff（相对 minirootfs）+ PACK_EXTRAS 双模式
- 把闭包 apk 全解压进 minirootfs 副本，与原始 minirootfs 做 diff 取增量。
- **不要** `rsync --compare-dir`（沙箱没装 rsync）。内嵌一段 Python：遍历 full，凡 base 不存在 / size 不同 / symlink 目标不同者，复制到 diff。symlink 用 `os.symlink` 原样保留（busybox 多链接靠它）。
- **PACK_EXTRAS 双模式**（`<url>#<dest>[#<zip内模式...]`）：
  - `dest` 以 `/` 结尾 = 目录：把 URL（zip）解压进去。第三字段省略 = 整包解压；给出则只解压匹配模式（空格分隔）。
    例：`build-tools_r34-linux.zip#opt/d8/#android-14/d8 android-14/lib/d8.jar`。
  - `dest` 不以 `/` 结尾 = 文件：原样落盘。**jar 是 zip，"整包解压"只会把 class 文件撒出来**，必须用文件模式。
    例：`kotlin-compiler-2.0.21.jar#opt/kotlinc/lib/kotlin-compiler-2.0.21.jar`。

### 2.6 qemu 验证：`-L` 语义、GUEST_ROOT 预演、JDK re-exec 突破、statx 限制

验证段对 bundle 做四件事：①干净 rootfs+bundle 解压核对（`PACK_EXPECT` 断言）→ ②钩子预演 → ③qemu 冒烟（`PACK_SMOKE_CMD`）→ ④落盘 + `.sha256`。

**qemu-user `-L` 路径翻译语义**（本 spec 全部实证）：
- guest 侧 open/stat/dlopen 的绝对路径 → 翻译到 `-L` 前缀（util/path.c 的 `path()` 被 openat/fstatat/readlinkat 等 case 调用；`access(base+name, F_OK)` 探测，存在才翻译，**失败也缓存**）。
- 顶层 execve（qemu argv 传入的二进制、ELF interpreter）→ 走 `-L` 翻译（这是 `-L` 的本职）。
- **guest 内嵌套 execve（脚本 exec 外部命令）→ 不翻译**，直接落宿主内核：宿主有同名二进制就跑**宿主的**（hook 里跑成宿主 GNU `ls`——报错带引号是 GNU 特征，busybox 不带引号）。
- **已存在的宿主路径 → 直通**（翻译优先用前缀路径，不存在则原样用）。这是 `$VERIFY` 占位符技巧的基础。

**GUEST_ROOT 钩子预演**：设备侧 hook 用绝对路径写（`GUEST_ROOT` 默认空）；构建机预演时设 `GUEST_ROOT=$VERIFY`（宿主 verify 树路径）——hook 里的外部命令（经 ash 嵌套 exec 落宿主同名工具，参数是宿主路径故作用对象正确）与重定向（进程内 open，走 -L 翻译/直通）都能正确作用于 verify 树。**构建期即可验证设备侧 hook**。

**JDK launcher re-exec 关卡**：musl 下 `java_md.c` 的 `RequiresSetenv()` 恒真 → launcher putenv 后 execve 重 exec 自己 → qemu 无 binfmt 必 ENOEXEC。突破：源码早退分支——LD_LIBRARY_PATH 已以期望前缀（`<jvmdir>:<jre>/lib:<jre>/../lib`）开头则直接 return 不 re-exec。脚本对 `*/java-*-openjdk/bin/*` 冒烟命令自动注入该前缀（用宿主前缀路径）。

**`$VERIFY` 占位符（qemu-user 8.2 statx 限制）**：
- **现象**：kotlinc 冒烟 `ClassNotFoundException`，而宿主路径 classpath 同样命令成功。
- **根因**（strace + 源码双重实证）：
  1. qemu-user 8.2 的 `TARGET_NR_statx` case 直接 `sys_statx(dirfd, p, ...)` 拿 guest 原始路径调宿主，**不做 `-L` 翻译**（仅 `-ENOSYS` 回退分支才走 `path()`；宿主内核有 statx，永不回退）。
  2. OpenJDK 17 校验 classpath jar 时会调 statx——strace 实证同一 jar `newfstatat(...)=0`（成功）紧接着 `statx(...,/opt/kotlinc/lib/kotlin-compiler.jar,...)=-1 ENOENT`，JVM 据此判定 jar 不存在。
  3. 宿主前缀路径无需翻译，一切正常。
- **修法**：`PACK_SMOKE_CMD` 中写 `$VERIFY`，构建期替换成 verify 树宿主绝对路径（脚本冒烟段 `smoke="${smoke//\$VERIFY/$VERIFY}"`）。仅 kotlinc/d8 这类"JVM 开 classpath jar"的冒烟需要。
- **设备侧不受影响**：proot 用 ptrace 做全 syscall 路径翻译（含 statx），wrapper 的 guest 路径通配 classpath（`-cp "/opt/kotlinc/lib/*"`）在真机上正常工作。这是纯粹的构建机 qemu 限制。

### 2.7 镜像实测（wget / HTTP-1.1 口径）

| 镜像 | 速度 | 结论 |
|---|---|---|
| 官方 CDN（dl-cdn） | 曾整体 0B/s | 不可达 |
| mirrors.aliyun.com | HTTP/2 快（curl 4MB/s）；**HTTP/1.1 限速 ~22KB/s** | apk.static/wget 都是 HTTP/1.1，44MB 包要半小时——别用 |
| **mirror.nju.edu.cn** | **44MB gcc 包 2.4s（≈18MB/s）** | **默认推荐** |
| mirrors.tuna.tsinghua.edu.cn | ≈3MB/s | 备选 |
| mirrors.ustc.edu.cn | 不可达 | — |
| maven.aliyun.com（jar） | wget 11MB/s 正常 | kotlin jar 走它 |
| dl.google.com（d8 zip） | wget 10MB/s 正常 | d8 走它 |

### 2.8 gcc 深验（qemu 下无嵌套 exec，绕过 driver 直调）
- gcc driver 调 cc1 是 guest 内嵌套 exec（不翻译，宿主 exec guest ELF 必 ENOEXEC）。深验链路直调：
  `cc1 -quiet hello.c -o hello.s` → `as -o hello.o hello.s` → `ld -static -o hello crt1.o crti.o hello.o crtn.o libc.a` → qemu 运行 → 输出 "hello from aarch64 gcc toolchain" ✅。
- 顺带发现：guest gcc 编出的静态小程序可在 qemu 直跑（`-nostdlib -static` + 内联汇编），是排查 guest syscall 行为的利器。

### 2.9 jdk 包的四道关卡（全部已破）

1. **jmods 剔除**：`PACK_SKIP=openjdk17-jmods`（jmods 仅 jlink 需要，javac/kotlinc/d8 均不碰，省 ~100MB）。
2. **kotlin jar 改名**：Maven jar 带版本名（`kotlin-stdlib-2.0.21.jar`），而 kotlin-home 探测（`PathUtil.KotlinPathsFromHomeDirectory`）按官方布局固定名 `lib/kotlin-stdlib.jar` 找——不改名则 kotlinc 每次运行报 "unable to find kotlin-stdlib.jar" 警告。`-Dkotlin.home` 系统属性**不能**解决（实测），必须由 post-install 钩子幂等改名（`kotlin-compiler.jar`/`kotlin-stdlib.jar`/`kotlin-reflect.jar`/`kotlin-script-runtime.jar`）。
3. **d8 布局**：`build-tools_r34-linux.zip` 内容全在 `android-14/` 下；d8 脚本 `#!/bin/bash` + `declare -a`（busybox ash 不认 → **jdk 包必须带 bash**）；脚本用 `$0` 反推 lib/ 目录。
4. **statx 冒烟**：见 §2.6 `$VERIFY` 占位符。

### 2.10 设备侧钩子机制（PACK_POST_INSTALL + GUEST_ROOT）

- 钩子随 bundle 分发为 `usr/lib/acfcn-pack-post-install.sh`；`linux_pack_install` 安装成功后由 `linux_exec` 以 `/bin/sh` 执行一次（best-effort，失败仅记 note 不回滚）。
- 钩子可识别 `GUEST_ROOT`：构建机 qemu 预演时设为宿主 verify 树路径，设备侧为空——同一脚本两处运行。
- jdk 钩子四段职责：①kotlin jar 改名成官方布局固定名（幂等）②遍历 `$JVM/bin/*` 全量建 `/usr/local/bin` 包装（`exec` 真实路径，避免 `/proc/self/exe` 干扰）③kotlinc 包装（`-Dkotlin.home=$R/opt/kotlinc -cp "$KLIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`）④d8 包装。
- 容器 PATH 含 `/usr/local/bin`（ContainerSession 设置），包装可被发现。

## 3. 复现命令

```bash
# python3（已发布）
MIRROR=https://mirror.nju.edu.cn/alpine ROOTFS_VER=3.20.9 OUT_DIR=dist/packs \
  PACK_SMOKE_CMD='/usr/bin/python3 -c "import ssl,ctypes,json,sys;print(sys.version)"' \
  bash scripts/make-resource-pack.sh python3 3.20 python3

# gcc（已发布）
MIRROR=https://mirror.nju.edu.cn/alpine ROOTFS_VER=3.20.9 \
  PACK_SMOKE_CMD='/usr/bin/gcc --version;;/usr/bin/g++ --version;;/usr/bin/as --version;;/usr/bin/ld --version;;/usr/bin/make --version' \
  bash scripts/make-resource-pack.sh gcc 3.20 gcc g++ make

# jdk（已发布）
MIRROR=https://mirror.nju.edu.cn/alpine ROOTFS_VER=3.20.9 PACK_KEEP=1 OUT_DIR=dist/packs \
  PACK_SKIP='openjdk17-jmods' \
  PACK_POST_INSTALL=scripts/pack-hooks/jdk-post-install.sh \
  PACK_EXPECT='usr/lib/jvm/java-17-openjdk/bin/java;usr/lib/jvm/java-17-openjdk/bin/javac;opt/kotlinc/lib/kotlin-compiler-2.0.21.jar;opt/d8/android-14/lib/d8.jar' \
  PACK_EXTRAS='<7 个 kotlin jar，文件模式>;https://dl.google.com/android/repository/build-tools_r34-linux.zip#opt/d8/#android-14/d8 android-14/lib/d8.jar' \
  PACK_SMOKE_CMD='/usr/lib/jvm/java-17-openjdk/bin/java -version;;/usr/lib/jvm/java-17-openjdk/bin/javac -version;;/usr/lib/jvm/java-17-openjdk/bin/java -Dkotlin.home=/opt/kotlinc -cp "$VERIFY/opt/kotlinc/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -version;;/usr/lib/jvm/java-17-openjdk/bin/java -cp "$VERIFY/opt/d8/android-14/lib/d8.jar" com.android.tools.r8.D8 --version' \
  bash scripts/make-resource-pack.sh jdk 3.20 openjdk17-jdk bash
```

- `3.20` 是 minor：apk 闭包从 minor 分支解析（包取最新 patch）。
- `ROOTFS_VER` 不设则自动取该 minor 下最新 patch；app 内置是 `alpine-minirootfs-3.20.9-aarch64.tar.gz`（`ContainerPaths.ASSET_ROOTFS`），**制作必须对齐**，否则基线 rootfs 与设备上的不一致，diff 会漏/多文件。
- `v3.20.9` 这种 patch 级仓库分支 CDN 上没有（community 404），所以闭包解析只能用 minor 分支 + `ROOTFS_VER` 固定基线。
- **上传 Release**：`gh release create container-packs <三个 tar.gz + .sha256>`（上传大资产用 `curl` 直传 uploads.github.com 并给足 `--max-time`，`gh release create` 带资产曾整体 408 超时回滚）。

## 4. 已知限制（设备侧真机验证项）

- **qemu 冒烟 ≠ 真机行为**：qemu-user 下 guest 内嵌套 exec 落宿主（gcc 调 cc1、sh 脚本调 java、经 `/usr/local/bin` 包装调 java 均不可冒烟）——这些只能在设备侧 proot 验证。冒烟首 token 必须是 rootfs+bundle 内真实二进制绝对路径，命令自身不能依赖二次 exec。
- **JVM classpath 通配的 qemu 限制**：见 §2.6 statx——设备侧无此问题。
- **真实 rootfs 触发脚本（.post-install/.trigger）**：gcc/jdk 闭包实测无 trigger 文件（无需 hook 跑 trigger）；jdk 的 PATH 包装/jar 改名由 `PACK_POST_INSTALL` 钩子承担。若未来引入含 trigger 的包，把 trigger 内容固化进 bundle 的 `/etc/profile.d` 或钩子。
- **musl 版本漂移**：minirootfs 基线固定 `3.20.9`（与 app 内置一致），闭包从 `v3.20` 解析。若闭包里的 `musl` 比基线新，diff 会带上新 musl，解压覆盖——自洽。**不要**让 `ROOTFS_VER` 与 app 内置资产不一致。
- **W^X 无关**：资源包内的二进制走 rootfs（`filesDir/linux/rootfs`，可写目录），由 proot 在 `nativeLibraryDir` 的 proot 进程内 exec，不触碰 W^X 限制（W^X 只拦 app 直接 exec 私有可写目录；proot 自身的 exec 发生在其进程上下文且 proot 本体在 nativeLibraryDir）。已确认。
