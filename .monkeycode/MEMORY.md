# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-10-02
- Context: User clarified project ownership
- Instructions:
  - ACFCN (https://github.com/hit-droid/ACFCN) 是主要仓库，是 SelfModAgent 的完整演进版（同一 com.selfmod.agent 包）。后续开发以 ACFCN 为主。
  - SelfModAgent 也可以顺手完善，但不要投入太多时间。
  - 工作区 /workspace 现在克隆的就是 ACFCN 主仓库。
  - ACFCN 是私有仓库：GitHub API/网页会 404，但 git clone/ls-remote 正常（走凭据）。
  - 分身包名相同 com.selfmod.agent；端侧推理路线不同：ACFCN 用内置 llama.cpp JNI（app/src/main/cpp），SelfModAgent 用 MediaPipe/MNN。

[Project Knowledge Summary]
- Date: 2026-10-02
- Context: Discovered by Agent while setting up the ACFCN build environment from scratch
- Category: Environment Configuration / Build Methods
- Instructions:
  - 沙箱初始无 JDK/Android SDK。安装步骤：(1) `apt-get update && apt-get install -y openjdk-17-jdk-headless`（必须先 update）；(2) 下载 commandlinetools-linux-11076708_latest.zip，解压到 /opt/android-sdk/cmdline-tools/latest；(3) `yes | sdkmanager --licenses`；(4) `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.0.12077973" "cmake;3.22.1"`（ACFCN 需要 NDK27 + CMake3.22.1 编译 llama.cpp JNI）；(5) `echo "sdk.dir=/opt/android-sdk" > local.properties`。
  - Gradle wrapper 下载走不通：直接 curl 从 `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip` 下到 `~/.gradle/wrapper/dists/gradle-8.9-bin/<hash>/gradle-8.9-bin.zip`。
  - 仓库 gradle.properties 无代理；但构建时需显式关闭可能的代理：`-Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.nonProxyHosts="*"`。
  - 关键坑：Maven Central 在沙箱不稳定（repo1.maven.org 时通时断，repo.maven.apache.org 常被墙；dl.google.com 也会偶发 TLS handshake 失败）。可靠做法：用 init 脚本把 Central 的 url 改成 `https://maven.aliyun.com/repository/central`（阿里云镜像稳定），见 /tmp/opencode/mirror.init.gradle.kts（勿提交）。dl.google.com 偶发失败重试即可。
  - 实测可用命令：`./gradlew --no-daemon -I /tmp/opencode/mirror.init.gradle.kts -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.nonProxyHosts="*" :app:assembleDebug :app:testDebugUnitTest`。
  - ACFCN 基线验证（2026-10-02，HEAD=302301b=v2.6）：assembleDebug 成功，产出 34MB APK，含 `lib/arm64-v8a/libacfcn_llm.so`；testDebugUnitTest 23/23 通过（PromptTemplates 3、LlmConfig 2、StreamAssembler 4、ToolCallParser 4、GgufHeader 6、OnDeviceTemplates 4）。
  - 首次完整构建约 9 分钟，之后增量 ~2 分钟。
  - CI：.github/workflows/release.yml，推 v* tag 触发：跑单测 + assembleDebug + assembleRelease（验证 R8）+ 可选签名 + 发布 Release 资产。
