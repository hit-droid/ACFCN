# ACFCN 多智能体协作板 (COORDINATION)

> 这是一个**异步留言板**，不是实时聊天。任何 agent 开工前先读本文件 + `board/` 下所有人的文件，收工后只更新**自己的** board 文件。
> 人类（用户）负责把最新状态带给每个 agent。

## 0. 铁律（必须遵守）

1. **一个工作目录只允许一个 agent 操作。** 当前 `/workspace` 归 `opencode`。其他 agent 请克隆到独立目录（如 `/workspace-qoder`），不要共用 `/workspace`，否则 git HEAD 会互相踩。
2. **main 分支禁止直推。** 一律走 `feat/*` 或 `fix/*` 分支 → 推送 → 提 PR → CI 绿 → 合并。
3. **领域所有权**（见第 2 节）。改别人领域的文件前，先在自己的 board 文件里登记并知会主责方。
4. **提交前本地跑通** `:app:testDebugUnitTest` 和 `:app:assembleDebug`。PR 默认等 CI 绿再合；CI 中断时按过渡规则本地全量验证（tests + assembleDebug + assembleRelease）后合并并在 PR 描述注明。
5. **占用登记 / 留言写自己的 board 文件**：`board/opencode.md`、`board/qoder.md`。每人只编辑自己的文件（他人文件只读），避免同文件冲突把彼此 PR 打 dirty。历史沿革见各文件。
6. **任务认领只改自己那一行**（第 3 节把 `[ ]` 改成 `[@你的名字] 分支名`），不要重排或改写别人的行。
7. 提交信息格式：`fix(领域): 描述` 或 `feat(领域): 描述`。作者邮箱用 `*@users.noreply.github.com`（真实邮箱会被 GitHub 隐私保护拒绝）。

---

## 1. 板文件索引

| 文件 | 归属 | 用途 |
| --- | --- | --- |
| `board/opencode.md` | opencode | 占用登记、留言、完成记录 |
| `board/qoder.md` | Qoder | 同上 |

---

## 2. 领域所有权（避免改同一文件）

| 领域 | 主责 | 目录/文件 |
| --- | --- | --- |
| 端侧推理 native | opencode | `app/src/main/cpp/` |
| 端侧引擎封装 | opencode | `app/src/main/java/com/selfmod/agent/offline/` |
| LLM 路由/解析 | opencode | `app/src/main/java/com/selfmod/agent/llm/` |
| 智能体循环 | 轮流（先登记） | `app/src/main/java/com/selfmod/agent/agent/` |
| 浏览器自动化 | Qoder/workbuddy | `app/src/main/java/com/selfmod/agent/browser/` |
| UI/Compose | Qoder/workbuddy | `app/src/main/java/com/selfmod/agent/ui/` |
| 存储/稳定性 | 轮流（先登记） | `app/src/main/java/com/selfmod/agent/store/`, `util/` |
| 构建/CI | 轮流（先登记） | `app/build.gradle.kts`, `.github/` |

> 约定：**主责领域**里别人不得直接改；确需修改须先在自己的 board 文件登记并知会主责方。

---

## 3. 待认领任务（来自 2026-10-02 的代码审查 + 2026-10-04 用户报告）

> 认领时把 `[ ]` 改成 `[@你的名字] 分支名`，**只改你那一行**。

### 高严重度
- [x] H1 端侧取消 — **已修复**（PR #1, merge c9b47f4）
- [x] H2 端侧全局锁 — **已修复**（PR #1, merge c9b47f4）
- [x] H3 `config.timeoutSeconds` 未接入实际请求超时 — **已修复**（PR #10, merge bf7a9d7）
- [x] H4 非原生工具历史 — **已修复**（PR #5, merge 1ba2974）
- [@Qoder] H5 端侧上下文溢出无截断 — PR #4 `feat/ondevice-ctx-truncate`（待 rebase）
- [x] H6 浏览器 `navigate()` 可中断 — **已修复**（PR #2, merge 43dc398）
- [x] H7 浏览器 `click/type` 索引防漂移 — **已修复**（PR #2, merge 43dc398）
- [x] H8 崩溃存档静默丢失 — **已修复**（PR #6, merge c6e9297）
- [x] H9 Android 16 卡顿/ANR — **已修复**（PR #21, merge 146f69c）
- [ ] H10 端侧推理 tokens/s 本体（线程策略、KV 复用、批量 prefill）— 需真机 profile 后再动

### 中严重度
- [x] M1 `nativeGenerate()` 死代码 — **已删除**（`chore/remove-native-generate`；Kotlin `generate()` 无调用者，连同 JNI export 一并移除）
- [ ] M2 采样参数不支持 seed/repeat/presence/frequency penalty；远程未发 top_p/top_k（需 ui/ 配字段，找 Qoder 对齐）
- [x] M3 `StreamAssembler.applyJson` tool_calls index 回退缺陷；name 重复追加 — **已修复**（PR #15, merge 4e4edc1）
- [x] M4 `ToolCallParser.parseReact` 按行号配对错位 — **已修复**（PR #16, merge 156f743）
- [ ] M5 `SettingsStore.llmConfig()` 在 getter 里做密钥迁移，有写盘副作用
- [ ] M6 `ConfigScreen.currentCfg()` 丢失端侧字段 → **已修复**（da5d129）
- [ ] M7 `SecretStore` 加密失败静默降级明文；加密判断靠类名字符串
- [x] M8 `SessionStore` 不存 toolCalls — **已修复**（PR #12, merge 88fa621）
- [x] M9 端侧过滤 `tool` 角色消息时静默丢弃 — **已修复**（PR #14, merge 015fc75）
- [ ] M10 `App.initAll()` 失败后 lateinit 未初始化，可能崩溃循环
- [x] M11 `onMainSync` 默认值 — **已修复**（PR #2, merge 43dc398）
- [x] M12 WebView saveState/restoreState/destroy — **已修复**（PR #3, merge 5e31008）
- [x] M13 `LocalLlmEngine.load/unload` 无同步 — **已修复**（PR #24, merge 87f07a6；native 部分已被 H1/H2 覆盖，本次修 Kotlin 层）
- [ ] M14 CrashHandler 无法捕获 native SIGSEGV
- [ ] M15 测试覆盖不足：AgentCore/LocalLlmEngine 零测试

### 低严重度
- [x] L1 端侧 maxTokens 默认 2048 过大 — **已修复**（PR #21, 默认 512）
- [ ] L2 `ToolRegistry.coerceArgs` 对纯文本参数"撒网式"填充
- [ ] L3 `Tools.browserType/browserOpen/browserSnapshot` 返回格式不统一 — [@Qoder] PR #11 待 rebase
- [ ] L4 `AgentScreen.EmptyChat` 示例不可点击 — [@Qoder] PR #8 待 rebase
- [ ] L5 `MarkdownText` 不支持链接 — [@Qoder] PR #9 待 rebase
- [x] L6 `Diagnostics` 用非线程安全 SimpleDateFormat → **已修复**（da5d129）
- [ ] L7 `OfflineScreen` 模型列表用 remember 快照 — [@Qoder] PR #7 待 rebase
- [x] L8 `isLocalHost()` 漏判 172.17–172.31 私网段 → **已修复**（da5d129）
- [ ] L9 `StorageStats.clearModelCopies` 未与引擎状态联动 — [@Qoder] PR #13 待 rebase
- [ ] L10 插件安装无签名/完整性校验
- [ ] L11 `ScriptEngine` 无执行超时 — [@Qoder] PR #23 待 rebase
- [x] L12 native 循环每次 `GetMethodID` — **已过时**（复核：`generate_impl` 每次*调用*只解析一次 methodID，H1/H2 重构已覆盖；每 token 仅必要的 NewStringUTF）
- [ ] L13 `localPathFor` 与 `materialize` 路径语义不一致
- [x] L14 `chatOllama` 忽略 tools — **已修复**（PR #25, merge 43fd080）
- [x] L15 崩溃报告可能含 Key/header — **已修复**（PR #17, merge f1d9a09）
- [ ] L16 `App.uiEvents` 缓冲溢出静默丢弃 — [@Qoder] PR #20 待 rebase

---

## 4. 构建/测试（沙箱内实测可用）

```bash
# 首次环境（沙箱无 JDK/SDK 时）
apt-get update && apt-get install -y openjdk-17-jdk-headless
# 装 SDK: platform-tools / platforms;android-34 / build-tools;34.0.0 / ndk;27.0.12077973 / cmake;3.22.1
echo "sdk.dir=/opt/android-sdk" > local.properties

# 构建（沙箱网络受限时用镜像 init 脚本 + 关代理）
./gradlew --no-daemon -I /tmp/opencode/mirror.init.gradle.kts \
  -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.nonProxyHosts="*" \
  :app:testDebugUnitTest :app:assembleDebug
```

- 首次完整构建 ~9 分钟，增量 ~2 分钟，`assembleRelease`（R8）额外 ~4-12 分钟。
- 当前基线：`testDebugUnitTest` **96/96**（SessionCodec 7、OnDevicePrompts 7、SecretRedactor 6、DeltaPacer 4、EngineLifecycle 8、LlmClientOllamaTools 3、CrashStore 6、StreamAssembler 8、ToolCallParser 8 等），`assembleDebug` 产出 ~45MB APK。
- release APK 未配签名 secrets（`ACFCN_*`），产出 unsigned，需自签后安装。

---

## 5. 变更记录（本板自身）

- 2026-10-04 opencode 板改组：占用/留言拆分为每人一个文件（`board/*.md`），结束"每次合 PR 把别人全部打 dirty"的同文件冲突；任务清单状态同步（M9/M13/L14 已合，基线 96/96）。
- 2026-10-02 opencode 建立本文件（领域表、任务清单、构建说明）。
