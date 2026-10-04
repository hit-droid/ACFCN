# M10 设计 — `App.initAll()` 失败后 lateinit 未初始化

## 整体方案

把 13 个 `lateinit var` 全部改成 `var x: T? = null` 形式 + 公开 getter。`initAll()` 不再被一整个 `runCatching` 包住，而是**按特性分组逐段**包：

1. `crashStore` / `storage` —— 永远最先赋值（保持现状）。
2. `uiQueue` / `uiEvents` / `uiNotifier` —— 在 `initAll()` 之前构造（已经是这样）。
3. `settings` / `repo` / `plugins` / `models` / `tester` / `sessions` —— "持久化 + 仓库"，一个 `runCatching`。
4. `engine` / `llmClient` —— "引擎 + LLM"，单独一个 `runCatching`，便于定位 native 失败。
6. `scriptEngine` / `browser` —— 各自独立 `runCatching`（失败不致命，可降级）。
7. `scriptHost` / `agent` —— "依赖前面所有东西"的复合体，最后构造。

每段抛异常时：
- 记 `Log.e(TAG, "app_init <feature> failed", e)`；
- 写 `crashStore.record(CrashStore.Severity.CRASH, kind="app_init", feature=<name>, throwable=e)`；
- 该段的 `var` 保持 `null`；
- 后续段如果依赖失败的段（如 `scriptHost` 依赖 `settings`/`repo`/`plugins`/`llmClient`），直接跳到下一段 `null`。

## 关键决策

### 决策 1：不用 `lazy` 而用 `var + public getter`

`lazy` 会把异常延后，但同一时刻仍然抛 `UninitializedPropertyAccessException` 风格的问题，且 `lazy` 不能表达"曾尝试过但失败"这个事实。显式 `var x: T? = null` + 公开 getter（标注 `@Nullable`）更直接。

### 决策 2：调用点改造最小化

不强制每个调用点判 null，因为：
- `MainActivity` 已经在 `browser` 周围用 `runCatching` 包了一层；
- `AgentViewModel` 唯一危险点是 `app.uiEvents`，而这个字段永远不为 null（`uiQueue` 在 init 之前构造）。

但 `MainActivity` 增加 1 处判 `browser == null` 显示占位的逻辑。

### 决策 3：不引入 `AppNotInitializedException`

调用点都是已知有限的 4 个，直接判 null 比"先要捕获的异常类型再判 feature 名"省事。如果后续 ViewModel 增多，再考虑异常。

## API 变化

```kotlin
// 之前
lateinit var agent: AgentCore

// 之后
var agent: AgentCore? = null
    private set

val uiEvents: Flow<UiEvent> get() = uiQueueOrNull()?.events ?: emptyFlow()
```

公开字段全部变成 nullable。**这是 API breaking**，但调用点都是同仓库内（`internal` package），所以可以一次性改完。

## 文件清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/selfmod/agent/App.kt` | 13 个 `lateinit var` → `var ?: null`；`initAll()` 拆 5 段，每段独立 `runCatching`；新增私有 `initXxx()` 方法 |
| `app/src/main/java/com/selfmod/agent/MainActivity.kt` | `browser == null` 时显示占位（而不是依赖 `runCatching` 吞掉） |
| `app/src/test/java/com/selfmod/agent/AppInitTest.kt` | 新增：3 个用例 |

## 测试策略

- `AppInitTest` 用纯 JVM，不引 Robolectric：
  - 用 `org.mockito.kotlin` mock 关键构造点（`LocalLlmEngine.ensureLoaded` 等静态方法）；
  - 或者用 `SettingsStore` / `CodeRepository` 的真实子类，但 mock 出 `LocalLlmEngine.ensureLoaded`。
- 跑 3 个用例：正常路径、引擎 OOM、repo 抛错。
- 已有 164 个单测不应受影响（不触动它们的代码）。

## 风险

- `var x: T?` + 公开 getter 让调用者必须处理 null。如果漏改某个调用点，编译期 Kotlin 会强制检查，但运行时旧代码风格（`!` 强转）会绕过。审查时要 grep `app\.\w+\.` 看是否还有 `!!`。
- 把所有 `lateinit var` 改成 nullable，等于把"启动失败"这件事**显式化**到所有调用点。这是有意为之：晚点崩溃不如早显示。

## 回滚

`git revert` 即可。改动局限在 3 个文件内。

## 任务清单

1. 把 `App.kt` 13 个 `lateinit var` 改 `var ?: null`。
2. 把 `initAll()` 拆 5 段 `runCatching`。
3. 在 `MainActivity.kt` 加 `browser == null` 占位逻辑。
4. 加 `AppInitTest` 3 个用例。
5. 跑 `./gradlew :app:testDebugUnitTest --tests com.selfmod.AppInitTest :app:assembleDebug`。
6. PR 标题：`feat(app): M10 initAll 按特性分组，失败后字段为 null 而非崩溃循环`
7. COORDINATION.md 状态从 `[ ]` 改为 `[x] M10 ... 已修复（PR #?, merge ?）`。