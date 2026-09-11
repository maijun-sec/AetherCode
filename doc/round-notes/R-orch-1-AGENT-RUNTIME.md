# R-orch-1 AgentRuntime 集成 (2026-09-12)

## 触发

R-radar 6/7/8 各自独立完成 (V 校验器 + Self-correction + Multi-Agent 编排),
但彼此没有串起来 — 一个真实的 AI agent 需要的是:

```
V (verify) → fail → self-correct loop → fail → ensemble (multi-agent) → verify → return
```

R-orch-1 引入 `AgentRuntime` 把这三层连成一个 thin orchestrator,
上层 (DeepAgentsSystem / AgentTool) 只需要一个入口.

## 实际产出 (2 main + 2 test, 1 round)

- `orchestration/AgentRuntime.java` (231 lines, 10 KB):
  - 单 action 流程: `verify → self-correct (on fail) → ensemble (on exhaust) → verify`
  - 多 candidate 流程: 每个 candidate 走 V+self-correct, survivors 喂给 ensemble
  - 5 种 outcome: `passed` / `self-corrected` / `self-correct-exhausted` /
    `ensemble-passed` / `ensemble-failed`
  - Builder pattern + `Objects.requireNonNull` 构造期验证
  - `RuntimeResult` record 携带 trace + 自纠 result + ensemble result
  - `summary()` 给 audit log: outcome / passed / attempts / self_correct_attempts /
    ensemble_consensus
- `orchestration/RuntimeTrace.java` (62 lines, 2.3 KB):
  - `Entry(phase, action, result)` record 三元组
  - `add(Entry)` + `absorb(LoopResult)` 把 self-correct attempt 折进 trace
  - `entries()` 返回不可变副本
- 2 test class (21 tests):
  - `AgentRuntimeTest` (14 tests): 单 action 5 种 outcome + ensemble 模式 +
    异常处理 + Builder 验证
  - `RuntimeTraceTest` (7 tests): add/absorb/last/entries/Entry 构造验证
- **aethercode-evals: 354/354 (333 R-radar + 21 R-orch-1)**, 0 回归

## 关键技术决定 (8 条)

1. **AgentRuntime 是 thin orchestrator** - 业务逻辑全部在 R-radar-6/7/8 的 class
   里, runtime 只做调度. 任何一层可换 (CompositeVerifier / RetryStrategy /
   VoteStrategy 等).
2. **Builder pattern + Objects.requireNonNull** - 构造期验证比运行时检查更
   fail-fast, 避免 NPE 飘到调用栈深处.
3. **5 种 outcome, 不是 boolean** - 失败有 3 种, audit log 区分 "放弃自我纠"
   vs "ensemble 都救不回来" 很重要.
4. **共享 verifier 陷阱** - runtime + self-correct 用同一 verifier 实例,
   测试需 `failsN(N)` 算 N (runtime 1 次 + loop 1-N 次 + ensemble 1 次).
5. **trace 用 phase 标签区分** - "verify" / "self-correct" / "ensemble-verify"
   / "candidate-verify" / "ensemble-verify", 审计日志按 phase filter.
6. **ensemble strategy 复用** - `MultiAgentOrchestrator<T>.strategy()` 暴露给
   runtime, 候选 candidate 走 self-correct 后用同一 strategy 投票.
7. **onEnsembleAdopted hook** - 默认 identity, 调用方可注入 (e.g. 选 winner
   后做 unit-test / 二次 validate / canonicalize).
8. **runtime 不修改原 V/self-correct/ensemble** - R-radar-6/7/8 的 class 完全
   不知道 AgentRuntime 存在, 集成是 additive.

## API shape (3 个 public method)

```java
AgentRuntime<T> runtime = AgentRuntime.<T>builder()
    .name("paper-summarizer")
    .verifier(myVerifier)              // R-radar-6
    .selfCorrect(mySelfCorrect)         // R-radar-7
    .ensemble(myOrchestrator)           // R-radar-8
    .onEnsembleAdopted(this::sanitize)  // optional
    .build();

RuntimeResult<T> result = runtime.run(action);         // single action
RuntimeResult<T> result2 = runtime.runEnsemble(candidates);  // N candidates

result.passed();       // true if passed / self-corrected / ensemble-passed
result.summary();      // Map<String, Object> for audit log
result.outcome();      // "passed" / "self-corrected" / "self-correct-exhausted"
                       // / "ensemble-passed" / "ensemble-failed"
```

## Runtime flow

### Single action (`runtime.run(action)`)

```
┌────────────────────────────────────┐
│ verify(action)                     │
│   ├─ pass → "passed"               │
│   └─ fail                          │
└──────┬─────────────────────────────┘
       │ fail
       ▼
┌────────────────────────────────────┐
│ selfCorrect.run(action)            │
│   ├─ pass → "self-corrected"       │
│   ├─ exhaust + no ensemble         │
│   │     → "self-correct-exhausted" │
│   └─ exhaust + ensemble configured │
└──────┬─────────────────────────────┘
       │
       ▼
┌────────────────────────────────────┐
│ ensemble.run(orchestrator)         │
│   ├─ post-verify pass              │
│   │     → "ensemble-passed"        │
│   └─ post-verify fail              │
│         → "ensemble-failed"        │
└────────────────────────────────────┘
```

### N candidates (`runtime.runEnsemble(candidates)`)

```
for each candidate:
  verify(candidate) → pass? add to survivors
  fail? selfCorrect(candidate) → pass? add survivor
end

if survivors empty: fall back to all candidates
else: stub-wrap survivors, run orchestrator(strategy)
verify(winner) → outcome
```

## 修的测试 bug

**Bug**: `RuntimeTraceTest.absorbFromSelfCorrectLoopAddsOneEntryPerAttempt` 失败,
expected 2 entries, got 1.

**根因**: `failsN(2)` 是 `calls >= 2 ? pass : fail` (fail on 1st, pass on 2nd).
测试先 `sc.run("x")` 预热消耗 2 次, 再 `sc.run("y")` 时 calls 已经是 2,
直接 pass, 只有 1 个 attempt.

**修法**: 删掉预热 `sc.run("x")` 行. 预热不是必要的, 直接用新 verifier 跑测试.

**教训**: 共享 mutable 计数器的 test helper 必须 fresh per test, 预热会污染状态.

## 累计测试 (R-orch-1 后)

- aethercode-deepagents: 253/253 Java
- aethercode-talon: 5/5 Java
- aethercode-a2a: 33/33 Java
- aethercode-a2a-deepagent-bridge: 8/8 Java
- aethercode-cli: 48/48 Java
- aethercode-tools vision: 25/25 Java
- aethercode-workflows: 63/63 Java
- **aethercode-evals: 354/354 Java** (127 R-radar-2 + 114 R-radar-6 + 49
  R-radar-7 + 43 R-radar-8 + 21 R-orch-1)
- aethercode-tools (含 R-radar-4/5): 322/324 (2 个 pre-existing)
- aethercode-desktop (TS): 1042/1042 pass
- aethercode-desktop (Rust): 14/14 pass
- **0 回归**

## 教训 (新增 8 条, 累计 200+)

193. **共享 verifier 陷阱** - runtime + self-correct 共享 verifier, 测试
    需要 `passOnN(N)` 算 N (runtime 1 次 + loop 1-N 次 + 最后 pass)
194. **record 字段命名** - `attempt.action()` 跟 `lastAttempt().action()`
    是两个不同 field, 注意 final vs var
195. **Builder 模式 + Objects.requireNonNull** - 构造期验证比运行时检查更
    fail-fast
196. **runtime reuses self-correct 状态** - `lastAttempt.action()` 可能跟
    input 不同 (strategy 改了), 不要假设 "self-correct fail" 就是原 action
197. **orchestration 不修改原 V/self-correct/ensemble** - AgentRuntime 是
    thin orchestrator, 业务逻辑全部在 R-radar-6/7/8 的 class 里
198. **runtime trace + summary** - 每个 Entry 三元组 (phase, action, result) +
    summary() 给 audit log
199. **AgentRuntime outcome 5 种** - "passed" / "self-corrected" /
    "self-correct-exhausted" / "ensemble-passed" / "ensemble-failed"
200. **ensemble strategy 复用** - `MultiAgentOrchestrator<T>.strategy()` 暴露
    给 runtime, candidate ensemble 走同一 strategy 投票

## 后续 (下一批)

- R-orch-2: 把 AgentRuntime 串进 `DeepAgentsSystem.respond()` 流程
- R-bugfix-1: 修 Cli parser 2 bug (remainder mode + nested sub-options)
- R-orch-3: 跟 `reference/papers/` 8 篇 paper 一起跑 end-to-end
- R-perf-1: cost ceiling + early-exit + cache
- R-mod-1: 抽 `aethercode-orchestration` 独立 module
