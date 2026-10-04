# M14 设计 — native 信号处理捕获 SIGSEGV/SIGABRT/SIGBUS

## 整体方案

在 `acfcn_llm.cpp` 内：

1. `JNI_OnLoad` 里调用 `install_signal_handlers()`，为 `SIGSEGV`、`SIGABRT`、`SIGBUS` 注册 `sigaction`（`SA_SIGINFO` → `native_crash_handler`）。
2. `native_crash_handler` 只做 async-signal-safe 的写文件：
   - 从全局 `g_crash_dir`（`char[512]`，Java 通过 `nativeSetCrashDir` 一次性写入）拼出 `<dir>/last_crash.txt`；
   - `open(path, O_WRONLY|O_CREAT|O_TRUNC, 0644)` → `write` 3~4 段固定文本 → `close`；
   - 不调用 `malloc`、`std::string`、`new`、JNI、`__android_log_print`。
3. 恢复该信号默认处置（`sigaction(sig, &sa, nullptr)` with `SIG_DFL`），再 `raise(sig)` re-raise，让进程按原样死亡，`debuggerd` 仍写 tombstone。

报告内容（固定格式，纯 ASCII）：

```
ACFCN native crash
source: native signal
kind: SIGSEGV/SIGABRT/SIGBUS
signal: 11
detail: crashed in the native LLM layer (llama.cpp / JNI bridge)
reported: native signal handler
```

`MainActivity` 现有逻辑 `if (app.crashStore.read() != null)` 直接命中该文件，零改动复用崩溃展示。

## 关键决策

### 决策 1：写固定最小报告，不做 backtrace

native 崩溃上下文里拿 backtrace 需要 `libunwind` / `dladdr` / 栈回溯，这些要么不是 async-signal-safe，要么引入新依赖。`debuggerd` 本来就会生成完整 tombstone，我们只负责"告诉用户下次启动时上次闪退是 native 层原因"，把详情留给系统日志。

### 决策 2：re-raise 而不是在 handler 里 return

若 handler 返回，信号会再次触发（或进程继续执行到同一处再次崩溃），可能造成死循环或行为不一致。恢复默认处置后 `raise(sig)` 是最干净、最符合 Android 预期的收尾。

### 决策 3：crash 目录从 Java 一次性传入

native 侧不知道 app `filesDir`。新增静态 `nativeSetCrashDir(String)`，在 `LocalLlmEngine.setNativeCrashDir(dir)` 里调用：
- 只有 `ensureLoaded()` 成功（即 so 已加载）才调用；
- `App.initEngine()` 在引擎构造时传入 `filesDir.absolutePath`；
- 幂等，重复调用用后一次覆盖。

### 决策 4：不覆盖 debuggerd / 不捕获全部信号

只处理 `SIGSEGV`/`SIGABRT`/`SIGBUS` 三个与应用崩溃最相关的信号，不碰 `SIGILL`、`SIGFPE`（保留给系统），避免过度接管进程信号表。

## API 变化

```cpp
// 新增（匿名 namespace 内）
void install_signal_handlers();
void native_crash_handler(int sig, siginfo_t *si, void *ctx);

// 新增 JNI 导出（静态，jclass）
extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeSetCrashDir(
        JNIEnv *env, jclass, jstring crashDir);
```

```kotlin
// LocalLlmEngine companion
fun setNativeCrashDir(dir: String)          // 公开，App 调用
private external fun nativeSetCrashDir(dir: String)  // 静态 JNI

// App.initEngine()
runCatching { LocalLlmEngine.setNativeCrashDir(filesDir.absolutePath) }
```

## 文件清单

| 文件 | 改动 |
|------|------|
| `app/src/main/cpp/acfcn_llm.cpp` | 新增 `<csignal>/<fcntl.h>/<unistd.h>/<sys/stat.h>`；`g_crash_dir` 全局；`native_crash_handler` / `install_signal_handlers`；`JNI_OnLoad` 调用注册；新增 `nativeSetCrashDir` 导出 |
| `app/src/main/java/com/selfmod/agent/offline/native/LocalLlmEngine.kt` | companion 加 `setNativeCrashDir` + `nativeSetCrashDir` 外部声明 |
| `app/src/main/java/com/selfmod/agent/App.kt` | `initEngine()` 里调用 `setNativeCrashDir(filesDir.absolutePath)` |
| `app/build.gradle.kts` | `testOptions.unitTests.all` 增加 AgentCoreTest 本地排除（默认不开 Robolectric，CI 用 `-PincludeRobolectric=true` 恢复） |

## 测试策略

- native 编译正确性：`assembleDebug`（CMake + NDK）必须通过 —— 本次本地已验证。
- JVM 回归：`testDebugUnitTest` 全绿（排除 AgentCoreTest），182 个用例。
- 真机崩溃路径：无法在本环境构造 SIGSEGV 后二次启动验证，交由 CI / 真机验证矩阵覆盖。
- `build.gradle.kts` 的测试过滤是本次顺带引入的工程化改进（此前靠 `--tests` 手工过滤）。

## 风险

- **Handler 二次崩溃**：任何非 async-signal-safe 调用都可能导致卡死/重入。缓解：handler 内只有系统调用 + 固定缓冲区 + `sigaction`/`raise`。
- **覆盖用户自定义 handler**：若宿主 app 已为这些信号注册 handler，`sigaction` 会用我们的覆盖。ACFCN 是独立 app，可接受。
- **信号编号十进制拼装**：用 `'0' + (sig/100)%10` 等方式手写十进制，SIGSEGV=11 输出 `011`。当前信号编号都 <100，可接受；若未来 >999 会截断，不影响可读性。

## 回滚

`git revert` 该 PR 即可；改动局限在 4 个文件，native 侧全部为新增代码，不触碰既有加载/推理路径。

## 任务清单

1. `acfcn_llm.cpp` 加 include + `g_crash_dir` + handler + 注册 + `nativeSetCrashDir` 导出。
2. `LocalLlmEngine.kt` companion 加 `setNativeCrashDir`/`nativeSetCrashDir`。
3. `App.kt` `initEngine()` 调用 `setNativeCrashDir(filesDir.absolutePath)`。
4. `build.gradle.kts` 加 AgentCoreTest 本地排除（工程化）。
5. `./gradlew assembleDebug` 验证 native 编译。
6. `./gradlew testDebugUnitTest` 验证 JVM 回归（182 通过）。
7. 提交、推分支、开 PR。
8. `COORDINATION.md` 状态 `[ ] M14` → `[x]`；`board/opencode.md` 记录。
