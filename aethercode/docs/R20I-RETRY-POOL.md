# R20-I: Subagent 池 + 失败重试 + 指数退避 (2026-08-06)

## 目标

让 plan 步骤失败时自动重试，而不是直接放弃。10 小时长任务里网络抖动、rate limit、临时资源争抢都是常见故障源，retry 是关键。

## 变更

### 新增类 (`aethercode-sdk/.../sdk/`)

- `RetryPolicy` (record) — 重试配置：`maxAttempts` + `baseBackoffMs` + `maxBackoffMs` + `multiplier` + `jitter`。提供 3 个常量：
  - `DEFAULT` — 3 attempts, 1s → 2s 退避
  - `AGGRESSIVE` — 5 attempts, 100ms → 200ms → 400ms
  - `NONE` — 1 attempt, no retry
- `backoffFor(attempt)` — 计算第 N 次重试前的退避（指数 + jitter）
- `shouldRetry(attempt)` — 是否还要重试
- 构造器校验：`maxAttempts >= 1` / `base >= 0` / `max >= base` / `multiplier >= 1.0` / `jitter in [0, 1]`

- `RetryHelper` — 重试执行器
- `RetryHelper.Sleeper` 接口（可插拔）— 测试用 FakeSleeper 替代 Thread.sleep
- `run(Callable, policy)` / `run(Callable, policy, sleeper)`
- `Result<V>` record — `value` / `attempts` / `error` + `isSuccess()` / `isFailure()`

### `PlanExecutor` 改造

- 加 `retryPolicy` 字段（默认 `RetryPolicy.DEFAULT`）
- `withRetryPolicy(policy)` 链式 setter
- 每步执行用 `RetryHelper.run(...)` 包住。失败 3 次后才标记 FAILED
- 成功但有 retry 的步骤，summary 标注 `[retried Nx] ...` 让用户看见
- 失败时 error 消息加上 `after N attempts: ...` 标注
- 新增 `execute_withRetryPolicyNone_propagatesErrorVerbatim` 测试验证 NONE 模式

## 设计要点

- **可插拔 Sleeper** — 让测试不用真等 1 秒。`RetryHelper.Sleeper` 接受 `Thread::sleep` 或 fake 替身。
- **指数退避 + jitter** — 防止 thundering herd：多个失败任务同步重试时不会被同一时间窗口击中。
- **cap 兜底** — `maxBackoffMs` 防止长运行下退避无限大。
- **保守默认** — 3 attempts, 1s base。失败 3 次 = 1+2 = 3 秒后最终放弃。避免用户等太久。
- **summary 标注 retry** — `[retried 2x]` 让用户看见"模型其实卡了 2 次但最终成功"，对调试有用。

## 测试

新增 24 tests，0 regression：

| Test class | Tests |
|---|---|
| `RetryPolicyTest` | 13 (构造校验 / 指数增长 / cap / jitter / shouldRetry / DEFAULT / AGGRESSIVE / NONE) |
| `RetryHelperTest` | 9 (首次成功 / 重试到成功 / 放弃 / NONE / 退避累计 / interrupt / null) |
| `PlanExecutorTest` 扩展 | +2 (retry policy NONE 保留原 error) |
| **合计** | **24** |

总测试数：**1392** (R20-H: 1368 → R20-I: 1392, +1.8%)

## 关键 pitfall

1. **interrupted 测试逻辑写错** — 起初 `run_interruptedDuringSleep_returnsInterrupted` 用 `() -> "ok"` 这种不会失败的 task，sleep 永远不会被调用。改成 `() -> { throw new RuntimeException(); }` 先触发重试，再在 sleep 里 interrupt。
2. **PlanExecutor 旧测试期望原 error message** — 改用默认 retry policy 后，error 变成 `after 3 attempts: boom`，旧测试期望 `boom`。两个修法：要么改测试，要么把 PlanExecutor 默认 policy 改成 NONE。选前者（保留默认 retry 更安全），并加新测试覆盖 NONE 行为。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1392 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20i/`
- 上一轮：`docs/R20H-AUTO-APPROVE.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-J 监督定时器 + checkpoint + 恢复
