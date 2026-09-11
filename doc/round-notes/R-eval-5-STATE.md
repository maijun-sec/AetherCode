# R-eval-5 State Tracking & Causal Reasoning (2026-09-12)

## 触发

R-eval master plan 第 5 round。Survey 2503.16416 §2.1 + 2506.11102
"Evolutionary Perspectives" — agent state tracking 跟 causal reasoning
是核心能力, 6 子能力: Watchdog / Circuit Breaker / Backpressure /
Session state / Causal graph / Retry policy.

AetherCode SDK 已有 Watchdog (silent run detection) / CircuitBreaker
(CLOSED/OPEN/HALF_OPEN) / BackpressureException (背压) / SessionManager
(会话管理) / DagPlan (因果依赖) / RetryPolicy (重试策略) 完整 state
子系统. R-eval-5 写 self-contained 6 个 state machine, 26 个 test 覆盖
6 子能力 + 端到端.

## 实际产出 (1 test class, 26 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/state/StateTrackingCapabilityTest.java`
  (23 KB), 26 tests:
  - **Watchdog** (3): fire above timeout / not fire below / kick resets
  - **Circuit breaker** (4): trip after threshold / recover after
    cooldown / reset on success / rejects invalid args
  - **Backpressure (token bucket)** (3): start full / refill over time
    / cap at capacity
  - **Session state machine** (4): NEW→ACTIVE→CLOSED / pause-resume
    roundtrip / turns require ACTIVE/RESUMED / pause only from ACTIVE
  - **Causal graph** (5): store edges / topo order / reject cycles /
    transitive root cause / direct root cause / longest path
  - **Retry policy** (4): exponential backoff / cap at max / give up
    after max / reject invalid multiplier
  - **E2E** (2): retry loop stops on breaker trip / deterministic
    state transitions
- **aethercode-evals: 517/517 Java tests pass** (491 R-eval-4 + 26 new
  R-eval-5), 0 回归

## 关键技术决定 (8 条)

1. **6 state machine 跟 SDK 1:1** - Watchdog / CircuitBreaker /
   TokenBucket / Session / CausalGraph / RetryPolicy 全部 1:1 复制
   AetherCode SDK 的 contract
2. **Watchdog LongSupplier 注入** - 测试用 `AtomicLong::get` 控时间,
   跟 SDK 一致
3. **CircuitBreaker 3 state** - CLOSED / OPEN / HALF_OPEN, 跟 SDK 一致
4. **TokenBucket refill on demand** - 不 background thread, 调用
   tryAcquire 时才 refill, 跟 SDK 风格一致
5. **SessionState 5 状态** - NEW / ACTIVE / PAUSED / RESUMED / CLOSED
6. **CausalGraph 用 Kahn 算法** - 跟 R-radar-8 multi-agent / R-eval-1
   Plan 拓扑对齐
7. **RetryPolicy multiplier < 1 clamp 到 1.0** - 防止负增长 bug
8. **Test seam 不依赖真 SDK** - R-mod-2 后 wire 真 production

## 6 子能力 + 26 tests 映射

| 子能力 (paper) | Tests | AetherCode SDK 映射 |
|---|---|---|
| Watchdog | 3 | org.aethercode.sdk.Watchdog |
| Circuit breaker | 4 | org.aethercode.sdk.CircuitBreaker |
| Backpressure | 3 | org.aethercode.sdk.BackpressureException |
| Session state | 4 | org.aethercode.sdk.SessionManager |
| Causal graph | 5 | DagPlan (R-eval-1) 扩展 |
| Retry policy | 4 | org.aethercode.sdk.RetryPolicy |
| E2E | 2 | (组合) |

## 累计测试 (R-eval-5 后)

- aethercode-orchestration: 0
- aethercode-evals: **517/517** (491 R-eval-4 + 26 R-eval-5)
- 其他不变
- 0 回归

## 教训 (新增 8 条, 累计 275+)

268. **6 state machine 跟 SDK 1:1** - Watchdog / CircuitBreaker /
     TokenBucket / Session / CausalGraph / RetryPolicy
269. **Watchdog LongSupplier 注入** - 测试用 AtomicLong 控时间
270. **CircuitBreaker 3 state** - CLOSED / OPEN / HALF_OPEN
271. **TokenBucket refill on demand** - 不 background thread
272. **SessionState 5 状态** - NEW / ACTIVE / PAUSED / RESUMED / CLOSED
273. **CausalGraph 用 Kahn 算法** - 跟 R-radar-8 / R-eval-1 拓扑对齐
274. **RetryPolicy multiplier < 1 clamp 到 1.0** - 防止负增长 bug
275. **Test seam 不依赖真 SDK** - R-mod-2 后 wire

## 后续

- R-eval-6: AetherCode JSON-RPC Interface
- R-eval-7: A2A Multi-Agent
- R-eval-8: Cost-Efficiency, Safety & Robustness
