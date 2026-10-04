# L10 设计 — 插件完整性校验

## 方案

1. `CodeRepository.installPlugin`：写入 `.dex` 后计算 SHA-256（`MessageDigest`，hex）写入 `plugins/<name>.sha256`。
2. `CodeRepository.verifyPluginIntegrity(name)`：返回 `null` 或失败原因字符串（dex 缺失 / 无摘要 / 摘要不匹配）。
3. `PluginRegistry.load`：先 `verifyPluginIntegrity`，非 null 则 `error(issue)`，阻止加载。
4. `deletePlugin`：删除 `.dex/.entry/.sha256`。

## 关键决策

### 决策 1：SHA-256 完整性而非 PKI 签名

低严重度 + 本地自用 app，公钥签名需要密钥托管、信任锚、安装流程改造，收益不匹配。完整性校验能拦截篡改/损坏，符合"按比例修复"。

### 决策 2：校验放在 `load` 而非 `install`

安装时字节来自工具调用方（agent 自己），真实威胁在"落盘后到加载前"的磁盘篡改。所以记录在 install、校验在 load。

### 决策 3：无摘要的旧插件拒绝加载并要求重装

存量插件没有 `.sha256`，一律视为不可信。信息明确提示 reinstall。

## API 变化

```kotlin
// CodeRepository
fun pluginDigest(name: String): String?
fun verifyPluginIntegrity(name: String): String?   // null = OK，否则失败原因
// installPlugin / deletePlugin 行为扩展（写/删 .sha256）
```

## 文件清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/selfmod/agent/repo/CodeRepository.kt` | `installPlugin` 写摘要；新增 `pluginDigest`/`verifyPluginIntegrity`；`deletePlugin` 删摘要；`sha256Hex` 私有辅助 |
| `app/src/main/java/com/selfmod/agent/plugin/PluginRegistry.kt` | `load` 前校验，失败抛异常 |
| `app/src/test/java/com/selfmod/agent/repo/CodeRepositoryPluginIntegrityTest.kt` | 新增 6 个纯 JVM 用例（`TemporaryFolder` 构造目录） |

## 测试策略

- `CodeRepository` 构造只需 `File`，纯 JVM 可测，不引 Robolectric。
- 6 个用例覆盖：正常 / 篡改 / 缺摘要 / 缺 dex / 删除清理 / 重装恢复。

## 风险

- 存量插件无摘要会被拒载 → 用户重装即可；app 自带/示例插件若受影响需一并重装。
- 校验读取整个 dex 计算哈希，对大 dex 有少量 IO 开销，仅发生在 load 时一次。

## 回滚

`git revert` 即可；改动局限 3 个文件。
