# R-eval-4 Self-Reflection & Self-Correction (2026-09-12)

## 触发

R-eval master plan 第 4 round。Survey 2503.16416 §2.3 + 2601.08173 +
Reflexion (2303.11381 引用 in 2512.13564 §5.2.1) — 自我反思 + 自纠
是 agent 核心能力, 6 子能力: self-correction loop / reflection
quality / verifier crash containment / cost-aware / multi-strategy
fallback / audit trail.

之前 R-radar-7 + R-orch-1 + R-perf-1 已经把 self-correction 写到
production. R-eval-4 写一个 fresh test seam, 24 个 test 覆盖 6 子能力.

## 实际产出 (1 test class, 24 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/reflect/SelfReflectionCapabilityTest.java`
  (24.5 KB), 24 tests:
  - **Self-correction loop** (3): passing terminal / budget exhausted /
    strategy null gives up
  - **Reflection quality** (2): strategy incorporates reason /
    knowledge accumulation
  - **Verifier crash containment** (3): crashing verifier contained /
    crashing strategy graceful / crash then pass
  - **Cost-aware reflection** (2): cost ceiling short-circuit / within
    budget terminates
  - **Multi-strategy fallback** (2): cheap → expensive tier / human gate
  - **Audit trail** (2): every attempt recorded / last action final
  - **Validation** (3): blank name / null verifier / zero attempts
  - **Diverse patterns** (2): fold previous / backtrack to good state
  - **E2E** (5): first success termination / final attempt on failure /
    V → reflect → V integration / reason exposed to strategy / smart
    context-aware recovery
- **aethercode-evals: 491/491 Java tests pass** (467 R-eval-3 + 24 new
  R-eval-4), 0 回归

## 关键技术决定 (8 条)

1. **Fresh test seam** - 重新实现 ReflectionLoop, 不 import 真
   SelfCorrectionLoop. 1:1 contract, R-mod-2 后 wire 真 production
2. **Verifier crash containment** - 跟 R-radar-6 一致: try/catch
   verifier.verify(), 失败包成 BLOCK reason 但 loop alive
3. **Strategy crash containment** - 跟 R-radar-7 一致: try/catch
   strategy.reflect(), crash 当 null 处理, give up gracefully
4. **4 种 GiveUpReason** - PASSED / BUDGET_EXHAUSTED /
   STRATEGY_RETURNED_NULL / INTERRUPTED, 跟 R-radar-7 一致
5. **ReflectionStrategy reflect(T, VerificationResult, int)** - 给
   strategy 看 prev / failure reason / attempt number, 让 strategy
   能 craft 更好的 next attempt
6. **Audit trail = List<Attempt>** - 跟 RuntimeTrace contract 一致
7. **Tier fallback 模式** - cheap → LLM revise → human gate, 跟
   R-radar-7 三档 escalation
8. **Backtrack pattern** - 记录 best-so-far, 失败时回到 best, 跟
   Reflexion 论文的 memory pattern 一致

## 6 子能力 + 24 tests 映射

| 子能力 (paper) | Tests | 论文 |
|---|---|---|
| Self-correction loop | 3 | 2503.16416 §2.3 / R-radar-7 |
| Reflection quality | 2 | 2503.16416 §2.3 / Reflexion |
| Verifier crash containment | 3 | 2503.16416 §2.3 |
| Cost-aware reflection | 2 | 2601.08173 / R-perf-1 |
| Multi-strategy fallback | 2 | 2503.16416 §2.3 |
| Audit trail | 2 | 2503.16416 §2.3 / R-orch-1 |
| Validation | 3 | (sanity) |
| Diverse patterns | 2 | 2503.16416 §2.3 |
| E2E | 5 | 2503.16416 §2.3 |

## 累计测试 (R-eval-4 后)

- aethercode-orchestration: 0
- aethercode-evals: **491/491** (467 R-eval-3 + 24 R-eval-4)
- 其他不变
- 0 回归

## 教训 (新增 8 条, 累计 267+)

260. **Fresh test seam 优于 wire 真 production** - 1:1 contract,
     R-mod-2 后 wire, 行为对得上
261. **Verifier crash containment 是 default** - try/catch + 包成
     BLOCK failure
262. **Strategy crash containment 是 default** - try/catch + crash
     当 null
263. **4 种 GiveUpReason** - PASSED / BUDGET_EXHAUSTED /
     STRATEGY_RETURNED_NULL / INTERRUPTED
264. **reflect(T, VerificationResult, int) signature** - 给 strategy
     3 个 signal 决策
265. **Audit trail = List<Attempt>** - 跟 RuntimeTrace 1:1
266. **Tier fallback 3 档** - cheap / LLM revise / human gate
267. **Backtrack pattern** - 记录 best-so-far, 失败时回 best

## 后续

- R-eval-5: State Tracking & Causal Reasoning (Watchdog/CircuitBreaker)
- R-eval-6: AetherCode JSON-RPC Interface
- R-eval-7: A2A Multi-Agent
- R-eval-8: Cost-Efficiency, Safety & Robustness
