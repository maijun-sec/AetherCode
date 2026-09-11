# R20-E: PlanExecutor (2026-08-06)

## 目标

让引擎（而不是模型）按 plan 步骤自动执行。每步 = 一次 user query，引擎驱动状态机：PENDING → RUNNING → COMPLETED/FAILED/SKIPPED。

## 变更

### 新增类 (`aethercode-sdk/.../sdk/`)

- `PlanExecutor` — 核心执行器。`execute(List<String> stepTitles)` 返回 `Stream<PlanEvent>`。`abort()` 让执行器在当前步完成后停止。`results()` 返回不可变的 `List<StepResult>`。
- `PlanEvent` — sealed interface，5 个 records：`StepStarted` / `StepCompleted` / `StepFailed` / `PlanAborted` / `PlanCompleted`。
- `StepExecutor` — 函数式接口，`execute(int stepIndex, String title, Task task)`。
- `EngineStepExecutor` — 默认实现，把 title 作为 query 喂给 `engine.query()`，收集所有 TextDelta 作为 summary。
- `StepResult` (record) — 包含 `stepIndex` / `title` / `StepOutcome` (COMPLETED/FAILED/SKIPPED) / `detail` / `elapsedMs`。
- `Step` (record) — `index` + `title`（内部用）。

### `PlanPanel.approve()` 改造

不再只发一个 hint 让模型自己驱动，而是用 `PlanExecutor` 直接驱动：
1. 推 approved steps 到 AppState todoList (R19-C 行为不变)
2. 渲染 hint message
3. `CompletableFuture.runAsync(() -> pe.execute(titles).forEach(...))` — 后台线程跑，REPL 不阻塞
4. 每步 lifecycle event 输出到 stdout (TUI 的 scrollback 会接住)

### 设计要点

- **不引 tui 依赖**：原计划 `execute(StructuredPlan)`，但 `StructuredPlan` 在 aethercode-tui，aethercode-sdk 不能反向依赖。改成 `execute(List<String> stepTitles)`，更解耦。
- **状态机**：每步对应一个 `TaskType.WORKFLOW` task，存进 `TaskRegistry.instance()`。PENDING → RUNNING → COMPLETED/FAILED。
- **abort**：在 `tryAdvance` 开头检查 `aborted.get()`。当前步跑完后，下一个 `tryAdvance` 看到 abort=true，把剩余 steps 标记为 SKIPPED（result 里有，但不发每步 SKIPPED 事件，只发最终的 PlanAborted），spliterator 一次性终结。
- **异常隔离**：单步失败 → emit `StepFailed`，继续到下一步（不中断整个 plan）。失败 detail 是 exception message 或 class 简单名。

## 测试

新增 12 tests，0 regression：

| Test class | Tests |
|---|---|
| `PlanExecutorTest` | 12 (null plan / empty / 单步 / 多步 / 失败 / task 持久化 / elapsed / abort / 不可变 results / 结果含 outcome / isAborted) |
| **合计** | **12** |

总测试数：**1318** (R20-D: 1306 → R20-E: 1318, +0.9%)

## 关键 pitfall

1. **aethercode-sdk 不能依赖 aethercode-tui** — 起初 `execute(StructuredPlan plan)`，编译失败（tui 在更高层）。改成 `execute(List<String> stepTitles)`，调用方负责把 plan 转成 titles 列表。
2. **spliterator 一次 tryAdvance 只能 emit 一个 event** — 起初 abort 时在 `tryAdvance` 内部循环 `for (each remaining step) action.accept(StepSkipped)` 然后 `return false`，但 spliterator 协议规定一次 tryAdvance 至多 deliver 一个 element。改成 abort 路径只 emit 单个 `PlanAborted`，per-step SKIPPED 写进 `results()` 让消费者查。
3. **失败不中断** — 单步 throw → emit StepFailed → 继续下一步。`execute_abortedBeforeStart_skipsRemainingSteps` 起初期望 2 个 SKIPPED 事件流出来，错了；改测 results()。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1318 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20e/`
- 上一轮：`docs/R20D-PERSISTENT-TASK-REGISTRY.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-F 滑动窗口 + 摘要链（10 小时不爆上下文）
