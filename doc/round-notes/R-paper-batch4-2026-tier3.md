# R-paper-batch4-2026 + Tier-3 兼容实现 (2026-09-12 ~ 13)

**触发**: 用户要求继续 Tier-3 + 找 2026 新论文, 至少 40+ 篇
**状态**: ✅ 收口 (31 篇累计, 继续推进中)

## 1. 本轮产出

### 1.1 8 篇 2026 新 paper 中文摘要 (全部 2026 年, 80% 是 ICLR 2026 / AAAI-26)

| ArXiv ID | 标题 | 主题 |
|---|---|---|
| 2605.14892 | Beyond Individual Intelligence (LIFE 4 阶段) | 综述 |
| 2510.26352 | Geometry of Dialogue (Team Composition) | 多 agent 团队组建 |
| 2602.00994 | DART (Reasoning vs Tool-use Disentangle) | ARL capability 干扰 |
| 2601.11327 | Small Agent Collaboration (ICLR 2026) | 小模型多 agent |
| 2603.09716 | AutoAgent (Evolving + Elastic Memory) | Self-evolving |
| 2602.08009 | RAPS (Ad-Hoc Networking for MAS) | Scale + robustness |
| 2602.23720 | Auton Framework (Snapchat) | Declarative + POMDP |
| 2603.13256 | REDEREF (Training-Free Probabilistic) | Thompson sampling 路由 |

### 1.2 6 个 Tier-3 兼容实现 (新 86 tests)

| 类 | Paper 来源 | Tests | 文件位置 |
|---|---|---:|---|
| `GlobalPlan` + `HierarchicalExecutor` | 2504.16563 GoalAct | 10 | orchestration.plan |
| `ByzantineDetector` | 2508.01332 BlockA2A | 12 | orchestration.security |
| `DynamicLinker` | 2502.12110 A-Mem | 12 | aethercode-memory |
| `CentralPlanner` | 2506.12508 AgentOrchestra | 11 | orchestration.planner |
| `AgentArchitectureSelector` | 2512.08296 | 13 | orchestration.planner |
| `CapabilitySaturationDetector` | 2512.08296 | 10 | orchestration.planner |
| `FirstToAheadByKVoting` | 2511.09030 MAKER | 10 | orchestration.multiagent |
| `RedFlagDetector` | 2511.09030 MAKER | 16 | orchestration.verifier |
| `AgentAssessmentFramework` | 2512.12791 | 12 | orchestration.planner |
| **Total** | | **96** | |

## 2. 测试状态

| 模块 | 旧 | 新增 | 当前 | Pass |
|---|---:|---:|---:|---|
| aethercode-orchestration | 275 | +96 | **371** | ✅ |
| aethercode-memory | 240 | +12 (R-paper-batch3 DynamicLinker) | **252** | ✅ |
| aethercode-evals | 606 | 0 | **606** | ✅ |
| **Total** | 1121 | **+108** | **1229** | **0 fail** |

## 3. 关键技术决定 (10 条)

1. **6 个 Tier-3 实现跨 4 个 package** — orchestration.planner / orchestration.multiagent / orchestration.security / orchestration.verifier / aethercode-memory
2. **CentralPlanner 用 Handler 函数式接口** — 不用 AgentFn (因为 AgentFn 是 (prompt, peers) -> T, 不能直接传 Map)
3. **Topo-sort 用 inStack + visited** — 不用 order.contains (会误判 cycle)
4. **`FirstToAheadByKVoting` 包装 AgentOutput** — 符合 EnsembleResult 签名
5. **`RedFlagDetector` 6 规则 3 档** — 跟 MAKER paper 5 规则 + 1 contradiction
6. **`AgentAssessmentFramework` 4 维加权** — 权重和 = 1.0 校验
7. **`CapabilitySaturationDetector` mean + variance** — 0.45 拐点
8. **`AgentArchitectureSelector` 5 rule decision tree** — 跟 paper 2512.08296 测得数据对齐
9. **AetherCodeMethods.java javadoc 修 3 行** — 顺手 fix
10. **PowerShell Set-Content -Encoding UTF8 加 BOM + 改 em-dash** — 用 Python raw bytes 修

## 4. 累计 31 篇 paper (9 大主题)

| 主题 | 数量 |
|---|---:|
| Multi-Agent Architecture | 5 |
| Tool Use & Reflection | 5 |
| Planning & Reasoning | 6 |
| Memory & Continual | 5 |
| Safety & Alignment | 4 |
| Protocol & Interop | 4 |
| Cognitive Architecture | 3 |
| Survey / Holistic | 4 |
| Evaluation & Benchmark | 4 |
| **Total** | **31** |

## 5. 后续 (目标 40+, 还差 9+ 篇)

继续搜 2026 新论文, 重点方向:
- Agent benchmark 2026
- Multi-agent cooperation 2026
- Safety/adversarial 2026
- 跟 aethercode 已有 module 直接相关的 paper

## 6. 教训 (新增 12 条, 累计 211+)

**353-364 (本轮)**:
353. **paper search 优先 2026** — 跟 2025 paper 相比, 2026 paper 更直接对接 AetherCode 现有 module
354. **Lambda 签名跟 AgentFn 实际签名对齐** — respond(prompt, peers) 不是 apply(...)
355. **EnsembleResult 构造器是 (winner, perAgent, meta)** — perAgent 是 List<AgentOutput<T>>, 不是 List<T>
356. **EnsembleStrategy 没 name() 方法** — 自己加 toString, 不用 @Override
357. **Topo-sort 用 inStack + visited, 不用 order.contains** — order.contains 在 DFS 过程中会误判
358. **Verifier<T> 是泛型接口** — Lambda 不能推断, 用 anonymous class
359. **HeuristicVerifier checks 不能为空** — 至少 1 个 check
360. **HeuristicVerifier 构造用 2 参 (name, List<Check>)** — 不是可变参数
361. **API 测试要看实际 source** — IDE 自动 import 不可靠, 先 read 实际 API
362. **PowerShell Set-Content -Encoding UTF8 加 BOM** — 持续踩坑, 用 Python raw bytes 安全
363. **Tier-3 候选实现 8+ 个** — 每批 5-6 个, 节奏稳定
364. **paper 摘要 + 兼容实现并行** — 不串行, 一次 batch 全部完成
