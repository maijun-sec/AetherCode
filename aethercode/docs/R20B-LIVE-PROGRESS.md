# R20-B: 实时进度 (2026-08-06)

## 目标

让 query 期间的状态栏真正「活」起来 — 不再等下一个事件才刷新。

## 变更

### 新增类

- `TokenRateTracker` — 滑动窗口 token 速率追踪（默认 5 秒窗口）。`record(total)` 喂数据，`tokensPerSecond()` 读出速率。线程安全。
- `ToolCallTimer` — 工具调用计时器。`start(id)` / `stop(id)` 配对；`currentElapsedMs()` 给状态栏用。线程安全。
- `RepaintScheduler` — 后台 repaint ticker，`AutoCloseable`。单线程 ScheduledExecutor，250ms 周期。`start(callback, intervalMs)` / `close()`。callback 抛异常被吞，不影响调度。

### `LayoutState` 扩展
- `tokensPerSecond` (double)
- `currentToolElapsedMs` (long)
- `lastEventAgeMs` (long) — 上次事件到现在多久
- `lastToolName` (String) — 上次完成工具的名字
- `lastToolElapsedMs` (long) — 上次完成工具的耗时
- 静态方法 `formatMs(long)` / `formatTokPerSec(double)`

### `StatusBar` 升级
- 工具段：`⚙ bash 1.4s  ls -la`（加上计时器）
- token 段：`23K/200K 11%  [▓░░░░░░░░░]  12 tok/s`（加上速率）

### `ReplApp` 改造
- 加 3 个字段：`tokenRate` / `toolTimer` / `repaintScheduler`
- `runOne()` 启动时 `tokenRate.reset()` / `toolTimer.reset()` / 启动 `RepaintScheduler`
- `updateScreenState()`:
  - `TextDelta` → `tokenRate.record(estimateTokensUsed())`
  - `ToolUseStart` → `toolTimer.start(tu.id())`
  - `ToolResult` → `toolTimer.stop(tr.id())` 记录 `lastToolName` / `lastToolElapsedMs`
- `repaintScreen()`:
  - 读 `toolTimer.currentElapsedMs()` 到 `currentToolElapsedMs`
  - 读 `tokenRate.tokensPerSecond()` 到 `tokensPerSecond`
  - 读 `lastEventAtMs` 算 `lastEventAgeMs`
- `runOne()` finally 块关闭 repaint scheduler

## 测试

新增 25 tests，0 regression：

| Test class | Tests |
|---|---|
| `TokenRateTrackerTest` | 6 (空 / 单样本 / 双样本速率 / reset / 滑动窗口 / lastTotal) |
| `ToolCallTimerTest` | 9 (start/stop / 未知 id / 空闲 / 增长 / currentToolId / lastCompleted / markCompleted / reset / 多工具) |
| `RepaintSchedulerTest` | 4 (重复 fire / close 停 / close 幂等 / 异常不杀调度) |
| `LayoutStateTest` 扩展 | +6 (formatMs / formatTokPerSec / snapshot 含 R20-B 字段) |
| **合计** | **25** |

总测试数：**1283** (R20-A: 1258 → R20-B: 1283, +2.0%)

## 关键 pitfall

1. **多线程竞态**：`tokenRate` / `toolTimer` / `lastEventAtMs` 必须线程安全（事件线程写、repaint 线程读）。`TokenRateTracker` / `ToolCallTimer` 内部 synchronized；`lastEventAtMs` 用 AtomicLong。
2. **RepaintScheduler 异常吞掉**：callback 抛 RuntimeException 不能停调度 — `try { ... } catch (Throwable) { LOG.warn(...) }` 包住。
3. **测试断言格式**：formatTokPerSec(12000.0) → "12K tok/s" 不是 "12.0K tok/s"（>=10K 走 %.0f 分支）。第一次写测试把 12K 写成 12.0K 漏了。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1283 tests, 0 fail, 0 error
$ mvn -B -pl aethercode-tui install -DskipTests
$ mvn -B -pl aethercode-cli package -DskipTests
$ java -jar aethercode-cli-0.1.0-SNAPSHOT-shaded.jar --version
  → aethercode 0.1.0
```

## 关联

- 备份目录：`docs/backups/r20b/`
- 上一轮：`docs/R20A-FULLSCREEN-TUI.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-C 配色 + 边框 + 启动 banner
