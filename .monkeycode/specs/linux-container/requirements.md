# Linux 容器 — 需求（requirements）

> 任务：`feat/linux-container` | 负责：workbuddy | 日期：2026-10-18
> 状态：第一阶段（容器运行时 + `linux_exec` 工具）

## 背景

ACFCN 是运行在 Android 上的自进化智能体。当前智能体的"动手能力"分两级：

1. **内置工具**：浏览器（snapshot/click/type）、脚本（Rhino JS 沙箱）、插件（DexClassLoader 热加载 dex）。
2. **短板**：没有完整 POSIX 环境。文本处理、格式转换、编译构建这类任务只能拆成脆弱的 JS 脚本或依赖人工在电脑上预编译 dex 插件。

「自进化」的叙事因此停在半路：agent 能改脚本、能装别人编好的插件，但**不能在设备上生产自己的零件**。

## 目标（第一阶段）

| # | 目标 | 验收标准 |
| --- | --- | --- |
| G1 | app 内置 Linux 容器运行时，无需 root | Android 10+ 真机 `linux_exec("echo hello && uname -m")` 返回 `aarch64` |
| G2 | agent 可通过工具在容器内执行 shell 命令 | `linux_exec` 工具在 AgentCore 循环中可用，返回 stdout/stderr/exit_code |
| G3 | 容器与 app 共享工作区 | 容器内写 `workspace/out.txt`，app 侧可读；反之亦然 |
| G4 | 超时与取消 | 超过 timeout_ms 或用户点停止，进程树被杀，不残留僵尸 |
| G5 | 失败可诊断、可降级 | proot 启动失败（如 seccomp 拦截）时返回明确错误信息，提示改用 Rhino 脚本 |

## 非目标（明确不做）

- **不是安全沙箱**：容器进程与 app 同 uid，共享宿主网络栈。它的价值是"能力沙箱"——容器内只有 Linux 二进制，碰不到 Android framework API。安全边界 = app 现有沙箱，不会更差也不会更好。
- 不做 mount/namespace/PID 隔离（无 root 做不到）。
- 不支持 x86/x86_64 二进制（端侧仅 arm64-v8a，与现有 llama.cpp 策略一致）。
- 不做 x86 模拟器（qemu-user）。

## 约束

- APK 体积增量 ≤ 10MB（Alpine minirootfs ≈ 3MB 压缩 + proot 二进制 ≈ 1MB）。
- minSdk 26（arm64-v8a only）。
- 构建环境不变：NDK 27 + CMake 3.22.1，不引入新的构建依赖。
- 遵守协作板铁律：main 禁直推；`agent/ToolRegistry.kt` / `Tools.kt` 只碰注册区块；不碰 `ui/`、`browser/`、`llm/`、`offline/`、`store/`。

## 后续阶段（本文档只覆盖第一阶段，列出以供设计时预留）

- 第二阶段：按需资源包（python → gcc → JDK + kotlinc + d8），容器内自产 dex → `PluginLoader` 热加载，闭合自进化闭环。
- 第三阶段：工具链安装状态管理、资源包下载/校验/清理、fallback 策略完善、共享工作区目录规范。
