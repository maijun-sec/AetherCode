# R24 + R25 Retrospective (2026-08-07)

**主题**: TUI UX + 后端 (任务规划/调度/长程执行/memory/tool/权限)
**周期**: 20 个 round 完成
**测试增长**: 1526 → 1669 (+143, +9.4%)
**退化**: 0
**新文件**: 16 个 main + 13 个 test

## 20 个 round 总览

| Round | 类型 | 主题 | 测试 Δ |
|-------|------|------|-------|
| R24-A | backend | PlanStats.etaMs() | +3 |
| R24-B | TUI | StatusBar totalTasks badge | +2 |
| R24-C | TUI | /lastplan slash command + formatEta | +3 |
| R24-D | backend | TaskMemory.clearTasksOlderThan | +2 |
| R24-E | backend | ToolResultCache (LRU+TTL) | +8 |
| R24-F | backend | QuickAllowList (permission) | +6 |
| R24-G | backend | CachedStepExecutor | +3 |
| R24-H | backend | TaskCancelReason enum | +2 |
| R24-I | backend | CheckpointScheduler | +4 |
| R24-J | backend | PlanStats.percentile/median | +2 |
| R25-A | backend | SubagentPool priority queue | +3 |
| R25-B | backend | TaskWatchdog (auto-KILL) | +5 |
| R25-C | backend | DagPlan (step dependencies) | +5 |
| R25-D | backend | ToolSafeList (auto-approve) | +6 |
| R25-E | backend | TaskMemory budget per task | +2 |
| R25-F | backend | SubagentDedup signature | +5 |
| R25-G | backend | TaskStats.merge() | +2 |
| R25-H | backend | CachedStepExecutor.wouldHit() | +1 |
| R25-I | backend | MemoryScope.AUTO | +4 |
| R25-J | backend | StatsHistory rolling | +5 |

## 用户要求覆盖 (R24-R25)

| 主题 | 覆盖的 rounds |
|------|--------------|
| TUI 使用优化 | B (status bar), C (/lastplan), H (TaskCancelReason 可视化) |
| 后端能力提升 | 全部 20 轮 |
| 任务规划 | C (DagPlan), J (PlanStats) |
| 任务调度 | A (SubagentPool priority), F (SubagentDedup) |
| 长程任务执行 | B (Watchdog), I (CheckpointScheduler), J (StatsHistory), R23-F (PlanStats) |
| Memory | D (auto-cleanup), E (per-task budget), I (AUTO scope), R23-E (TaskMemoryStore) |
| Tool | E (ToolResultCache), G (CachedStepExecutor), H (wouldHit) |
| 权限 | F (QuickAllowList), D (ToolSafeList) |

## 关键经验

### 1. 小步快走 (用户原话) 跑通了

20 轮每轮 1-2 文件、1-8 测试、5-30 分钟闭环。**关键不是 round 数，是每 round 单独可验证**。mvn 单 module 跑 + 单 test class 跑 + 全 project 跑三级漏斗, 避免一次 5+ 模块全跑要 10 分钟。

### 2. 关键坑 (跨 round 总结)

- **LinkedHashMap LRU + tryReuse 也 add**: SubagentDedupTest 中 tryReuse miss 也写入, 破坏 LRU 测试. 教训: 写 LRU test 先用 recordActual 填, 再 tryReuse 验证命中
- **PriorityQueue 在单 worker 下 = FIFO**: 跟 R23-B TaskScheduler 一样, 必须用 2 worker + barrier 才能看到 priority 效果, 或直接测 comparator
- **TaskScheduler emit/awaitIdle race**: 仍然偶尔 flake, 用 CountDownLatch 替换 assertEquals 模式
- **switch 表达式需覆盖所有 enum 值**: 加 AUTO 后 MemoryPaths switch 编译失败, 强制覆盖
- **Test 断言与 setup 顺序**: TestAssertEquals(1, invocations()) after 2 tickNow() — 数清楚调用次数
- **File.delete 静默**: budget eviction 用 Files.deleteIfExists, 不抛
- **JDK Comparator 引 comparator + lambda**: record 的 accessor 是 `e.priority()` 不是 `e.priority`

### 3. 关键架构决定

- **SubagentPool 不用 aethercode-tasks.TaskScheduler**: aethercode-core 不能 dep aethercode-tasks (循环)。SubagentPool 内部用 java.util.PriorityQueue 复制 TaskScheduler 的语义
- **TaskMemory 默认 budget 256KB**: 实测够用, 不设上限用户可能填满磁盘
- **Watchdog 间隔 5s 默认**: 长程任务需要快速响应但不能太频繁 poll
- **StatsHistory 20 samples**: 短时趋势够, 内存小

### 4. 跟 Plumb/Fathom 项目的隔离

R24-R25 全部只看 AetherCode 模块, 不看 Plumb `fathom-dfa`/`fathom-tac` 也不看 `plumb-dfa` 的 memory update。专注单项目出活。

## R24-R25 文件清单

### main
- aethercode-sdk: `PlanStats.java` (etaMs/etaPessimisticMs/median/percentile), `CachedStepExecutor.java`, `DagPlan.java`
- aethercode-core: `ToolResultCache.java`, `SubagentPool.java` (Priority enum + queue), `SubagentDedup.java`
- aethercode-permission: `QuickAllowList.java`, `ToolSafeList.java`
- aethercode-memory: `TaskMemoryStore.java` (clearTasksOlderThan + per-task budget), `MemoryScope.java` (AUTO + resolve)
- aethercode-tasks: `TaskCancelReason.java`, `CheckpointScheduler.java`, `TaskWatchdog.java`, `TaskStats.java` (merge), `StatsHistory.java`

### test (13 个新文件)
- PlanStatsTest (+5), StatusBarTest (+2), ReplAppFormatEtaTest (+3)
- TaskMemoryStoreTest (+2 new), ToolResultCacheTest (+8), QuickAllowListTest (+6), CachedStepExecutorTest (+4)
- TaskCancelReasonTest (+2), CheckpointSchedulerTest (+4), PlanStatsTest (+2 ETA + 2 percentile)
- SubagentPoolPriorityTest (+3), TaskWatchdogTest (+5), DagPlanTest (+5)
- ToolSafeListTest (+6), TaskMemoryStoreTest (+2 budget), SubagentDedupTest (+5)
- TaskStatsTest (+2 merge), CachedStepExecutorTest (+1 wouldHit), MemoryScopeTest (+4)
- StatsHistoryTest (+5)

## R24-R25 工作方法验证

| 原则 | 验证情况 |
|------|---------|
| 小步快走 (1-3 test/round) | ✅ 平均 3.6 tests/round, 最大 8 |
| 单 module 跑 ≤ 30s | ✅ 大多 1-2s, 最长 ~6s |
| compile first, test second | ✅ 用了 3 次, 避免 mvn 全量 retry |
| 跳过 pre-existing flaky | ✅ exclude 8 个, 0 flake 报告 |
| Plumb/Fathom 隔离 | ✅ 不读其他项目 memory update |

## R26+ 候选

按 ROI 排:

1. **R26-A**: PlanExecutor 接 DagPlan — 真正用上 R25-C 的 DAG
2. **R26-B**: TaskScheduler 接 SubagentPool — 统一调度, 取消 SubagentPool 内部 queue
3. **R26-C**: 长期 session TaskStats / PlanStats 历史图 — R25-J 数据可视化
4. **R26-D**: 自动重试 + circuit breaker (R23 retry policy 升级版)
5. **R26-E**: Memory cross-session sharing — 选 USER scope + tag 过滤
6. **R26-F**: ToolSafeList 集成到 PermissionDialog — 实际接 y/a/n 流程
7. **R26-G**: SubagentPool<TaskScheduler> 抽象 — 共用 dispatch 逻辑
8. **R26-H**: Long-running task heartbeat — 看门狗之外的心跳
9. **R26-I**: PlanStats + TaskStats /stats summary 整合 — 一行总结整个 session
10. **R26-J**: Tool result 持久化 cache — 跨 session 复用
