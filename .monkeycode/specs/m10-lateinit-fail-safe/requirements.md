# M10 — `App.initAll()` 失败后 lateinit 未初始化，可能崩溃循环

## 背景

`App.onCreate()` 把 `initAll()` 整段包在 `runCatching` 里：

```kotlin
override fun onCreate() {
    super.onCreate()
    crashStore = CrashStore(this)
    storage = StorageStats(this)
    runCatching { CrashHandler.install(crashStore) }
    runCatching { initAll() }.onFailure {
        Log.e(TAG, "App init FAILED — app will start but features may be broken", it)
    }
}
```

但 `initAll()` 里用的是 `lateinit var` 一次性赋值。只要赋值中途某一步抛异常（比如 `LocalLlmEngine.ensureLoaded()` OOM、`CrashHandler.install()` 之后的某个 `runCatching {}` 失败），后续 13 个 `lateinit var` 中有一部分已经赋值、有一部分没赋值。应用不会崩在 onCreate 上，但随后：

- `MainActivity` 启动时 `application as App` 取到的就是半成品单例；
- 任何 ViewModel / 屏幕按需访问 `app.agent`、`app.sessions`、`app.settings` 等字段时，会抛 `UninitializedPropertyAccessException`；
- CrashHandler 会把这个崩溃落盘，下次启动还会触发 `initAll()`，再抛一次，形成崩溃循环。

代码里能看到几个证据：

- `MainActivity.kt:33/57/63` 访问 `browser` 时已经用 `runCatching` 包了一层（说明历史上崩过）；
- `AgentViewModel.kt:219` 直接读 `app.uiEvents`，无 `runCatching` 兜底；
- `App.uiNotifier()` 在 `initAll()` 之外用 lambda 初始化，是安全的；但 `app.crashStore` / `app.storage` 在 `initAll()` 之前赋值，所以这两个从来不会未初始化。

## 目标

1. 任何步骤失败都不让应用进入"晚点、晚点"的混合状态。
2. 失败后应用仍能起来，只在用户走到对应功能时给可读的提示，而不是崩。
3. 不引入额外的运行时异常分类框架，控制在 `App.kt` 内 + 少量调用点。

## EARS 需求

### EARS-RE-01（普遍要求）

**当** `App.initAll()` 任一子步骤抛异常时，
**则** 应用**必须**只把已经成功构造好的组件暴露出去；
**并且** 没构造好的字段**必须**返回 `null` 或抛受控 `AppNotInitializedException`（带 `feature` 名），而不是抛 `UninitializedPropertyAccessException`；
**并且** `CrashHandler`**必须**捕获并落盘这条异常，`Log.e`**必须**给出失败的 `feature` 名。

### EARS-RE-02

**当** `MainActivity.onCreate()` 访问 `app.browser` 时，
**则** 若 `browser == null`（init 中途失败），
**则** Activity**必须**显示「端侧浏览器暂不可用」占位，
**并且不**抛 `UninitializedPropertyAccessException`。

### EARS-RE-03

**当** `MainActivity.onCreate()` 访问 `app.crashStore` 时，
**则** 该字段**必须永不**为 `null`（即使后续 init 失败）。
原因：`crashStore` 在 `initAll()` 之前赋值，且仅依赖 `Context`。

### EARS-RE-04

**当** `AgentViewModel` 通过 `app.uiEvents` 订阅 UI 事件时，
**则** 若 `uiEvents` 不可用（理论上不会，因为 `uiQueue` 在 `initAll()` 之前构造，但保险起见），
**则** ViewModel**必须**不抛未捕获异常，把 UI 事件管道设为空流即可。

### EARS-RE-05

**当** `App.initAll()` 因致命错误（如 `LocalLlmEngine.ensureLoaded()` 抛 OOM）走到 `onFailure` 分支时，
**则** `Log.e`**必须**记录 `feature` 名 + 异常类型 + message；
**并且** `CrashStore.record(severity = CRASH, kind = "app_init", feature = "engine")`**必须**被调用一次，
**并且不**进入崩溃循环（因为 `CrashHandler.install` 已经先跑过，第二次启动还会再失败 —— 这是可接受的，我们只确保不引入新的崩溃循环）。

### EARS-NFR-01

- 全部改动控制在 `App.kt` + `MainActivity.kt` + `AgentViewModel.kt`，不改动其他模块。
- 不引入新依赖。
- 必须新增 `AppInitTest` 单测：模拟 `initAll()` 抛 `RuntimeException("engine OOM")`，断言：
  - `app.engine` 返回 `null`，不抛 `UninitializedPropertyAccessException`；
  - `app.crashStore` 不为 `null`；
  - `app.storage` 不为 `null`；
  - `app.uiEvents` 返回空流；
  - `app.uiNotifier()` 不抛；
  - 日志中包含 `"app_init engine"` 字样。

### EARS-NFR-02

- 不修改现有 164 个单测的行为。
- 不引入新的 Robolectric（避免本地 SDK 19 下载问题），`AppInitTest` 用纯 JVM `mock` + `lateinit` 默认值即可。

## 不在范围内

- `LocalLlmEngine.ensureLoaded()` 的失败处理本身（M14 / native 层另行处理）。
- 多进程并发初始化。
- 把所有 `lateinit var` 全部包成 `lazy`（过度重构，不在 M10 范围内）。

## 测试矩阵

| 用例 | 步骤 | 期望 |
|------|------|------|
| 正常初始化 | Robolectric `Application` + 真实 `initAll` | 全部字段非空，agent 跑通 |
| 引擎 OOM | mock `LocalLlmEngine.ensureLoaded()` 抛 `OutOfMemoryError` | `engine == null`，`agent == null`，`crashStore != null`，`storage != null`，`uiEvents` 为空流，日志含 `"engine OOM"` |
| repo 抛错 | mock `CodeRepository` 构造抛 `IOException` | `repo == null`，后续依赖 `repo` 的 `agent`/`plugins`/`scriptHost` 也为 `null`，但 `crashStore != null` |