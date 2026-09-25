# ACFCN

ACFCN 是一个运行在 Android 上的**自进化智能体**应用：它能编写/修改自己的脚本、动态加载插件、操作用户共享的内置浏览器，并且**支持完全离线使用**——导入本机 GGUF 模型后，通过本机 OpenAI 兼容运行时（Ollama / llama.cpp / LM Studio / Jan）驱动智能体调用工具。

## 功能

- **智能体 Tab**：思考-行动-观察循环；流式输出；停止 / 重试；会话自动持久化。
- **浏览器 Tab**：常驻 WebView，用户与智能体共用；按编号元素操作（snapshot/click/type/extract）。
- **离线 Tab**：扫描本机推理端口、导入 GGUF/ONNX（解析架构 / 量化 / 上下文 / 参数量）、一键接入。
- **脚本 Tab**：沙箱内执行 JavaScript（Rhino），持久化脚本 + 自动版本回滚。
- **插件 Tab**：运行时通过 DexClassLoader 加载 `.dex` 插件。
- **API Tab**：云端 / 本机预设一键接入，测试连接、列出模型、配置档案；**API Key 使用系统密钥库加密存储**。

## 离线使用

1. 把 `.gguf` 拷到手机，离线页导入。
2. 用 Ollama `ollama create` 或 llama.cpp `-m` 加载该文件，启动本机服务。
3. 离线页扫描本机端口并「接入」，打开离线模式。
4. 智能体即可在无外网时调用脚本、插件与内置浏览器。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 34）。

```bash
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/`。

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

端侧内嵌推理（llama.cpp JNI 直接跑 GGUF）尚未实现，当前离线路径是「导入权重 + 本机兼容服务」。
