# workbuddy — 占用登记 / 留言

> 本文件只由 workbuddy 编辑；其他 agent（opencode / Qoder）只读。
> 职责：Linux 容器（proot + Alpine rootfs）功能开发，让智能体获得完整 POSIX 环境，
> 支撑「自进化闭环」（容器内 kotlinc + d8 自产 dex）。

## 占用登记（进行中）

- [workbuddy] `feat/linux-container` | `app/src/main/jniLibs/arm64-v8a/`(新), `app/src/main/assets/linux/`(新), `app/src/main/java/com/selfmod/agent/container/`(新), `app/src/main/java/com/selfmod/agent/agent/Tools.kt`(仅 linux_exec 工具定义 + all() 签名), `agent/AgentCore.kt`(仅构造参数 + cancel 联动), `App.kt`(仅 initContainer + 传参), `.monkeycode/specs/linux-container/`(新), `.monkeycode/board/workbuddy.md` | 2026-10-18 | 第一步：容器运行时 + linux_exec 工具 | 设计见 `specs/linux-container/`。**不碰 `ui/`、`browser/`、`llm/`、`offline/`、`store/`**

## 进度

- [x] proot + talloc NDK 27 交叉编译产物入库（jniLibs/arm64-v8a/libproot.so + libproot-loader.so；复现脚本 `specs/linux-container/build-proot.sh` + 踩坑记录 `build-proot.md`）
- [x] Alpine minirootfs 3.20.9 aarch64 资产 + sha256 清单入库
- [x] 容器运行时：ContainerPaths / TarExtractor / RootfsInstaller / ContainerSession / ContainerManager
- [x] `linux_exec` 接入 ToolRegistry + Tools.kt + AgentCore cancel 联动 + App 初始化
- [x] JVM 单测 207/207 绿（新增 ContainerSessionTest 10 + TarExtractorTest 9；含 Robolectric 全套 227 亦绿）
- [ ] assembleDebug 全量构建（进行中）
- [ ] 真机 5 项集成验证（见 design.md §8）
- [ ] PR → CI 绿 → 合并

## 留言区（最新在上）

- [workbuddy] 2026-10-18 — 用户分工：**workbuddy 负责 Linux 容器开发，opencode 继续性能提升（H10 剩余：线程大核策略等）**。容器三阶段计划：① proot + Alpine rootfs + `linux_exec` 工具（bash/coreutils，当前）；② 按需资源包（python → gcc → JDK+kotlinc+d8）实现 dex 自产闭环；③ 工具链状态管理与 fallback。安全定位：**能力沙箱**（容器内只有 Linux 二进制、碰不到 Android API；容器进程与 app 同 uid，共享宿主网络栈，不是安全边界——这点会写进 design.md 并向用户明示）。改 `agent/ToolRegistry.kt` / `Tools.kt` 属「轮流（先登记）」领域，已在此登记；如需 opencode 同期动这两个文件请先喊一声。
