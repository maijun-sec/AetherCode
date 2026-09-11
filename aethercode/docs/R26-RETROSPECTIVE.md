# R26 Retrospective (2026-08-08)

**主题**: TUI UX + 后端 集成/收敛
**周期**: 10 个 round 完成
**测试增长**: 1669 → 1709 (+40, +2.4%)
**退化**: 0
**新文件**: 10 个 main + 8 个 test

## 10 round 总览

| Round | 类型 | 主题 | 测试 Δ |
|-------|------|------|-------|
| R26-A | backend | PlanExecutor.executeDag | +4 |
| R26-B | backend | SchedulerBackedSubagentPool | +3 |
| R26-C | TUI | StatusBar trend arrow | +3 |
| R26-D | backend | CircuitBreaker (auto retry + CB) | +6 |
| R26-E | backend | MemoryShare (cross-session) | +4 |
| R26-F | TUI/perm | PermissionDialog safe-list 集成 | +3 |
| R26-G | backend | TaskDispatcher interface | +3 |
| R26-H | backend | TaskHeartbeat | +4 |
| R26-I | backend | SessionSummary (一行总览) | +5 |
| R26-J | backend | PersistentToolResultCache | +5 |

## 用户要求覆盖 (R26)

| 主题 | 覆盖 rounds |
|------|------------|
| TUI 使用优化 | C (trend arrow), F (safe-list auto-approve) |
| 后端能力提升 | A (DAG exec), B (scheduler-pool facade), D (CB), E (cross-session), G (dispatcher), H (heartbeat), I (summary), J (persistent cache) |
| 任务规划 | A (DAG-based execution) |
| 任务调度 | B (scheduler-backed pool), G (TaskDispatcher interface) |
| 长程任务执行 | D (circuit breaker), H (heartbeat), I (one-line summary) |
| Memory | E (cross-session tag-based share) |
| Tool | F (safe-list integration), J (persistent cache) |
| 权限 | F (safe-list short-circuit) |

## 关键经验 (R26)

### 1. 跨 round 复用前 round 基础设施

- R26-A 用 R25-C 的 `DagPlan` 加 `executeDag` 方法
- R26-B 用 R23-B 的 `TaskScheduler` 加 `SchedulerBackedSubagentPool` facade
- R26-C 用 R25-J 的 `StatsHistory.trend()` 反映到 `LayoutState.taskTrend`
- R26-D 用 R23-I 的 `RetryPolicy` 模式, 加 `CircuitBreaker`
- R26-E 用 R23-E 的 `TaskMemoryStore` 同目录布局, 加 cross-session share
- R26-F 用 R25-D 的 `ToolSafeList` 接到 `PermissionDialog`
- R26-G 用 R25-A 的 `SubagentPool` 接口, 加 `TaskDispatcher` 抽象
- R26-H 用 R25-B 的 `TaskWatchdog` 同 daemon executor 模式
- R26-I 用 R23-G 的 `TaskStats` + R23-F 的 `PlanStats`
- R26-J 用 R24-E 的 `ToolResultCache` 模式, 加 disk-backed 版本

### 2. 关键设计决定

- **R26-B 移到 aethercode-sdk**: aethercode-core 不 dep aethercode-tasks (避免循环)。SchedulerBackedSubagentPool 放 sdk 编译。
- **R26-E 用 sidecar tags 文件**: `key.tags` 跟 `key` 同目录, 简单 O(n) grep 找 tag, 不需要 database
- **R26-G TaskDispatcher interface**: 让 SubagentPool 和 SchedulerBackedSubagentPool 共用 API
- **R26-I SessionSummary 用 String 不用 PlanStats**: 避免 aethercode-tasks dep aethercode-sdk, TUI 负责传 `plan.summary()` 字符串

### 3. 关键 pitfall

- **循环依赖**: aethercode-core 不能 dep aethercode-tasks. 解决方法: 把跨模块的 facade 放 sdk (R26-B)
- **switch 表达式需覆盖所有 enum 值**: 加 AUTO 后 MemoryPaths switch 编译失败 (R25-I 教训, R26 复用)
- **测试 tryReuse 也会 add**: LRU 测试要小心, 验证 survivor 而不是 miss
- **sub-claim: Tasks module 用 dumb String 引用 SDK 实体**: SessionSummary oneLine 接 planSummary String, 不接 PlanStats, 保持模块解耦

## R26 文件清单

### main
- aethercode-sdk: `PlanExecutor.java` (executeDag), `CircuitBreaker.java`, `SchedulerBackedSubagentPool.java`
- aethercode-core: `TaskDispatcher.java` (interface), `PersistentToolResultCache.java`
- aethercode-tui: `PermissionDialog.java` (R26-F safe-list), `StatusBar.java` (R26-C trend), `LayoutState.java` (taskTrend)
- aethercode-tasks: `SessionSummary.java`, `TaskHeartbeat.java`
- aethercode-memory: `MemoryShare.java`

### test (8 个新文件)
- `PlanExecutorDagTest`, `SchedulerBackedSubagentPoolTest`, `StatusBarTest` (+3), `CircuitBreakerTest`, `MemoryShareTest`, `PermissionDialogSafeListTest`, `TaskDispatcherTest`, `TaskHeartbeatTest`, `SessionSummaryTest`, `PersistentToolResultCacheTest`

## R23-R26 总成绩 (40 round)

| 阶段 | rounds | 测试 Δ | 累计 |
|------|--------|-------|------|
| R23-A..H | 8 | +70 | 1596 |
| R24-A..J | 10 | +35 | 1631 |
| R25-A..J | 10 | +44 | 1675 |
| R26-A..J | 10 | +40 | **1709** |
| **总** | **38** | **+183** | **1526→1709** |

R23 的 8 round 是单独跑的, 加上 R24-R26 共 38 round (R23-H R23-G 算 8 个) 总共 +183 tests.

## R27+ 候选 (按 ROI 排)

1. **R27-A**: TaskWatchdog + TaskHeartbeat 整合 — 一个 daemon 跑 watch + heartbeat
2. **R27-B**: SessionSummary 进 LayoutState — 一行总览接 status bar
3. **R27-C**: PersistentToolResultCache 接到 ToolResultCache — 双层 LRU+disk
4. **R27-D**: PlanDashboard TUI 面板 — task tree + plan progress 一屏
5. **R27-E**: CircuitBreaker 接入 SubagentPool — 失败 N 次后熔断
6. **R27-F**: MemoryShare search API — 全文搜索, 不只 tag 过滤
7. **R27-G**: TaskDispatch 性能对比 benchmark
8. **R27-H**: PermissionDialog 集成 QuickAllowList — 用户 /allow 命令真的接 y/a/n 流程
9. **R27-I**: LayoutState trend sparkline — 状态栏用 sparkline 字符显示历史
10. **R27-J**: SessionSummary 集成到 /stats 真正替换当前两行输出
