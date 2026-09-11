# AetherCode Memory 系统理解

> **配套材料**:
> - 参考文献: `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf` (14.9 MB, 47 作者, arXiv:2512.13564v2, 2026-01-13)
> - 翻译: `reference/papers/2512.13564v2_中文翻译.md` (196 KB)
> - 摘要: `reference/papers/2512.13564v2_摘要.md` (15.8 KB)
> - 设计方案: `doc/项目文档/AetherCode-Memory-Optimization-Design.md` (同目录)
> **生成时间**: 2026-09-07
> **作者**: Mavis (Mavis Code, 调研用)

---

## 一、文献核心框架：形式—功能—动态三角

该论文 (Hu et al. 2026) 提出的"形式—功能—动态"三角分析框架旨在终结智能体记忆领域的概念碎片化。

### 1.1 三种形式 (Forms) — 什么承载记忆

| 形式 | 特征 | AetherCode 对应 |
|------|------|----------------|
| **词元级 (Token-level)** | 符号化、可寻址、可编辑 | ✅ 主要形式 (facts/rules/changes/breadcrumbs) |
| **参数化 (Parametric)** | 隐式、抽象、可泛化 | ❌ 未实现 (依赖 LLM 内部权重) |
| **潜在 (Latent)** | 隐式、机器原生、跨模态 | ❌ 未实现 |

词元级按拓扑又分：
- **1D 扁平**：序列累积 (MemGPT 风格)
- **2D 平面**：树/图 (A-MEM, HippoRAG)
- **3D 层次**：多层图 (Zep, GraphRAG)

### 1.2 三种功能 (Functions) — 智能体为何需要记忆

| 功能 | 回答的问题 | 时间属性 | AetherCode 对应 |
|------|----------|---------|----------------|
| **事实性记忆 (Factual)** | "智能体知道什么？" | 长期 | ✅ `Fact` 类型 (key/value 标签) |
| **经验性记忆 (Experiential)** | "智能体如何提升？" | 长期 | ⚠️ 部分 (compression 蒸馏策略) |
| **工作记忆 (Working)** | "智能体此刻在想什么？" | 短期 | ⚠️ 部分 (transcript + AppState) |

**关键认知科学映射**:
- 事实性 ↔ 陈述性记忆 (Tulving)
- 经验性 ↔ 非陈述性记忆 (程序性 + 习惯)
- 工作记忆 ↔ Baddeley 工作记忆模型

**重要观察**: 智能体版本具备生物对应物所缺乏的能力——**可内省、可编辑、可版本化**。

### 1.3 三种动态 (Dynamics) — 记忆如何运作

```
形成 (Formation)  →  演化 (Evolution)  →  检索 (Retrieval)
       ↑                                       │
       └───────── 反馈闭环 ──────────────────┘
```

**记忆形成 (5 类)**:
1. 语义摘要 (MemGPT、MEM1)
2. 知识蒸馏 (TiM、RMM、MEM-α)
3. 结构化构建 (KGT、AriGraph、Zep 知识图谱)
4. 潜在表征 (MemoryLLM、MemGen、KV 缓存)
5. 参数内化 (MEND、ROME、MEMIT)

**记忆演化 (3 类)**:
1. 整合 (Consolidation) — 合并新旧，合成高层洞察
2. 更新 (Updating) — 解决冲突，修订过时
3. 遗忘 (Forgetting) — 剪枝冗余/过时

**记忆检索 (4 步)**:
1. 时机与意图 (什么时候触发)
2. 查询构建 (HyDE、Visconde、Auto-RAG)
3. 检索策略 (BM25、向量、图、生成、混合)
4. 后检索处理 (重排序、过滤、聚合)

### 1.4 八大研究前沿

| 前沿 | 核心命题 | AetherCode 现状 |
|------|---------|----------------|
| **F1. 检索 vs. 生成** | 从识别到主动合成 | ❌ 只检索，未做生成式记忆 |
| **F2. 自动化记忆管理** | 从手工到自组织 | ⚠️ 部分 (`shouldTriggerCompression` 启发式) |
| **F3. RL × 记忆** | 端到端学习 | ❌ 未探索 |
| **F4. 多模态记忆** | 文本+图像+视频+音频 | ❌ 仅文本 |
| **F5. 多智能体共享记忆** | 集体认知基底 | ❌ Subagent 仅本地 |
| **F6. 世界模型中的记忆** | 状态模拟 | ❌ 不适用 |
| **F7. 可信记忆** | 隐私+可解释+抗幻觉 | ⚠️ 部分 (SecureFilePermissions 0600) |
| **F8. 人类认知连接** | 离线整合、生成式重构 | ❌ 未探索 |

---

## 二、AetherCode Memory 系统现状

### 2.1 三大内存层 (Scope)

```
┌─────────────────────────────────────────────────────────────┐
│                      AetherCode Memory                       │
├──────────────────┬──────────────────┬───────────────────────┤
│  GLOBAL (用户级)   │  PROJECT (项目级)  │  SESSION (会话级)     │
│  ~/.aethercode/   │  <cwd>/.aethercode/│  当前会话 transcript  │
│  memory.md        │  memory.md        │  + session_facts      │
├──────────────────┼──────────────────┼───────────────────────┤
│ 用户偏好/编码风格  │ 项目说明/构建命令  │ 当前任务上下文         │
│ 长期、跨项目       │ 长期、跨会话       │ 短期、单会话          │
└──────────────────┴──────────────────┴───────────────────────┘
```

### 2.2 四种条目类型 (Kind)

```typescript
// aethercode-memory/src/types.ts
type MemoryEntry = Fact | Rule | ChangeEntry | Breadcrumb;

interface Fact       { kind: 'fact';       key: string; value: string; }      // 事实
interface Rule       { kind: 'rule';       text: string; }                    // 规则
interface ChangeEntry{ kind: 'change';     description: string; compressed: boolean; }  // 变更
interface Breadcrumb { kind: 'breadcrumb'; message: string; }                 // 面包屑
```

### 2.3 三层后端瀑布 (Backend Cascade)

```
┌─────────────┐      miss       ┌─────────────┐      miss       ┌─────────────┐
│  LRU Cache  │  ────────────►  │   SQLite    │  ────────────►  │ .md 文件     │
│  (内存)      │                 │  sessions.db │                 │ (用户级/项目级)│
│  <10ms       │                 │  ~50ms       │                 │  <200ms       │
└─────────────┘                 └─────────────┘                 └─────────────┘
```

### 2.4 模块分层 (按代码)

| 层 | 路径 | 角色 |
|----|------|------|
| **核心库** | `aethercode-memory/` (TS, 独立 npm package) | 类型/MD 解析/SQLite 迁移/压缩/RPC/CLI |
| **桌面 UI** | `aethercode-desktop/src/store/index.ts` | 3-scope 镜像 (USER/PROJECT/SESSION) + 通知刷新 |
| **TUI 面板** | `aethercode-tui/src/components/MemoryPanel.tsx` (R80) | 3-tab 列表 + 编辑对话框 + 压缩按钮 |
| **Java 引擎** | `aethercode-core/.../AppState.java` | `surfacedMemories` (R19-B 去重) + `cwd(Path)` 路径 |
| **RPC 层** | `aethercode-protocol/.../AetherCodeMethods.java` | `memory/get` + 5 个 RPC 方法 (T-070 ~ T-075) |
| **CLI** | `ac-mem` bin (T-090 ~ T-099) | show/edit/compact/reset 子命令 |

### 2.5 完整文件清单 (Phase 1 R80 已完成)

```
aethercode-memory/src/
├── types.ts              # Fact/Rule/Change/Breadcrumb + 类型守卫
├── markdown.ts           # parse/serialize global memory
├── project-markdown.ts   # parse/serialize project memory
├── sqlite.ts             # 版本化迁移运行器 (3 个 migration)
├── session-store.ts      # session_messages 表读写
├── project-store.ts      # project + project_changes 表
├── jsonl-writer.ts       # 原子写入 + 0600 权限
├── jsonl-fold.ts         # 会话消息折叠
├── lru-cache.ts          # LRU 缓存 (T-030+)
├── memory-store.ts       # 高层 API: ensureProjectFile / ensureGlobalFile
├── compression.ts        # 4-shot LLM 压缩 + WAL + resume
├── project-switcher.ts   # hash(cwd) + breadcrumb + supervisor hook
├── rpc.ts                # 6 个 JSON-RPC 方法
├── cli.ts                # 4 个 CLI 子命令 (33 KB)
└── security/permissions.ts  # POSIX 0600 helper
```

### 2.6 6 个 JSON-RPC 方法 (T-070 ~ T-075)

| 方法 | 作用 |
|------|------|
| `memory/get` | 读一个 scope 的全部条目 |
| `memory/appendProjectChange` | 追加 project 修改记录 |
| `memory/appendSessionFact` | 追加 session 级别 fact |
| `memory/compact` | 触发 LLM 压缩 |
| `memory/switchProject` | 切换项目 cwd + breadcrumb |
| `memory/list` | 列出所有项目 |

### 2.7 压缩流水线 (compression.ts)

```
触发 (T-050 shouldTriggerCompression, 启发式)
  │
  ▼
WAL entry (T-052 startWalEntry, 崩溃恢复)
  │
  ▼
LLM 调用 (T-053 4-shot prompt, markWalLlmDone)
  │
  ▼
Commit (T-053 markWalCommitted)
  │
  ▼
失败 (T-053 markWalFailed)  ←  resumable
```

---

## 三、文献三角 × AetherCode 现状：Gap 分析

### 3.1 形式维度 (Forms) — Gap 评估

| 文献分类 | AetherCode 现状 | 缺口 |
|---------|---------------|------|
| 词元级 1D 扁平 | ✅ MemoryStore (序列累积) | OK |
| 词元级 2D 平面 | ❌ 没有图/树结构 | **需要**：AetherCode 当前 facts/rules 互相独立，无关联 |
| 词元级 3D 层次 | ❌ 没有多层结构 | **需要**：长期目标 |
| 参数化 | ❌ 不适用 | OK (依赖 LLM 训练，超出范围) |
| 潜在 (KV/向量) | ❌ 没有向量检索 | **需要**：没有 embedding + 向量存储 |

**关键 gap**: AetherCode 全部是 **1D 扁平 token-level**，没有 2D/3D 拓扑、也没有潜在记忆。检索只能按 key 前缀或全表扫描，无法支持语义检索。

### 3.2 功能维度 (Functions) — Gap 评估

| 文献分类 | AetherCode 现状 | 缺口 |
|---------|---------------|------|
| **事实性 (Factual)** | ✅ Fact 类型 + memory.md | OK 但缺乏用户/环境分轴 (4.1.1/4.1.2) |
| **经验性 (Experiential)** | | |
| ├ 基于案例 (Case) | ⚠️ ChangeEntry 类似 | 弱关联，没有 trajectory/solution 元数据 |
| ├ 基于策略 (Strategy) | ⚠️ Rule 类型 | 没有"工作流"或"思维模板"概念 |
| ├ 基于技能 (Skill) | ❌ 无 | **需要**：可执行 skill/MCP 协议引用 |
| ├ 混合 | ❌ 无 | **需要** |
| **工作记忆 (Working)** | | |
| ├ 单轮 (LLMLingua) | ❌ 无 | **需要**：长上下文压缩 (R228 todo plan 走了单轮方案但没复用此机制) |
| ├ 多轮 (MEM1, ReSum) | ⚠️ AppState 整个 transcript | 缺乏主动压缩/折叠 |

**关键 gap**:
1. **没有技能记忆** (skill/mcp/executable)。AetherCode 的 skill 系统和 memory 系统是两套独立机制，没有交叉。
2. **没有案例/策略** 区分。ChangeEntry 是最接近 case 的，但 description 是单字符串，没有 trajectory/solution 字段。
3. **没有工作记忆的主动管理**。当前 transcript 是 append-only，8 百万 token 才会触发压缩。MEM1/ReSum 这种"在线折叠"机制缺失。

### 3.3 动态维度 (Dynamics) — Gap 评估

| 阶段 | 文献方法 | AetherCode 现状 | 缺口 |
|------|---------|---------------|------|
| **形成** | 5 种策略 | | |
| ├ 语义摘要 | ✅ compression.ts | 4-shot LLM | OK |
| ├ 知识蒸馏 | ⚠️ partial | compression 输出结构化 | 缺乏"反思" (Reflection) 步骤 |
| ├ 结构化构建 | ❌ 无 | **需要**：facts 之间无关联 |
| ├ 潜在表征 | ❌ 无 | **需要**：向量存储 |
| ├ 参数内化 | ❌ 不适用 | OK | |
| **演化** | 3 种机制 | | |
| ├ 整合 (Consolidation) | ❌ 无 | **需要**：没有把多条 facts 合成更高层 fact 的机制 |
| ├ 更新 (Updating) | ❌ 无 | **需要**：冲突检测/解决 |
| ├ 遗忘 (Forgetting) | ❌ 无 | **需要**：过期/低价值清理 (only 50-条阈值触发压缩, 不是真遗忘) |
| **检索** | 4 步 | | |
| ├ 时机与意图 | ⚠️ partial | refreshMemory() 手动触发 | **需要**：模型驱动的"何时检索" |
| ├ 查询构建 | ❌ 无 | **需要**：HyDE/Visconde |
| ├ 检索策略 | ⚠️ 1 种 | 按 key 过滤 | **需要**：BM25/向量/图 |
| ├ 后检索处理 | ⚠️ 简单 | 直接返回 | **需要**：重排序、聚合 |

**关键 gap**:
1. **演化机制完全缺失**。AetherCode 只有 append，没有 consolidate/update/forget。这导致记忆库会无限增长、过期信息不会被清理、冲突信息会累积。
2. **检索策略只有"按 key 过滤"一种**。没有 BM25/语义/图检索，对自然语言查询支持差。

### 3.4 八大前沿 — Gap 评估

| 前沿 | 评估 | 优先级 |
|------|------|-------|
| **F1 检索 vs 生成** | 全部检索 | P0 (核心缺口) |
| **F2 自动化管理** | 部分 (heuristic compression trigger) | P1 |
| **F3 RL × 记忆** | 不适用 (AetherCode 不用 RL 训练) | P3 |
| **F4 多模态** | 全部文本 | P2 (skill/工具输出算半结构化) |
| **F5 多智能体共享** | Subagent 无记忆 | P0 (AetherCode 有 subagent!) |
| **F6 世界模型** | 不适用 | P3 |
| **F7 可信记忆** | 部分 (0600 权限) | P1 |
| **F8 人类认知** | 无离线整合 | P3 (长期) |

**最关键的 3 个 gap**:
1. **F1 + 检索策略单一** — 用户问"上次我怎么处理 X 的？" 模型答不上来，因为只能按 key 查。
2. **F5 + Subagent 无记忆** — AetherCode 的 subagent 不能跨调用保留任何信息，每次都是 fresh。
3. **演化缺失** — memory.md 文件会无限增长，200 条后会卡顿。

---

## 四、AetherCode 已有但文献缺失/弱化的能力

文献重点是 LLM 智能体 (交互式)，AetherCode 是 **IDE 集成 + 多项目** 形态。有些 AetherCode 强项文献没强调：

1. **多项目隔离 (Project Switcher)** — hash(cwd) breadcrumb 切换。文献没把"多项目"列为独立维度。
2. **POSIX 0600 权限** — 安全落盘，文献 F7 只说"细粒度权限化"，AetherCode 已经做了。
3. **JSONL + 原子写入 + WAL** — 工程化的崩溃恢复。文献只提"safety and auditability"，AetherCode 已经实现。
4. **三层 (User/Project/Session) 物理隔离** — `~/.aethercode/memory.md` + `<cwd>/.aethercode/memory.md` + transcript 三个不同位置。文献没强调"多 scope 物理隔离 vs 逻辑分区"。

---

## 五、可立即着手的优化方向 (优先级排序)

按"对当前开发体验影响最大 + 实现复杂度可控"排序：

### 5.1 [P0] 添加向量检索 (F1 + 检索策略)
- 加 `embedding.ts` 模块，使用本地 embedding 模型 (e.g. all-MiniLM-L6-via ONNX) 或远程 API
- SQLite 加 `facts_vec` 表 (vec0 扩展) 或外置向量存储
- `memory/find` RPC 支持自然语言查询，返回 top-k 相似 facts
- **对开发体验的影响**: 大幅提升"上次我怎么处理 X"类问题的检索质量

### 5.2 [P0] 演化机制 (Consolidation + Forgetting)
- `memory/evolve` RPC：定期检查，过期 facts 标记，相似 facts 合并
- 项目级压缩后保留"反思" (Reflection) 步骤，类似 Reflexion
- **对开发体验的影响**: memory.md 不再无限增长，知识质量持续提升

### 5.3 [P0] Subagent 共享记忆 (F5)
- Subagent 启动时接受 `sharedMemory: boolean` 参数
- 共享时挂载到主 agent 的 PROJECT scope (read) + 自己的 SESSION scope (write)
- 完成后回写关键发现到 PROJECT scope
- **对开发体验的影响**: 多步任务可以分工，每步 subagent 知道前面的进展

### 5.4 [P1] 技能记忆 (Skill-based Memory)
- `Skill` 类型 (`executable_ref: string`, 引用 MCP/skill)
- `memory/appendSkill` RPC：自动从 skill 注册表关联
- 检索时返回"可执行的能力"而不是"静态的事实"
- **对开发体验的影响**: "如何做 X" 类问题可以从"陈述性"记忆升级到"程序性"记忆

### 5.5 [P1] 主动遗忘 (Forgetting)
- 引入 `last_accessed` 字段到 Fact
- `memory/forget` RPC：根据"过期 + 低访问 + 低信息价值"三标准剪枝
- 配合 Compression 实现 "Compress + Forget" 双轨
- **对开发体验的影响**: 长期项目不卡顿，知识库自我清洁

### 5.6 [P1] 反思/更新机制 (Updating)
- 冲突检测：当新 fact 的 key 与旧 fact 相同但 value 不同时，标记 `pending_update`
- 二次确认：自动或人工 resolve，决定 override/keep/coexist
- **对开发体验的影响**: 避免"过时信息继续影响模型"

### 5.7 [P2] 增强可信记忆 (F7)
- 引入"可追溯访问路径"：每个 fact 记录被哪些 session/query 引用
- 引入"反事实推理"：模拟"删除这条 fact 后会怎样" (类似 git blame)
- **对开发体验的影响**: 用户能审计自己"为什么模型知道 X"

### 5.8 [P3] 自动化记忆管理 (F2 长期)
- 引入"记忆管理工具"作为 LLM 可调用工具
- 模型自己决定 read/write/compress/forget
- 起点：`memory_search`、`memory_append`、`memory_compress_now` 三个 tool

---

## 六、术语映射表 (双向)

| AetherCode 现行 | 文献标准 | 说明 |
|---------------|---------|------|
| `Fact` (key/value) | **事实性记忆 (Factual)** 部分子集 | AetherCode 把 user fact 和 env fact 都压成 key/value，丢了语义 |
| `Rule` (text) | **经验性记忆** 中的 "基于策略" | AetherCode Rule 是高层策略但没结构化 |
| `ChangeEntry` | **经验性记忆** 中的 "基于案例" | AetherCode 简化了，只有 description 单字段 |
| `Breadcrumb` | 不直接对应 | 文献没强调跨项目导航；AetherCode 项目切换是独特能力 |
| `transcript` | **工作记忆 (Working)** | AetherCode 整个 transcript 都在工作记忆里，没折叠 |
| `Compression` | **演化 (Evolution) - 整合** | AetherCode 只做了 consolidation 部分 |
| (无对应) | **演化 - 更新** | 缺失 |
| (无对应) | **演化 - 遗忘** | 缺失 |
| `memory/find` (按 key) | **检索 - 词法/索引** | 只有这一种，缺语义/图/混合 |
| (无对应) | **形成 - 知识蒸馏 (反思)** | 压缩缺反思步骤 |
| (无对应) | **形成 - 结构化构建** | 缺图谱 |
| (无对应) | **形成 - 潜在表征** | 缺 embedding |
| (无对应) | **技能记忆** | 缺可执行能力引用 |
| (无对应) | **多智能体共享** | Subagent 隔离 |

---

## 七、参考文献 (本项目)

1. **Hu et al. (2026)**. "Memory in the Age of AI Agents: A Survey — Forms, Functions and Dynamics." arXiv:2512.13564v2, Jan 13 2026.
2. **AetherCode 项目内**:
   - `aethercode-memory/README.md` (Phase 1 R80 设计)
   - `aethercode-memory/src/types.ts` (4 entry kinds 核心类型)
   - `aethercode-memory/docs/R80-CLI-MEMORYPANEL-2026-08-28.md` (R80 收尾报告)
   - `aethercode/aethercode-core/src/main/java/org/aethercode/core/app/AppState.java` (R19-B surfacedMemories)

## 八、相关调研的延伸阅读

- Mem0: "Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory" (2024)
- Zep: "Zep: A Temporal Knowledge Graph Architecture for Agent Memory" (2025)
- A-MEM: "Agentic Memory" (2025)
- MEM1: "MEM1: Learning to Synergize Memory and Reasoning for Efficient Long-Horizon Agents" (2025)
- MEM-α: "MEM-α: Scaling Memory for Personalized AI Agents" (2025)
- Reflexion: "Reflexion: Language Agents with Verbal Reinforcement Learning" (2023)
- Generative Agents: "Generative Agents: Interactive Simulacra of Human Behavior" (2023, Park et al.)
- ReasoningBank: "ReasoningBank: Scaling Agent Self-Evolving with Reasoning Memory" (2025)

> **下一步**: 见 `doc/项目文档/AetherCode-Memory-Optimization-Design.md`
