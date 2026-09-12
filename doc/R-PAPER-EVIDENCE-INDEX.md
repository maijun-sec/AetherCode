# R-PAPER-EVIDENCE-INDEX: AI Agent Paper 观点依据索引

> **目的**: 把 22 篇 AI Agent / Multi-Agent / Tool Use / Memory / Safety / Planning 主题 arXiv paper 的核心观点, 按主题分类索引, 跟 AetherCode 实现状态一一对照。
> 后续 paper 增量更新直接 append 到本文件, 不另起新文件。

**更新日期**: 2026-09-12
**覆盖范围**: 22 篇 paper, 9 大主题
**关联工程**: aethercode (v0.2.65+), aethercode-orchestration, aethercode-evals

---

## 0. Paper 清单 (按主题分类)

| 主题 | Paper 数量 | Paper IDs |
|---|---:|---|
| Multi-Agent Architecture | 4 | 2512.08296, 2506.12508, 2601.01743, 2501.06322 |
| Tool Use & Reflection | 5 | 2506.04625, 2505.20670, 2608.04719, 2603.22862, 2509.18847 |
| Planning & Reasoning | 5 | 2503.09572, 2504.16563, 2511.09030, 2410.07869, 2508.17281 |
| Memory & Continual Learning | 4 | 2502.12110, 2501.07278, 2512.13564v2, 2508.03341 |
| Safety & Alignment | 3 | 2510.05442, 2508.01332, 2508.10146 |
| Protocol & Interop | 3 | 2505.02279, 2506.01804, 2502.16750 |
| Cognitive Architecture | 2 | 2503.03459, 2505.07087 |
| Survey / Holistic Review | 4 | 2510.25445, 2508.10146, s11831-026-10675-8, 2608.20379 |
| Evaluation & Benchmark | 3 | 2512.12791, 2510.22898, 2510.10472 |

---

## 1. Multi-Agent Architecture (4 篇)

### 1.1 paper 2512.08296 — Towards a Science of Scaling Agent Systems

**核心观点**:
- **5 architecture 分类**: Single / Independent / Centralized / Decentralized / Hybrid
- **错误放大率**: Single=1.0×, Centralized=4.4×, Independent=**17.2×**
- **任务适用**: dynamic web (+9.2% Independent), parallelizable (+80.8% Centralized)
- **推荐**: Hybrid (中心 verify + 去中心 exec) 平衡错误与性能

**AetherCode 对应**:
- `VoteStrategy` (Centralized)
- `CritiqueStrategy` (N proposer + M critic, 中心评判)
- `IndependentStrategy` (R-orch-3.5, paper 兼容实现, 7 tests)
- `HybridStrategy` (R-orch-3.5, paper 兼容实现, 8 tests)
- `AgentRuntime` 5 outcome (R-orch-1)

**实现状态**: ✅ 4/5 覆盖, Decentralized 缺

### 1.2 paper 2506.12508 — AgentOrchestra (Hierarchical Agent Architecture)

**核心观点**:
- **4 tier hierarchy**: Planner → Admin → Specialists (3-5) → Workers
- **Specialist = domain expert** (e.g. 编程 / 写作 / 搜索)
- **Admin = orchestrator**, 协调 specialist + 决定 worker 委派
- **Planner = strategic goal**

**AetherCode 对应**:
- `MultiAgentOrchestrator` (orchestration 入口) ≈ Planner
- `MultiAgentStrategy` (Vote/Debate/Critique/Independent/Hybrid) ≈ Admin
- `AgentFn` functional interface ≈ Specialist
- `DagPlan` ≈ workflow plan

**兼容实现候选**:
- `SpecialistProfile` (domain + tools + skill enum)
- `AdminRoleAssigner` (根据 task 选 specialist)
- `CentralPlanner` (跟 AetherCode DagPlan 集成)

**实现状态**: ⚠️ 4-tier 概念覆盖, 但 CentralPlanner 缺 (Tier-3 候选)

### 1.3 paper 2601.01743 — LLM Multi-Agent Systems: A Survey (5 维评估)

**核心观点**:
- **5 维 evaluation matrix**: LLM / Memory / Tools / Environment / Task
- **Robustness metric**: RobustSucc / WorstSucc / Var / RecoveryRate
- **Loop detection**: LoopRate (检测 agent 死循环)
- **4 architecture 分类**: Network / Supervisor / Hierarchical / Custom

**AetherCode 对应**:
- R-eval-9 (Robustness, 12 tests)
- R-eval-11 (Loop & Drift, 12 tests)
- R-eval-4 (Task 维)
- R-eval-7 (Environment)

**实现状态**: ✅ 5/5 维覆盖, R-eval-9/11 强测

### 1.4 paper 2501.06322 — Multi-Agent Collaboration Mechanisms: Survey

**核心观点**:
- **5 维 framework**: Actors / Types / Structures / Strategies / Protocols
- **Types**: Cooperation / Competition / Coopetition
- **Structures**: Peer-to-peer / Centralized / Distributed
- **802 citation** (高引, 重要 survey)

**AetherCode 对应**:
- Vote/Debate/Critique strategy = Cooperation
- IndependentStrategy = 弱竞争
- HybridStrategy = Coopetition
- AgentCard = protocol 抽象

**实现状态**: ✅ 5 维都有对应

---

## 2. Tool Use & Reflection (5 篇)

### 2.1 paper 2506.04625 — Tool-MVR (KDD 2025)

**核心观点**:
- **MAMV**: Multi-Agent Meta-Verification (4 agent 验证: API / query / reasoning / consistency)
- **EXPLORE**: Error → Reflection → Correction 动态学习
- **ToolBench-V + ToolBench-R**: 验证数据集 + 反射数据集
- **数据**: StableToolBench +23.9% (vs ToolLLM), 错误修复 9.1% → **58.9%**

**AetherCode 对应**:
- `StandardTools` 17 tool 严格 schema (减少 hallucination)
- `SelfCorrectionLoop` + `RetryStrategy.transform` (EXPLORE 风格)
- `Verifier` (MAMV 风格 multi-verify)
- R-eval-12 (Canary, 12 tests)
- R-sdk-5 Tool interface (8 tests)

**兼容实现候选**:
- `MAMVToolValidator` (4-agent verify 工具调用)
- `ToolErrorFixRate` metric
- `ExplorationReflectionTrainer` (在线学)

**实现状态**: ⚠️ 概念覆盖, MAMV 多 agent verify 缺

### 2.2 paper 2505.20670 — MIRROR (Intra + Inter Reflection)

**核心观点**:
- **Intra-reflection**: action 执行前 mental simulation
- **Inter-reflection**: action 执行后 trajectory 调整
- **仿人**: 决策前预演 + 行后复盘
- **数据**: StableToolBench + TravelPlanner SOTA

**AetherCode 对应**:
- `SelfCorrectionLoop` (inter-reflection, 已有)
- `Watchdog` pre-check (intra-reflection 部分)
- ⚠️ 没有显式 MentalSimulation API

**兼容实现候选**:
- `IntraReflectionHook` (action 前 mental sim)
- `MentalSimulation` (LLM 预演执行结果)
- `DualLayerSelfCorrect` (intra + inter 集成)

**实现状态**: ⚠️ Inter 有, Intra 缺

### 2.3 paper 2608.04719 — Tool Canary Safety

**核心观点**:
- **Canary tool**: 假 tool, 测 agent 是否被 prompt injection 误导调错
- **不修改真 tool, 注入 canary**, 监控调用率
- **核心**: agent 必须区分真 tool / canary tool

**AetherCode 对应**:
- R-eval-12 (ToolCanarySafety, 12 tests)
- `CommandAllowlist` (静态 allow/deny)
- R-eval-3 (Tool 维评估)

**实现状态**: ✅ R-eval-12 测过

### 2.4 paper 2603.22862 — Long-Horizon Multi-Tool

**核心观点**:
- **5+ tool 串联** 长链任务, 中间失败检测
- **Tool dependency graph**: tool 之间有依赖
- **Canary 注入 5% 工具调用**

**AetherCode 对应**:
- R-eval-10 (MultiToolPipeline, 10 tests)
- `DagPlan` tool dependency
- `StandardTools` 17 tool

**实现状态**: ✅ R-eval-10 测过

### 2.5 paper 2509.18847 — Failure Makes the Agent Stronger (Structured Reflection)

**核心观点**:
- **Structured Reflection**: Error → Reflection → Correction 是显式 trainable action
- **DAPO + GSPO 目标**: 优化 Reflect → Call → Final
- **Tool-Reflection-Bench**: programmatic 评估 (structural validity / executability)

**AetherCode 对应**:
- R-eval-9 (Robustness 测 recovery rate)
- `SelfCorrectionLoop` (correction step)
- ⚠️ DAPO/GSPO 训练目标缺

**实现状态**: ⚠️ 概念覆盖, 训练目标缺

---

## 3. Planning & Reasoning (5 篇)

### 3.1 paper 2503.09572 — Plan-and-Act

**核心观点**:
- **Planner + Executor 显式分离**
- **Dynamic replan**: 每步重新生成 plan
- **数据**: WebArena-Lite 57.58% (+23% vs ReAct), WebVoyager 81.36%
- **合成数据**: 反向从 trajectory 标注 plan, fine-tune Planner

**AetherCode 对应**:
- `DagPlan` (plan) + `AgentRuntime` (exec)
- ⚠️ 无显式 dynamic replan API

**兼容实现候选**:
- `PlannerExecutorSplit` (2-agent pipeline)
- `DynamicReplanner` (每步重生成 plan)
- `PlanStep` record

**实现状态**: ⚠️ 概念覆盖, dynamic replan API 缺

### 3.2 paper 2504.16563 — GoalAct (清华)

**核心观点**:
- **持续更新 Global Plan**: 每步 query 重新生成 G
- **Hierarchical skill**: plan 只指定 high-level skill (searching/coding/writing)
- **Plan 末位强制 Finish**
- **数据**: LegalAgentBench SOTA +12.22%

**AetherCode 对应**:
- `DagPlan.update()` (⚠️ 需暴露 mutator)
- `StandardTools` 17 tool (⚠️ 无显式 skill 抽象)
- ⚠️ Plan 末位 Finish 缺

**兼容实现候选**:
- `GlobalPlan` (持续更新 plan)
- `Skill` enum (high-level skill 抽象)
- `HierarchicalExecutor` (skill 分发)
- `PlanUpdatePolicy` (`π(Q, T, S_t) → G_{t+1}`)

**实现状态**: ⚠️ 概念覆盖, skill 抽象 + GlobalPlan 缺

### 3.3 paper 2511.09030 — MAKER (Million-Step Zero Error)

**核心观点**:
- **MDAP**: Massively Decomposed Agentic Processes
- **Maximal Agentic Decomposition (MAD)**: m=1, 每步一个 micro-agent
- **First-to-ahead-by-k voting**: 投票阈值 k = Θ(ln s)
- **Red-flagging**: 检测高风险 response 拒绝
- **数据**: Towers of Hanoi 20 盘 = 1,048,575 步, 0 errors

**AetherCode 对应**:
- `AgentRuntime.run` 单步粒度
- `VoteStrategy.MAJORITY` (51% 阈值)
- `Verifier` (红旗检测)
- `DagPlan` 任务拆解

**兼容实现候选**:
- `FirstToAheadByKVoting` (任意 k 投票)
- `RedFlagDetector` (格式/长度/重复/矛盾)
- `MaximalDecomposer` (m=1 任务拆解)

**实现状态**: ⚠️ 概念覆盖, 红 k voting / red flag 缺

### 3.4 paper 2410.07869 — WorFBench (ICLR 2025)

**核心观点**:
- **复杂图 workflow benchmark** (WorFBench) + 评测协议 (WorFEval)
- **3 层评测**: Holistic / Subsequence / Subgraph
- **Sequence vs Graph plan gap ~15%** (GPT-4)
- **生成 workflow 可喂下游 agent** (推理加速)

**AetherCode 对应**:
- `DagPlan` 节点+边
- `WorkflowEngine` execute
- ⚠️ 只有 holistic 评测, subsequence/subgraph 缺

**兼容实现候选**:
- `SubsequenceMatchMetric`
- `SubgraphMatchMetric`
- `WorkflowReplayer`

**实现状态**: ⚠️ 概念覆盖, 细粒度 metric 缺

### 3.5 paper 2508.17281 — From Language to Action (LLM Agents Survey)

**核心观点**:
- **5 维 agent 能力**: Memory / Planning / Tool Use / Action / Reflection
- **Self-Refine**: 自我迭代修正
- **ReAct**: Reason + Act 范式

**AetherCode 对应**:
- R-eval 全覆盖 (5 维)
- `SelfCorrectionLoop` (reflection)
- `DagPlan` (planning)
- `StandardTools` (tool use)

**实现状态**: ✅ 5 维全覆盖

---

## 4. Memory & Continual Learning (4 篇)

### 4.1 paper 2502.12110 — A-Mem (Zettelkasten Memory)

**核心观点**:
- **5 attribute memory note**: contextual_description / keywords / tags / vector / links
- **Dynamic linking**: 新 memory 加入时, LLM 扫描历史, 建立双向 links
- **Memory evolution**: 旧 memory 根据新 memory 触发更新
- **仿 Zettelkasten**: atomic note + dynamic linking

**AetherCode 对应**:
- `ExperienceRecord` (kind/content/score/ts), 缺 tag / links
- `ForgettingPolicy` (衰减), 缺 update
- ⚠️ 无 Zettelkasten 风格 linking

**兼容实现候选**:
- `AgenticMemoryNote` (5-attribute record)
- `DynamicLinker` (LLM 扫描 + 建 link)
- `MemoryEvolutionPolicy` (旧 memory 更新)
- `ZettelkastenStore` (无 schema 知识图)

**实现状态**: ⚠️ 概念部分覆盖, DynamicLinker 缺

### 4.2 paper 2501.07278 — Lifelong Learning of LLM Agents: Roadmap

**核心观点**:
- **3 module**: perception / memory / action
- **Catastrophic forgetting**: 学新忘旧
- **Stability-plasticity dilemma**: 稳定 vs 适应

**AetherCode 对应**:
- `ExperienceRecord` (memory)
- `ForgettingPolicy` (缓解 forgetting)
- ⚠️ 缺 lifelong learning 框架

**实现状态**: ⚠️ 概念部分覆盖

### 4.3 paper 2512.13564v2 — Memory in the Age of AI Agents

**核心观点**:
- **3 维 taxonomy**: Forms (Token/Parametric/Latent) / Functions (Factual/Experiential/Working) / Dynamics (Formation/Evolution/Retrieval)
- **Agent Memory ≠ RAG ≠ Context Engineering** (3 概念明确区分)
- **Latent Memory**: 隐状态 (vs external DB)

**AetherCode 对应**:
- `ExperienceRecord` (Experiential memory, 部分)
- `Blackboard` (working memory, partial)
- ⚠️ 无 Latent memory 概念

**实现状态**: ⚠️ 部分覆盖, Latent memory 缺

### 4.4 paper 2508.03341 — Nemori (Self-Organizing Memory)

**核心观点**:
- **Two-Step Alignment Principle**: Event Segmentation Theory 启发的 episode 切分
- **Predict-Calibrate Principle**: 主动从 prediction gap 学
- **SOTA on LoCoMo / LongMemEval**

**AetherCode 对应**:
- `ExperienceRecord` 已有, ⚠️ 缺 episode 切分
- ⚠️ 无 predict-calibrate 机制

**实现状态**: ⚠️ 概念部分覆盖

---

## 5. Safety & Alignment (3 篇)

### 5.1 paper 2510.05442 — ARLAS (Adversarial RL Agent Safety)

**核心观点**:
- **Attacker-Defender 二人零和博弈**
- **Population-based training**: Defender 训练时对所有历史 attacker checkpoint
- **Sparse episode reward**: 整 episode 末尾给 reward
- **数据**: AgentDojo ASR 5.88% → 0.43%

**AetherCode 对应**:
- `CommandAllowlist` (静态规则, 不够)
- R-eval-12 (Canary 测试)
- ⚠️ 无 adversarial RL 训练

**兼容实现候选**:
- `PromptInjectionSimulator`
- `AdversarialDefender`
- `SafetyWinRate` metric

**实现状态**: ⚠️ 静态安全有, RL 训练缺

### 5.2 paper 2508.01332 — BlockA2A (Secure A2A)

**核心观点**:
- **3 大支柱**: DID (身份) + 区块链 (审计) + smart contract (access control)
- **Byzantine agent flagging**: 检测偏离行为
- **DOE**: Defense Orchestration Engine (3 机制: flag + halt + revoke)
- **Sub-second overhead**

**AetherCode 对应**:
- `aethercode-a2a` AgentCard (本地, 无 DID)
- `RuntimeTrace` (内存, 不持久)
- `CommandAllowlist` (静态)
- ⚠️ 无 Byzantine 检测

**兼容实现候选**:
- `ByzantineDetector`
- `PersistentRuntimeTrace` (append-only log)
- `DidAgentIdentity`
- `DefenseOrchestrationEngine`

**实现状态**: ⚠️ 部分覆盖, ByzantineDetector 缺

### 5.3 paper 2508.10146 — Agentic AI Frameworks Survey (含安全)

**核心观点**:
- **Agentic AI 框架分类**
- **安全挑战**: prompt injection / jailbreak / 隐私泄露
- **对策**: capability-based access control + audit log

**AetherCode 对应**:
- R-eval-12 (Canary)
- `CommandAllowlist`
- `RuntimeTrace` (audit)

**实现状态**: ✅ 概念覆盖

---

## 6. Protocol & Interop (3 篇)

### 6.1 paper 2505.02279 — Agent Interop Protocols Survey (MCP/ACP/A2A/ANP)

**核心观点**:
- **4 协议对比**: MCP (tool) / ACP (messaging) / A2A (agent) / ANP (marketplace)
- **Phased adoption roadmap**: MCP → ACP → A2A → ANP
- **互补不是竞争**

**AetherCode 对应**:
- `aethercode-mcp` (MCP)
- `aethercode-a2a` (A2A)
- `aethercode-protocol` (JsonRpcCodec = ACP 基础)
- ⚠️ ANP 缺

**实现状态**: ✅ MCP + A2A + ACP 覆盖, ANP 缺

### 6.2 paper 2506.01804 — MCP × A2A Framework

**核心观点**:
- **MCP 管 tool, A2A 管 agent** (vertical vs horizontal)
- **7 步集成方法论**: capability model → tool wrap → protocol select → discovery → auth → task delegate → result aggregate

**AetherCode 对应**:
- `AgentCard` (capability model 部分)
- `McpRegistry` (tool wrap)
- `Task/TaskStatus` (task delegate)

**兼容实现候选**:
- `AgentCapabilityModeler` (自动从代码生成 Card)
- `ProtocolRouter` (按方向自动选协议)
- `ResultAggregator`

**实现状态**: ⚠️ 概念覆盖, 自动化缺

### 6.3 paper 2502.16750 — Guardians of the Agentic System (Many-Shot Jailbreak)

**核心观点**:
- **Many-shot jailbreak**: 长 prompt 绕过 static guardrail
- **Reverse Turing Test**: 检测 rogue agent
- **94% 检测率 (Gemini 1.5 pro), 但长 prompt 后失败**

**AetherCode 对应**:
- `CommandAllowlist.deny` 静态规则
- R-eval-12 Canary
- ⚠️ 无 Reverse Turing Test

**实现状态**: ⚠️ 静态安全有, 动态检测缺

---

## 7. Cognitive Architecture (2 篇)

### 7.1 paper 2503.03459 — Unified Mind Model (UMM)

**核心观点**:
- **Global Workspace Theory**: agent = specialist module, workspace = message bus
- **8 大认知能力**: perception / planning / reasoning / tool use / learning / memory / reflection / motivation
- **MindOS**: 无代码 agent 构建

**AetherCode 对应**:
- 7/8 能力覆盖 (perception / planning / reasoning / tool / learning / memory / reflection)
- ⚠️ Motivation 弱
- `Blackboard` (global workspace)

**兼容实现候选**:
- `MotivationalDriver` (补 motivation 能力)
- `CognitiveAbilityInventory` (8 能力枚举)
- `AgentCard.completeness()` (8 能力 gap report)

**实现状态**: ⚠️ 7/8 覆盖, Motivation 缺

### 7.2 paper 2505.07087 — Cognitive Design Patterns for LLM Agents

**核心观点**:
- **Recurring cognitive patterns** 跨多 architecture (pre-transformer)
- **应用到 LLM agent**: 找出 gap
- **3 大 trustworthy 设计原则**: modular decomposition / adaptive governance / transparent state

**AetherCode 对应**:
- R-orch-1 (modular runtime)
- Hook 7 枚举 (governance)
- RuntimeTrace (transparent state)
- `Verifier` (adaptive governance)

**实现状态**: ✅ 3 原则全覆盖

---

## 8. Survey / Holistic Review (4 篇)

### 8.1 paper 2510.25445 — Agentic AI Comprehensive Survey

**核心观点**: Agentic AI 全面综述, 涵盖 24+ 维 evaluation matrix

**AetherCode 对应**: R-eval 1-12 覆盖大部分维度

### 8.2 paper 2508.10146 — Agentic AI Frameworks Architectures

**核心观点**: 8 大主流 framework 对比 (CrewAI / AutoGen / LangGraph / MetaGPT / AgentScope / Swarm / Agents SDK)

**AetherCode 对应**: 自有 architecture (跟上述 8 家不同, 偏 runtime + protocol + memory)

### 8.3 paper s11831-026-10675-8 — Holistic Review of Agentic AI

**核心观点**: 全景综述, 偏 academic perspective

**AetherCode 对应**: 多个 module 对应综述提到的 capability

### 8.4 paper 2608.20379 — Multimodal Agentic Frameworks Survey

**核心观点**: 多模态 agent 框架综述, vision / audio / video 输入

**AetherCode 对应**: `aethercode-tools` StandardTools (目前 text 偏多, 多模态未覆盖)

**实现状态**: ⚠️ 多模态弱

---

## 9. Evaluation & Benchmark (3 篇)

### 9.1 paper 2512.12791 — Beyond Task Completion (Agent Assessment)

**核心观点**:
- **4 维 agent 评估**: LLM / Memory / Tools / Environment
- **聚合 metric**: LlmScore / MemoryScore / ToolsScore / EnvScore
- **16 case study**

**AetherCode 对应**:
- R-eval-1/2/3/4 (LLM/Memory/Tools/Env 各自覆盖)
- R-sdk-1/2/3/4/5/6/7 (interface 覆盖)
- 聚合 metric 缺

**实现状态**: ✅ 4 维覆盖, 聚合 metric 缺

### 9.2 paper 2510.22898 — MAVEN (Adversarial Verification)

**核心观点**:
- **OOD benchmark**: math / physics adversarial
- **CoreThink**: symbolic reasoning + adaptive tool orchestration
- **数据**: < 50% 准确率 (MOST), 5-30% 提升

**AetherCode 对应**:
- R-eval-9 (Robustness)
- R-eval-12 (Canary)
- ⚠️ 缺 OOD benchmark

**实现状态**: ⚠️ 部分覆盖

### 9.3 paper 2510.10472 — FML-bench (Exploration Breadth)

**核心观点**:
- **8 基础 ML 研究任务**
- **5 维 metric**: 全面评估 research agent
- **发现**: 宽探索 > 窄深探索

**AetherCode 对应**:
- R-eval-3 (Tool 探索)
- ⚠️ 缺 research agent benchmark

**实现状态**: ⚠️ 缺 research agent benchmark

---

## 10. 实现状态总览

| 主题 | 已实现 (✅) | 部分 (⚠️) | 缺 (❌) |
|---|---|---|---|
| Multi-Agent Architecture | 4/5 architecture (缺 Decentralized) | — | 1 (Decentralized) |
| Tool Use & Reflection | Verifier, SelfCorrect | MAMV, Intra-Reflection | — |
| Planning & Reasoning | DagPlan, R-eval | DynamicReplan, GlobalPlan | MentalSimulation (paper 强相关) |
| Memory & Continual | ExperienceRecord, ForgettingPolicy | A-Mem 概念 | DynamicLinker, EpisodeSeg |
| Safety & Alignment | CommandAllowlist, Canary | RuntimeTrace | ByzantineDetector, ARLAS |
| Protocol & Interop | MCP, A2A, ACP 基础 | Automation | ANP, DID |
| Cognitive Architecture | 7/8 能力 | Motivation | — |
| Survey | R-eval 24 维 | — | 多模态 |
| Evaluation | R-eval 1-12 | Aggregation | OOD benchmark |

**总实现率**: 28/40 = **70%**

---

## 11. 兼容实现候选清单 (Tier-3 候选池)

按 paper 来源整理, 全部已在 R-AUDIT-SELF-IMPROVEMENT Tier-3 列出。

| 类名 | Paper | 文件位置 | 优先级 |
|---|---|---|---|
| `CentralPlanner` | 2506.12508 | orchestration.plan | 高 |
| `AgentArchitectureSelector` | 2512.08296 | orchestration.architecture | 中 |
| `CapabilitySaturationDetector` | 2512.08296 | orchestration.architecture | 中 |
| `AgentAssessmentFramework` | 2512.12791 | evals.assessment | 中 |
| `GlobalPlan` | 2504.16563 | orchestration.plan | 高 |
| `Skill` enum | 2504.16563 | orchestration.skill | 高 |
| `HierarchicalExecutor` | 2504.16563 | orchestration.skill | 中 |
| `DynamicReplanner` | 2503.09572 | orchestration.split | 中 |
| `FirstToAheadByKVoting` | 2511.09030 | orchestration.multiagent | 中 |
| `RedFlagDetector` | 2511.09030 | orchestration.verifier | 中 |
| `MaximalDecomposer` | 2511.09030 | orchestration.plan | 中 |
| `AgenticMemoryNote` | 2502.12110 | aethercode-memory | 中 |
| `DynamicLinker` | 2502.12110 | aethercode-memory | 中 |
| `MemoryEvolutionPolicy` | 2502.12110 | aethercode-memory | 低 |
| `ZettelkastenStore` | 2502.12110 | aethercode-memory | 低 |
| `MotivationalDriver` | 2503.03459 | orchestration.motivation | 低 |
| `MAMVToolValidator` | 2506.04625 | evals.tools | 中 |
| `IntraReflectionHook` | 2505.20670 | orchestration.hooks | 中 |
| `ByzantineDetector` | 2508.01332 | orchestration.security | 中 |
| `PersistentRuntimeTrace` | 2508.01332 | orchestration.runtime | 中 |
| `DidAgentIdentity` | 2508.01332 | aethercode-a2a | 低 |
| `DefenseOrchestrationEngine` | 2508.01332 | aethercode-permission | 低 |
| `AnpDiscovery` | 2505.02279 | aethercode-a2a | 低 |
| `ProtocolRouter` | 2506.01804 | aethercode-protocol | 中 |
| `SubsequenceMatchMetric` | 2410.07869 | evals.metrics | 中 |
| `SubgraphMatchMetric` | 2410.07869 | evals.metrics | 中 |

**候选池总数**: 26 个兼容实现候选 (覆盖 14 篇 paper)

---

## 12. 后续增量更新流程

每下载 / 写一篇新 paper 摘要, 按以下步骤更新本文件:

1. 在 `0. Paper 清单` 加入一行 (主题 + paper id)
2. 在对应主题 section 加入 `### paper <id>` 子 section
   - 核心观点 (3-5 条)
   - AetherCode 对应 (实现状态)
   - 兼容实现候选 (如有)
3. 更新 `10. 实现状态总览` 表格
4. 更新 `11. 兼容实现候选清单` 表格 (如有新候选)

**不另起新文件** — 所有 paper 观点都在本文件, 持续积累。

---

## 13. 相关文档

| 文档 | 范围 |
|---|---|
| `doc/R-AGENT-MULTI-AGENT-ARCHITECTURE-LITERATURE.md` | 3 篇架构综述 (2512.08296 / 2512.12791 / 2506.12508) |
| `doc/R-AETHERCODE-MULTI-AGENT-IMPLEMENTATION.md` | 5 strategy 详解 + cheat sheet |
| `doc/round-notes/R-AUDIT-SELF-IMPROVEMENT.md` | Self-audit + Tier-1/Tier-2/Tier-3 |
| `doc/round-notes/R-MASTER-SUMMARY.md` | 28 R-round 收口总览 |
| `reference/papers/<id>_<title>_摘要.md` | 单篇 paper 完整中文摘要 (22 篇) |

---

**维护者**: AetherCode R-round
**最后更新**: 2026-09-12 (本轮新增 12 篇 paper 摘要)


---

## 13. 2026 新论文 (本轮新增 8 篇)

### 13.1 paper 2605.14892 — Beyond Individual Intelligence (LIFE 4 阶段)

**作者**: Shihao Qi, Jie Ma 等 17 人 (西安交大 + Lenovo + Sydney + 华中师大)

**核心**: **LIFE Progression** 4 阶段框架 — Lay / Integrate / Find faults / Evolve, 揭示多 agent 系统从能力建设到自我进化的因果依赖链。

**关键发现**:
- 4 阶段有**因果依赖** — 没 foundation, integration 易崩; 没 attribution, evolution 难闭环
- Cross-stage 反馈是研究前沿
- 89 页综述, 是 multi-agent 演化必读

**AetherCode 对应**:
- Lay (R-eval-1 + R-sdk-1) ✅
- Integrate (5 strategy) ✅
- Find (RuntimeTrace + R-eval-11 + ByzantineDetector) ✅
- Evolve (ExperienceRecord + ForgettingPolicy + DynamicLinker) ✅ 部分
- **缺**: Cross-stage 闭环 (Evolve → Lay 反哺)

**兼容实现候选**: `LifeProgressionMonitor` / `CrossStageFeedback` / `FaultAttributionReport`

### 13.2 paper 2510.26352 — Geometry of Dialogue (Team Composition)

**作者**: Kotaro Furuya, Yuichi Kitagawa (Hitachi)

**会议**: AAAI-26 Workshop on LaMAS (Oral)

**核心**: Interaction-Centric Team Composition — pairwise 对话 embedding 构造 language model graph, 社区检测发现协同 cluster, 自动化 team 组建。

**关键发现**:
- 发现的 cluster 跟 model 已知 specialization **一致**
- 自动 team 在 benchmark 上**超 random**, 跟手工 curated **相当**

**AetherCode 对应**:
- `Blackboard` (cross-agent 共享 KV) 部分
- `MultiAgentOrchestrator` 5 strategy ✅
- `AgentCard` (capability 描述) ✅
- `GlobalPlan` (GoalAct 风格) ✅

**兼容实现候选**: `LanguageModelGraph` / `SynergisticTeamFinder`

### 13.3 paper 2602.00994 — DART (Reasoning vs Tool-use Disentangle)

**作者**: Yu Li 等 (Huawei + SJTU + THU)

**会议**: ICLR 2026 Workshop

**核心**: **DART** — 独立 LoRA 分别调 reasoning 和 tool-use, 解决 ARL 中 capability interference。

**关键发现**:
- CEA 量化发现: reasoning + tool-use 经常**诱导 misaligned gradient**
- 简单 LoRA 拆分 = 13 benchmark 普遍提升, 接近 2-Agent upper bound

**AetherCode 对应**:
- `Verifier` (reasoning) + `StandardTools` (tool) 已模块化 ✅
- ⚠️ 偏 training, 概念可借鉴, 实现需 ML 背景

**兼容实现候选**: `CapabilityInterferenceMetric` / `DisentangledExecutor` / `CEAReport`

### 13.4 paper 2601.11327 — Small Agent Collaboration (ICLR 2026)

**作者**: Agata Zywot, Xinyi Chen, Maarten de Rijke (Amsterdam)

**会议**: ICLR 2026 Workshop on MALGAI

**核心**: **小模型多 agent > 大单 agent** (无 tools 时) — 4B + tools > 32B no tools (GAIA benchmark)

**关键发现**:
- **Orchestrator 容量是关键** — 投资 orchestrator, sub-agent 可省
- Sub-agent 别让它 think — orchestrator think, sub-agent do
- 跟 MAKER (2511.09030) 共识: 极小 LLM + 大量 micro-agent 即可

**AetherCode 对应**:
- `MultiAgentOrchestrator` orchestrator 决策 ✅
- `StandardTools` 17 tool + R-eval-10 ✅
- ⚠️ 当前未专门测 4B vs 32B

**兼容实现候选**: `ModelSizeBenchmark` / `OrchestratorCapacityProfile`

### 13.5 paper 2603.09716 — AutoAgent (Evolving + Elastic Memory)

**作者**: Xiaoxing Wang, Ning Liao 等 (MemTensor + SJTU)

**核心**: **3 组件** — Evolving Cognition / On-the-fly Decision / Elastic Memory, 闭环 cognitive evolution 无需外部 retrain。

**AetherCode 对应**:
- 4 维 cognition (tool / self / peer / task) ⚠️ 缺显式 class
- `MultiAgentOrchestrator.run` per-step ✅
- `SessionMemoryStore` + `ProjectMemoryCompressor` + `LayeredMemoryStore` ✅
- `DynamicLinker` (R-paper-batch3) ✅
- `StandardTools` + `AgentFn` unified action space ✅

**兼容实现候选**: `CognitionEvolver` / `ElasticMemoryOrchestrator` / `ClosedLoopEvolutionHook`

### 13.6 paper 2602.08009 — RAPS (Ad-Hoc Networking for MAS)

**作者**: Rui Li, Zeyu Zhang 等

**核心**: **RAPS** — 把多 agent 协调类比为 ad-hoc networking, 用 intent-based pub/sub + Bayesian reputation 解决 scale + robustness。

**关键发现**:
- 3 axis 一致提升: adaptivity / scalability / robustness
- 100+ agent 不掉性能

**AetherCode 对应**:
- `Blackboard` (跨 agent KV) ✅
- ⚠️ 缺 reputation 机制
- `ByzantineDetector` (R-paper-batch3) ✅
- `GlobalPlan.update` ✅
- ⚠️ 100+ agent 未压测

**兼容实现候选**: `ReputationScore` / `PubSubBlackboard` / `IntentRefiner` / `ScaleBenchmark`

### 13.7 paper 2602.23720 — Auton Framework (Snapchat)

**作者**: Sheng Cao 等 (Snap Inc.)

**核心**: **Auton Framework** = Cognitive Blueprint (declarative spec) + Runtime Engine (execution substrate), 解决 "Integration Paradox"。

**4 大 pillar**:
- AgenticFormat Standard (YAML/JSON)
- Deterministic Governance (Constraint Manifold)
- Cognitive Persistence (Hierarchical memory)
- 3-Level Self-Evolution

**AetherCode 对应**:
- `AgentCard` (capability 描述) ✅
- aethercode-runtime 跟 aethercode-core 分离 ✅
- `CommandAllowlist` 静态规则 ⚠️ 静态而非 projection
- `LayeredMemoryStore` + `ForgettingPolicy` ✅
- ⚠️ 仅 in-context, 缺 meta-prompt + RL
- `MultiAgentOrchestrator` (并行 agent) ✅
- ⚠️ 缺 speculative inference, dynamic context pruning ✅ 部分

**兼容实现候选**: `AgenticFormatSchema` / `ConstraintManifold` / `SpeculativePrefetcher` / `ThreeLevelEvolution`

### 13.8 paper 2603.13256 — REDEREF (Training-Free Probabilistic Control)

**作者**: Mohammad Parsa Hosseini 等

**核心**: **REDEREF** — training-free controller, Thompson sampling + reflection reroute + memory priors 改进多 agent 路由效率。

**关键数据**:
- Token -28%, call -17%, time -19%
- Recursive retry alone 饱和, 加 belief-guided routing 才有效

**AetherCode 对应**:
- ⚠️ 无 probabilistic 路由
- `SelfCorrectionLoop` ✅
- `CritiqueStrategy` ✅
- `ExperienceRecord` ✅

**兼容实现候选**: `ThompsonSamplingRouter` / `BeliefState` / `ReflectionDrivenRerouter` / `TrainingFreeController`

---

## 14. 累计 31 篇 paper (主题覆盖 9 大)

| 主题 | 数量 | 代表 paper |
|---|---:|---|
| Multi-Agent Architecture | 5 | 2512.08296, 2506.12508, 2601.01743, 2501.06322, 2605.14892 |
| Tool Use & Reflection | 5 | 2506.04625, 2505.20670, 2608.04719, 2603.22862, 2509.18847 |
| Planning & Reasoning | 6 | 2503.09572, 2504.16563, 2511.09030, 2410.07869, 2508.17281, 2601.11327 |
| Memory & Continual | 5 | 2502.12110, 2501.07278, 2512.13564v2, 2508.03341, 2603.09716 |
| Safety & Alignment | 4 | 2510.05442, 2508.01332, 2508.10146, 2603.13256 |
| Protocol & Interop | 4 | 2505.02279, 2506.01804, 2502.16750, 2602.08009 |
| Cognitive Architecture | 3 | 2503.03459, 2505.07087, 2602.23720 |
| Survey / Holistic | 4 | 2510.25445, s11831-026, 2608.20379, 2602.00994 |
| Evaluation & Benchmark | 3 | 2512.12791, 2510.22898, 2510.10472, 2510.26352 |
| **Total** | **31** | |
