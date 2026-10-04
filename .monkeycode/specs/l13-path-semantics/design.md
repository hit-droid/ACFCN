# L13 设计 — 统一路径语义

## 方案

在 `LocalModelStore` 新增 `loadablePathFor(id: String): String?`：

```
resolveRealPath(uri) 可读？  → 返回该真实路径   （与 materialize 第一步一致）
否则 modelsDir 拷贝存在？   → 返回拷贝路径     （与 materialize 第二步一致）
否则                        → null
```

`localPathFor` 保持不变（只认 app 私有拷贝），语义在 KDoc 中明确为"是否存在可删除的拷贝"，`clearModelCopies()` 的统计继续用它，行为不变。

## 关键决策

### 决策 1：新增方法而非改 `localPathFor` 语义

`localPathFor != null` 在 `clearModelCopies` 里承担"该模型是否占用了 app 存储"的判定。若把真实路径也算进去，清理报告会虚报可释放量。新增 `loadablePathFor` 表达"能否本地加载"，`localPathFor` 保留"是否有拷贝"。

### 决策 2：逻辑与 `materialize` 前两步保持一致

`materialize` 也走"真实路径 → 拷贝 → 拷贝落盘"三步；`loadablePathFor` 覆盖前两步（只读、不触发拷贝）。这样任何调用方用 `loadablePathFor` 判断的结果与真的去 `materialize` 得到的路径一致。

## 文件清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/selfmod/agent/offline/LocalModelStore.kt` | 新增 `loadablePathFor`；`localPathFor` 补充 KDoc 说明语义边界 |

## 测试策略

- `LocalModelStore` 依赖 `Context`/`ContentResolver`/`SharedPreferences`，无既有纯 JVM 测试，不做 Robolectric。
- 回归：现有 182 测试全绿 + `assembleDebug` 通过。

## 回滚

`git revert` 即可，改动局限 1 个文件、纯新增方法。
