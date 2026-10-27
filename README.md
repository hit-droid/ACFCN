# ACFCN

ACFCN 是一个运行在 Android 上的**自进化智能体**应用：它能编写/修改自己的脚本、动态加载插件、操作用户共享的内置浏览器，并且**支持完全离线使用**——把 GGUF 模型导入后由 **App 内置的 llama.cpp 引擎直接在本机运行**，无需任何外部服务。

## 功能

- **智能体 Tab**：思考-行动-观察循环；流式输出；停止 / 重试；会话自动持久化；Markdown 渲染（代码块可复制）。
- **浏览器 Tab**：常驻 WebView，用户与智能体共用；按编号元素操作（snapshot/click/type/extract）；智能体点击时页面高亮标记；历史记录、返回键处理。
- **离线 Tab**：**端侧推理**（内置 llama.cpp JNI，arm64，mmap；ARM dotprod CPU 加速 + Adreno GPU 自动，见下）；导入 GGUF；按机型下调 ctx/batch；诊断日志。
- **脚本 Tab**：沙箱内执行 JavaScript（Rhino），持久化脚本 + 自动版本回滚。
- **插件 Tab**：运行时通过 DexClassLoader 加载 `.dex` 插件。
- **API Tab**：云端 / 本机预设一键接入，测试连接、列出模型、配置档案；**API Key 使用系统密钥库加密存储**；存储空间查看与清理。
- **稳定性**：全局崩溃捕获 + 崩溃报告页（可复制/分享）。

## 离线使用（端侧，推荐）

1. 从推荐列表下载一个 `.gguf`（建议 1.5B / Q4_K_M 起步）到手机。
2. 离线页「从文件导入」选择该文件。
3. 点对应模型的**「在本机加载」**（会先复制到 App 目录，带进度）。
4. 加载完成后即可对话，全程不联网。

> 端侧仅支持 arm64-v8a。模型越大越吃内存：2B 需约 3GB 空闲内存。

### 端侧加速（CPU dotprod + GPU 自动）

构建默认启用 ARMv8.2 的 `dotprod`/`fp16` 内核，量化（Q4_K）矩阵乘走 SDOT/UDOT，比 NDK 默认的 armv8-a 基线快数倍。

Release 包内置 llama.cpp 的 OpenCL 后端（Adreno）。模型加载时默认尝试把全部层放到 GPU；GPU 后端初始化失败会自动回退到上面的优化 CPU 路径，无需手动切换。如需强制指定，用运行时开关（下次加载模型生效）：

```bash
# 强制纯 CPU
adb shell setprop debug.acfcn.gpu_layers 0

# 强制全部层走 GPU
adb shell setprop debug.acfcn.gpu_layers 99

# 覆盖线程数（默认按 CPU 核数）
adb shell setprop debug.acfcn.threads 8

# 覆盖 prefill 批处理规模（n_batch == n_ubatch，默认按机型 RAM 分档）
# 长 prompt 时加大可显著加速 prefill（更少的 decode 调用）；真机 A/B 调优时
# 不用重新打包，下次加载模型生效
adb shell setprop debug.acfcn.batch 512
```

### 端侧加速（大核线程策略）

多数 Android 手机是 big.LITTLE 架构：几个高频「大核」+ 一堆低频「小核」。llama.cpp 的 worker 线程池默认会铺满所有核，而解码是计算密集的，慢的小核会拖累每一个 token。ACFCN 在加载模型时把计算线程池**钉在最快的大核上**：

- 默认（不设 `debug.acfcn.cpu_affinity`）按 `/sys` 的 `cpu_capacity` / `cpuinfo_max_freq` **自动识别大核簇**并钉选——所以对任何 big.LITTLE 机型都生效，而不只是某一台被调过的机器；
- 模型加载（`nativeInit`）时通过 `sched_setaffinity` 钉当前线程，Linux/Android 新线程继承创建者亲和性，于是 ggml 线程池在 `llama_init_from_model` 里生成时就自动落到大核，整个上下文生命周期内持续生效（无需侵入 ggml）；
- Kotlin 侧默认线程数也按识别到的大核数收敛（上限 4，见 `defaultThreads()`），避免大核数少于 4 时把线程铺到小核。

运行时开关（下次加载模型生效）：

```bash
# 强制自动识别大核（显式，等价于不设）
adb shell setprop debug.acfcn.cpu_affinity auto

# 手动指定核心（逗号分隔范围，覆盖自动识别；骁龙 8+ Gen1 即 0-3）
adb shell setprop debug.acfcn.cpu_affinity 0-3

# 关闭钉选，退回内核默认调度
adb shell setprop debug.acfcn.cpu_affinity none
```

离线页「诊断日志」加载后会多一行 `cpu_affinity: ...`，显示是自动识别到的大核簇、手动指定的掩码，还是因设备无拓扑信息跳过了钉选。

App 离线页「诊断日志」在加载后会显示：本构建是否支持 GPU offload、请求的 GPU 层数、CPU 指令集（确认 dotprod 已启用）、以及 llama.cpp 打印的 OpenCL 设备与实际放置到 GPU 的层数；生成后还会显示首 token、总耗时与解码 tok/s。以上均无需 adb。详细日志（含 llama.cpp/ggml/OpenCL 后端输出）：

```bash
adb logcat -s ACFCN-LLM
```

重点看 `n_gpu_layers=`、`OpenCL: using ICD ...`、`prefill done ... tok/s`、`decode done ... tok/s`。

### 端侧加速（批量 prefill 调优与诊断）

解码逐 token、瓶颈在 KV 带宽；而**首 token / 长 prompt 的 prefill** 是一次把整段提示词喂给模型，瓶颈在算力与每次 `llama_decode` 调用的固定开销。引擎把 `n_batch` 与 `n_ubatch` **设成同一值**（默认即如此），于是单次 `llama_decode` 就能并行处理整块提示词；prefill 循环按 `min(n_batch, 剩余)` 切块，所以：

- **所有长度的 prompt 调用次数都更少、首 token 延迟更低**（总计算量不变，但每次调用的固定开销与并行度更优）；`n_batch ≥ prompt 长度` 时整段一次喂完。
- 长 prompt 相对收益最大——例如 2000 token 在 64 下要约 32 次调用，512 下只要约 4 次（**约 8× 更少的 kernel 启动**），GPU 上每次调用固定开销占比更高、收益最大。

`n_batch` 越大，临时计算图缓冲（按 n_batch 个 token 规模分配，不是 KV 缓存）越大，但相对 KV 缓存只是零头。默认按机型 RAM 分档（见 `AgentViewModel`：6GB→32、7–10GB→128、11–19GB→256、≥20GB→512）。本版新增 **`debug.acfcn.batch` 运行时覆盖**（上限抬到 2048），方便**真机 A/B 验证 prefill 提速而无需重新打包**：

```bash
# 真机 A/B：对比默认与加大批处理的 prefill tok/s（看 logcat 的 prefill done ... tok/s）
adb shell setprop debug.acfcn.batch 512
# 恢复默认（清掉属性，下次加载模型生效）
adb shell setprop debug.acfcn.batch ""
```

离线页「诊断日志」加载后会多一行 `prefill_batch: n_batch=... n_ubatch=...`，确认本次加载实际生效的 prefill 批处理规模与是否走了覆盖。

### 端侧加速（KV 缓存量化 + Flash Attention，CPU/GPU 统一）

解码是逐 token 的，**每生成一个 token 都要把整段 KV 缓存从头读一遍**，所以 KV 缓存的**带宽**是 decode 的瓶颈，KV 宽度决定每个 token 的成本。把 KV 缓存从 `f32` 量化到 `q8_0` 后宽度减到 1/4（ctx4096 的 4B 模型约 1.15GiB → 288MiB），叠加 Flash Attention 融合注意力，decode tok/s 明显提升。

- **v2.30 起 CPU 路径与 GPU 路径统一**：此前 CPU-only 模式（大模型、或 GPU offload 失败回退）被硬编码成 `f16` KV + 关 Flash Attention「以求可移植」，等于把 CPU 用户留在慢路径上。现在 `debug.acfcn.kv` / `debug.acfcn.fa` 在 CPU 与 GPU 上**同样生效**，且 **CPU 默认也走 `q8_0` KV + Flash Attention**（与 GPU 默认一致）——llama.cpp 的 CPU 后端同样实现了量化 KV 的 Flash Attention，arm64 上正确。仅当 `kv=f16` 时（含 OpenCL 上 `f16` 触发 E031.41 编译器崩溃那条老路径）才关 FA，自动规避历史坑。
- 构建失败（个别驱动编译不出量化 KV 的 FA 内核）会自动回退到 `f16` KV + 关 FA，**只会变慢、不会加载失败**。
- 离线页诊断日志会打印 `flash attn ... kv cache ... on ...`，一眼确认本次加载走的是哪条 KV/FA 路径（CPU 会显示 `on cpu`）。

```bash
# CPU/GPU 通用调参（下次加载模型生效，无需重打包）
adb shell setprop debug.acfcn.kv q8_0      # KV 缓存类型：q8_0(默认) | q4_0(更激进) | f16 | f32
adb shell setprop debug.acfcn.fa off       # 关 Flash Attention（回退 f16 KV + 无 FA 的老安全路径）
```

本地从源码构建时，如需自行验证 OpenCL 路径：

```bash
# 把 OpenCL 头 + 自建 ICD loader 装进 NDK（版本需与 build.gradle.kts 的 ndkVersion 一致）
./scripts/setup-opencl-ndk.sh arm64-v8a

# 开启 OpenCL 构建
ACFCN_OPENCL=true ./gradlew :app:assembleDebug
```

不设置 `ACFCN_OPENCL` 时构建为纯 CPU（默认行为），与旧版本完全一致。

## 离线使用（本机服务）

也可以用 Ollama / LM Studio / llama.cpp 起服务，在离线页扫描端口并「接入」。

## 构建

需要 JDK 17、Android SDK（compileSdk 34）、NDK 27 + CMake 3.22。

```bash
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 发布包（需配置签名，见下）
```

APK 输出在 `app/build/outputs/apk/`。

### Release 签名

通过环境变量或 gradle 属性注入，缺省时 release 走未签名：

```
ACFCN_KEYSTORE / ACFCN_STORE_PASSWORD / ACFCN_KEY_ALIAS / ACFCN_KEY_PASSWORD
```

GitHub Actions 中配置相应 secrets（`ACFCN_KEYSTORE_BASE64` 等）后，打 tag 会自动构建并发布签名包。

## 发行

推送 `v*` tag 会触发 GitHub Actions（`.github/workflows/release.yml`）自动运行单元测试、构建 APK 并作为 Release 资产上传：

```bash
git tag v1.1 && git push origin v1.1
```

## 技术栈

- Kotlin 2.0.20 / Jetpack Compose (Material 3) / AGP 8.5.2
- minSdk 26 / targetSdk 34
- OkHttp（OpenAI 兼容 + Ollama 原生 + llama.cpp 回退）
- Mozilla Rhino（脚本沙箱）
- androidx.security-crypto（密钥加密）

## 说明

端侧走内嵌 llama.cpp（CPU / arm64-v8a）。6GB 机建议 1B–1.5B Q4_K_M；2B 能加载，首 token 可能要几十秒。诊断日志在离线页可复制。
