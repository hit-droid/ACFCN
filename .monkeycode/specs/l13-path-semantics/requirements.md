# L13 — `localPathFor` 与 `materialize` 路径语义不一致

## 背景

`LocalModelStore` 里 `localPathFor(id)` 只检查 `modelsDir` 里是否存在已拷贝的文件，返回拷贝路径；而 `materialize(id)` 第一步会通过 `resolveRealPath` 尝试直接解析 content URI 指向的真实文件路径（Downloads/Documents 等走 `_data` 列时常见），不拷贝即可打开。

后果：对同一个模型，问"是否已有本地可加载路径"时，
- `materialize` 认为可加载（返回真实路径），
- `localPathFor` 返回 `null`（它只认拷贝），
两者语义不一致，调用方拿到矛盾答案。

## 目标

1. 提供单一事实来源：一个"不拷贝也能判断模型能否本地加载、并给出将实际被打开的真实路径"的 API。
2. 保留 `localPathFor` 的原有语义（"是否存在 app 私有拷贝"），供 `clearModelCopies` 统计可释放的拷贝数使用——那条语义本来就对。
3. 不改变 `materialize` 的拷贝/进度行为。

## EARS 需求

### EARS-RE-01

**当** 调用 `loadablePathFor(id)` 且模型存在时，
**则** **必须**优先返回 content URI 可解析且可读的真实路径（与 `materialize` 第一步一致）；
**并且** 若该路径不可用，**必须**回退返回 app 私有目录中已存在的拷贝路径（与 `materialize` 第二步一致）；
**并且** 两者皆无时返回 `null`。

### EARS-RE-02

**当** 调用 `localPathFor(id)` 时，
**则** **必须**只返回 app 私有目录中已存在的拷贝路径（语义不变，仍用于统计"已拷贝/可删除"）。

### EARS-RE-03

**当** `clearModelCopies()` 统计可释放拷贝数量时，
**则** **必须**继续用 `localPathFor != null` 判定（真实路径的模型不占 app 存储，不计入）。

## 不在范围内

- 修改 `materialize` 的拷贝/进度语义。
- 引入缓存/URL 规范化。

## 测试矩阵

| 用例 | 步骤 | 期望 |
|------|------|------|
| 无真实路径、无拷贝 | `loadablePathFor(id)` | `null` |
| 无真实路径、有拷贝 | `loadablePathFor(id)` | 返回拷贝路径 |
| 有真实可读路径 | `loadablePathFor(id)`（mock `resolveRealPath`） | 返回真实路径 |
| 拷贝计数 | `clearModelCopies` 用 `localPathFor != null` | 只计 app 私有拷贝 |