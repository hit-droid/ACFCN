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

[Project Knowledge Summary]
- Date: 2026-10-04
- Context: Discovered by Agent while running unit tests via background terminal (M8 branch)
- Category: Environment Configuration / Troubleshooting & Debugging
- Instructions:
  - 坑：`background_terminal_create` 没有 workdir 参数，继承持久 shell 的当前目录，且该 cwd 可能是 `/workspace.SelfModAgent.bak`（旧备份树），导致 Gradle 编译了备份目录、报 "No tests found"。**所有后台终端命令必须显式 `cd /workspace &&` 开头**。
  - `/workspace` 与 `/workspace.SelfModAgent.bak` 是两个不同目录（不同 inode）：前者是 ACFCN 主工作区，后者是 2026-10-02 之前的旧备份，勿混淆。
  - 提交前基线已更新：testDebugUnitTest 80/80（SessionCodec 7、OnDevicePrompts 7、SecretRedactor 6、CrashStore 6、StreamAssembler 8、ToolCallParser 8 等），`assembleRelease` 本地约 12 分钟可跑通。
  - 2026-10-04 起 GitHub Actions 因账户计费（payment failed / spending limit）全面中断，job 不启动、无日志无产物；修复前 PR 无法拿绿勾、v2.8 tag 无法发版。失败详情看 check-run annotations（logs blob 会 404）。

[Project Knowledge Summary]
- Date: 2026-10-22
- Context: Discovered while building ACFCN in the sandbox during the multi-session PR (#60); CMake FetchContent pulls llama.cpp from github.com, which times out in the sandbox
- Category: Environment Configuration / Build Methods / Troubleshooting & Debugging
- Instructions:
  - ACFCN 的 CMake 用 FetchContent 拉取 llama.cpp：`app/src/main/cpp/CMakeLists.txt` 中 `FetchContent_Declare(llama GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git GIT_TAG b11179 GIT_SHALLOW TRUE)`。任何触发 CMake 配置的操作（assembleDebug / assembleRelease，以及 testDebugUnitTest 也会触发 configure）都需要联网拉这个仓库。
  - 沙箱坑：github.com:443 直连不稳，匿名 `git clone` 与带 token clone 均超时；FetchContent 走原生 git 不带 token，且沙箱对 github.com 连接常中断 → CMake configure 卡在 FetchContent 几十分钟直至失败（表现为构建长时间无输出后报错）。
  - 本地镜像解法（稳定）：① `curl -L https://codeload.github.com/ggml-org/llama.cpp/tarball/b11179 -o /tmp/llama.tar.gz`（约 37MB，codeload 比 git clone 稳得多）；② 解压 `mkdir -p /tmp/llama-cpp && tar -xzf /tmp/llama.tar.gz -C /tmp/llama-cpp --strip-components=1`；③ 在 /tmp/llama-cpp 内 `git init && git add -A && git commit -m init && git tag b11179`（codeload tarball 不含 .git，必须手动打 tag 才能命中 GIT_TAG）；④ `git config --global url."file:///tmp/llama-cpp/".insteadOf "https://github.com/ggml-org/llama.cpp.git"`；⑤ 清 `app/.cxx` 缓存后重新构建，CMake 走本地镜像不再联网。
  - 该 `insteadOf` 是**全局** git 配置，仅沙箱本地需要，**切勿提交到仓库**；换机器或重搭环境需重新执行上述步骤。

[Project Knowledge Summary]
- Date: 2026-10-29
- Context: Discovered while rebasing onto main after the llama.cpp pin moved from b11640 to a master SHA (74bca395, v2.30)
- Category: Build Methods / Environment Configuration
- Instructions:
  - llama.cpp 的 pin 现在是 SHA（`GIT_TAG 74bca395...` + `GIT_SHALLOW TRUE`），不再是 tag 名。CMake 的 gitclone 脚本是 `clone --depth 1 --no-single-branch` 再 `checkout <SHA>`，所以本地镜像 `/tmp/llama-cpp` 里该 SHA **必须作为某个分支的 tip** 存在，否则报 `fatal: reference is not a tree` / `Failed to checkout tag`。
  - pin 变更后的修法：`cd /tmp/llama-cpp && GIT_CONFIG_GLOBAL=/dev/null git fetch --depth 1 https://github.com/ggml-org/llama.cpp.git <SHA>:refs/heads/pin-<短SHA>`（`GIT_CONFIG_GLOBAL=/dev/null` 用来临时绕过全局 `insteadOf` 重写，直连 github 可用）。
  - 拉完把旧的 `app/.cxx/Debug/*/arm64-v8a/_deps/llama-src` 移走（clone 目标目录非空会失败），再重新构建；换了 pin 后 C++ 全量重编，本地约 10 分钟。
  - main 的 `f51bf75`（v2.30）修了 Vulkan 头版本错配：CMake 现在把 `Vulkan_INCLUDE_DIR` 强制指向 NDK sysroot，前提是 `scripts/setup-vulkan-ndk.sh` 已把配套 vulkan.hpp/vulkan_core.h 铺进去。CI 与 release 都会先跑该脚本，本地若报 `Wrong VK_HEADER_VERSION!` 先补跑它。
