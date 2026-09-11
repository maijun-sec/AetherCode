# R20 路线图 — TUI 改造 + 10 小时长任务引擎 (2026-08-06)

## 目标

把 AetherCode 提升到大规模任务自动化执行的水平：

1. **TUI** — 页面非常好看 + 功能好用。参考 Claude Code / OpenCode / MiniMax Code 的 UI 范式。
2. **后端** — 10 小时不间断长任务执行。模型自规划 → 创建待执行任务列表 → 引擎驱动任务执行。

R19 baseline: **1227 tests / 14 modules / 0 regression**。

## 关键痛点 (R19 现状)

| 维度 | 现状 | 缺口 |
|------|------|------|
| TUI 渲染 | 行式 REPL + ANSI 码，逐行 append | 无全屏 alt-buffer，每次都是「print」而非「repaint」 |
| 实时进度 | 只有 "  … connecting …" 提示 | 无 token 计数 / elapsed / 当前工具 |
| 任务持久化 | `TaskRegistry` 纯内存 | 进程崩溃 = 任务丢失 |
| 计划执行 | 计划批准后靠 model 自己驱动 | 无引擎级 orchestration |
| 上下文压缩 | 单次摘要，重复增长 | 摘要自己也会爆上下文 |
| 失败重试 | 无 | 单点失败 = 全盘失败 |
| 监督 / checkpoint | 无 | 10 小时无声 hang = 不可恢复 |

## 10 轮方案（交替 TUI / 后端，每轮独立可验证）

### R20-A: TUI 全屏框架
- 切到 alt-buffer，全屏 repaint
- 布局：header（logo/model/session）+ 主区（scrollback）+ 底栏（token / tool / elapsed）
- 兼容：`--no-fullscreen` 回退行式
- **Tests**: 8-10（Screen redraw / layout / status bar update）

### R20-B: 实时进度
- 查询期间 status bar 显示 `▶ <tool> <preview>`、`▓▓▓░░ 23K/200K`、elapsed `01:23`
- 后端：新增 `StreamEvent.Progress` 类型（model + tool 进度）
- **Tests**: 6-8（progress widget 渲染 / event 序列）

### R20-C: 配色 + 边框 + 启动 banner
- 统一调色板（muted purple + warm yellow + cyan accent，参考 Claude Code 风格）
- 面板用 Unicode 边框（┌─┐│└┘├┤┬┴┼）
- `/help` 用 banner 渲染
- **Tests**: 6-8（panel / banner / theme 切换）

### R20-D: 持久化 TaskRegistry
- 每次变更写 `~/.aethercode/tasks/<sessionId>.jsonl`
- 启动时 `TaskRegistry.restore(sessionId)` 重建
- 监听器在 restore 期间触发
- **Tests**: 8-10（写入 / 读取 / 崩溃恢复 / 监听器触发）

### R20-E: PlanExecutor
- `engine.executePlan(plan)` 按步骤驱动执行
- 每步 = 一次 query，引擎把当前步骤作为 active TODO
- 失败 / 跳过 / 重试由引擎控制
- TUI: 当前步骤在 todos 面板高亮
- **Tests**: 10-12（顺序执行 / 跳过 / 中止 / 恢复）

### R20-F: 滑动窗口 + 摘要链
- 新 `SlidingWindowCompactor`：保留最近 N 条原文 + 多个摘要块
- 摘要自身作为「对话记忆」喂给模型
- 防止单次摘要无限增长
- **Tests**: 10-12（链式压缩 / 摘要大小 / 上下文恒定）

### R20-G: 任务树可视化
- tasks 面板用 box-drawing 渲染树（└── / ├── / │）
- 显示 child count、elapsed、retry count
- plan 执行时主区显示进度条
- **Tests**: 8-10（树渲染 / 嵌套 / 计时格式）

### R20-H: 自动规划 + 自动批准
- 新 `PlanExecuteMode`：模型返回 plan 后，引擎判断
  - 简单 plan（≤3 步，无破坏性工具）→ 自动批准 + 自动执行
  - 复杂 plan → 提示用户
- 新命令 `/auto` 开关
- **Tests**: 10-12（简单 / 复杂判定 / 用户提示 / 执行流）

### R20-I: Subagent 池 + 重试
- 失败步骤用指数退避重试（1s, 2s, 4s, 8s, max 5 次）
- 子代理池大小限制（max 4 并发）
- 上抛父任务时记录原因
- **Tests**: 10-12（重试 / 退避 / 上限 / 池限制）

### R20-J: 监督 + Checkpoint + 恢复
- Watchdog：60s 无事件 → ping 模型或 abort
- Checkpoint：每 N 步写 `~/.aethercode/checkpoints/<taskId>.json`
- `/resume <taskId>` 从 checkpoint 恢复
- **Tests**: 12-14（watchdog 触发 / checkpoint 往返 / 恢复）

## 验证指标

- 进度：每轮 +N 测试，0 regression
- T20 末：~1320+ tests
- 端到端：模拟 10 小时长任务（时间压缩），验证不爆 / 可恢复
- 视觉：TUI 截图 + banner

## 实施次序

按上表顺序执行。每轮独立可验证。每 5 轮写一次 retrospective。

## 关联

- R19 完成：streaming / memory / plan-todo / multi-step subagent / session search / custom agents / bash streaming / multimodal / web search / 性能
- 上一轮回顾：R19 累计 +51 测试，无退化
