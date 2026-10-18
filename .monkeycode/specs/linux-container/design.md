# Linux 容器 — 设计（design）

> 配套 `requirements.md` | 负责：workbuddy | 2026-10-18
> 阶段一：容器运行时 + `linux_exec` 智能体工具

## 1. 路线选择

Android 上无 root 运行 Linux 的方案对比：

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| **proot**（Termux 同款） | ✅ 采用 | ptrace 用户态模拟 chroot；不依赖内核特权；Termux 十年验证；Alpine/Debian rootfs 即插即用 |
| chroot / lxc / docker | ❌ | 需要 root |
| KVM / QEMU 整机 | ❌ | `/dev/kvm` 需特权；pKVM 多数设备未启用 |
| bubblewrap | ❌ | 需 user namespace，Android 默认禁止 app 使用 |

参考实现：Termux `proot-distributions`（proot 3.x + talloc）。

## 2. 架构

```
app/src/main/assets/linux/
└── alpine-minirootfs-3.20.9-aarch64.tar.gz   # 3.9MB，sha256 锁定

app/src/main/jniLibs/arm64-v8a/
├── libproot.so              # proot 3.x 主程序（PIE，NDK 27 交叉编译，285KB）
└── libproot-loader.so       # proot loader（静态 ELF，5.3KB，经 PROOT_LOADER 指定）

app/src/main/java/com/selfmod/agent/container/
├── ContainerManager.kt      # 生命周期：rootfs 安装状态机、会话工厂、取消联动
├── RootfsInstaller.kt       # assets → filesDir/linux/ 解压与 sha256 校验（幂等）
├── ContainerSession.kt      # 一次命令执行：进程生命周期、管道、超时、取消
├── ContainerPaths.kt        # 路径常量；proot/loader 从 nativeLibraryDir 解析
└── TarExtractor.kt          # 零依赖 tar 解析（ustar + GNU LongName，越界拒绝）
```

**安装时机**：`App.onCreate()` 里 `initContainer()` 只构造 `ContainerManager`（零成本）；真正的 rootfs 解压发生在智能体首次调用 `linux_exec` 时（阻塞该次调用，约 1-2s），失败返回明确错误而不是拖垮启动。

**proot 为什么在 jniLibs 而不是 assets**：Android 10（API 29）起平台拒绝 exec 应用私有可写目录里的文件（W^X 政策），`nativeLibraryDir`（APK 提取的原生库目录）是唯一被允许 exec 的例外，因此二进制必须以 `lib*.so` 命名经 jniLibs 分发，原地执行、不拷贝。

**rootfs 布局**（app 私有目录，无外部存储权限也可用）：

```
filesDir/linux/
├── rootfs/            # Alpine 根（/bin, /usr, /etc；/workspace 由 bind 提供）
└── workspace/         # 共享工作区：容器内 /workspace ↔ app filesDir/linux/workspace
```

**bind mount 策略**（proot `-b`，白名单制）：
- `-b <filesDir>/linux/workspace:/workspace`（共享工作区，agent 与容器互见）
- 后续按需再开放 `/sdcard` 子目录（需用户在 UI 授权，本文档不含）

## 3. 命令执行（ContainerSession）

```
proot -R rootfs -b /proc -b /dev -b /sys -b workspace:/workspace --kill-on-exit --cwd=<cwd> /bin/sh -c "<command>"
```

- `--kill-on-exit`：确保 sh 退出时子进程树被清理，防僵尸。
- 通过 `ProcessBuilder` 启动，**不走 JNI**。exec 目标为 `nativeLibraryDir/libproot.so`（APK 提取目录，W^X 政策下唯一可 exec 的位置）；loader 经 `PROOT_LOADER` 环境变量指定。
- `/proc /dev /sys` 显式绑定：`-R` 的默认绑定集只含 etc 配置文件，缺了这三个 busybox 半数工具（ps、mount、/dev/urandom）不可用。
- stdout/stderr 各起一个读取线程，累计上限 **256KB**（超出置 `truncated=true` 后继续排空管道防阻塞）。
- 超时看门狗：`timeout_ms`（默认 60s，钳制上限 600s）到点先 `destroy()`，2s 宽限后 `destroyForcibly()`。
- 取消：agent 停止按钮 → `cancel()` → 同上销毁流程；`destroyForcibly` 后 `waitFor` 收尸。
- 环境变量：默认 `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`、`HOME=/root`、`TERM=dumb`、`LANG=C.UTF-8`；调用方传入的 `env` 覆盖同名单。

**退出码约定**：透传容器内 exit code；超时/取消返回特殊码（如 -1 取消、-2 超时）并在结果里给 `status` 字段（ok/timeout/cancelled/spawn_failed）。

## 4. `linux_exec` 工具定义

```json
{
  "name": "linux_exec",
  "description": "在内置 Linux 容器（Alpine, aarch64）中执行 shell 命令。可用 bash/coreutils 等 POSIX 工具；工作目录 /workspace 与 app 共享。用于文本处理、文件整理、脚本运行等浏览器/JS 脚本覆盖不了的任务。",
  "parameters": {
    "type": "object",
    "properties": {
      "command": { "type": "string", "description": "要执行的 shell 命令（/bin/sh -c）" },
      "cwd": { "type": "string", "description": "容器内工作目录，默认 /workspace" },
      "timeout_ms": { "type": "integer", "description": "超时毫秒，默认 60000，上限 600000" },
      "env": { "type": "object", "description": "额外环境变量（字符串→字符串）" }
    },
    "required": ["command"]
  }
}
```

返回给模型的文本（Observation）：

```
exit_code: 0
duration_ms: 142
stdout:
<stdout，截断至约 8KB 给模型，完整结果存 Diagnostics>
stderr:
<stderr>
truncated: false
```

- `command` 空/纯空白 → 拒绝执行并提示。
- 单次输出给模型截断 8KB（防 context 爆炸），完整输出落 `Diagnostics.log("linux", ...)` 供用户在诊断页查看。
- 执行计数进入 `ToolLoopGuard` 既有配额（与 browser/script 工具同池，不新开）。

## 5. 安全模型（向用户明示）

- **能力沙箱，非安全沙箱**：容器内进程 uid = app uid；能读 app 私有目录、能联网（共享宿主网络栈）。它约束的是"agent 使不出 Android API 这件武器"，不是"恶意代码跑不出圈子"。
- 防误伤设计：白名单 bind（只共享 `/workspace`）；默认 `--kill-on-exit`；超时强杀；`rm -rf /` 之类命令由模型自觉（工具层不解析命令语义，保持通用性）。
- rootfs 完整性：assets 内 tarball 带 sha256 清单，安装时校验，失败则容器不可用并报诊断（与 L10 插件完整性校验同思路）。

## 6. proot 二进制来源（已完成）

**NDK 源码交叉编译**（可控、无外部运行时依赖），完整复现步骤见同目录 `build-proot.sh` 及 `build-proot.md`：

- talloc 2.4.2（samba.org FTP 官方源，waf cross-answers 交叉配置）+ Termux proot 源码（含 ashmem_memfd.c 补丁）。
- NDK 27 / API 26：talloc 静态编入 proot；产物依赖仅 `libc.so`/`libdl.so`（bionic 自带）。
- 产物 `libproot.so`（285KB PIE）+ `libproot-loader.so`（5.3KB 静态）→ `app/src/main/jniLibs/arm64-v8a/`。
- 名称必须是 `lib*.so`：Android 10+ 只允许 exec `nativeLibraryDir` 下的库文件（见 §2）。

三个踩坑记录（`build-proot.md` 有详情）：系统 awk 需 gawk（`strtonum`）；GNUmakefile 的 `OBJIFY` 宏依赖 objdump 解析架构而 llvm-objdump 输出格式不同；`OBJCOPY/OBJDUMP` 必须显式指向 NDK llvm 工具，否则 make 落到系统 GNU binutils。

## 7. 兼容性与风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| seccomp 白名单拦个别 syscall（Android 8+） | proot 偶发失败 | 启动失败返回明确错误 + 提示改用 Rhino 脚本；Termux 补丁经验可借鉴 |
| proot syscall 翻译开销 | CPU 密集任务慢 2-3 倍 | 文档明示；编译类任务走第二阶段资源包时给出预期 |
| Android 16 兼容未验证 | 可能启动失败 | 真机验证清单；失败走 fallback |
| APK +4MB | 可接受 | rootfs 换更老的 Alpine 版本或裁剪（删 man/doc/apk cache）可再降 |
| 32 位设备 | 不支持 | minSdk 26 的 arm64 覆盖主流；启动时检测 `Build.SUPPORTED_ABIS`，无 arm64 则禁用工具并提示 |

## 8. 测试策略

**JVM 单元测试**（纯逻辑，无真机）：
- `linux_exec` 参数校验：空 command、timeout 钳制（<1000 → 1000，>600000 → 600000）、cwd 非绝对路径拒绝。
- 输出截断逻辑：256KB 管道上限与 8KB 模型截断。
- `RootfsInstaller` 幂等性：已安装且版本匹配则跳过；版本不匹配重装。
- ToolSpec schema 与 `ToolRegistry` 注册完整性（与既有 `CoerceArgsTest` 同风格）。

**真机/模拟器集成验证**（合并前人工+CI 之外）：
1. `echo hello && uname -m` → aarch64
2. 容器内 `echo data > /workspace/t.txt` → app 侧读 filesDir/linux/workspace/t.txt
3. app 侧写文件 → 容器内 `cat /workspace/t.txt`
4. `sleep 30` + timeout_ms=2000 → timeout 状态、无残留进程
5. agent 循环中调用一次真实任务（如"统计 /workspace 下行数"）

**CI**：现有 `assembleDebug` 自动验证资产打包；无需新增 CI 步骤。

## 9. 第一阶段里程碑

1. proot 静态编译产物入库（或 Plan B）
2. Alpine minirootfs 资产入库 + sha256 清单
3. `ContainerRuntime`/`RootfsInstaller`/`ContainerSession` 实现
4. `linux_exec` 接入 `ToolRegistry` + `Tools.kt`
5. JVM 单测 + 本地 `assembleDebug`
6. 真机 5 项集成验证
7. PR → CI 绿 → 合并 → 更新 board
