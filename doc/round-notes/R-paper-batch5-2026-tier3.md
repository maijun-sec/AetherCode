# R-paper-batch5-2026 + Tier-3 batch 3 (2026-09-13)

**触发**: 用户要求继续 Tier-3 + 找 2026 新论文到 40+ 篇
**状态**: ✅ 收口 (41 paper 累计, 1287 tests pass)

## 1. 本轮产出

### 1.1 10 篇 2026 新 paper 中文摘要 (batch 5)

| ArXiv ID | 标题 | 主题 |
|---|---|---|
| 2602.07755 | ALMA (Meta-learning Memory) | Memory meta-learning |
| 2606.06787 | AdMem (3 Memory Types) | Semantic/Episodic/Procedural |
| 2605.21951 | MoLEM (Latent Memory MoE) | Continual learning |
| 2604.12179 | AgeMem (Unified LT/ST) | Memory tools + RL |
| 2607.20064 | PRO-LONG (Programmatic Memory) | Code-based log search |
| 2601.07577 | TDP (Task-Decoupled Planning) | Scoped context, 82% token 节省 |
| 2601.12538 | Agentic Reasoning Survey 2026 | 3 方向 + 2 路径综述 |
| 2605.22138 | SR2AM (Self-Regulated Planning) | 3 系统 (I/II/III) |
| 2604.05939 | CVA (Context-Value-Action, 北大) | 行为 fidelity + Schwartz 价值 |
| 2608.28978 | Selective Forgetting (Graph Memory) | 3 维 forgetting |

**累计 41 paper**, 已超 40+ 目标 ✅

### 1.2 5 个 Tier-3 兼容实现 (57 new tests)

| 类 | Paper 来源 | Tests | 文件位置 |
|---|---|---:|---|
| `TaskDecoupledPlanner` | 2601.07577 TDP | 11 | orchestration.planner |
| `ProceduralMemory` | 2606.06787 AdMem | 10 | aethercode-memory |
| `SelectiveForgettingPolicy` | 2608.28978 | 12 | aethercode-memory |
| `GraphMemoryStore` | 2608.28978 | 16 | aethercode-memory |
| `MemoryCriticAgent` | 2606.06787 AdMem | 8 | aethercode-memory |
| **Total** | | **57** | |

## 2. 测试状态

| 模块 | 旧 | 新增 | 当前 | Pass |
|---|---:|---:|---:|---|
| aethercode-orchestration | 371 | +11 (TaskDecoupledPlanner) | **382** | ✅ |
| aethercode-memory | 252 | +47 (ProceduralMemory 10 + SelectiveForgetting 12 + GraphMemoryStore 16 + MemoryCritic 8) | **299** | ✅ |
| aethercode-evals | 606 | 0 | **606** | ✅ |
| **Total** | 1229 | **+58** | **1287** | **0 fail** |

## 3. 累计 Tier-3 兼容实现 (15 个)

| 批次 | 数量 | 类 |
|---|---:|---|
| R-paper-batch3 | 4 | GlobalPlan, HierarchicalExecutor, ByzantineDetector, DynamicLinker |
| R-paper-batch4 | 6 | CentralPlanner, AgentArchitectureSelector, CapabilitySaturationDetector, FirstToAheadByKVoting, RedFlagDetector, AgentAssessmentFramework |
| R-paper-batch5 | 5 | TaskDecoupledPlanner, ProceduralMemory, SelectiveForgettingPolicy, GraphMemoryStore, MemoryCriticAgent |
| **Total** | **15** | |

## 4. 累计 41 paper 主题分布 (10 主题)

| 主题 | 数量 |
|---|---:|
| Multi-Agent Architecture | 5 |
| Tool Use & Reflection | 5 |
| **Planning & Reasoning** | **9** |
| **Memory & Continual** | **9** |
| Safety & Alignment | 4 |
| Protocol & Interop | 4 |
| Cognitive Architecture | 4 |
| Survey / Holistic | 4 |
| Evaluation & Benchmark | 4 |
| Programmatic / Code Memory | 2 |
| **Total** | **41** |

## 5. 关键技术决定

1. **Memory 主题成最大类** — 9 篇 paper, 反映 LLM agent 2026 焦点
2. **Planning 主题 9 篇** — Planning 是 long-horizon 关键
3. **5 个 Tier-3 实现跨 2 个 module** — orchestration.planner + aethercode-memory
4. **TaskDecoupledPlanner 严格 scoped context** — Planner 只看 prereq outputs
5. **Self-Revision 显式可插拔** — `RevisionPolicy` functional interface
6. **ProceduralMemory 跟 SemanticMemory/EpisodicMemory 分离** — 跟 AdMem 3-type 对齐
7. **SelectiveForgettingPolicy 3 维 (recency + frequency + importance)** — 跟 paper 严格对齐
8. **GraphMemoryStore multi-hop BFS** — 支持 paper 2608.28978 multi-hop 检索
9. **MemoryCriticAgent 3 decisions (KEEP/MERGE/PRUNE)** — 跟 AdMem reward-based governance
10. **Test 用 policyAt(NOW) 工厂** — 因为 default policy 的 nowMs 是 System.currentTimeMillis, test 不可靠

## 6. 教训 (新增 10 条, 累计 222+)

**365-374 (本轮)**:
365. **2026 论文是主流** — 比 2025 更直接对应 AetherCode
366. **Memory 主题爆棚** — 9 篇 paper, 反映 agent 演化焦点
367. **Test 用固定 nowMs** — default policy 用 System.currentTimeMillis, test 不可靠
368. **GraphMemoryStore removeNode 用 iterator** — removeIf 在某些场景 CME
369. **ProceduralMemory 独立类** — 不跟 ExperienceRecord 混
370. **MemoryCritic 3 decisions** — KEEP / MERGE / PRUNE, 跟 AdMem governance 对齐
371. **TDP 82% token 节省** 是最强 single-data point
372. **Paper 2607.20064 PRO-LONG +18pp ARC-AGI-3** 验证 programmatic memory
373. **Schwartz 10 价值观** 是新维度, 跟 task success 互补
374. **41 paper 已超 40+ 目标** — 后续 R-round 可聚焦深化而非增量
