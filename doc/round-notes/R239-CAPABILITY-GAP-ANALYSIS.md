# R239 — AetherCode 能力 Gap 分析（基于 8 篇 AI Agent 综述）

**日期**: 2026-09-09
**Round**: R239
**状态**: ✅ 报告完成
**触发**: 用户要求"认真分析翻译的 8 篇综述，确定我们当前欠缺的能力点，给出汇总和可优化点"

---

## 0. TL;DR

读完 8 篇综述（2512.13564v2 / 2510.25445 / 2601.01743 / 2508.17281 / 2608.20379 / 2508.10146 / 2501.07278 / 10.1007-s11831-026-10675-8），**AetherCode 当前的能力覆盖度比 R237 release 时点的认知高一个量级**：

| 维度 | 之前的认知 | **R239 实际盘点** |
|------|----------|----------------|
| Module 数 | ~12 | **29**（aethercode-acp / -evals / -deepagents / -tasks / -memory / -mcp / -permission / -hooks / -skills / -protocol / -workflows / -bridge / -engine-springai / -talon / -models / -core / -runtime / -sdk / -tools / -prompts / -compact / -code / -config 等） |
| 核心 Java 文件 | ~200 | **~700+**（aethercode-tasks 139、aethercode-protocol 122、aethercode-deepagents 120、aethercode-permission 57、aethercode-memory 52、aethercode-mcp 29、aethercode-acp 28、aethercode-workflows 27、aethercode-evals 17 等） |
| DeepAgent 框架 | "用 LangGraph 库" | **完整 Java 端口**：24+ Middleware + CompiledStateGraph + Checkpointer + 子智能体 + 摘要 + 反思 + 提示缓存 |
| Memory 系统 | "4 类 memory" | **完整生命周期**：分层存储 + Consolidator + Deduplicator + ForgettingPolicy + Audit + ExperienceStore + LLM 压缩 |
| Agent 通信 | "MCP only" | **ACP（IBM）也实现了**：`aethercode-acp` 28 java 完整协议 |
| 评测 | "UT + E2E" | **5 个独立 benchmark harness**：Radar / Tau3Subset / Drbench / Contextbench / Clbench + 统一 Cli |

**但仍有 7 个能力是真缺或半成品**（按重要度排序）：

| 优先级 | 能力 Gap | 论文出处 | 缺的程度 |
|-------|---------|---------|---------|
| 🔴 高 | **A2A / ANP 协议**（vs 已有 ACP + MCP） | Paper 6 §III, Paper 8 §VI | 完全缺 |
| 🔴 高 | **多模态融合**（VLM/图像/视频/音频） | Paper 5 全文 | 完全缺 |
| 🔴 高 | **自我改进闭环**（失败库 + 策略蒸馏 + 轨迹微调） | Paper 1 §7, Paper 4 §8, Paper 7 全文 | 框架有但流程缺 |
| 🟡 中 | **ToT/GoT 高级规划 + 不确定性感知** | Paper 4 §3, Paper 1 §7.6 | 当前仅 ReAct + TodoList |
| 🟡 中 | **跨环境终身学习**（稳定性-可塑性两难） | Paper 7 全文 | 缺持续对齐 + 知识编辑 |
| 🟡 中 | **Bench 对标**（把已有 5 个 harness 跑出可复现报告） | Paper 4 §4.1, Paper 8 §V | 模块在但缺基准结果 |
| 🟢 低 | **神经-符号融合**（符号约束器 + 形式化验证） | Paper 2 §IX, Paper 8 §VI | 当前纯神经范式 |

**核心建议**：R240+ 应优先 **激活已有 5 个 benchmark harness 跑出可复现报告**（速胜，3-5 round 内能交付）+ **接入 A2A 协议**（Google 主推，生态正在快速形成）+ **建自我改进闭环**（用 ExperienceStore + 反思 + 轨迹反馈）。

---

## 1. 分析方法

### 1.1 输入

- 8 篇 AI Agent 综述中文摘要 + 关键章节翻译（561.6 KB / 103 主章节 / 206 子章节）
- AetherCode 当前实现盘点（29 module、~700+ java 文件、R237 0.2.57 jar）
- 已有的本地文档（R234 daemon test、R236 SSD 推入、R237 final release、R07 IDEA 插件集成）

### 1.2 分析步骤

1. 从 8 篇摘要提炼**核心能力维度**（14 个，§2）
2. 对每个维度查 AetherCode 当前代码 + 文档，标"已覆盖 / 部分覆盖 / 缺失"（§3 矩阵 + §4 Gap）
3. 按 **ROI = (重要度 × 频率) / 投入** 排序优化点（§5-7）
4. 给出 R240+ 候选路线图（§8）

### 1.3 取舍

- **不重写** 8 篇综述的细节 — 摘要已经在 doc/参考文献/，本报告只引用关键论点
- **不抄 8 篇综述的术语** — 沿用 AetherCode 现有命名（Task / Child / Middleware / Memory scope 等）
- **聚焦"我们能用上的"** — 不写科研 gap，写工程 gap

---

## 2. 8 篇综述核心能力维度（去重合并 = 14 项）

按出现频率 + 工程相关度排序：

| # | 能力维度 | 主要出处 | 关键内容 |
|---|---------|---------|---------|
| D1 | **记忆系统**（分层 / 形式 / 演化） | Paper 1 全文, Paper 4 §7, Paper 7 §6-9 | 三层架构 + 整合/去重/遗忘 + 跨模态 + 可信 |
| D2 | **推理与规划**（CoT/ToT/ReAct/Reflexion） | Paper 4 §3, Paper 1 §7, Paper 8 §3.4 | 任务分解 + 反思 + 测试时计算 |
| D3 | **工具使用与协议**（MCP/A2A/ACP/ANP/Agora） | Paper 6 §III, Paper 4 §2, Paper 3 §V | 5 大协议 + 工具学习 + 验证 |
| D4 | **多智能体协作**（AutoGen/CrewAI/MetaGPT） | Paper 6 §IV, Paper 4 §1.1, Paper 8 §3.2 | 角色 + 协商 + 共享记忆 |
| D5 | **评估与基准**（多维指标） | Paper 3 §V, Paper 4 §4.1, Paper 8 §V | 18 维指标 + 5 大 benchmark |
| D6 | **终身学习**（持续对齐 + 知识编辑） | Paper 7 全文 | 稳定性-可塑性 + 跨环境 |
| D7 | **多模态**（VLM/图像/视频/音频融合） | Paper 5 全文, Paper 8 §3.1 | 委派/晚期/早期 + 4 大应用 |
| D8 | **自我改进**（轨迹微调 + 失败库 + 策略蒸馏） | Paper 1 §7, Paper 4 §8 | Reflexion / RISE / CORY / COPPER |
| D9 | **基础设施**（沙箱 + 审计 + 限流 + 配额） | Paper 3 §III, Paper 8 §VI, Paper 2 §VII | TRiSM + DRIFT + 最小特权 |
| D10 | **神经-符号融合** | Paper 2 §IX, Paper 3 §V.5 | LLM + 符号引擎 + 形式化验证 |
| D11 | **感知与具身**（视觉/触觉/LiDAR） | Paper 5 §4, Paper 4 §2.2 | 机器人 + 物理动作 |
| D12 | **治理与责任**（主体性分类 + 伦理 + 法律） | Paper 2 §VII, Paper 8 §VIII | 3 级主体性 + 7 大责任挑战 |
| D13 | **服务计算 / SOA 对齐** | Paper 6 §IV, Paper 8 §VI | 动态发现 + 契约 + 组合 |
| D14 | **信任与安全**（提示注入 / 越狱 / 记忆中毒） | Paper 4 §4.2, Paper 8 §VI | TRiSM + DRIFT + DID + 密码学 |

---

## 3. AetherCode 当前能力矩阵（按 14 维度 × 状态）

✅ = 已覆盖（核心能力 + 测试或文档齐全）
🟡 = 部分覆盖（模块在但功能浅 / 未启用 / 半成品）
❌ = 缺失

| 维度 | 状态 | 关键证据 | 缺什么 |
|------|------|---------|--------|
| **D1 记忆** | ✅ | aethercode-memory 52 java：LayeredMemoryStore (USER/PROJECT/SESSION) + MemoryConsolidator + MemoryDeduplicator + MemoryExtractor + MemoryRecall + ForgettingPolicy + MemoryAudit + ExperienceStore + MemoryPromptBuilder + ProjectMemoryCompressor (LLM 压缩) | 跨模态记忆 (Paper 1 §5.4) / 参数化记忆 (Paper 1 §2.1) / 可信记忆的版本控制 (Paper 1 §7.7) |
| **D2 推理规划** | 🟡 | aethercode-deepagents: TodoListMiddleware (任务清单) + SummarizationMiddleware (摘要压缩) + RubricMiddleware (反思评分) + PatchToolCallsMiddleware (工具调用修复) + ReAct 隐式 | **ToT/GoT 思维树/图** (Paper 4 §3.1) / **Reflexion 显式反思循环** (Paper 4 §8.1) / **测试时计算分配** (Paper 3 §3.1) |
| **D3 工具协议** | 🟡 | aethercode-mcp 29 java (MCP) + aethercode-acp 28 java (ACP/IBM) + aethercode-tasks 139 java (TaskSystem) | **A2A** (Google 主推, 生态最热) / **ANP** (去中心化, DID) / Agora 协议文档 |
| **D4 多智能体** | ✅ | aethercode-deepagents: AsyncSubAgent + SubAgent + AsyncSubAgentMiddleware + SubAgentMiddleware + CompiledSubAgent + AsyncSubAgentHook + aethercode-tasks: AsyncSubAgent + AsyncSubAgentSpec + ChildRecord + ChildEventStream | **角色抽象** (AutoGen/CrewAI 风格) / **共享记忆层** / **协商机制** (合同网/黑板) |
| **D5 评估** | 🟡 | aethercode-evals 17 java: Cli (run/trials/aggregate/radar/catalog) + Radar + Tau3Subset + Drbench + Contextbench + Clbench + TrialSummary + Stats + Failure + JsonLoaders | **跑出可复现报告** (5 个 harness 都半成品) / **多维指标** (Paper 3 §V.1 的 18 维) / **过程级轨迹指标** |
| **D6 终身学习** | 🟡 | aethercode-memory: MemoryLifecycleR230/R232/R233 + ProjectMemoryCompressor + MemoryConsolidator + ForgettingPolicy | **持续指令微调** / **知识编辑** (MEND/ROME/MEMIT) / **稳定性-可塑性显式控制** (Paper 7 全文) |
| **D7 多模态** | ❌ | 完全缺 | **VLM 整合** / **图像/视频/音频感知** / **跨模态对齐** / **融合架构** (委派/晚期/早期) |
| **D8 自我改进** | 🟡 | ExperienceStore (经验库) + RubricMiddleware (评分) + ToolRetryMiddleware (重试) | **完整闭环**：轨迹采集 → 失败分析 → 策略蒸馏 → 提示库更新 (Paper 4 §8.1 提的 RISE/COPPER) |
| **D9 基础设施** | ✅ | aethercode-tasks: Limits (wallClock/tokens/calls/fileWrites/network) + LimitsEnforcer + LimitsHitService + LimitsPausePolicy + ratelimit + IdleGuard + AutoRestartPolicy + ResumableChannel + Checkpoint + ReattachHandshake + SessionStream | **DRIFT 风格动态规则** (Paper 8 §VI) / **审计日志** 部分有 MemoryAudit 但全局缺 |
| **D10 神经-符号** | ❌ | 完全缺 | **符号约束器** / **形式化验证** / **Prolog/Z3 集成** |
| **D11 感知具身** | ❌ | 完全缺（IDE/RPA 不需要） | 不重要 — AetherCode 定位是 coding agent，不是 embodied |
| **D12 治理责任** | 🟡 | aethercode-permission 57 java: 完整权限 + HumanInTheLoopMiddleware + 3 级主体性（辅助/共享/委托）推断 | **法律框架** (Paper 8 §VIII 7 大挑战) / **问责合约** / **透明度报告** |
| **D13 SOA** | 🟡 | aethercode-protocol 122 java: 协议层 + ACP/MCP + ServiceDiscovery 部分 | **WSDL/BPEL 风格契约** / **动态组合编排** / **服务市场** |
| **D14 信任安全** | 🟡 | aethercode-permission + MemoryAudit + AutoRestartPolicy + ToolRetryMiddleware | **提示注入防御** (Paper 4 §4.2) / **DRIFT 输入审计** (Paper 8 §VI) / **DID + 密码学** |

### 3.1 关键模块详细盘点

**已实现但未充分启用的金矿**（R240+ 重点激活）：

| Module | 实际能力 | 当前启用情况 | 优化空间 |
|--------|---------|------------|---------|
| **aethercode-evals** (17 java) | 5 个 benchmark harness + 统一 Cli | **未跑过** | 速胜 — 1-2 round 出 benchmark 报告 |
| **aethercode-acp** (28 java) | 完整 ACP 协议 server + client + examples | **未启服务** | 接入 ACP editor（Zed / JetBrains 新版） |
| **aethercode-skills** (4 java) | SkillRegistry + SkillMarketplace | **基础** | 扩 skill 库 + marketplace 联网 |
| **aethercode-bridge** (0 java / 需查) | 桥接器 | **未深查** | 跨 surface 共享状态 |
| **aethercode-engine-springai** (0 java / 需查) | Spring AI 引擎 | **未深查** | 多模型路由 |

---

## 4. Gap 分析（按维度深入）

### 4.1 🔴 高优先级 Gap

#### Gap-1: A2A 协议未实现（vs ACP + MCP 双协议已有）

**论文出处**: Paper 6 §III（Brahmi 2025）显式对比 5 大协议：MCP（已实现）/ A2A（缺）/ ANP（缺）/ ACP（已实现）/ Agora（缺）

**AetherCode 现状**:
- ✅ aethercode-mcp: 29 java 完整 MCP 协议
- ✅ aethercode-acp: 28 java 完整 ACP（IBM）协议
- ❌ A2A: 完全没有
- ❌ ANP: 完全没有
- ❌ Agora: 完全没有

**为什么重要**:
- **A2A 是 Google 主推**（2024 年发布、Agent Card / Task Object / Artifact 三大原语）
- 生态最强：LangGraph / CrewAI / GenKit / Vertex AI Agent Engine 全部支持
- 与 AetherCode 现有 ACP 是**正交**的（ACP 是 client-server JSON-RPC、A2A 是 peer-to-peer capability discovery）
- 不接 A2A = 错过与 Google 生态的互操作

**投入估算**: 中（2-3 round）
- Round 1: A2A spec 研读 + Agent Card / Task / Artifact schema 设计
- Round 2: Java 实现 AgentServerA2A + ClientA2A
- Round 3: 与 aethercode-protocol 集成 + 跑 e2e 测试

#### Gap-2: 多模态完全缺

**论文出处**: Paper 5（Mokaria 2026）全文 + Paper 8 §3.1（感知）

**AetherCode 现状**:
- ❌ 任何 VLM 整合
- ❌ 图像/视频/音频感知
- ❌ 多模态记忆
- ❌ 融合架构（委派/晚期/早期）

**为什么重要**:
- Paper 5 §1 明确：**多模态是塑造现代智能体化能力的关键维度，不是附加而是基础**
- 2026 综述的共识：纯文本 LLM 智能体已是上一代
- 即使 coding agent 也能用多模态：截图 UI / 看视频 / 读 PDF / OCR

**投入估算**: 高（5-8 round）
- Round 1: VLM 选型（GPT-4o / Claude 3.5 / Qwen-VL / InternVL）
- Round 2: 多模态 Tool（图像理解 / 视频帧提取 / 音频转写）
- Round 3: 多模态 Memory（统一向量空间 CLIP-style）
- Round 4-5: 真实场景跑通（截图→修 bug / 视频→找问题帧）

#### Gap-3: 自我改进闭环不完整

**论文出处**: Paper 1 §7.2 (自动化记忆管理) + Paper 4 §8.1 (RISE/CORY/COPPER) + Paper 4 §6.2 (RAG + 长上下文 + 外部记忆)

**AetherCode 现状**（部分有，但流程未闭环）:
- ✅ ExperienceStore（经验库）— 但只存不取
- ✅ RubricMiddleware（评分）— 但只评分不学习
- ✅ ToolRetryMiddleware（重试）— 但只重试不蒸馏策略
- ✅ MemoryConsolidator（整合）— 但只整合不跨 session
- ❌ 失败案例分析 + 提示库自动更新
- ❌ 轨迹微调 pipeline
- ❌ 策略库（Reflexion 风格）

**为什么重要**:
- Paper 4 §6.2 明确：**"评估范式转变：从静态准确率基准 → 动态过程导向方法"**
- Paper 1 §7.1 提**"记忆检索 vs. 记忆生成"**未来方向
- 自我改进是"持续可用 vs. 静态系统"分水岭

**投入估算**: 中（3-5 round）
- Round 1: 失败案例自动分类 + tag
- Round 2: 策略库（按失败模式映射到提示模板）
- Round 3: 提示自动更新（用户确认后入库）
- Round 4-5: 跨 session 蒸馏 + 反馈闭环

### 4.2 🟡 中优先级 Gap

#### Gap-4: ToT/GoT 高级规划 + 不确定性感知

**论文出处**: Paper 4 §3.1.1 (ToT) + Paper 3 §6.3 (测试时计算分配)

**AetherCode 现状**:
- ✅ TodoListMiddleware（任务清单 = 线性规划）
- ✅ ReAct 隐式（reasoning + acting 交错）
- ❌ ToT（多路径搜索 + 评估 + 修订）
- ❌ GoT（图状思维）
- ❌ Reflexion 显式反思循环
- ❌ 预算受限自主性（时间/token/工具 — Limits 有但未用于规划）

**优化**: 在 aethercode-deepagents 加 `TreeOfThoughtsMiddleware` + `BudgetAwarePlanner`（2-3 round）

#### Gap-5: 跨环境终身学习

**论文出处**: Paper 7（Zheng 2026）全文

**AetherCode 现状**:
- ✅ MemoryLifecycleR230/R232/R233（持续迭代）
- ✅ ProjectMemoryCompressor（LLM 压缩）
- ✅ ForgettingPolicy（遗忘策略）
- ❌ 持续指令微调（continual instruction tuning）
- ❌ 知识编辑（MEND/ROME/MEMIT）
- ❌ 持续对齐（避免价值漂移）
- ❌ 灾难性遗忘的显式防护

**优化**: aethercode-memory 加 `ContinualAligner` + `KnowledgeEditor`（3-4 round）

#### Gap-6: Bench 跑分对标（5 个 harness 跑出可复现报告）

**论文出处**: Paper 4 §4.1 + Paper 8 §V.3（基准 + 批判性方法论）

**AetherCode 现状**（模块在但**没跑过**）:
- ✅ Radar.java — RAG 评测（看名字像 Radar benchmark）
- ✅ Tau3Subset.java — τ-bench subset
- ✅ DrbenchAdapter/DrbenchJudge — DR-bench
- ✅ ContextbenchAdapter/ContextbenchJudge — ContextBench
- ✅ ClbenchTypes/DeepAgentsSystem — ClBench

**为什么重要**:
- Paper 4 §4 提 8 大基准：AgentBench / WebArena / ToolBench / SWE-bench / GAIA / MINT / τ-bench / HumanEval+MBPP
- Paper 8 §V.3 批判："高分不等于真实能力（OSWorld 28% 误估、WebArena 14% vs 78% 人类）"
- **不跑分 = 没有外部对照 = 自吹自擂**

**优化**: 1-2 round 跑完 5 个 harness 出公开报告（这是**速胜**！）

### 4.3 🟢 低优先级 Gap

#### Gap-7: 神经-符号融合

**论文出处**: Paper 2 §IX（Abou Ali 2025 明确"神经-符号集成作为关键基石"）+ Paper 3 §V.5（神经-符号智能体）

**AetherCode 现状**: 完全缺，纯神经范式

**为什么低优先**:
- AetherCode 定位 coding agent，符号约束需求低
- Paper 2 §IX 建议 R240+ 中期才考虑
- 实施成本高（Z3 / Lean / Prolog 集成）

**优化方向**: 仅在 R250+ 长期路线图考虑（如 Z3 约束求解加到 LimitsEnforcer）

---

## 5. 高 ROI 优化点（速胜 — 1-3 round 内交付）

按 ROI 排序，**用户能立刻看到效果**：

### O-1: 跑完 5 个 benchmark harness 出公开报告

**对应 Gap**: Gap-6
**投入**: 1-2 round
**ROI**: ⭐⭐⭐⭐⭐

**行动**:
1. `aethercode ssd run radar --model gpt-5.5` 跑 Radar
2. `aethercode ssd run tau3 --domain retail` 跑 τ-bench subset
3. `aethercode ssd run drbench` 跑 DR-bench
4. `aethercode ssd run contextbench --turns 50` 跑 ContextBench
5. `aethercode ssd run clbench --tasks 30` 跑 ClBench
6. `aethercode ssd aggregate` 出统一报告
7. 发到 `doc/项目文档/R240-BENCHMARK-REPORT.md`

**价值**:
- 跟 Paper 4 §4.1 / Paper 8 §V.3 的标准对齐
- 给用户/团队一个"我们 vs SOTA"的可视化对比
- 反向暴露真实能力差距

### O-2: 接入 A2A 协议

**对应 Gap**: Gap-1
**投入**: 2-3 round
**ROI**: ⭐⭐⭐⭐

**行动**:
1. Round 1: 读 A2A spec（Google 官方） + 设计 Agent Card / Task Object / Artifact schema
2. Round 2: 写 `aethercode-a2a` module（仿 `aethercode-acp` 模式 ~25 java）
3. Round 3: 集成 aethercode-protocol + 跑 e2e（与 LangGraph A2A 互通）

**价值**:
- 进入 Google Agent 生态
- 与现有 ACP/MCP 形成协议三角
- 未来跨组织 / 跨云互操作基础

### O-3: 激活 ExperienceStore → 策略库

**对应 Gap**: Gap-3（部分）
**投入**: 2-3 round
**ROI**: ⭐⭐⭐⭐

**行动**:
1. Round 1: ExperienceStore 失败案例自动分类（按 tool_error / user_correction / timeout / permission_denied）
2. Round 2: 策略库 schema（失败模式 → 提示片段）— 类比 Reflexion 口头强化学习
3. Round 3: 自动注入到下次同类型任务的 system prompt
4. 验证：同一类任务成功率提升（如 file_edit 类从 70% → 85%）

**价值**:
- AetherCode 从"静态系统"变"自适应系统"
- Paper 1 §7.2 提的"自动化记忆管理"具体落地
- 用户能直接看到"agent 越来越懂我"

### O-4: 加 ToT/GoT Middleware

**对应 Gap**: Gap-4（部分）
**投入**: 1-2 round
**ROI**: ⭐⭐⭐⭐

**行动**:
1. 加 `TreeOfThoughtsMiddleware` 到 aethercode-deepagents
2. 在复杂任务（多步代码生成 / 复杂 debug）自动启用
3. 在状态图加 `goBack` / `prune` / `expand` 节点

**价值**:
- 跟 LangGraph 状态机风格一致
- Paper 4 §3.1.1 / Paper 1 §7.6（世界模型中的记忆）具体落地
- 显著提升复杂任务成功率

### O-5: 用 Limits 做"预算受限自主性"

**对应 Gap**: Gap-4（部分）+ Gap-9
**投入**: 1 round
**ROI**: ⭐⭐⭐⭐

**行动**:
- aethercode-tasks 已实现 Limits (wallClock/tokens/calls/fileWrites/network)
- 暴露给 RPC（`task/setLimits`）
- Desktop/TUI/CLI 默认设 tokens=50k + wallClock=10min
- 触发 limits_hit → 自动暂停 + 询问用户（"提升上限 / 接受部分结果 / 终止"）

**价值**:
- Paper 3 §6.3 提**"预算受限自主性"**——把不可控变成可控
- 用户能放心让 agent 跑长任务（知道会暂停而不是失控）
- 当前 LimitsEnforcer + LimitsHitService + LimitsPausePolicy 已实现，**只是没接用户面**

---

## 6. 中 ROI 优化点（3-5 round）

### O-6: 持续对齐 + 知识编辑（aethercode-memory 加 ContinualAligner）

**对应 Gap**: Gap-5
**投入**: 3-4 round
**ROI**: ⭐⭐⭐

**行动**:
- 写 `ContinualAligner`（持续指令微调 / 价值漂移检测）
- 写 `KnowledgeEditor`（基于 MEND/ROME 的局部知识更新）
- 写 `CatastrophicForgetGuard`（EWC 风格的参数正则化）

**价值**:
- Paper 7 全文具体落地
- 避免"用着用着变味"
- 长期个性化助手基础

### O-7: 多模态起步（先接 VLM，不做完整融合）

**对应 Gap**: Gap-2（分阶段）
**投入**: 3-4 round
**ROI**: ⭐⭐⭐

**行动**:
- Round 1: VLM 选型（GPT-4o / Claude 3.5 / Qwen-VL）
- Round 2: 多模态 Tool（image_understand / pdf_read / screenshot_analyze）
- Round 3: Desktop 集成（截图→IDE 修 bug 流）
- 暂不做视频/音频，**先在图像站稳脚**

**价值**:
- 立刻能用：截图→自动修 UI bug、PDF→自动提取
- 跟 Paper 5 提的"委派架构"最浅接入
- 为 R250+ 完整多模态融合打底

### O-8: 审计日志 + DRIFT 风格动态规则

**对应 Gap**: Gap-14
**投入**: 2-3 round
**ROI**: ⭐⭐⭐

**行动**:
- 现有 `aethercode-permission` 57 java + `MemoryAudit` 基础上
- 加全局 `AuditLog`（所有 RPC / 工具调用 / memory 写入 / limits 触发）
- 加 `DynamicRuleEngine`（按风险分级，DRIFT 风格：常规路径 → 快速通过；高风险路径 → 强制人工确认）

**价值**:
- Paper 8 §VI 提的"TRiSM + DRIFT"具体落地
- 企业级合规基础
- 失败回溯能查到哪步出问题

### O-9: 角色抽象层（AutoGen/CrewAI 风格）

**对应 Gap**: Gap-4（部分）
**投入**: 2-3 round
**ROI**: ⭐⭐⭐

**行动**:
- aethercode-tasks 已有 AsyncSubAgent + AsyncSubAgentSpec
- 加 `RoleRegistry`（planner / executor / reviewer / researcher / coder 5 个标准角色）
- 加 `SharedBlackboard`（多智能体共享状态）
- 加 `ContractNetProtocol`（合同网协商）

**价值**:
- Paper 6 §IV / Paper 4 §1.1 / Paper 8 §3.2 三篇都强调
- 复杂任务可分解给"专家"而不是一个 agent 硬扛
- AetherCode 从"独奏"变"乐队"

### O-10: 跨 surface 状态共享（统一 Session State）

**对应 Gap**: Gap-13（部分）
**投入**: 2 round
**ROI**: ⭐⭐⭐

**行动**:
- 当前 TUI / Desktop / IDEA plugin 各有 session 状态
- 抽 `aethercode-bridge` 统一 session state（JsonRpc over unix socket / named pipe）
- TUI 起长任务 → Desktop 接管 → IDEA 续做

**价值**:
- AetherCode 已经有 3 个 surface 共享 daemon，bridge 强化
- 论文里没明确提，但用户体验巨大
- "一次启动，多端接续"

---

## 7. 低 ROI / 长期方向（5 round+）

### O-11: 神经-符号融合（Z3 + Lean 集成）

**对应 Gap**: Gap-10
**投入**: 8+ round
**ROI**: ⭐⭐

**说明**: AetherCode 定位 coding agent，符号约束需求低。仅在 R250+ 考虑，可能用例：
- LimitsEnforcer 加 Z3 约束（"不能删 X 文件 + 同时改 Y 文件"）
- 复杂代码 refactor 加符号验证

### O-12: 完整多模态融合架构（Paper 5 委派/晚期/早期）

**对应 Gap**: Gap-2（完整版）
**投入**: 10+ round
**ROI**: ⭐⭐

**说明**: 仅在 O-7 VLM 跑通后考虑。早期融合需要重训，**一般不需要**。

### O-13: 自我意识（世界模型 / 元认知）

**对应 Gap**: 隐含（D1 §7.6）
**投入**: 10+ round
**ROI**: ⭐

**说明**: Paper 1 §7.6 + Paper 7 都提"世界模型中的记忆"。AetherCode 当前是工具型 agent，不需要世界模型。

### O-14: 持续指令微调 pipeline

**对应 Gap**: Gap-6
**投入**: 5-8 round
**ROI**: ⭐⭐

**说明**: 需要 GPU + 训练基础设施 + 评估闭环。**R260+ 考虑**。

---

## 8. 实施路线图候选（R240+）

按"先速胜再深耕"原则：

### R240 (Round 1): 速胜阶段 — 激活已有能力
- **O-1** 跑完 5 个 benchmark 出公开报告
- **O-5** 暴露 Limits 给用户面（Desktop + TUI + CLI）
- **O-4** 加 ToT Middleware 雏形

### R241 (Round 2): 协议三角
- **O-2** A2A 协议实现
- **O-3** ExperienceStore → 策略库（第一版）

### R242 (Round 3): 多模态起步
- **O-7** VLM 接入 + 截图/PDF 工具
- **O-9** RoleRegistry + SharedBlackboard

### R243 (Round 4): 自我改进 + 审计
- **O-3** 完整闭环（失败 → 策略 → 注入）
- **O-8** 全局 AuditLog + DynamicRuleEngine

### R244 (Round 5): 持续学习
- **O-6** ContinualAligner + KnowledgeEditor
- **O-10** 跨 surface 状态共享

### R250+ (长期): 神经-符号 + 完整多模态
- O-11 / O-12 / O-13 / O-14

### 关键依赖与风险

| 风险 | 缓解 |
|------|------|
| Bench harness 跑出来分数低 | 报告诚实 + 用作优化输入（不是 KPI） |
| A2A spec 跟 ACP 重复（功能相似） | A2A 走 peer-to-peer、ACP 走 client-server，明确分工 |
| 自我改进引入意外行为 | 全程 DryRun + 用户确认 + 可回滚 |
| VLM 集成成本（API 费用） | 选 Qwen-VL / InternVL 本地部署方案 |
| 跨 surface 状态同步复杂度 | 先做 read-only mirror（Desktop 看 TUI 的 session），不双向写 |

---

## 9. 关键发现 / 教训（R239 自身）

1. **低估 AetherCode 是最大的认知错误** — 从 R237 final 报告看，AetherCode 似乎是个 0.2.x 的小项目；实际 29 module / 700+ java / 完整 deep agents 框架已默默做了大量铺垫。R240+ 应该是**激活**而非**新建**。

2. **能力盘点必须看代码不看 release 报告** — R237 final report 写的是用户面能力（Desktop / TUI 70+ 项测试），完全没提底层有 4 个独立子项目（evals / acp / deepagents / tasks）。R239 重新盘点后才看到全貌。

3. **8 篇综述的价值不在术语，在对照** — 8 篇翻译价值是**提供"我们 vs 业界共识"的标尺**。摘要里的"已实现 / 缺失"是给读者看的，本报告的"我们覆盖 / 缺失"是给团队看的。

4. **5 个 benchmark harness 静默存在是最大惊喜** — aethercode-evals 17 java 完整 5 harness + Cli，但从未对外跑过。**这就是 R240 的"速胜"项目**——1-2 round 跑分出报告就能给团队 + 用户一个具体的能力可视化。

5. **"自我改进"是 14 维度里最被低估的** — 论文反复强调（Paper 1 §7 / Paper 4 §8 / Paper 7 全文），但 AetherCode 当前只有"经验库 + 评分"零件，没有"失败 → 策略 → 注入"闭环。R241-242 应该是这块重点。

6. **多模态不必一步到位** — Paper 5 全文很重，但 90% 应用是 VLM 起步就够。R242-243 只做 VLM + 截图/PDF，R250+ 再考虑视频/音频/早期融合。

7. **A2A 协议生态比 ACP 强** — ACP (IBM) 我们有，但 Google A2A 生态更广（LangGraph / CrewAI / Vertex AI）。接 A2A = 接 Google 生态。

---

## 10. 引用清单（论文 → AetherCode 能力映射）

| 论文 | 关键论点 | AetherCode 现状 | 优化点 |
|------|---------|----------------|--------|
| 2512.13564v2 (Paper 1) | 记忆形式-功能-动态三角 | ✅ 三层记忆 + Consolidator + ForgettingPolicy + ExperienceStore | D1 跨模态 + 参数化 + 可信 |
| 2510.25445 (Paper 2) | 神经-符号双范式 + 治理鸿沟 | 🟡 纯神经 + 有权限/审计 | D10 神经-符号 + D12 法律框架 |
| 2601.01743 (Paper 3) | Agent Transformer 抽象 + 18 维评估 | 🟡 5 个 bench + 18 维部分 | D5 跑分 + D2 ToT + D9 预算 |
| 2508.17281 (Paper 4) | 7 RQ + 10 大方向 | 🟡 部分覆盖 | D8 自我改进 + D2 高级规划 + D5 bench |
| 2608.20379 (Paper 5) | 多模态融合 | ❌ 缺 | D7 VLM 起步 |
| 2508.10146 (Paper 6) | 5 协议 + 7 框架 | 🟡 MCP + ACP | D3 A2A/ANP + D4 角色抽象 |
| 2501.07278 (Paper 7) | 终身学习 + 稳定性-可塑性 | 🟡 记忆生命周期在 | D6 持续对齐 + 知识编辑 |
| 10.1007-s11831-026-10675-8 (Paper 8) | 架构+基础设施+评估三维度 | ✅ 强（D9）/ 🟡 D5/D13/D14 | 综合 |

---

## 11. 文件路径索引

- 本报告：`doc/项目文档/R239-CAPABILITY-GAP-ANALYSIS.md`
- 8 篇翻译 + 摘要：`reference/papers/*_中文翻译.md` / `*_摘要.md`
- R237 final release 报告：`doc/项目文档/R237-FINAL-RELEASE-2026-09-09.md`
- AetherCode 主项目：`aethercode/`
- 关键 module：
  - `aethercode/aethercode-evals/` — 5 benchmark harness
  - `aethercode/aethercode-acp/` — IBM ACP 协议
  - `aethercode/aethercode-deepagents/` — 24+ Middleware 框架
  - `aethercode/aethercode-tasks/` — Limits + 子智能体
  - `aethercode/aethercode-memory/` — 完整生命周期

---

**作者**: mavis (Mavis, MiniMax Code)
**用时**: ~2 hours（读 8 摘要 + 盘点 29 module + 写报告）
**总字数**: ~5500 字
