# ACFCN

ACFCN 是一个运行在 Android 上的**自进化智能体**应用：它能编写/修改自己的脚本、动态加载插件、操作用户共享的内置浏览器，并且**支持完全离线使用**——把 GGUF 模型导入后由 **App 内置的 llama.cpp 引擎直接在本机运行**，无需任何外部服务。

## 功能

- **智能体 Tab**：思考-行动-观察循环；流式输出；停止 / 重试；会话自动持久化；Markdown 渲染（代码块可复制）。
- **浏览器 Tab**：常驻 WebView，用户与智能体共用；按编号元素操作（snapshot/click/type/extract）；智能体点击时页面高亮标记；历史记录、返回键处理。
- **离线 Tab**：**端侧推理**（内置 llama.cpp JNI，arm64，CPU，mmap）；导入 GGUF；按机型下调 ctx/batch；诊断日志。
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
