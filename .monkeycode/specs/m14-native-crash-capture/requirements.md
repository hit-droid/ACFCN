# M14 — native SIGSEGV/SIGABRT/SIGBUS 崩溃 CrashStore 捕获不到

## 背景

`CrashHandler.kt` 只注册了 Java 层 `Thread.setDefaultUncaughtExceptionHandler`。当崩溃发生在 native 层时（llama.cpp / `acfcn_llm` JNI bridge：GGUF 张量形状损坏、mmap 失败、模型文件损坏、`nativeChat` 内解码越界等），信号直接终止进程：

- Java `uncaughtException` 回调**不会触发**；
- 进程静默死亡，`CrashStore` 里没有报告；
- 下次启动 `MainActivity` 检查 `crashStore.read() == null`，用户看到的是"突然闪退"且没有解释，无法复现、无法分享堆栈。

## 目标

1. native 层崩溃时，至少在 `last_crash.txt` 落一条最小报告，让下次启动走 **同一个** 崩溃展示路径（`CrashActivity`）。
2. 处理程序必须 async-signal-safe：崩溃上下文里不许 malloc / std::string / JNI / `__android_log_print`，否则二次崩溃。
3. 不吞信号：写完报告后恢复默认处置并 re-raise，进程照常死亡，`debuggerd` tombstone 正常生成。
4. 不影响 Java 层崩溃捕获、不影响 $NN 层正常执行路径性能。

## EARS 需求

### EARS-RE-01（普遍要求）

**当** 进程收到 `SIGSEGV` / `SIGABRT` / `SIGBUS` 且场景与 `acfcn_llm`（llama.cpp）相关时，
**则** 已注册的 native 信号处理程序**必须**：

- 检测到 crash 目录已配置后，
- 用 async-signal-safe 调用（`open`/`write`/`close`）向 `<crashDir>/last_crash.txt` 写入最小报告，
- 报告**必须**包含：来源标记 `native signal`、信号编号、以及"崩溃位于 native LLM 层"的描述，
- 然后恢复该信号的默认处置并 re-raise（`raise(sig)`），
- 不允许逻辑在 re-raise 前返回（否则信号会再次进入我们的 handler）。

### EARS-RE-02

**当** Java 层通过 `LocalLlmEngine.setNativeCrashDir(dir)` 告知 crash 目录时，
**则** 该路径**必须**在加载任何模型之前写入 native 全局状态；
**并且** 仅当 `System.loadLibrary` 成功后才调用（否则静默跳过）；
**并且** 函数对重复调用**必须**幂等。

### EARS-RE-03

**当** `sigaction` 安装信号处理程序时，
**则** 必须使用 `SA_SIGINFO` 并传入 `siginfo_t`；
**并且** 不得改变除 `SIGSEGV`/`SIGABRT`/`SIGBUS` 之外任何信号的处置。

### EARS-RE-04

**当** native 崩溃报告写入成功后，
**则** `MainActivity` 的现有崩溃检测**必须**不做任何修改即可展示该报告
（**即** 复用 `crashStore.read()` 对 `last_crash.txt` 的读取逻辑）。

### EARS-NFR-01

- 报告中出现"signal: <编号>"，编号为十进制；`SIGSEGV=11, SIGBUS=7, SIGABRT=6`。
- 处理程序体内禁止任何堆分配、异常、日志宏、JNI 调用。
- 不引入新依赖（不需要 `libunwind`、不需要 LLVM 符号化）。
- 必须在原生编译（`assembleDebug` 走 CMake）时通过，且不破坏现有 176+ 个 JVM 单测。
- 本地排除 `AgentCoreTest`（Robolectric SDK 19 下载挂起），CI 全量跑。

## 不在范围内

- 完整 native backtrace / 寄存器快照 / so 符号化 —— 留给 `debuggerd` tombstone。
- Java 层更多防护（M10 已处理 lateinit 失败）。
- 把 `last_crash.txt` 的格式升级为结构化 JSON。

## 测试矩阵

| 用例 | 步骤 | 期望 |
|------|------|------|
| 编译 | `./gradlew assembleDebug`（CMake/NDK） | 通过 |
| JVM 套件回归 | `./gradlew testDebugUnitTest`（排除 AgentCoreTest） | 全绿，数量不下降 |
| 崩溃路径（真机 CI） | 构造损坏 GGUF 触发 `nativeChat` SIGSEGV | `last_crash.txt` 存在，含 `native signal` 与 `signal: 11`；进程死亡；下次启动进 `CrashActivity` |
| 正常路径 | 模型加载 + 正常对话 | 无额外文件写入，无性能回退 |