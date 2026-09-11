# R-radar-7: Self-correction 机制 (论文 2508.17281 gap #2)

**Round**: R-radar-7  
**Date**: 2026-09-12  
**Module**: `aethercode/aethercode-evals/src/main/java/org/aethercode/evals/selfcorrect/`  
**Goal**: 补全论文 2508.17281 §5.2 "反思重规划" 缺失的 self-correction 框架, 把 R-radar-6 的 V 校验器接上一个能 retry / revise / escalate 的循环。

## TL;DR

- 新增 5 个 main 文件 (~17 KB) + 4 个 test 文件 (~30 KB) 在 `aethercode-evals/selfcorrect/`
- **49 tests** 跨 4 个 test class, **291/291 aethercode-evals tests pass** (127 R-radar-2 + 114 R-radar-6 + 49 R-radar-7)
- Self-correction loop: V → if fail → CorrectionStrategy → T → V → ... (with budget)
- 3 种 CorrectionStrategy: RetryStrategy (cheap) / LlmSelfCorrectionStrategy (LLM revise) / HumanCorrectionStrategy (human gate)
- 4 种 terminal reasons: PASSED / BUDGET_EXHAUSTED / STRATEGY_RETURNED_NULL / INTERRUPTED
- 完整 attempt history 给 audit log / eval report 用

## 关键决定 (8 条)

1. **跟 R-radar-6 的 V 直接集成** — SelfCorrectionLoop 接受任何 `Verifier<T>`, 用 R-radar-6 的 CompositeVerifier (ALL/ANY/MIN_THRESHOLD) 也能直接接入。**V 失败 reason 进 self-correction prompt**, 让 LLM / human 知道"哪里错了"
2. **3 种 CorrectionStrategy 而不是 1 个 mega-class** — RetryStrategy (cheap, deterministic) / LlmSelfCorrectionStrategy (LLM revise) / HumanCorrectionStrategy (human gate)。按 risk 选 strategy: 简单 retry → LLM revise → human gate
3. **pluggable ReviseFn for LLM** — LlmSelfCorrectionStrategy 不内置 LLM call, 而是 `ReviseFn = (prev, fail, attempt) -> T`。测试用 stub, production wire 真 LLM。给后续 round 留好接口
4. **fallback chain** — HumanCorrectionStrategy 跟 LlmSelfCorrectionStrategy 都接受 `fallback: CorrectionStrategy<T>`。当 primary 给 null (human 取消 / LLM 决定放弃) 时, 自动 consult fallback。fallback 可以是另一个 LLM, 也可以是 retrySame
5. **错误 containment** — verifier 抛 / strategy 抛 / future timeout / future failed, 都不挂掉 loop。verifier crash → 当成 BLOCK 失败 + 记录 attempt; strategy crash → 当成 STRATEGY_RETURNED_NULL
6. **4 种 terminal reason** — PASSED (成功) / BUDGET_EXHAUSTED (重试太多) / STRATEGY_RETURNED_NULL (strategy 决定放弃) / INTERRUPTED (外部中断)。每个 terminal 携带 final 失败 reason 在 metadata
7. **budget 是 attempts, 不是 retries** — `maxAttempts=3` 表示最多跑 3 次 V (含初次), 2 次 correction。这样 R-radar-6 的 V 每次都被 verify, history 完整
8. **Audit log 友好** — `Attempt<T>` record 携带 (attempt #, action, VerificationResult), 整个 `LoopResult.attempts` 列表给 audit log / eval report 用。`failureTrail()` 是 one-line summary

## 架构

```
                    ┌────────────────────────────────┐
                    │ SelfCorrectionLoop<T>          │
                    │  - name                         │
                    │  - maxAttempts (budget)         │
                    └────────────────────────────────┘
                                  │
            ┌─────────────────────┼──────────────────────┐
            ▼                     ▼                      ▼
   ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
   │ Verifier<T>     │   │ CorrectionStrat │   │ LoopResult<T>   │
   │                 │   │   <T>           │   │  - passed       │
   │ (R-radar-6)     │   │                 │   │  - terminal     │
   │  - Schema       │   │ - RetryStrategy │   │  - attempts     │
   │  - Rule         │   │ - LlmSelfCor    │   │  - failureTrail │
   │  - Heuristic    │   │ - HumanCorrect  │   │  - summary      │
   │  - LlmJudge     │   │                 │   │                 │
   │  - Human        │   │ + fallback      │   │                 │
   │  - Composite    │   │   chain         │   │                 │
   └─────────────────┘   └─────────────────┘   └─────────────────┘
```

## 3 种 CorrectionStrategy

### 1. RetryStrategy (cheap, deterministic)
- `retrySame()` — 返回原 action, 让 loop 再次 verify (transient 失败适用)
- `transform(UnaryOperator)` — 应用固定变换 (`toUpperCase` / `+ "!"`)
- `giveUpImmediately()` — 第一次失败就给 null
- `boundedRetry(op, limit)` — 限制 N 次后给 null

### 2. LlmSelfCorrectionStrategy (LLM revise)
- `ReviseFn<T>` 接 (prev, fail, attempt), 返回 revised action 或 null
- Pluggable: 测试用 stub `(p, f, a) -> "fixed"`, production wire 真 LLM
- 接受 `fallback: CorrectionStrategy<T>`, LLM null 时 consult fallback
- Static factory `of(name, BiFunction)` 给常见 BiFunction caller

### 3. HumanCorrectionStrategy (human gate)
- `AskHuman<T>` 接 (prev, fail, attempt), 返回 `CompletableFuture<T>`
- 3 种模式: wired callback / auto-give-up (null callback) / timeout (default 30s)
- Future 4 种处理: success / null / crash / timeout → 都 fall back to fallback (or give up)
- 跟 R-radar-6 的 HumanVerifier 一样的 fail-loud default

## SelfCorrectionLoop 行为

```
attempt 1: V(action_1) → fail (BLOCK)
            ↓
            CorrectionStrategy.next(action_1, fail, 1) → action_2
            ↓
attempt 2: V(action_2) → fail (BLOCK)
            ↓
            CorrectionStrategy.next(action_2, fail, 2) → action_3
            ↓
attempt 3: V(action_3) → pass
            ↓
terminal = PASSED, 3 attempts used
```

Budget exhausted:
```
attempt 1-3: all fail (BLOCK)
            ↓
terminal = BUDGET_EXHAUSTED, 3 attempts used
```

Strategy gives up:
```
attempt 1: V(action_1) → fail
            ↓
            CorrectionStrategy.next → null
            ↓
terminal = STRATEGY_RETURNED_NULL, 1 attempt used
```

## 跟 R-radar-6 的关系

R-radar-6 提供 V, R-radar-7 提供 failure reaction。两者一起形成论文 2601.01743 §V "verify-aware planning" 的完整落地:

```
A = ⟨π_θ, M, T, V, E⟩  (R-radar-6)
   π_θ → T → V → M
   ↑                  ↓
   └── Correction ────┘   (R-radar-7)
```

V 失败 → Correction → 新 action → V → ...

## 跟 R-radar-8 (Multi-Agent) 的关系

R-radar-8 多个 agent 跑同一个 task, 每个 output 都过 V + Self-correction。R-radar-7 的 loop 给 R-radar-8 的 per-agent loop 提供基础。

## 测试 (49 tests, 0 失败)

| Test class | Tests | 覆盖 |
|---|---:|---|
| `SelfCorrectionLoopTest` | 18 | 构造 + 4 terminal reason + history + crash containment + budget + attempt record |
| `RetryStrategyTest` | 10 | retrySame + transform + giveUpImmediately + boundedRetry (边界测试) |
| `LlmSelfCorrectionStrategyTest` | 10 | 构造 + revise + 3 fallback 场景 + of() factory + accessors |
| `HumanCorrectionStrategyTest` | 11 | unwired / wired / null future / crash / timeout / fallback / accessors |
| **Total** | **49** | |

## 跟 aethercode 现有模块的集成 (后续 R-round)

R-radar-7 给后续 round 提供了几个 hook:

1. **DeepAgentsSystem.respond()** — 在 execute action 之前, 跑 SelfCorrectionLoop(compositeVerifier, retrySame, 3). 失败 3 次就 fallback 到 hard-coded safe action
2. **LanggraphAgent.makeBareGraph** — 给 LangGraph 多步 task 加 V + Self-correction, 替换 hard-coded tool failure handling
3. **R-radar-8 Multi-Agent** — 每个 sub-agent 跑一个 loop, 主 agent 投票决定哪个 sub-agent output 通过

## 教训 (新增 8 条, 累计 184+)

177. **🆕 Self-correction 是 V 的反应, 不是替代** — V 失败 reason 进 self-correction prompt, 让 LLM / human 知道"哪里错了"。这跟 R-radar-6 是孪生框架: V 评, self-correction 应
178. **🆕 CorrectionStrategy 三档 (cheap / LLM / human) by risk** — simple retry → LLM revise → human gate, 按 risk 升级, 跟 R-radar-6 CompositeVerifier 的 3 policy 平行
179. **🆕 fallback chain 很重要** — primary strategy 给 null 时, 自动 consult fallback。fallback 可以是另一种 strategy。这样 V 失败 → LLM 决定放弃 → retrySame (transient 适用) → 通过
180. **🆕 budget 是 attempts 不是 retries** — `maxAttempts=3` 是 3 次 verify (含初次), 2 次 correction。每次 V 都跑, history 完整, audit 友好
181. **🆕 错误 containment 是 default** — verifier crash / strategy crash / future timeout / future failed → 都不挂掉 loop, 当作特殊 terminal reason 记录。这样 production 不会因为一个 transient error 把整个 self-correction budget 烧光
182. **🆕 4 种 terminal reason 不是 overkill** — PASSED / BUDGET_EXHAUSTED / STRATEGY_RETURNED_NULL / INTERRUPTED, 4 种给 audit log 提供 actionable signal。Eval report 可以算"agent 在 5 步内能自己修好"这种 metric
183. **🆕 HumanCorrectionStrategy 的 4 种 future 处理** — success / null / crash / timeout 都 fall back to fallback (or null)。跟 R-radar-6 HumanVerifier 同样的"任何意外都 graceful 退出"哲学
184. **🆕 Audit log friendly** — `Attempt<T>` record + `LoopResult.attempts` 列表, audit log 可以看到每个 (action, verdict) pair。`failureTrail()` 是 one-line summary 给 evals

## 后续 (R-radar-8+)

- **R-radar-8**: Multi-Agent 对抗, 复用 R-radar-6 V + R-radar-7 Self-correction
- **R-radar-7 后续**: 集成进 DeepAgentsSystem.respond() 流程
- **可能单独 round**: 把 selfcorrect package 提到新 `aethercode-selfcorrect` module, 给 aethercode-a2a 等其他模块用
