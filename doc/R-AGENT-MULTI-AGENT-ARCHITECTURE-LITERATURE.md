# AI Agent Multi-Agent Architecture Literature Review

**Date**: 2026-09-12
**Status**: 持续更新 (基于 3 篇核心 paper + 后续 paper)
**Scope**: AI Agent 多 agent 架构的文献综述, 跟 AetherCode 实现对齐

## 一、核心立场 (从 3 篇 paper 抽取)

### 1.1 paper 2512.08296 — Scaling Agent Systems (MIT, 2025-12)

**核心论点**: Agent 系统的可扩展性不是"越多越好", 而是"架构-任务对齐"
- 5 canonical architectures: Single / Independent / Centralized / Decentralized / Hybrid
- 3 大效应:
  1. **Capability Saturation**: 单 agent 达 ~45% 后多 agent 收益递减 (β=-0.408)
  2. **Tool-Coordination Tradeoff**: tool-heavy 任务多 agent overhead 显著
  3. **Topology-Dependent Error Amplification**: Independent 17.2× vs Centralized 4.4×

**260 configurations** 跨 6 benchmark + 5 architecture + 3 LLM family:
- 性能范围: +80.8% (decomposable financial) 到 -70.0% (sequential planning)
- 预测准确率: 87% held-out
- 5 architecture 排名: Centralized > Hybrid > Single > Decentralized > Independent (按 task 类别)

**AetherCode 对齐**:
- 已有 VoteStrategy (Centralized 雏形) + CritiqueStrategy (Centralized 中央评分)
- 新增 IndependentStrategy (paper Independent 架构) + HybridStrategy (paper Hybrid 架构)
- 缺: Architecture 选择器 (按 task 特征推荐), Capability Saturation 检测

### 1.2 paper 2512.12791 — Beyond Task Completion (IBM, 2025-12)

**核心论点**: 二元 task completion metric **不够**, 必须看 4 维行为
- 4 evaluation pillars: LLM / Memory / Tools / Environment
- 关键发现: 同一 prompt 不同 run 行为不一致 (call sequence / tool selection / 错误模式)
- 必须评估**分布**而非仅平均

**AetherCode 对齐** (天然覆盖):
- LLM: R-eval-1 (planning) + R-eval-4 (reflection) + R-sdk-1/2/8
- Memory: R-eval-2 + R-sdk-3
- Tools: R-eval-3/10/12 + R-sdk-4/5
- Environment: R-eval-6/7/8/9/11 + R-sdk-4/6/7

**缺**: 顶层聚合 API `AgentAssessmentFramework` (4 维聚合 + 跨 run 行为分布 + variance)

### 1.3 paper 2506.12508 — AgentOrchestra (Skywork AI, 2025-06)

**核心论点**: 层次 multi-agent (central planner + 5 specialized agents) > flat-agent
- 5 类 specialized agents: Data Analysis / File Operation / Web Navigation / Interactive Reasoning / MCP Manager
- GAIA benchmark: 83.39% (top tier general-purpose agent)
- Central Planner: 分解 goal → 委派 sub-task → 监控 progress
- 通信协议: 显式 sub-goal 公式化
- Adaptive Role Allocation: 动态角色分配

**AetherCode 对齐** (部分覆盖):
- 已有: spawn_agent tool, SubagentRegistry, A2A protocol, A2A-DeepAgent bridge
- 缺: Central Planner 类 (显式 plan → dispatch 委派), SubGoal 协议 record
- 缺: Adaptive Role Allocation (按 task 特征动态分配)

## 二、5 Architecture 矩阵 (paper 2512.08296 + AetherCode)

| Architecture | 通信模式 | 错误放大 | 适用任务 | AetherCode 实现 |
|---|---|---:|---|---|
| **Single** | N/A | 1.0× | 简单 / 工具少 | AgentRuntime.run (单 runtime) |
| **Independent** | 无 | **17.2×** | dynamic web (+9.2%) | **IndependentStrategy** (R-orch-3.5) |
| **Centralized** | 中央协调 | **4.4×** | parallelizable (+80.8%) | VoteStrategy + CritiqueStrategy |
| **Decentralized** | P2P 动态 | (中间) | 复杂协同 | ❌ 缺 (未来 R-round) |
| **Hybrid** | 中心 verify + 去中心 exec | < 4.4× | 工具多 + 需要 verify | **HybridStrategy** (R-orch-3.5) |

### 2.1 关键公式 (paper 2512.08296)

**Error Amplification Factor (EAF)**:
```
EAF(topology) = (errors in multi-agent run) / (errors in single-agent run)
```

- Independent: EAF = 17.2 (worst)
- Centralized: EAF = 4.4 (best with verification)
- Hybrid: EAF < 4.4 (combines best of both)

**Capability Saturation Curve**:
```
performance_gain(multi_agent) = max_gain × (1 - e^(-saturation / (single_agent_baseline - 0.45)))
```
- single_agent_baseline < 0.45: gain positive
- single_agent_baseline ≈ 0.45: gain ≈ 0
- single_agent_baseline > 0.45: gain negative (degradation)

## 三、Multi-Agent 通信协议对比

| 协议 | 通信方式 | 延迟 | 复杂度 | AetherCode |
|---|---|---|---|---|
| **Shared Memory (Blackboard)** | 写共享 KV | 低 | 中 | ✅ InMemoryBlackboard (R-sdk-13) |
| **Message Passing** | 显式消息 | 中 | 高 | ❌ (未来) |
| **Sub-Goal 公式化 (AgentOrchestra)** | 显式 sub-goal record | 中 | 中 | ❌ (未来 R-sdk) |
| **A2A Streaming** | HTTP/SSE streaming | 中-高 | 高 | ✅ A2A server + client (R-sdk-7) |
| **Blackboard + Bridge** | InMemory 或 Sqlite | 低 | 中 | ✅ SqliteBlackboard |

## 四、4 维 Agent Assessment 框架 (paper 2512.12791)

| 维度 | 子能力 | AetherCode 测试覆盖 | 聚合 metric |
|---|---|---|---|
| **LLM** | 推理 / 规划 / 反思 | R-eval-1, R-eval-4 | LlmScore (0-1) |
| **Memory** | episodic/semantic/procedural | R-eval-2 | MemoryScore |
| **Tools** | 选择/参数/多 tool/canary | R-eval-3/10/12 | ToolsScore |
| **Environment** | 协议/安全/cost | R-eval-6/7/8/9 | EnvScore |

**跨 Run 行为分布**:
- variance (paper 2601.01743 §5.5)
- worst-case run id
- best-case run id
- 行为聚类 hash

## 五、可实现的 4 个新 SDK class (Tier-3 候选)

### 5.1 AgentArchitectureSelector
```java
class AgentArchitectureSelector {
    Architecture recommend(TaskFeatures features);
    // 输入: parallelizable / sequential / tool-heavy / dynamic / single-agent-baseline
    // 输出: Single / Independent / Centralized / Decentralized / Hybrid
    // 跟 paper 2512.08296 §3.3 一致
}
```

### 5.2 CapabilitySaturationDetector
```java
class CapabilitySaturationDetector {
    boolean isSaturated(double singleAgentBaseline);
    // singleAgentBaseline > 0.45 → isSaturated = true
    // 跟 paper 2512.08296 §3.1 一致
}
```

### 5.3 AgentAssessmentFramework (4 维聚合)
```java
class AgentAssessmentFramework {
    AssessmentReport assess(AgentRun run);
    // 4 维评分 + variance + worst/best
}
```

### 5.4 CentralPlanner (2506.12508)
```java
class CentralPlanner {
    Plan plan(String goal, List<AgentSpec> available);
    SubGoal[] decompose();
    // 委派 sub-task 到 specialized agents
}
```

## 六、AetherCode 多 Agent 路线图 (2026 路线)

### Round 1 (✅ done) — R-mod-2 Tier-2
- IndependentStrategy (paper Independent)
- HybridStrategy (paper Hybrid)
- 5 architecture matrix 文档化

### Round 2 (计划) — AgentArchitectureSelector + CapabilitySaturation
- 跟 2512.08296 兼容: task features → architecture 推荐
- 当 single agent 达 45% 时报警, 提示升级

### Round 3 (计划) — AgentAssessmentFramework 顶层 API
- 跟 2512.12791 兼容: 4 维聚合 + variance
- 集成 R-eval 1-12 现有 metric

### Round 4 (计划) — CentralPlanner + SubGoal
- 跟 2506.12508 兼容: 委派 sub-task
- 跟 A2A protocol 集成

## 七、关键洞见 (从 3 篇 paper 抽取)

1. **架构-任务对齐**: 错的架构降低 70% 性能 (2512.08296)
2. **错误控制 > token 优化**: Centralized 把错误从 17.2× 降到 4.4× (2512.08296)
3. **能力饱和点 ~45%**: 单 agent 达 45% 后多 agent 收益递减 (2512.08296)
4. **Centralized 适合 parallelizable**: +80.8% (financial reasoning) (2512.08296)
5. **Decentralized 适合 dynamic**: +9.2% (web nav) vs +0.2% (Centralized) (2512.08296)
6. **二元 task completion 不够**: 必须看 4 维行为分布 (2512.12791)
7. **Central Planner > flat-agent**: 83.39% GAIA (2506.12508)
8. **Adaptive Role Allocation 关键**: 动态角色分配 vs 静态 (2506.12508)

## 八、参考文献

1. **arXiv:2512.08296** — Kim, Gu, Park et al. "Towards a Science of Scaling Agent Systems" (2025-12, MIT)
   `reference/papers/2512.08296-scaling-agent-systems.pdf`
2. **arXiv:2512.12791** — Akshathala, Adnan et al. "Beyond Task Completion" (2025-12, IBM Research)
   `reference/papers/2512.12791-beyond-task-completion.pdf`
3. **arXiv:2506.12508** — Zhang, Zeng et al. "AgentOrchestra" (2025-06, Skywork AI)
   `reference/papers/2506.12508-agentorchestra.pdf`
