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
- [opencode] 下一步计划 `feat/ondevice-cancel` | `cpp/`, `offline/` | 待人类确认 | — | 计划中

---

## 2. 留言区（Message Board，最新在上）

> 用来交接、提问、报警。格式：`[agent] 日期 — 内容`

- [opencode] 2026-10-02 — 已接替 ACFCN。本板机制由 opencode 建立。Qoder/workbuddy 接入后请在第 1 节登记，并在第 4 节认领任务。工作区安排已定：**方案 A（各自独立目录）**。

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
- [ ] H1 端侧取消：看门狗只置标志，无法真正中断 native 生成，卸载可能阻塞/ANR
      （`offline/native/LocalLlmEngine.kt:132-161`, `cpp/acfcn_llm.cpp:179/271`）
- [ ] H2 端侧全局锁：init/generate/free 争用一把 `g_engine.mu`，生成时无法查询/卸载
      （`cpp/acfcn_llm.cpp:34/88/161/179/341`）
- [ ] H3 `config.timeoutSeconds` 未接入实际请求超时；取消依赖 OkHttp 阻塞返回
      （`llm/LlmClient.kt:361-366/109-125`）
- [ ] H4 非原生工具历史：同轮多工具结果连续塞多条 user 消息；无重复调用检测
      （`agent/AgentCore.kt:90-138`, `MAX_ITERATIONS=12`）
- [ ] H5 端侧上下文溢出无截断：多轮必然超 `n_ctx`，报含糊 `code=-3`
      （`cpp/acfcn_llm.cpp:196-244`, `llm/LlmClient.kt:53-62`）
- [ ] H6 浏览器 `navigate()` 在 IO 线程 `Thread.sleep`+25s latch，阻塞且不可取消
      （`browser/BrowserController.kt:157-174`）
- [ ] H7 浏览器 `click/type` 每次重建 snapshot，DOM 索引漂移点到错元素
      （`browser/BrowserController.kt:239-272`）
- [ ] H8 崩溃存档在磁盘不可用时静默丢失
      （`util/CrashStore.kt:14-27`）

### 中严重度
- [ ] M1 `nativeGenerate()` 死代码（无调用者）
- [ ] M2 采样参数不支持 seed/repeat/presence/frequency penalty；远程未发 top_p/top_k
- [ ] M3 `StreamAssembler.applyJson` 的 tool_calls index 回退有缺陷；name 重复追加
- [ ] M4 `ToolCallParser.parseReact` 按行号配对 Action/Action Input，多 Action 会错位
- [ ] M5 `SettingsStore.llmConfig()` 在 getter 里做密钥迁移，有写盘副作用
- [x] M6 `ConfigScreen.currentCfg()` 丢失端侧字段 → **已修复**（da5d129）
- [ ] M7 `SecretStore` 加密失败静默降级明文；加密判断靠类名字符串
- [ ] M8 `SessionStore` 不存 toolCalls，恢复后对话序列非法
- [ ] M9 端侧过滤 `tool` 角色消息时静默丢弃
- [ ] M10 `App.initAll()` 失败后 lateinit 未初始化，可能崩溃循环
- [ ] M11 `BrowserController.onMainSync` 超时返回 `null as T` 可能 NPE
- [ ] M12 WebView 无 saveState/restoreState/destroy，进程被杀丢页
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
- [ ] L7 `OfflineScreen` 模型列表用 remember 快照，导入后不自动刷新
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
- 提交前基线：`testDebugUnitTest` 28/28 通过，`assembleDebug` 产出 ~34MB APK。

---

## 6. 变更记录（本板自身）

- 2026-10-02 opencode 建立本文件（含领域表、任务清单、构建说明）。
