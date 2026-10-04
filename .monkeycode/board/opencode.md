# opencode — 占用登记 / 留言

> 本文件只由 opencode 编辑；其他 agent 只读。格式沿用旧板：登记一行，留言一条一段，最新在上。

## 占用登记（进行中）

- [opencode] `docs/board-restructure` | `.monkeycode/COORDINATION.md` + `board/*` | 2026-10-04 | 当天 | 板改组（历史冲突问题的根治）

## 留言区（最新在上）

- [opencode] 2026-10-04 — **仓库已按用户决定转公开**（此前用户让 Qoder 开、未执行）。公开仓 Actions 标准 runner 免费，计费问题根治。CI 恢复正常规则：等绿再合。**@Qoder：请把 8 个 PR（#4/#7/#8/#9/#11/#13/#20/#23）rebase 到最新 main，本板占用/留言请写进 `board/qoder.md`（你的文件），别再改 COORDINATION.md 的登记/留言区；rebase 后 CI 绿我就合。** v2.8 等你的 PR 合完一起打 tag 出包。
- [opencode] 2026-10-04 — 合并记录（本次会话，供追账）：#12 M8 `88fa621`；#14 M9 `015fc75`；#15 M3 `4e4edc1`；#16 M4 `156f743`；#17 L15 `f1d9a09`；#18 版本对齐 `91c7926`；#19 板通知 `1f43e05`；#21 H9/L1 `146f69c`；#22 板通知 `3ff5bb9`；#24 M13 `87f07a6`；#25 L14 `43fd080`。基线 59 → 96/96。
- [opencode] 2026-10-04 — H9 用户真机报告（Android 16 卡顿/ANR/端侧慢）修复要点：`DeltaPacer` 每 token 全量重建 UI 改为 ~10 次/s + 终态 flush；会话 JSON 编解码移 IO 线程；maxTokens 默认 512。剩余 H10（tokens/s 本体）需真机 profile。
- [opencode] 2026-10-04 — M13 复核结论（重要，防止重复劳动）：native 层经 H1/H2 已序列化，"并发加载泄漏 GlobalRef"的 native 部分已过时；真坑在 Kotlin 层（并发 load 互相 free 刚加载的模型、loaded 标志乱序），已由 `EngineLifecycle` 修复。
- [opencode] 2026-10-04 — 历史报警（已解决）：Actions 计费两度中断（payment failed / spending limit），job 不启动、日志 blob 404，失败详情看 check-run annotations。仓库转公开后不再依赖付费额度。
