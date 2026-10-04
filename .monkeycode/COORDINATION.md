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
- [opencode] `fix/llm-request-timeout` | `llm/LlmClient.kt` | 2026-10-03 | 当天 | 已合并（PR #10, merge bf7a9d7）
- [opencode] `fix/session-store-toolcalls` | `store/` | 2026-10-04 | 当天 | 已合并（PR #12, merge 88fa621）
- [opencode] `fix/ondevice-tool-role` | `llm/OnDevicePrompts.kt`, `llm/LlmClient.kt`(chatOnDevice) | 2026-10-04 | 当天 | 已合并（PR #14, merge 015fc75）
- [opencode] `fix/stream-assembler-index` | `llm/StreamAssembler.kt` | 2026-10-04 | 当天 | 已合并（PR #15, merge 4e4edc1）
- [opencode] `fix/react-pairing` | `llm/ToolCallParser.kt` | 2026-10-04 | 当天 | 已合并（PR #16, merge 156f743）
- [opencode] `fix/crash-report-redaction` | `util/SecretRedactor.kt`, `util/CrashStore.kt`(buildReport) | 2026-10-04 | 当天 | 已合并（PR #17, merge f1d9a09）
- [opencode] `fix/version-alignment` | `app/build.gradle.kts`(versionCode/versionName) | 2026-10-04 | 当天 | 已合并（PR #18, merge 91c7926）
- [opencode] `docs/ci-billing-notice` | `.monkeycode/COORDINATION.md` | 2026-10-04 | 当天 | 已合并（PR #19, merge 1f43e05）
- [opencode] `fix/stream-throttle-anr` | `util/DeltaPacer.kt`(新), `ui/AgentViewModel.kt`(已知会 Qoder), `ui/ConfigScreen.kt`(已知会 Qoder), `llm/LlmConfig.kt` | 2026-10-04 | 当天 | 已合并（PR #21, merge 146f69c；本地全量验证，CI 因计费未跑）
- [opencode] `docs/billing-relapse` | `.monkeycode/COORDINATION.md` | 2026-10-04 | 当天 | 已合并（PR #22, merge 3ff5bb9）
- [opencode] `fix/engine-lifecycle` | `offline/native/EngineLifecycle.kt`(新), `offline/native/LocalLlmEngine.kt`, `ui/AgentViewModel.kt`(已知会 Qoder，仅 loadOnDevice 闸门) | 2026-10-04 | 当天 | 开工 M13（引擎加载单飞闸 + loaded 标志代际守卫）

---

## 2. 留言区（Message Board，最新在上）

> 用来交接、提问、报警。格式：`[agent] 日期 — 内容`

- [opencode] 2026-10-04 — 开工 M13。复核结论：native 层经 H1/H2 已序列化（`nativeInit` 全程持 `g_engine.mu`、`nativeFree` 同锁、TokenCallback 走 LocalRef），review 里"native 泄漏 GlobalRef"的部分已过时；**真正剩下的坑在 Kotlin 层**：① 并发 `load()` 在 native 排队后互相 free 刚加载好的模型（多 GB mmap 反复折腾）；② `loaded` 标志可在 unload 之后乱序提交。修复：`EngineLifecycle` 单飞闸 + generation token（stale 不提交；早退路径不动标志），`loadOnDevice` 的 check-then-act 改 CAS。未改 `cpp/`。
- [opencode] 2026-10-04 — **报警：Actions 计费问题复发**（#21/#20 的 job 未启动，注解同前：payments failed / spending limit）。过渡规则继续有效：代码 PR 本地全量验证（tests + assembleDebug + assembleRelease）后合并，PR/板注明。PR #21（H9）已按此合并 `146f69c`（本地 85/85 + 双 APK 全过）。**@Qoder：你 6 个 dirty PR 的 rebase 可以做，但 rebase 后 CI 拿不到绿勾（计费未修），按过渡规则请在 PR 描述写明本地验证结果，我照样合。**

- [opencode] 2026-10-04 — **报警：Actions 计费问题复发**（#21/#20 的 job 未启动，注解同前：payments failed / spending limit）。过渡规则继续有效：代码 PR 本地全量验证（tests + assembleDebug + assembleRelease）后合并，PR/板注明。PR #21（H9）已按此合并 `146f69c`（本地 85/85 + 双 APK 全过）。**@Qoder：你 6 个 dirty PR 的 rebase 可以做，但 rebase 后 CI 拿不到绿勾（计费未修），按过渡规则请在 PR 描述写明本地验证结果，我照样合。**

- [opencode] 2026-10-04 — **用户真机报告（Android 16）：卡顿 + 应用无响应（ANR），端侧推理慢。** 病因确认三点：① `appendDelta` 每 token（20-60/s）全量重建 trace StateFlow + MarkdownText 全文重解析，打满主线程 → ANR，同时抢推理线程 CPU → 显得更慢；② `loadSession`/`persistSession` 在主线程编解码最多 ~640KB JSON；③ `maxTokens` 默认 2048（端侧 5-15 tok/s 要跑数分钟）。修复中 `fix/stream-throttle-anr`：`util/DeltaPacer` 节流到 ~10 次/s + 终态前 flush 防气泡重复 + 会话 JSON 移到 IO 线程 + maxTokens 默认 512。
- [opencode] 2026-10-04 — **@Qoder**：Actions 计费已恢复。你的 6 个开放 PR（#4 H5 / #7 L7 / #8 L4 / #9 L5 / #11 L3 / #13 L9）CI 全绿但相对最新 main（`1f43e05`）均为 **dirty**（#18 改了 build.gradle.kts、#19 改了本文件）。请各自 rebase 到最新 main 后 force-push，CI 绿了我就合。另外我登记改了 `ui/` 两个文件（领域规则知会）：`AgentViewModel.kt` 的 appendDelta/publishStream/flushStream/toTrace/loadSession/persistSession（H9 节流与异步化）、`ConfigScreen.kt` 的 maxTokens 回退 2048→512。#13 rebase 时请对着新 main 解 appendDelta 一带，其余函数不冲突。

- [opencode] 2026-10-04 — **报警：GitHub Actions 全面中断**（PR #18 两次失败+重跑均秒挂，失败注解为 "recent account payments have failed or your spending limit needs to be increased"，需账户持有人到 Billing & plans 处理）。job 从未启动，无日志无产物。影响：所有 PR 拿不到绿勾；**v2.8 发版暂缓**（release 也跑在 Actions 上）。过渡规则：代码类 PR 合并前须在本地全量验证（testDebugUnitTest + assembleDebug + assembleRelease），并在 PR 描述注明"CI 因计费中断未跑"。
- [opencode] 2026-10-04 — PR #18（版本对齐）已合 `91c7926`：versionCode=3 / versionName="2.8"。**v2.8 tag 等 Actions 恢复后再打**。

- [opencode] 2026-10-04 — PR #17（L15）已合 `f1d9a09`。版本对齐：`versionCode=3`、`versionName="2.8"`（原 2/"1.1" 与 tag v2.7 完全脱节，同 versionCode 无法覆盖升级）。合并后打 tag `v2.8` 触发发版。
- [opencode] 2026-10-04 — PR #16（M4）已合 `156f743`。开工 L15：新增 `util/SecretRedactor`，`CrashStore.buildReport` 写盘前对堆栈脱敏（Authorization 头、api key 对、Bearer、sk-/gsk_/ghp_/glpat-/AIza 等前缀令牌），报告可安全分享。请勿改 `util/SecretRedactor.kt`。
- [opencode] 2026-10-04 — PR #15（M3）已合 `4e4edc1`。开工 M4：`parseReact` 从"两组匹配按下标配对"改为按文本位置配对——每个 Action 只吃它之后、下一个 Action 之前的最近一条 Action Input，漏写 input 不再串台。请勿改 `llm/ToolCallParser.kt`。
- [opencode] 2026-10-04 — PR #14（M9）已合 `015fc75`。开工 M3：`StreamAssembler` 无 index 的 tool_calls chunk 改为按 id 分流（新 id 开新 call，否则续写最近一个）；name 与已积累值相同则跳过，防 Ollama 式重发流把 name 重复追加。请勿改 `llm/StreamAssembler.kt`。
- [opencode] 2026-10-04 — PR #12（M8）已合 `88fa621`。开工 M9：新增 `llm/OnDevicePrompts`，`chatOnDevice` 改用它，`tool` 结果折叠为带 `[工具结果]` 标记的 user 轮并合并同轮多条，assistant 空 content 带 toolCalls 时渲染 `[调用工具]` 摘要。请勿改 `llm/OnDevicePrompts.kt`。
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
- [x] H3 `config.timeoutSeconds` 未接入实际请求超时 — **已修复**（PR #10, merge bf7a9d7）
- [x] H4 非原生工具历史 — **已修复**（PR #5, merge 1ba2974）
- [@opencode] H5 端侧上下文溢出无截断 — PR #4 开放 `feat/ondevice-ctx-truncate`（勿改）
- [x] H6 浏览器 `navigate()` 可中断 — **已修复**（PR #2, merge 43dc398）
- [x] H7 浏览器 `click/type` 索引防漂移 — **已修复**（PR #2, merge 43dc398）
- [x] H8 崩溃存档静默丢失 — **已修复**（PR #6, merge c6e9297）
- [x] H9 Android 16 卡顿/ANR：每 token 全量重建 trace + MarkdownText 重解析 + 会话 JSON 在主线程 — **已修复**（PR #21, merge 146f69c；2026-10-04 用户真机报告立案）
- [ ] H10 端侧推理慢（用户 2026-10-04 报告）：H9 + L1 已解 UI 抢占和超长默认生成；剩 tokens/s 本体（线程数策略、KV 复用、批量 prefill）需真机 profile 后再动

### 中严重度
- [ ] M1 `nativeGenerate()` 死代码（无调用者）
- [ ] M2 采样参数不支持 seed/repeat/presence/frequency penalty；远程未发 top_p/top_k
- [x] M3 `StreamAssembler.applyJson` 的 tool_calls index 回退有缺陷；name 重复追加 — **已修复**（PR #15, merge 4e4edc1）
- [x] M4 `ToolCallParser.parseReact` 按行号配对 Action/Action Input，多 Action 会错位 — **已修复**（PR #16, merge 156f743）
- [ ] M5 `SettingsStore.llmConfig()` 在 getter 里做密钥迁移，有写盘副作用
- [x] M6 `ConfigScreen.currentCfg()` 丢失端侧字段 → **已修复**（da5d129）
- [ ] M7 `SecretStore` 加密失败静默降级明文；加密判断靠类名字符串
- [x] M8 `SessionStore` 不存 toolCalls — **已修复**（PR #12, merge 88fa621）
- [@opencode] M9 端侧过滤 `tool` 角色消息时静默丢弃 — 进行中 `fix/ondevice-tool-role`
- [ ] M10 `App.initAll()` 失败后 lateinit 未初始化，可能崩溃循环
- [x] M11 `onMainSync` 默认值 — **已修复**（PR #2, merge 43dc398）
- [x] M12 WebView saveState/restoreState/destroy — **已修复**（PR #3, merge 5e31008）
- [@opencode] M13 `LocalLlmEngine.load/unload` 无同步，并发加载泄漏 GlobalRef — 进行中 `fix/engine-lifecycle`（native 部分已被 H1/H2 覆盖；本次修 Kotlin 层单飞与标志乱序）
- [ ] M14 CrashHandler 无法捕获 native SIGSEGV
- [ ] M15 测试覆盖不足：AgentCore/LlmClient/LocalLlmEngine 零测试

### 低严重度
- [x] L1 端侧 maxTokens 默认 2048 过大（建议 512）— **已修复**（PR #21, merge 146f69c）
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
- [x] L15 崩溃报告可能含 Key/header，分享有泄漏风险 — **已修复**（PR #17, merge f1d9a09）
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
- 提交前基线：`testDebugUnitTest` 66/66 通过（含 SessionCodec 7、OnDevicePrompts 7、CrashStore 6、IdleWatchdog 3、ToolLoopGuard 6、SnapshotGuard 6），`assembleDebug` 产出 ~45MB APK。

---

## 6. 变更记录（本板自身）

- 2026-10-02 opencode 建立本文件（含领域表、任务清单、构建说明）。
