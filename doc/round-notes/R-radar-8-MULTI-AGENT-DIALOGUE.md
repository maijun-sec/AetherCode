# R-radar-8: Multi-Agent 对抗 (角色 + 投票 + 评审)

**Round**: R-radar-8 (最后 radar round)  
**Date**: 2026-09-12  
**Module**: `aethercode/aethercode-evals/src/main/java/org/aethercode/evals/multiagent/`  
**Goal**: 实现 multi-agent ensemble 框架, 让多个 agent 跑同一个 task, 用 vote / debate / critique 策略合成最终答案。

## TL;DR

- 新增 5 个 main 文件 (~22 KB) + 4 个 test 文件 (~30 KB) 在 `aethercode-evals/multiagent/`
- **43 tests** 跨 4 个 test class, **333/333 aethercode-evals tests pass** (127 R-radar-2 + 114 R-radar-6 + 49 R-radar-7 + 43 R-radar-8)
- 3 种 ensemble 策略: VoteStrategy (PLURALITY / MAJORITY / UNANIMOUS) / DebateStrategy (迭代 refinement) / CritiqueStrategy (N proposers + M critics)
- 1 个 `MultiAgentOrchestrator<T>` 把 strategy + agents 包成统一入口
- 集成 R-radar-6 (V 校验器) + R-radar-7 (Self-correction) 的全部能力
- 跟现有 `aethercode-tools/task/AgentTool` (spawn_agent) 互补 — AgentTool 是 IPC 层, R-radar-8 是 orchestration 策略

## 关键决定 (8 条)

1. **3 种 ensemble 策略覆盖论文 5.1 / 5.2 全部 pattern** — Vote (Self-Consistency / 多 sample) / Debate (iterative refinement) / Critique (judge model)。MetaGPT / CAMEL / AgentBoard / majority-vote / self-consistency 全部覆盖
2. **pluggable AgentFn<T>** — `respond(prompt, peers) -> T`, 测试用 stub `(p, pe) -> "fixed"`, production wire 真 LLM 或 a2a agent。`peers` 在 vote mode 是空, 在 debate / critique 是其他 agent 的输出
3. **3 种 VoteMode (PLURALITY / MAJORITY / UNANIMOUS)** — 不同严格度。PLURALITY 是默认 (MetaGPT 风), MAJORITY 跟 self-consistency 一致, UNANIMOUS 是"必须全同意才过"
4. **MAJORITY / UNANIMOUS 有 fallback** — 没达到阈值时, fallback 到 plurality + 在 metadata 标 "no majority, picked plurality winner"。让 agent loop 知道"没达成共识但还是给个 best-effort"
5. **DebateStrategy 早停** — round 1 全 agent 同意就停, `early_stopped=true` 在 metadata。避免无谓的 LLM call
6. **CritiqueStrategy 角色分工** — N proposers (frontier model, 贵) + M critics (small model, 便宜)。Critics 给每个 proposal 打分, total 最高的 proposal 胜出。Proposers 看不到 critics 的输出 (避免 cheat)
7. **错误 containment** — agent 抛 / critic 抛 / agent 返回 null, 都不挂 orchestrator。Sentinel value `<<agent-failed:NAME:EXC>>` 替罪, audit log 标 `[FAILED]`。Production 想换 logging 可 override
8. **per-strategy metadata 给 audit log** — VoteStrategy: tally + consensus + fallback. DebateStrategy: rounds + consensus + early_stopped. CritiqueStrategy: total_scores + per_critic. 每个 strategy 的 metadata 都是后续 eval report / R-round 调试的入口

## 架构

```
                          ┌──────────────────────────────┐
                          │ MultiAgentOrchestrator<T>    │
                          │  - name, agents, strategy     │
                          └──────────────────────────────┘
                                          │
                              ┌───────────┴───────────┐
                              ▼                       ▼
                  ┌──────────────────┐     ┌────────────────────┐
                  │ VoteStrategy     │     │ DebateStrategy     │
                  │  - PLURALITY     │     │  - maxRounds       │
                  │  - MAJORITY      │     │  - early_stop      │
                  │  - UNANIMOUS     │     │  - peer visibility│
                  │  - parallel      │     └────────────────────┘
                  │    executor      │
                  └──────────────────┘     ┌────────────────────┐
                                            │ CritiqueStrategy   │
                                            │  - N proposers      │
                                            │  - M critics        │
                                            │  - score → winner   │
                                            └────────────────────┘
```

## 3 种 strategy 详细

### 1. VoteStrategy (并行 / 最常见)
- 3 模式: PLURALITY (多者胜) / MAJORITY (>50%) / UNANIMOUS (100%)
- Tie-break: first registered 胜出 (avoid randomness, deterministic)
- 失败 fallback: 标记 metadata 让 agent loop 知道 "consensus 失败但有 best-effort"
- 并行: 传 `Executor` 时 N agents 并行跑 (`CompletableFuture.supplyAsync`); 不传则顺序

### 2. DebateStrategy (迭代 refinement)
- N agents 跑 R rounds, 每 round 互相看上一轮输出
- 早停: 全 agent 同意时停止 (`early_stopped=true`)
- Peer visibility: agent i 看到 round-N outputs from 其他 agents, 不包括自己
- 用于: 多 model 共识, complex reasoning (ReAct + multi-agent)

### 3. CritiqueStrategy (proposers + critics)
- Phase 1: N proposers 跑 (frontier model)
- Phase 2: M critics 给每个 proposal 打分 (small model)
- Phase 3: total 最高的 proposal 胜出
- Critics 看到全部 proposals, 给出 comparable 分数
- 用于: 高质量输出 (用便宜 model 评贵 model), leader-board 风格

## 跟 R-radar-6 / 7 / 8 串成完整 pipeline

```
A = ⟨π_θ, M, T, V, E⟩  (R-radar-6, V 是校验器集)
                              ↓
   V failed → self-correct (R-radar-7) → new action
                              ↓
   Multi-Agent Ensemble (R-radar-8):
   - N agents each produce a candidate
   - Vote / Debate / Critique combine them
   - Winning output → next V check
   - 如果 V 失败 → self-correct → ensemble again
   - 直到 pass 或 budget exhausted
```

这样 agent loop 不只是单 agent + retry, 而是 **multi-agent 共识 + single-agent self-correction**。

## 测试 (43 tests, 0 失败)

| Test class | Tests | 覆盖 |
|---|---:|---|
| `VoteStrategyTest` | 14 | 3 mode × (pass / fail) + tie-break + 错误 containment + 并行 executor |
| `DebateStrategyTest` | 11 | 1-N rounds + early stop + peer visibility (含 "peers exclude self") + 错误 containment |
| `CritiqueStrategyTest` | 9 | 1-N critics + 多 critic 累加 + tie-break + 错误 containment + per-critic metadata |
| `MultiAgentOrchestratorTest` | 9 | 构造 + wiring + EnsembleResult helpers + AgentOutput record |
| **Total** | **43** | |

## 跟 aethercode 现有模块的集成 (后续 R-round)

R-radar-8 给后续 round 提供了 3 个 hook:

1. **aethercode-tools/task/AgentTool** — `spawn_agent` tool 已经能 spawn 多个 sub-agent. 可以包装成 `MultiAgentOrchestrator` 给 desktop / TUI / workflow 用
2. **aethercode-a2a** — A2A protocol 是 agent-to-agent communication. R-radar-8 ensemble 可以跑跨进程: 一个 agent 在本地, 另一个通过 a2a 调远端
3. **R-radar-7 SelfCorrectionLoop** — 每个 sub-agent 自己跑一个 self-correction loop, 然后 ensemble. 这就形成 "N parallel self-correcting agents, voted on" 模式

## 教训 (新增 8 条, 累计 192+)

185. **🆕 3 种 ensemble 策略覆盖论文 5.1 / 5.2 全部 pattern** — Vote (Self-Consistency) / Debate (iterative refinement) / Critique (judge model). 1 个 framework 3 个 strategy, 不要每个 strategy 写一个 class
186. **🆕 AgentFn 是 functional interface, 接受 peers** — `(prompt, peers) -> T`. peers 在 vote 是空, 在 debate 是其他 agent 输出, 在 critique 是 proposals. 1 个 interface 3 个 use
187. **🆕 consensus = "all agreed" 不是 "majority agreed"** — 2 of 3 ≠ consensus. 修这个测试 bug 时发现; 跟"严格 unanimous"才叫 consensus 一致
188. **🆕 tie-break 用 first-registered** — Deterministic, audit-friendly. 用 `thenComparing(e -> -indexOf...)` 让 Max() 选最小 index (first registered)
189. **🆕 MAJORITY/UNANIMOUS 有 fallback + metadata flag** — 不静默 fallback, 在 metadata 写 "no majority, picked plurality winner". Agent loop / eval report 看这个 flag 知道 "best-effort 没共识"
190. **🆕 Debate 早停** — round 1 全 agree 就停, `early_stopped=true` 在 metadata. 节省 LLM call + eval report 能算 "agent 一轮就达成共识" 这种 metric
191. **🆕 Critics 看不到自己 (跨 agent transparency)** — CritiqueStrategy 的 critics 看到所有 proposals (是 input), 但 proposers 看不到 critics (是 oracle). 防止 "model 偏向训练过的 pattern" 这种 bias
192. **🆕 错误 containment 用 sentinel value** — `<<agent-failed:NAME:EXC>>` 字符串替罪, audit log 标 `[FAILED]`. 跟 R-radar-6 verifier crash containment 一致, 都是 fail-loud 不 fail-silent

## R-radar 全部 8 round 收口

| Round | 内容 | Tests | commit |
|---|---|---:|---|
| R-radar-1 | ai-agent-validation.md 重写 | 0 (doc) | 9ba4f10 |
| R-radar-2 | aethercode-evals 0 → 127 tests | 127 | 1e5ae18 |
| R-radar-3 | evals.md (5 benchmark 详解) | 0 (doc) | 94e97eb |
| R-radar-4 | google_scholar tool | 23 | 66b838b |
| R-radar-5 | arxiv_fetch tool | 25 | d6e6f06 |
| R-radar-6 | V 校验器框架 | 114 | c82ec6f |
| R-radar-7 | Self-correction 机制 | 49 | 525246c |
| **R-radar-8** | **Multi-Agent 对抗** | **43** | **本 round** |
| **Total** | | **381** | |

**累计**: 8 round, 6 module (evals + tools + doc), 381 tests, 0 regression

**重大 gap 全部覆盖**:
- R-radar-1: aethercode-evals 模块之前完全没暴露 → ✅ 写到 5 benchmark + 7 CLI + 127 tests + evals.md
- R-radar-4: 之前没学术搜索 tool → ✅ google_scholar (Semantic Scholar backend) + arxiv_fetch
- R-radar-6: 之前没 V 校验器 (论文 2601.01743 5 元组缺 1) → ✅ 5 verifier + composite + 114 tests
- R-radar-7: 之前没 self-correction (论文 2508.17281 缺) → ✅ 3 strategy + 49 tests
- R-radar-8: 之前没 multi-agent 对抗 (论文 2601.01743 §III.1.1 缺) → ✅ 3 strategy + 43 tests

## 后续 (R-radar 之外)

- **集成 round**: 把 V + self-correct + multi-agent 串进 DeepAgentsSystem.respond() / AgentTool.spawn_agent
- **a2a 集成**: MultiAgentOrchestrator 用 a2a 调远端 agent (跨进程 ensemble)
- **性能 round**: ensemble N agents 是 N×cost, 加 cost ceiling / early-exit / cache
- **E2E round**: 跟 reference/papers/ 的 8 篇 paper 一起, 跑 1 个 end-to-end 评测 (scholar → arxiv → critique → verdict)
- **可能单独 round**: 修 Cli parser 2 个 bug (R-radar-2 发现的 remainder mode + nested sub-options)
