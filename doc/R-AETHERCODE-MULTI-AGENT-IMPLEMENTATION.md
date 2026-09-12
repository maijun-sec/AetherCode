# AetherCode Multi-Agent Architecture Implementation Guide (深化版)

**Date**: 2026-09-12
**Status**: 跟 22 篇 paper 全面对齐
**Scope**: AetherCode 现有 + 计划的多 agent / tool / memory / safety / planning 实现, 跟 22 篇 arXiv paper 映射
**前置阅读**: `doc/R-PAPER-EVIDENCE-INDEX.md` (paper 观点依据索引)

---

## 一、5 Architecture 实现矩阵 (paper 2512.08296)

| Architecture | AetherCode Class | Location | Paper 来源 | 错误放大 | 何时用 |
|---|---|---|---|---:|---|
| **Single** | `AgentRuntime` (单 runtime) | orchestration.runtime | (R-orch-1) | 1.0× | 简单 / sequential |
| **Independent** | `IndependentStrategy` (R-orch-3.5) | orchestration.multiagent | 2512.08296 §3.3 | **17.2×** | dynamic web (+9.2%) |
| **Centralized** | `VoteStrategy` + `CritiqueStrategy` + `DebateStrategy` | orchestration.multiagent | 2601.01743 §III.1.1 | **4.4×** | parallelizable (+80.8%) |
| **Decentralized** | ❌ (未来) | (TBD) | 2512.08296 §3.3 | (中间) | 复杂协同 / 大规模 |
| **Hybrid** | `HybridStrategy` (R-orch-3.5) | orchestration.multiagent | 2512.08296 §3.3 | < 4.4× | 工具多 + 需要 verify |

---

## 二、5 Strategy 详解

### 2.1 VoteStrategy (Centralized 雏形)
**Paper**: 2601.01743 §III.1.1
```java
public final class VoteStrategy<T> implements MultiAgentOrchestrator.EnsembleStrategy<T> {
    public enum VoteMode { PLURALITY, MAJORITY, UNANIMOUS }
    public VoteStrategy(VoteMode mode) { ... }
    public EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents) { ... }
}
```
- N agent 跑同一 prompt, peers 列表为空
- 选出现次数最多的 output (PLURALITY / MAJORITY / UNANIMOUS)
- metadata: voteMode, winnerFrequency, tiesBroken

### 2.2 DebateStrategy (Centralized 迭代)
**Paper**: 2601.01743 §III.1.1
- N agent 迭代, 每 round 看到上一 round 的 outputs (peers 列表非空)
- 早停: 全 agree / 达 maxRounds
- metadata: rounds, converged, earlyStop

### 2.3 CritiqueStrategy (Centralized 中央评分)
**Paper**: 2601.01743 §III.1.1
- N proposer agent 跑 prompt
- M critic agent 给每个 proposal 打分 (0..1)
- 选 score 最高的 proposal

### 2.4 IndependentStrategy (paper 2512.08296)
- N agent 各自跑 (peers 列表永远空 — no communication)
- PickMode 决定: FIRST / RANDOM / LAST
- **错误放大 17.2× by design** (适合 dynamic web +9.2%)
- **为什么 no verify**: Independent 的特点就是"无 communication + no verify"

### 2.5 HybridStrategy (paper 2512.08296)
- 2 阶段: Decentralized execution + Centralized verification
- 第一个 pass 的 agent wins
- 全部 fail → 选 lowest-severity 的 fallback
- verifier crash → wrap 成 BLOCK failure, 不 poison loop
- 错误放大 < 4.4×

---

## 三、Planning 实现矩阵 (4 篇 paper)

### 3.1 4 Planning Architecture 对比

| Paper | 模式 | 适用 | 错误率 | AetherCode 状态 |
|---|---|---|---:|---|
| **2503.09572 Plan-and-Act** | Planner + Executor 分离, dynamic replan | WebArena / WebVoyager | 低 | ⚠️ DagPlan + AgentRuntime, 缺 dynamic replan API |
| **2504.16563 GoalAct** | Global Plan 持续更新 + Hierarchical skill | LegalAgentBench | 中 (SOTA) | ⚠️ 缺 GlobalPlan + Skill 抽象 |
| **2511.09030 MAKER** | MAD (m=1) + voting + red-flag | 长链 1M+ 步 | **零** | ⚠️ 概念覆盖, 缺 k-voting / red-flag |
| **2410.07869 WorFBench** | 复杂图 workflow + subsequence/subgraph 评测 | Workflow gen | N/A | ⚠️ 缺细粒度 metric |

### 3.2 跟 AetherCode DagPlan 集成 (待办)

```java
// GoalAct 风格 GlobalPlan (持续更新)
class GlobalPlan {
    Plan update(Plan current, String query, ToolRegistry tools, History state);
    // 每步调用, 重新生成 plan, 注入新观察到的实体
}

// GoalAct 风格 Skill 抽象
enum Skill { SEARCHING, CODING, WRITING, REASONING, OTHER }
class SkillExecutor { T execute(Skill skill, T input); }

// MAKER 风格 voting
class FirstToAheadByKVoting<T> {
    T vote(List<T> candidates, int k);
    // 第一个 margin >= k 的候选 wins
}

// MAKER 风格 red-flag 检测
class RedFlagDetector {
    List<String> detect(String output);
    // 格式异常 / 长度超限 / 重复 / 矛盾
}

// Plan-and-Act 风格 dynamic replan
class DynamicReplanner {
    Plan replan(String query, Plan current, List<Action> history);
}
```

---

## 四、Tool Use & Reflection 矩阵 (5 篇 paper)

### 4.1 Tool Reflection 5 篇对比

| Paper | 核心 | 数据 | AetherCode 状态 |
|---|---|---|---|
| **2506.04625 Tool-MVR** | MAMV multi-verify + EXPLORE error→reflect→correct | StableToolBench +23.9% | ⚠️ Verifier 有, MAMV 缺 |
| **2505.20670 MIRROR** | Intra (前) + Inter (后) 双层 reflection | TravelPlanner SOTA | ⚠️ Inter 有, Intra 缺 |
| **2608.04719 Canary** | Canary tool 测 prompt injection 防御 | 测 injection 调用率 | ✅ R-eval-12 |
| **2603.22862 Multi-Tool** | 5+ tool 串联 + canary 5% | 长链任务 | ✅ R-eval-10 |
| **2509.18847 Failure Stronger** | DAPO + GSPO 训练 Reflect→Call→Final | Tool-Reflection-Bench | ⚠️ 概念有, 训练目标缺 |

### 4.2 Tool Reflection 实现路径 (待办)

```java
// Tool-MVR MAMV
class MAMVToolValidator {
    VerificationResult verify(ToolCall call, ToolSchema schema, Context ctx);
    // 4 个 sub-agent: API check / query check / reasoning check / consistency check
}

// MIRROR Intra-Reflection
class IntraReflectionHook implements Hook {
    Decision evaluate(Decision proposed, Context ctx);
    // LLM mental simulation: 预演执行结果, 拦下高风险
}

// Tool 错误修复 metric (跟 paper 9.1% → 58.9% 对齐)
class ToolErrorFixRate {
    double measure(AgentRun run);
}
```

---

## 五、Memory & Continual Learning 矩阵 (4 篇 paper)

### 5.1 4 内存架构对比

| Paper | 核心 | 概念 | AetherCode 状态 |
|---|---|---|---|
| **2502.12110 A-Mem** | Zettelkasten 5-attribute note + dynamic linking | 旧 memory 随新 memory evolve | ⚠️ ExperienceRecord 缺 tag/links |
| **2501.07278 Lifelong Learning** | 3 module (perception/memory/action) + stability-plasticity | Catastrophic forgetting | ⚠️ ForgettingPolicy 部分 |
| **2512.13564v2 Memory Survey** | 3 维 taxonomy (Forms/Functions/Dynamics) | Agent Mem ≠ RAG ≠ Context | ⚠️ 部分覆盖 |
| **2508.03341 Nemori** | Two-Step Alignment + Predict-Calibrate | Episode Segmentation | ⚠️ 缺 episode 切分 |

### 5.2 Memory 5 Attribute 跟 AetherCode 对比

| A-Mem 5 attribute | AetherCode 现状 | 缺什么 |
|---|---|---|
| `contextual_description` | `content` 字段 | 描述性 metadata |
| `keywords` | ❌ | 关键词提取 |
| `tags` | `kind` enum | 灵活 tag 列表 |
| `embedding_vector` | ❌ | 向量检索 |
| `links` | ❌ | 双向链接 |

### 5.3 兼容实现 (待办)

```java
// A-Mem 5-attribute note
record AgenticMemoryNote(
    String id,
    String contextualDescription,
    List<String> keywords,
    List<String> tags,
    float[] embeddingVector,
    List<String> links  // other note ids
) {}

// Dynamic linker
class DynamicLinker {
    List<Link> link(AgenticMemoryNote newNote, List<AgenticMemoryNote> history);
    // LLM 扫描, 找相似, 双向建 link
}

// Evolution policy
class MemoryEvolutionPolicy {
    void onNew(AgenticMemoryNote newNote, List<AgenticMemoryNote> all);
    // 旧 memory 触发更新 (contextual_description 重写)
}
```

---

## 六、Safety & Alignment 矩阵 (3 篇 paper)

### 6.1 3 安全机制对比

| Paper | 核心 | 机制 | AetherCode 状态 |
|---|---|---|---|
| **2510.05442 ARLAS** | Attacker-Defender 二人零和博弈 + population-based | Adversarial RL 训练 | ⚠️ 静态 allowlist 够, RL 训练缺 |
| **2508.01332 BlockA2A** | DID + blockchain + smart contract + DOE | Byzantine 检测 + 持久审计 | ⚠️ 缺 ByzantineDetector + 持久 trace |
| **2502.16750 Guardians** | Reverse Turing Test + many-shot jailbreak | 动态检测 rogue agent | ⚠️ 缺动态检测 |

### 6.2 DOE 3 机制 (Defense Orchestration Engine)

```java
// 跟 BlockA2A DOE 对齐
class DefenseOrchestrationEngine {
    void flagByzantine(AgentId agent, Violation reason);   // 1. flag
    void haltExecution(TaskId task);                       // 2. halt
    void revokePermission(AgentId agent);                  // 3. revoke
}

// 持久 audit trace (区块链风格 append-only)
class PersistentRuntimeTrace {
    void append(RuntimeEvent event);
    List<RuntimeEvent> query(TraceFilter filter);
    // 不可篡改, 后续可接 blockchain
}

// ARLAS 风格 attacker 模拟
class PromptInjectionSimulator {
    String inject(String observation);
    // 模拟 attacker 注入恶意 instruction
}
```

---

## 七、Protocol & Interop 矩阵 (3 篇 paper)

### 7.1 4 协议对比 (paper 2505.02279)

| 协议 | 角色 | AetherCode 模块 | 状态 |
|---|---|---|---|
| **MCP** | agent ↔ tool | aethercode-mcp (McpRegistry) | ✅ |
| **ACP** | agent ↔ agent (REST+mime) | aethercode-protocol (JsonRpcCodec) | ✅ (基础) |
| **A2A** | agent ↔ agent (HTTP+SSE) | aethercode-a2a (AgentCard/Task) | ✅ |
| **ANP** | 开放 marketplace (DID) | ❌ | 缺 |

### 7.2 MCP × A2A 集成 (paper 2506.01804)

7 步集成方法论:
1. **Capability Modeling** → AgentCard (部分)
2. **Tool Wrapping** → MCP (✅)
3. **Protocol Selection** → ⚠️ 手动, 缺自动 router
4. **Discovery** → ⚠️ 缺
5. **Authentication** → ⚠️ 缺 OAuth/DID
6. **Task Delegation** → A2A Task/TaskStatus (✅)
7. **Result Aggregation** → ⚠️ 缺

---

## 八、Cognitive Architecture 矩阵 (2 篇 paper)

### 8.1 UMM 8 能力 vs AetherCode

| 能力 | AetherCode 对应 | 状态 |
|---|---|---|
| Multi-modal perception | StandardTools 17 tool | ✅ (text 偏多) |
| Planning | DagPlan | ✅ |
| Reasoning | LLM + Verifier | ✅ |
| Tool use | StandardTools + McpRegistry | ✅ |
| Learning | ExperienceRecord | ✅ |
| Memory | Blackboard + ForgettingPolicy | ✅ |
| Reflection | SelfCorrectionLoop | ✅ |
| **Motivation** | ⚠️ CostCeiling (cost 驱动) | ⚠️ 弱 |

**覆盖率**: 7/8 (缺 motivation)

---

## 九、Agent Architecture Selector (paper 2512.08296 §4)

```java
class AgentArchitectureSelector {
    enum Architecture { SINGLE, INDEPENDENT, CENTRALIZED, DECENTRALIZED, HYBRID }
    
    Architecture recommend(TaskFeatures features);
}

record TaskFeatures(
    boolean parallelizable,    // 任务可分解并行?
    boolean sequential,        // 任务强依赖?
    boolean toolHeavy,         // 工具调用多?
    boolean dynamic,           // 任务环境 dynamic?
    double singleAgentBaseline // 0..1, 单 agent baseline 性能
) {}
```

### 9.1 决策规则 (paper 2512.08296 实测)

| Task Type | Recommendation | 理由 (paper) |
|---|---|---|
| parallelizable + low single baseline (< 0.45) | **Centralized** | +80.8% on financial reasoning |
| parallelizable + high tool count | **Hybrid** | 17.2× + 4.4× 平衡 |
| dynamic web nav | **Independent** | +9.2% vs +0.2% Centralized |
| sequential reasoning | **Single** | multi-agent 全部 degrade 39-70% |
| simple read-only | **Single** | tool-heavy overhead 显著 |
| high single baseline (>= 0.45) | **Single** | capability saturation |

### 9.2 CapabilitySaturationDetector (paper 2512.08296)

```java
class CapabilitySaturationDetector {
    double measure(List<AgentRun> runs);
    // 0..1, >= 0.45 表示 single agent 已饱和, 不必上 multi-agent
    // 论文: 0.45 拐点, 之后 multi-agent 边际收益递减
}
```

---

## 十、4 维 Agent Assessment Framework (paper 2512.12791)

```java
class AgentAssessmentFramework {
    AssessmentReport assess(AgentRun run);
}

record AssessmentReport(
    double llmScore,        // 0..1, LLM 维 (planning + reflection + correction)
    double memoryScore,     // 0..1, Memory 维 (episodic + semantic + procedural + forgetting)
    double toolsScore,      // 0..1, Tools 维 (选择 + 参数 + 多 tool + canary)
    double envScore,        // 0..1, Environment 维 (协议 + 安全 + cost)
    double variance,        // 跨 run 行为方差
    String worstRun,
    String bestRun,
    Map<String, Double> subMetrics
) {
    double overall() { /* 加权平均 */ }
}
```

### 10.1 跟 AetherCode R-eval 映射

| 4 维 | AetherCode R-eval 覆盖 | R-sdk 覆盖 |
|---|---|---|
| LLM | R-eval-1, R-eval-4 | R-sdk-1, R-sdk-2, R-sdk-8 |
| Memory | R-eval-2 | R-sdk-3 |
| Tools | R-eval-3, R-eval-10, R-eval-12 | R-sdk-4, R-sdk-5 |
| Environment | R-eval-6, R-eval-7, R-eval-8, R-eval-9, R-eval-11 | R-sdk-4, R-sdk-6, R-sdk-7 |

### 10.2 variance 计算 (paper 2512.12791 §3.2)
```java
double variance = crossRunVariance(runs);
// = 1 - mean(pairwise agreement)
// runs 越相似, variance 越接近 0
```

---

## 十一、Central Planner (paper 2506.12508)

```java
class CentralPlanner {
    Plan plan(String goal, List<AgentSpec> availableAgents);
}

record SubGoal(String id, List<String> dependsOn, String expectedOutput, String assignedAgent) {}
record Plan(List<SubGoal> subGoals, String dispatchOrder) {}
```

### 11.1 4 tier hierarchy (AgentOrchestra)

```
Planner (strategic goal)
    ↓
Admin (orchestrator) ← AetherCode MultiAgentOrchestrator
    ↓
Specialist (domain expert) ← AetherCode AgentFn
    ↓
Worker (执行)
```

### 11.2 委派流程

1. Central Planner 接收 user goal
2. 分解 goal → List<SubGoal> (DAG 形式)
3. 匹配 sub-goal → available agents (capability match)
4. 输出 dispatch order (topo sort)
5. 把 sub-goal 委派给对应 agent
6. 监控 progress, 完成所有 sub-goal 后聚合结果

---

## 十二、跟 22 篇 paper 的完整映射

| Paper ID | 主题 | AetherCode 实现 | Status |
|---|---|---|---|
| 2512.08296 | 5 architecture | 5 strategy 覆盖 4/5 | ⚠️ 缺 Decentralized |
| 2512.08296 | capability saturation | CapabilitySaturationDetector | ❌ Tier-3 候选 |
| 2512.08296 | tool-coordination | (无显式 metric) | ❌ |
| 2512.08296 | error amplification | (无显式 metric) | ❌ |
| 2512.08296 | 87% predict accuracy | ArchitectureSelector | ❌ Tier-3 候选 |
| 2512.12791 | 4 维 evaluation | R-eval + R-sdk 全覆盖 | ✅ 概念 |
| 2512.12791 | variance | crossRunVariance | ❌ Tier-3 候选 |
| 2506.12508 | central planner | CentralPlanner | ❌ Tier-3 候选 |
| 2506.12508 | 5 specialized agents | spawn_agent tool | ✅ |
| 2506.12508 | sub-goal | SubGoal record | ❌ Tier-3 候选 |
| 2506.12508 | adaptive role | ArchitectureSelector | ❌ |
| 2503.09572 | Planner/Executor | DagPlan + AgentRuntime | ⚠️ 缺 dynamic replan |
| 2503.09572 | dynamic replan | DynamicReplanner | ❌ Tier-3 候选 |
| 2504.16563 | Global Plan | GlobalPlan | ❌ Tier-3 候选 |
| 2504.16563 | Hierarchical skill | Skill enum | ❌ Tier-3 候选 |
| 2511.09030 | MDAP | (R-orch-1 单步粒度) | ✅ 概念 |
| 2511.09030 | first-to-ahead-by-k | FirstToAheadByKVoting | ❌ Tier-3 候选 |
| 2511.09030 | red-flagging | RedFlagDetector | ❌ Tier-3 候选 |
| 2410.07869 | subsequence match | SubsequenceMatchMetric | ❌ Tier-3 候选 |
| 2410.07869 | subgraph match | SubgraphMatchMetric | ❌ Tier-3 候选 |
| 2508.17281 | 5 维 agent | R-eval 全覆盖 | ✅ |
| 2506.04625 | MAMV | MAMVToolValidator | ❌ Tier-3 候选 |
| 2506.04625 | ToolBench-V | (训练数据) | ❌ |
| 2505.20670 | Intra-reflection | IntraReflectionHook | ❌ Tier-3 候选 |
| 2505.20670 | Inter-reflection | SelfCorrectionLoop | ✅ |
| 2608.04719 | canary tool | R-eval-12 | ✅ |
| 2603.22862 | multi-tool pipeline | R-eval-10 | ✅ |
| 2509.18847 | DAPO/GSPO | (训练目标) | ❌ |
| 2502.12110 | 5-attribute note | AgenticMemoryNote | ❌ Tier-3 候选 |
| 2502.12110 | dynamic linking | DynamicLinker | ❌ Tier-3 候选 |
| 2502.12110 | memory evolution | MemoryEvolutionPolicy | ❌ Tier-3 候选 |
| 2501.07278 | lifelong learning | (框架) | ❌ |
| 2512.13564v2 | Forms/Functions/Dynamics | 部分覆盖 | ⚠️ |
| 2508.03341 | episode segmentation | (无) | ❌ |
| 2510.05442 | ARLAS RL | (无 RL 训练) | ❌ |
| 2510.05442 | sparse episode reward | (per-call cost) | ⚠️ 不同 |
| 2508.01332 | DID | (本地 AgentCard) | ❌ |
| 2508.01332 | blockchain audit | PersistentRuntimeTrace | ❌ Tier-3 候选 |
| 2508.01332 | DOE | DefenseOrchestrationEngine | ❌ Tier-3 候选 |
| 2508.10146 | 安全综述 | R-eval-12 + CommandAllowlist | ✅ 概念 |
| 2505.02279 | MCP | aethercode-mcp | ✅ |
| 2505.02279 | ACP | aethercode-protocol (基础) | ✅ |
| 2505.02279 | A2A | aethercode-a2a | ✅ |
| 2505.02279 | ANP | (无) | ❌ |
| 2506.01804 | MCP × A2A | 模块都有 | ⚠️ 缺自动化 |
| 2506.01804 | 7 步方法论 | 部分实现 | ⚠️ |
| 2502.16750 | many-shot jailbreak | (静态 allowlist) | ⚠️ |
| 2503.03459 | UMM 8 能力 | 7/8 覆盖 | ⚠️ 缺 motivation |
| 2503.03459 | global workspace | Blackboard | ✅ |
| 2505.07087 | 3 design principles | 全部覆盖 | ✅ |
| 2510.25445 | 24 维 evaluation | R-eval 部分 | ⚠️ |
| 2508.10146 | 8 framework 对比 | 自有 architecture | N/A |
| 2608.20379 | 多模态 | text 偏多 | ❌ |
| 2510.22898 | MAVEN OOD | (无 OOD benchmark) | ❌ |
| 2510.10472 | FML-bench | (无 research benchmark) | ❌ |

---

## 十三、关键洞见 (从 22 paper 抽取)

1. **Multi-agent 5 architecture 错误放大差异 17.2×** (paper 2512.08296 实测)
2. **Planner + Executor 分离 > 单 agent** (paper 2503.09572 +23% WebArena)
3. **Global Plan 持续更新是长链关键** (paper 2504.16563)
4. **MAD (m=1) + k-voting 可达 1M 步 0 错误** (paper 2511.09030)
5. **Intra + Inter reflection 互补** (paper 2505.20670)
6. **MAMV multi-verify 比单 verify 强** (paper 2506.04625)
7. **Zettelkasten 风格 memory: atomic note + dynamic linking** (paper 2502.12110)
8. **Adversarial RL 训练防御 > 静态规则** (paper 2510.05442)
9. **Byzantine detector + 持久 audit 是 MAS 安全关键** (paper 2508.01332)
10. **MCP + A2A 互补 (tool vs agent) 是 enterprise stack** (paper 2505.02279)
11. **8 大认知能力 (UMM) 缺一不可, 缺 motivation 是常见盲点** (paper 2503.03459)
12. **Capability saturation 0.45 拐点, 之后 multi-agent 边际收益递减** (paper 2512.08296)

---

## 十四、5 Strategy 选型 cheat sheet

```java
// 简单 / sequential → Single (AgentRuntime 单跑)
runtime.run(input);

// parallelizable + 无需 verify → Independent (最便宜)
new MultiAgentOrchestrator<>("i", agents, new IndependentStrategy<>()).run(prompt);

// parallelizable + 需要容错 → Centralized (Vote or Critique)
new MultiAgentOrchestrator<>("v", agents, new VoteStrategy<>(VoteMode.PLURALITY)).run(prompt);
new MultiAgentOrchestrator<>("c", agents, new CritiqueStrategy<>(critics)).run(prompt);

// 工具多 + 需要 verify → Hybrid (Centralized verify + Decentralized exec)
new MultiAgentOrchestrator<>("h", agents, new HybridStrategy<>(verifier)).run(prompt);

// 动态决策 → ArchitectureSelector (R-sdk-14)
var arch = new AgentArchitectureSelector().recommend(taskFeatures);
```

---

## 十五、Tier-3 兼容实现候选池 (跟 R-PAPER-EVIDENCE-INDEX 同步)

| 候选数 | 主题 | 详情见 R-PAPER-EVIDENCE-INDEX §11 |
|---:|---|---|
| 4 | Multi-Agent | CentralPlanner / AgentArchitectureSelector / CapabilitySaturationDetector / AgentAssessmentFramework |
| 4 | Planning | GlobalPlan / Skill / HierarchicalExecutor / DynamicReplanner |
| 3 | Tool/Reflection | MAMVToolValidator / IntraReflectionHook / ToolErrorFixRate |
| 3 | Memory | AgenticMemoryNote / DynamicLinker / MemoryEvolutionPolicy |
| 4 | Safety | ByzantineDetector / PersistentRuntimeTrace / DidAgentIdentity / DefenseOrchestrationEngine |
| 2 | Protocol | AnpDiscovery / ProtocolRouter |
| 2 | Workflow | SubsequenceMatchMetric / SubgraphMatchMetric |
| **22** | **总候选** | (待后续 R-round 实现) |

---

## 十六、相关文档

| 文档 | 范围 |
|---|---|
| `doc/R-PAPER-EVIDENCE-INDEX.md` | 22 paper 观点依据索引 (本 doc 引用) |
| `doc/R-AGENT-MULTI-AGENT-ARCHITECTURE-LITERATURE.md` | 3 篇架构综述 |
| `doc/round-notes/R-AUDIT-SELF-IMPROVEMENT.md` | Self-audit + Tier-1/2/3 |
| `doc/round-notes/R-MASTER-SUMMARY.md` | 28 R-round 收口 |
| `reference/papers/<id>_<title>_摘要.md` | 单篇 paper 完整摘要 (22 篇) |
