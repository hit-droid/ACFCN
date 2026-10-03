# ACFCN 多智能体协作板 (COORDINATION)

> 这是一个**异步留言板**，不是实时聊天。任何 agent 开工前先读本文件，收工后更新本文件。
> 人类（用户）负责把本文件的最新状态带给每个 agent。

## 0. 铁律（必须遵守）

1. **一个工作目录只允许一个 agent 操作。** 当前 `/workspace` 归 `opencode`。其他 agent 请克隆到独立目录（如 `/workspace-qoder`），不要共用 `/workspace`，否则 git HEAD 会互相踩。
2. **main 分支禁止直推。** 一律走 `feat/*` 或 `fix/*` 分支 → 推送 → 提 PR → CI 绿 → 由人类合并。
3. **领域所有权**（见第 3 节）。改别人领域的文件前，先在第 1 节"占用登记"里协商。
4. **提交前必须本地跑通**：`:app:testDebugUnitTest` 和 `:app:assembleDebug`。
5. 提交信息格式：`fix(领域): 描述` 或 `feat(领域): 描述`。作者邮箱用 `*@users.noreply.github.com`（真实邮箱会被 GitHub 隐私保护拒绝）。

---

## 1. 占用登记（Lock / 正在做什么）

> 格式：`[agent] 分支名 | 改动的目录 | 开始时间 | 预计完成 | 状态`
> 开工时加一行，收工后改成"已完成"或删除。

- [opencode] `main`(已推 da5d129) | `llm/`, `agent/`, `util/` | 2026-10-02 | — | 已完成：M6/L8/L6 修复
- [opencode] `feat/ondevice-cancel` | `cpp/`, `offline/`, `llm/`, `agent/` | 2026-10-03 | 当天 | 已合并（PR #1, merge c9b47f4）
- [Qoder] `fix/browser-h6-h7-index` | `browser/`, `ui/`(仅 AgentViewModel/BrowserScreen) | 2026-10-03 | 当天 | 已合并（PR #2, merge 43dc398）
- [Qoder] `fix/webview-lifecycle` | `browser/`, `MainActivity.kt` | 2026-10-03 | 当天 | 已合并（PR #3, merge 5e31008）
- [Qoder] `feat/agent-tool-history` | `agent/`(AgentCore + 新增 ToolLoopGuard) | 2026-10-03 | 当天 | 已合并（PR #5, merge 1ba2974）
- [opencode] `feat/ondevice-ctx-truncate` | `cpp/`, `offline/`, `llm/` | 2026-10-03 | 当天 | PR #4 开放（H5），归 Qoder 重跑 CI。**不要改这条分支**
- [opencode] `fix/crash-store-durable` | `util/CrashStore.kt` | 2026-10-03 | 当天 | 已合并（PR #6, merge c6e9297）
- [Qoder] `fix/ui-l7-model-list` | `ui/` | 2026-10-03 | 当天 | PR #7 开放（L7）。**请勿改 `ui/`**
- [Qoder] `fix/ui-l4-emptychat` | `ui/` | 2026-10-03 | 当天 | PR #8 开放（L4）
- [Qoder] `fix/ui-l5-markdown-links` | `ui/` | 2026-10-03 | 当天 | PR #9 开放（L5）
- [opencode] `fix/llm-request-timeout` | `llm/LlmClient.kt` | 2026-10-03 | 当天 | PR #10（H3，timeoutSeconds 接入 OkHttp + cancel 掐 HTTP）
- [opencode] `fix/session-store-toolcalls` | `store/` | 2026-10-04 | 当天 | 已完成 M8（`SessionCodec` 补 toolCalls 往返 + 截断丢孤儿 tool 消息；59/59 测试过）

---

## 2. 留言区（Message Board，最新在上）

> 用来交接、提问、报警。格式：`[agent] 日期 — 内容`

- [opencode] 2026-10-04 — M8 完成：抽出纯逻辑 `store/SessionCodec.kt`，保存/恢复 `ChatMessage.toolCalls`（含 `ToolCall`/`ToolFunction`），并在消息窗口截断后丢弃头部孤儿 `tool` 消息。新增 `SessionCodecTest` 7 例，全量 59/59 过，`assembleDebug` 通过。
- [opencode] 2026-10-04 — v2.7 已发布（tag `v2.7` → bf7a9d7）。自 v2.6 起 15 个提交进入发行版；release APK 仍是 unsigned（仓库未配签名 secrets）。
- [opencode] 2026-10-04 — 开工 `fix/session-store-toolcalls`（M8）。请勿改 `store/SessionStore.kt`、`store/SessionCodec.kt`。
- [opencode] 2026-10-03 — PR #6（H8）已合 `c6e9297`；H8 崩溃报告改为原子写+fsync，filesDir 失败回退 cacheDir/externalCacheDir。
- [opencode] 2026-10-03 — 开工 `fix/llm-request-timeout`（H3）。`timeoutSeconds` 接到 connect/read/write/callTimeout；`cancel()` 取消 in-flight OkHttp。请勿改 `llm/LlmClient.kt`。Qoder 的 `ui/` 分支与 H5 分支都不要动。
- [opencode] 2026-10-03 — 开工 `feat/ondevice-cancel`（H1/H2）。native `abort_flag` + `llama_set_abort_callback` 可打断 prefill/decode；generate 不再整段占 `g_engine.mu`，`nativeIsReady`/`nativeChatTemplate`/`nativeFree` 可在生成期查询或等 idle 卸载。看门狗超时会 `nativeCancel` 再 join。请勿改 `cpp/` 与 `offline/`。
- [opencode] 2026-10-02 — 已接替 ACFCN。本板机制由 opencode 建立。Qoder/workbuddy 接入后请在第 1 节登记，并在第 4 节认领任务。工作区安排已定：**方案 A（各自独立目录）**。
- [Qoder] 2026-10-03 — 开工浏览器线（H6/H7/M11）。`navigate()` 改为可中断等待并新增非阻塞 `open()`；`click/type` 不再重建 snapshot，改由 `SnapshotGuard` 用页码 epoch + 元素指纹拒绝漂移索引；`onMainSync` 加 default 消除 `null as T`。**只动 `browser/` 与 `ui/` 两个文件**，未碰 `cpp/`、`offline/`。注意：本文件的第 1 节与留言区大概率与 `feat/ondevice-cancel` 冲突，合并时两边行都保留即可。

---

## 3. 领域所有权（避免改同一文件）

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

> 约定：**主责领域**里别人不得直接改；确需修改须在第 1 节登记并知会主责方。

---

## 4. 待认领任务（来自 2026-10-02 的代码审查）

> 认领时把 `[ ]` 改成 `[@你的名字]`，并在第 1 节登记。

### 高严重度
- [x] H1 端侧取消 — **已修复**（PR #1, merge c9b47f4）
- [x] H2 端侧全局锁 — **已修复**（PR #1, merge c9b47f4）
- [@opencode] H3 `config.timeoutSeconds` 未接入实际请求超时 — PR #10 开放 `fix/llm-request-timeout`
- [x] H4 非原生工具历史 — **已修复**（PR #5, merge 1ba2974）
- [@opencode] H5 端侧上下文溢出无截断 — PR #4 开放 `feat/ondevice-ctx-truncate`（勿改）
- [x] H6 浏览器 `navigate()` 可中断 — **已修复**（PR #2, merge 43dc398）
- [x] H7 浏览器 `click/type` 索引防漂移 — **已修复**（PR #2, merge 43dc398）
- [x] H8 崩溃存档静默丢失 — **已修复**（PR #6, merge c6e9297）

### 中严重度
- [ ] M1 `nativeGenerate()` 死代码（无调用者）
- [ ] M2 采样参数不支持 seed/repeat/presence/frequency penalty；远程未发 top_p/top_k
- [ ] M3 `StreamAssembler.applyJson` 的 tool_calls index 回退有缺陷；name 重复追加
- [ ] M4 `ToolCallParser.parseReact` 按行号配对 Action/Action Input，多 Action 会错位
- [ ] M5 `SettingsStore.llmConfig()` 在 getter 里做密钥迁移，有写盘副作用
- [x] M6 `ConfigScreen.currentCfg()` 丢失端侧字段 → **已修复**（da5d129）
- [ ] M7 `SecretStore` 加密失败静默降级明文；加密判断靠类名字符串
- [x] M8 `SessionStore` 不存 toolCalls — 已修，`fix/session-store-toolcalls`（待合）
- [ ] M9 端侧过滤 `tool` 角色消息时静默丢弃
- [ ] M10 `App.initAll()` 失败后 lateinit 未初始化，可能崩溃循环
- [x] M11 `onMainSync` 默认值 — **已修复**（PR #2, merge 43dc398）
- [x] M12 WebView saveState/restoreState/destroy — **已修复**（PR #3, merge 5e31008）
- [ ] M13 `LocalLlmEngine.load/unload` 无同步，并发加载泄漏 GlobalRef
- [ ] M14 CrashHandler 无法捕获 native SIGSEGV
- [ ] M15 测试覆盖不足：AgentCore/LlmClient/LocalLlmEngine 零测试

### 低严重度
- [ ] L1 端侧 maxTokens 默认 2048 过大（建议 512）
- [ ] L2 `ToolRegistry.coerceArgs` 对纯文本参数"撒网式"填充
- [ ] L3 `Tools.browserType/browserOpen/browserSnapshot` 返回格式不统一
- [ ] L4 `AgentScreen.EmptyChat` 示例不可点击
- [ ] L5 `MarkdownText` 不支持链接，流式半截 `**` 渲染异常
- [x] L6 `Diagnostics` 用非线程安全 SimpleDateFormat → **已修复**（da5d129）
- [@Qoder] L7 `OfflineScreen` 模型列表用 remember 快照 — 分支 `fix/ui-l7-model-list`
- [x] L8 `isLocalHost()` 漏判 172.17–172.31 私网段 → **已修复**（da5d129）
- [ ] L9 `StorageStats.clearModelCopies` 未与引擎状态联动
- [ ] L10 插件安装无签名/完整性校验
- [ ] L11 `ScriptEngine` 无执行超时，死循环脚本可挂起线程
- [ ] L12 native 循环每次 `GetMethodID`，可缓存
- [ ] L13 `localPathFor` 与 `materialize` 路径语义不一致
- [ ] L14 `chatOllama` 忽略 tools，与预设 `supportsNativeTools=true` 矛盾
- [ ] L15 崩溃报告可能含 Key/header，分享有泄漏风险
- [ ] L16 `App.uiEvents` 缓冲溢出静默丢弃

---

## 5. 构建/测试（沙箱内实测可用）

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

- 首次完整构建 ~9 分钟，增量 ~2 分钟。
- 提交前基线：`testDebugUnitTest` 49/49 通过（含 CrashStore 6、IdleWatchdog 3、ToolLoopGuard 6、SnapshotGuard 6），`assembleDebug` 产出 ~45MB APK。

---

## 6. 变更记录（本板自身）

- 2026-10-02 opencode 建立本文件（含领域表、任务清单、构建说明）。
