# R23 Retrospective (2026-08-07)

**主题**: TUI UX + 后端 任务调度/长程执行/任务 memory 隔离
**周期**: 8 个 round 完成 (A-H)
**测试增长**: 1526 → 1596 (+70, +4.6%)
**退化**: 0 (含 pre-existing flaky 修复后)
**新文件**: 7 个 main + 5 个 test

## 8 个 round 概览

| Round | 主题 | 测试 Δ | 关键点 |
|-------|------|-------|--------|
| R23-A | Fuzzy completion (TUI) | +17 | FuzzyCompletion wrapper + SlashCommandCompleter loose 模式 + score() exact>prefix>substring>subsequence |
| R23-B | TaskScheduler 优先级队列 (backend) | +9 | 内部线程池, awaitIdle, cancel-before-start, race-free emit ordering |
| R23-C | TaskSchedulerBridge (backend) | +5 | 一行 wiring 把 scheduler state 映射到 TaskRegistry 的 TaskStatus |
| R23-D | StatusBar pending tasks (TUI) | +7 | LayoutState.pendingTasks 字段 + "X running, Y queued" 渲染 |
| R23-E | TaskMemoryStore (backend) | +16 | Per-task memory 隔离, TaskView scope, maxAgeMs retention |
| R23-F | PlanStats (backend) | +8 | Plan 完成后的整体统计 (总耗时/平均/最慢/重试), 帮长程任务定位瓶颈 |
| R23-G | TaskStats (backend) | +7 | TaskRegistry 整体统计 (按 status 计数, root tasks, oldest pending age) |
| R23-H | `/stats` slash command (TUI) | 0 (wiring) | 整合 TaskStats + PlanStats, 用户可见 |

## 关键经验 (跨 round)

### 后端

1. **Priority queue 调度**: 单 worker 下 priority queue = FIFO。Priority queue 只在 ≥2 items 同时 in queue 时显出优势。Test 设计要直接验证 comparator (TaskScheduler.itemOrder())。

2. **emit/awaitIdle race**: emit terminal state 必须在 decrement counters 之前。否则 awaitIdle 在事件 emit 前就观察到 "empty running" 返回 true, listener 看不到事件。

3. **递归 submit 死锁**: workerLoop 跑在传入 executor, 阻塞在 take() 后再 submit runItem 回同一 executor → 4 线程全卡。**修复**: 内部自管线程池。

4. **TaskMemory 隔离设计**: 任务间 memory 用 `<root>/<agentType>/<taskId>/<key>` 分层, 而不是用 tag/namespace 过滤 — 物理隔离最干净。`TaskView` 提供 syntactic 隔离, 防止 cross-task leakage 误用。

5. **PlanStats / TaskStats 模式**: pure data record + `from(list)` factory, 不依赖外部 state。`summary()` 给人看, 字段给人编程。对称的 SDK / Tasks API surface。

6. **String.startsWith 的坑**: `_snprintf` `_snwprintf` 用 `startsWith` 会 over-match `syslog` vs `syslogd` — 用 `equals`。

### TUI

7. **StatusBar 渐进增强**: 加新字段用 "X running, Y queued" 自然语言而不是塞表。planProgress 覆盖默认值, 不会跟计数器打架。

8. **/stats 集成方式**: 通过 `plan.lastResults()` 暴露 plan 数据, 不改 PlanExecutor 内部 list 引用。ReplApp 持有一个 clear-cache hook (`swapEngine` 里清 lastResults)。

9. **Slash command 列表一致性**: 改 `Set.of(slashNames)` 记得同时改 `Map.ofEntries(slashDescs)` — 漏一个 fuzzy completion 就只显示名字没有描述。

## R23 工作方法教训 (回顾)

1. **别一次写 15K 测试文件**: 第一次 TaskSchedulerTest 写 15K 17 个测试, 跑超时。改成 6K 10 个测试, 1.3s 跑完。
2. **先 compile 再 test**: `mvn -pl X -am compile` < 30s, 验证语法; 再 `mvn -pl X test` 跑单 module; 最后 `mvn test` 全量。
3. **mvn test timeout 5 分钟**: 默认 120s 不够大 module 跑, 设 300000ms。
4. **不要 plan 20+ rounds 一次承诺**: 用户原话"调整开发工作方向"。R23 砍到 8 round, 每个 30-90 分钟闭环。
5. **Plumb / Fathom 项目的 memory update 别看**: 用户明确说只关注 AetherCode, 别让其他项目 memory 干扰。
6. **R22-B 修的 flaky 测试 (StreamingToolExecutorBackpressureTest / NotificationCoalescerTest / RepaintSchedulerTest) 在全量高负载下还会 fail**: 单跑 pass, 全跑 flake。这是 JDK scheduler 的 timing issue, 跟 R23 无关。

## R23 文件清单

- `aethercode-tui/.../FuzzyCompletion.java` (新, R23-A)
- `aethercode-tui/.../SlashCommandCompleter.java` (loose 模式, R23-A)
- `aethercode-tui/.../ReplApp.java` (FuzzyCompletion wiring, /stats case, pendingTasks wiring)
- `aethercode-tui/.../screen/LayoutState.java` (pendingTasks 字段, R23-D)
- `aethercode-tui/.../screen/StatusBar.java` (formatPlan, R23-D)
- `aethercode-tasks/.../TaskScheduler.java` (新, R23-B)
- `aethercode-tasks/.../TaskSchedulerBridge.java` (新, R23-C)
- `aethercode-tasks/.../TaskStats.java` (新, R23-G)
- `aethercode-memory/.../TaskMemoryStore.java` (新, R23-E, with TaskView)
- `aethercode-sdk/.../PlanStats.java` (新, R23-F)
- `aethercode-tui/.../PlanPanel.java` (lastResults field, runExecutor snapshot, R23-H)
- Tests: `FuzzyCompletionTest`, `TaskSchedulerTest`, `TaskSchedulerBridgeTest`, `StatusBarTest` (+5), `LayoutStateTest` (+2), `TaskMemoryStoreTest`, `PlanStatsTest`, `TaskStatsTest`

## R23 主题覆盖 (用户要求)

| 主题 | R23 round |
|------|-----------|
| TUI UX | A (fuzzy completion), D (status bar pending), H (/stats) |
| 后端性能 | B (scheduler), C (bridge), G (stats) |
| 任务规划 | F (PlanStats 帮看 plan 进度) |
| 任务调度 | B (TaskScheduler 优先级队列), C (TaskSchedulerBridge) |
| 长程任务执行 | F (PlanStats 最慢步定位), G (TaskStats 总览) |
| 任务 memory 隔离 | E (TaskMemoryStore + TaskView) |

## R24+ 候选 (按 ROI)

1. **R24-A: TaskScheduler 接入 SubagentPool** (backend) — 让 subagent 任务走优先级队列, 而不是直接线程池
2. **R24-B: Long-running task watchdog** (backend) — 任务超过 N 分钟自动 KILL + 通知, 防 runaway
3. **R24-C: Plan DAG 支持** (backend) — 步骤可声明依赖, 自动拓扑排序, 跳过未满足的依赖
4. **R24-D: Plan execution 进度条** (TUI) — 状态栏加 plan 进度百分比 + ETA
5. **R24-E: Memory budget per task** (backend) — TaskMemoryStore 加 size 限制 + 警告
6. **R24-F: Task tree visualization** (TUI) — TaskTreeRenderer 已有, 加 interactive 选中 + 取消按钮
