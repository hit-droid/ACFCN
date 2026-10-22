# 资源包（Resource Pack）制作 — 踩坑与复现（pack-resources）

> 配套 `design.md` §10 | 负责：workbuddy | 2026-10-18
> 在 x86_64 构建机上制作 Android 容器内 aarch64 资源包（bundle），已 POC 打通 python3。

## 0. 一句话原理

app 的 Linux 容器是 Alpine rootfs，`apk` 包是「ustar tar.gz + `.PKGINFO`」。`apk fetch --recursive`
只下载并解压闭包，**不执行**被装包的 aarch64 二进制，因此 x86 机就能完整制作；最终 bundle 用
`qemu-user` 在 x86 上模拟 arm64 跑一条冒烟命令验证即可。

## 1. 制作机环境

- x86_64 Linux（本沙箱 Ubuntu 22.04）。
- **不需要** proot（ptrace 模拟在 qemu 下不可靠）。
- 需要 `qemu-user`（`apt-get install qemu-user`；提供 `qemu-aarch64`，binfmt 未激活，故显式 `-L <sysroot>` 调用）。
- 需要 Python3（diff 提取脚本用）。
- 全程离线工具链自包含：脚本自动下载 `apk-tools-static`(x86_64) + `alpine-keys`(x86_64，含 aarch64 公钥)。

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
- `--recursive` 拉的是依赖闭包（python3 → 21 个 apk / 18MB）。

### 2.5 增量 diff（相对 minirootfs）
- 把 21 个 apk 全解压进 minirootfs 副本，与原始 minirootfs 做 diff 取增量。
- **不要** `rsync --compare-dir`（沙箱没装 rsync）。内嵌一段 Python：遍历 full，凡 base 不存在 / size 不同 / symlink 目标不同者，复制到 diff。symlink 用 `os.symlink` 原样保留（busybox 多链接靠它）。
- diff 即 bundle 内容（python3：52MB 原增量 → gzip 17MB）。

### 2.6 打包与验证
- 打包：`tar czf <pack>-aarch64.tar.gz -C <diff> .`（`.` 前缀，解压进 rootfs 根覆盖即可）。
- 校验：`sha256sum` 出 `.sha256`。
- **qemu 验证**（关键，证明 bundle 可独立运行）：
  ```
  qemu-aarch64 -L <clean-rootfs+diff> /usr/bin/python3 -c "import ssl,ctypes,json;print(sys.version)"
  ```
  实测 python3 3.12.13 / aarch64，ssl/ctypes/json 模块齐全 ✅。

## 3. 复现命令

```bash
# 在仓库根：
bash scripts/make-resource-pack.sh python3 3.20 python3     # → python3-aarch64.tar.gz + .sha256
bash scripts/make-resource-pack.sh gcc     3.20 gcc         # → gcc-aarch64.tar.gz
bash scripts/make-resource-pack.sh jdk     3.20 openjdk17-jre openjdk17 kotlin  # → jdk-aarch64.tar.gz
```

产物建议上传 GitHub Release（按 `ALPINE_VER` 分目录），设备侧 `linux_pack_install` 据此 URL 下载。

## 4. 已知坑（设备侧实现时注意）

- **真实 rootfs 触发脚本（.post-install/.trigger）**：python3 闭包无 trigger；但 `gcc`/`jdk` 可能含
  （如 `libc6-compat` 的 ldconfig、ca-certificates 的 `update-ca-certificates`）。纯解压不跑 trigger 会让
  少数包软链/证书缺失。解决：bundle 里附带一个 `post-install.sh`，`linux_pack_install` 解压后由
  `linux_exec` 跑一遍（或把 trigger 内容固化进 bundle 的 `/etc/profile.d`）。待 gcc/jdk 制作时实测。
- **musl 版本漂移**：minirootfs 用 `3.20.9`，闭包解析用 `v3.20` 最新（已到 `3.20.10-...`）。若闭包里的
  `musl` 比 base 新，diff 会带上新 musl，解压覆盖——自洽。但建议制作时固定 `ALPINE_VER` 到与
  minirootfs 同 patch（如都 `3.20.9`）以杜绝漂移。脚本现接收 `ALPINE_VER` 参数即为此留口。
- **W^X 无关**：资源包内的二进制走 rootfs（`filesDir/linux/rootfs`，可写目录），由 proot 在
  `nativeLibraryDir` 的 proot 进程内 exec，不触碰 W^X 限制（W^X 只拦 app 直接 exec 私有可写目录；
  proot 自身的 exec 发生在其进程上下文且 proot 本体在 nativeLibraryDir）。已确认。
