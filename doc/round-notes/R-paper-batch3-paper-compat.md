# R-paper-batch3 + Tier-3 兼容实现 (2026-09-12)

**触发**: 用户要求 (1) paper 观点依据作为独立技术文档, (2) 多筛 AI Agent paper, 实现部分在文档中补充
**状态**: ✅ 收口

## 1. 本轮产出

### 1.1 9 篇新 paper 中文摘要

| ArXiv ID | 标题 | 主题 | PDF |
|---|---|---|---:|
| 2511.09030 | MAKER: Million-Step Zero Error | 极小 LLM + 投票 + 红旗 | 8.3 MB |
| 2503.09572 | Plan-and-Act | Planner/Executor 分离 | 1.5 MB |
| 2504.16563 | GoalAct | Global Plan + Hierarchical skill | 657 KB |
| 2506.04625 | Tool-MVR | MAMV + EXPLORE | 3.5 MB |
| 2505.20670 | MIRROR | Intra + Inter reflection | 0.97 MB |
| 2410.07869 | WorFBench | Workflow gen benchmark | 3.3 MB |
| 2502.12110 | A-Mem | Zettelkasten memory | 0.91 MB |
| 2510.05442 | ARLAS | Adversarial RL safety | 1.2 MB |
| 2505.02279 | Agent Interop Protocols | MCP/ACP/A2A/ANP survey | 4.9 MB |
| 2508.01332 | BlockA2A | Secure A2A + blockchain | 0.72 MB |
| 2506.01804 | MCP × A2A | Integration methodology | 1.1 MB |
| 2503.03459 | Unified Mind Model | 8 cognitive abilities | 0.4 MB |

**累计 paper 摘要**: 22 篇 (含之前 10 篇)

### 1.2 2 篇新独立技术文档

| 文档 | 范围 | 大小 |
|---|---|---:|
| `doc/R-PAPER-EVIDENCE-INDEX.md` | 22 paper 观点依据索引 (9 主题分类) | 23 KB |
| `doc/R-AETHERCODE-MULTI-AGENT-IMPLEMENTATION.md` (深化版) | 22 paper 全部整合, 5 architecture × 5 topic 矩阵 | 20 KB |

### 1.3 3 个 Tier-3 兼容实现 (34 新 tests)

| 类 | Paper 来源 | Tests | 文件位置 |
|---|---|---:|---|
| `GlobalPlan` + `HierarchicalExecutor` | 2504.16563 GoalAct | 10 | orchestration.plan |
| `ByzantineDetector` | 2508.01332 BlockA2A | 12 | orchestration.security |
| `DynamicLinker` | 2502.12110 A-Mem | 12 | aethercode-memory |
| **Total** | | **34** | |

## 2. 关键技术决定 (10 条)

1. **Paper evidence 走独立索引文档** — `R-PAPER-EVIDENCE-INDEX.md` 按 9 主题分章, 每个主题列 paper + 观点 + AetherCode 对应 + 候选
2. **22 paper 整合到 1 个深化 doc** — `R-AETHERCODE-MULTI-AGENT-IMPLEMENTATION.md` 升级, 不分散到 5 个 doc
3. **新 Tier-3 实现选 3 篇 paper, 跨 3 个 module** — GoalAct (orchestration.plan) + BlockA2A (orchestration.security) + A-Mem (aethercode-memory)
4. **`GlobalPlan` 末位强制 FINISH** — paper 明确要求, 用 enum + 构造校验
5. **`HierarchicalExecutor` 用 Builder 模式** — 每种 skill 可定制 strategy
6. **`ByzantineDetector` 5 规则 0 LLM** — 输出长度 / 重复 / 错误率 / 回声攻击 / 空输出, 纯 deterministic 启发式
7. **`ByzantineDetector.Severity.HIGH` 自动 flag agent** — 跟 paper DOE 的 "flag + halt + revoke" 对齐
8. **`DynamicLinker` 双向建链** — 新 note 加进去, 旧 note 也加 back-link
9. **`DynamicLinker` similarity 抽象** — `BiFunction<Note, Note, Double>` 可插拔, 默认 Jaccard over keywords
10. **修复 AetherCodeMethods.java javadoc 起始** — `**` 缺 `/`, 顺手 fix (3 行, em-dash 保留)

## 3. 测试状态

| Module | 旧 | 新增 | 当前 | Pass |
|---|---:|---:|---:|---|
| aethercode-orchestration | 275 | +23 (10 GlobalPlan + 12 Byzantine + 1 HierarchicalExecutor) | **298** | ✅ |
| aethercode-memory | 240 | +12 (DynamicLinker) | **252** | ✅ |
| aethercode-evals | 606 | 0 | **606** | ✅ |
| **Total** | 1121 | **+34** | **1156** | **0 fail** |

## 4. 累计 paper 主题覆盖 (22 篇)

| 主题 | 数量 | 代表 paper |
|---|---:|---|
| Multi-Agent Architecture | 4 | 2512.08296, 2506.12508, 2601.01743, 2501.06322 |
| Tool Use & Reflection | 5 | 2506.04625, 2505.20670, 2608.04719, 2603.22862, 2509.18847 |
| Planning & Reasoning | 5 | 2503.09572, 2504.16563, 2511.09030, 2410.07869, 2508.17281 |
| Memory & Continual | 4 | 2502.12110, 2501.07278, 2512.13564v2, 2508.03341 |
| Safety & Alignment | 3 | 2510.05442, 2508.01332, 2508.10146 |
| Protocol & Interop | 3 | 2505.02279, 2506.01804, 2502.16750 |
| Cognitive Architecture | 2 | 2503.03459, 2505.07087 |
| Survey / Holistic | 4 | 2510.25445, 2508.10146, s11831-026, 2608.20379 |
| Evaluation & Benchmark | 3 | 2512.12791, 2510.22898, 2510.10472 |
| **Total** | **22** | |

## 5. 后续 (Tier-3 候选池剩 23 个)

R-PAPER-EVIDENCE-INDEX §11 列了 26 个候选, 本轮实现 3 个, 剩 23 个候选待后续 R-round:

- CentralPlanner (2506.12508)
- AgentArchitectureSelector (2512.08296)
- CapabilitySaturationDetector (2512.08296)
- AgentAssessmentFramework (2512.12791)
- DynamicReplanner (2503.09572)
- FirstToAheadByKVoting (2511.09030)
- RedFlagDetector (2511.09030)
- MAMVToolValidator (2506.04625)
- IntraReflectionHook (2505.20670)
- AgenticMemoryNote (2502.12110)
- MemoryEvolutionPolicy (2502.12110)
- ZettelkastenStore (2502.12110)
- MotivationalDriver (2503.03459)
- PromptInjectionSimulator (2510.05442)
- PersistentRuntimeTrace (2508.01332)
- DefenseOrchestrationEngine (2508.01332)
- AnpDiscovery (2505.02279)
- ProtocolRouter (2506.01804)
- SubsequenceMatchMetric (2410.07869)
- SubgraphMatchMetric (2410.07869)
- ...

## 6. 教训 (新增 8 条, 累计 200+)

**345-352 (本轮)**:
345. **Paper evidence 走独立索引 doc** — 不分散到 5 个 doc, 一个 R-PAPER-EVIDENCE-INDEX 跟全部
346. **Tier-3 实现选 3 个跨 module** — 避免单 module 重复
347. **`GlobalPlan` 末位强制 FINISH** — paper 要求, 构造时校验
348. **`ByzantineDetector` 纯启发式 5 规则** — 0 LLM 也能 detect echo / overflow / error flood
349. **`ByzantineDetector.Severity.HIGH` 自动 flag** — DOE 的 flag 机制
350. **`DynamicLinker` 双向建链** — 新 + 旧 都更新
351. **similarity 抽象成 BiFunction** — Jaccard default, production 可换 embedding
352. **Set-Content -Encoding UTF8 加 BOM + 破坏 em-dash** — 修 javadoc 时踩坑, 改用 Python raw bytes
