# L10 — 插件安装无签名/完整性校验

## 背景

`install_plugin` 工具接受 base64 的 dex payload，`CodeRepository.installPlugin` 直接落盘 `plugins/<name>.dex`。之后 `PluginRegistry.load` 用 `DexClassLoader` 加载该 dex。整个过程没有任何完整性校验：

- 落盘文件被篡改/损坏（磁盘、中间人改 payload、其他工具改文件）时，仍会加载任意字节；
- 无法区分"损坏导致加载失败"与"被替换成恶意 dex"。

## 目标

1. 安装时记录 dex 的 SHA-256 摘要，加载前验证磁盘上的 dex 仍与摘要一致。
2. 不匹配时拒绝加载并给出可读原因，避免加载被篡改的字节码。
3. 不引入完整公钥签名体系（密钥管理对本地自用 app 属于过度设计），低严重度按比例修复。

## EARS 需求

### EARS-RE-01

**当** `CodeRepository.installPlugin(name, dexBytes, entryClass)` 被调用时，
**则** **必须**计算 `dexBytes` 的 SHA-256 摘要并持久化为 `plugins/<name>.sha256`。

### EARS-RE-02

**当** `PluginRegistry.load(name)` 被调用时，
**则** **必须**先调用完整性校验；
**并且** 若校验失败，**必须**抛出带可读原因的异常，**并且不**加载该 dex。

### EARS-RE-03

**当** `CodeRepository.verifyPluginIntegrity(name)` 被调用时，
**则** 若 dex 缺失、摘要缺失、或 dex 的 SHA-256 与记录不一致，
**则** **必须**返回非 null 的失败原因字符串；
**并且** 校验通过时返回 `null`。

### EARS-RE-04

**当** `deletePlugin(name)` 被调用时，
**则** **必须**一并删除 `.dex`、`.entry`、`.sha256` 三个文件。

## 不在范围内

- 公钥/私钥签名与信任锚。
- 插件来源白名单/审计。
- 对已存在但无摘要的插件自动补摘要（要求重装）。

## 测试矩阵

| 用例 | 步骤 | 期望 |
|------|------|------|
| 正常安装 | `installPlugin` + `verifyPluginIntegrity` | 通过，返回 `null` |
| 篡改 dex | 安装后改写 `.dex` 再校验 | 失败，含 "integrity check failed" |
| 缺失摘要 | 直接写 `.dex` 不安装再校验 | 失败，含 "no recorded integrity digest" |
| 缺失 dex | 校验不存在的插件 | 失败，含 "dex missing" |
| 删除清理 | `deletePlugin` | `.dex/.entry/.sha256` 全删 |
| 重装恢复 | 篡改后重装原字节 | 校验恢复通过 |