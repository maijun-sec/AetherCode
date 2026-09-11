# R230 — Memory 系统综述驱动优化

**Status**: design + first-batch implementation
**Date**: 2026-09-07
**Reference**: arXiv:2512.13564v2 *"Memory in the Age of AI Agents: A Survey — Forms, Functions and Dynamics"* (Hu, Liu, Yue et al., Jan 2026; 77 pages, 100+ authors)
**Goal**: 用综述的 Forms×Functions×Dynamics 三维框架审视 AetherCode 现有 Memory 系统，给出 (1) 系统理解、(2) 设计方案、(3) 第一批落地改造。

---

## 1. 文献核心：Forms × Functions × Dynamics

综述把整个 agent memory 领域压成 **三维坐标系**。这是当前社区最完整的概念框架。

### 1.1 Forms — 记忆的载体

| Form | 子类 | AetherCode 对应 | 综述典型代表 |
|---|---|---|---|
| **Token-level** | 1D Flat (无拓扑) | ✅ `FileBackedMemory` (JSONL) | ExpeL, Mem0, MemGPT |
| | 2D Planar (单层图/树) | ❌ 缺 | A-Mem, Zep, G-Memory |
| | 3D Hierarchical (多层) | ❌ 缺 | HippoRAG, GraphRAG, MemoryOS |
| **Parametric** | Internal (改基模) | N/A (不训练基模) | ROME, MEMIT, WISE |
| | External (LoRA/Adapter) | N/A | CoLoR, MemoryLLM |
| **Latent** | Generate / Reuse / Transform | ❌ 缺 | MemGen, M+, MemoryLLM |

### 1.2 Functions — 记忆的用途

| Function | 子类 | 综述问题 | AetherCode 对应 |
|---|---|---|---|
| **Factual** | User | "用户是谁 / 偏好 / 约束" | ✅ USER scope + Fact entry |
| | Environment | "外部世界 / 项目状态 / 工具" | ⚠️ 局部（PROJECT scope 文件） |
| **Experiential** | Case-based | "具体 trajectory / solution 库" | ⚠️ 弱（change log） |
| | Strategy-based | "可复用的 workflow / insight" | ❌ 缺 |
| | Skill-based | "可执行的 code / API / MCP" | ⚠️ 弱（靠 MEMORY.md 文字） |
| **Working** | Single-turn | "单 turn 内的 active scratchpad" | ❌ 缺 |
| | Multi-turn | "跨 turn 的 state consolidation" | ⚠️ 弱（auto-compress） |

### 1.3 Dynamics — 记忆的生命周期

| 阶段 | 子类 | AetherCode 对应 |
|---|---|---|
| **Formation** | Semantic Summarization | ✅ `ProjectMemoryCompressor`, `MemoryExtractor` |
| | Knowledge Distillation | ⚠️ 弱（仅抽取到 MEMORY.md 文字） |
| | Structured Construction | ❌ 缺（无图/树/时序） |
| | Latent Representation | ❌ 缺 |
| | Parametric Internalization | N/A |
| **Evolution** | Consolidation | ✅ `MemoryConsolidator` (Jaccard 合并) |
| | Updating | ⚠️ 弱（仅 append，无冲突解决） |
| | Forgetting | ❌ 缺（无衰减/淘汰政策） |
| **Retrieval** | Timing & Intent | ⚠️ 仅 query 开始时一次性 |
| | Query Construction | ✅ manifest + lexical scoring |
| | Strategy | ⚠️ 仅有 lexical + LLM 旁路消歧 |
| | Post-Retrieval | ✅ render 到 system prompt |

### 1.4 Frontiers — 综述指出的 7 个前沿

| Frontier | 综述判断 | AetherCode 现状 |
|---|---|---|
| Memory Retrieval → Generation | "retrieval→generation" 范式转移 | ❌ 仍以 retrieval 为主 |
| Automated Memory Management | 未来方向：tool-call 化、self-organizing | ⚠️ 局部（auto-compress） |
| RL × Memory | RL-driven 内存管理是下一阶段 | N/A (不训练) |
| Multimodal Memory | 视觉/音频模态 | ❌ 仅文本 |
| Shared Memory (MAS) | 多 agent 共享 + 角色感知 | ✅ `MemoryShare`, `TeamMemorySync` |
| Memory for World Model | SSM / 双系统 / 主动管理 | N/A |
| Trustworthy Memory | 隐私 + 可解释 + 抗幻觉 | ⚠️ 仅 POSIX 权限 |
| Human-Cognitive | CLS 理论 + sleep-like offline consolidation | ❌ 缺 |

---

## 2. AetherCode Memory 现状（截至 R229）

AetherCode 已经有相当扎实的 memory 基建，分布在 **两个并行模块**：

### 2.1 Java 端（`aethercode/aethercode-memory`，22 个文件）

```
aethercode-memory/src/main/java/org/aethercode/memory/
├── MemoryScope.java          # USER / PROJECT / SESSION / LOCAL / AUTO
├── MemoryPaths.java          # 路径解析 + sanitize
├── MemoryEntrypoint.java     # MEMORY.md entrypoint
├── FileBackedMemory.java     # JSONL 后端
├── LayeredMemoryStore.java   # R127 三层 facade
├── SessionMemoryStore.java   # SQLite 会话后端
├── ProjectMemoryCompressor.java  # LLM 驱动的项目摘要
├── MemoryConsolidator.java   # R7 Jaccard 去重合并（"auto-dream"）
├── MemoryDeduplicator.java   # Jaccard 相似度
├── MemoryExtractor.java      # LLM 提取 session 摘要（带子 agent）
├── MemoryRecall.java         # lexical + LLM 旁路消歧
├── MemoryPrompts.java        # 提取/合并 prompt 模板
├── MemoryShare.java          # R26-E 跨 session tag 共享
├── TaskMemoryStore.java      # R23-E per-task 隔离
├── TeamMemorySync.java       # R7 跨 session/机器 front-matter 共享
├── MemorySnapshot.java       # R4 文件哈希清单
├── MemorySnapshotSync.java   # 跨机同步
├── MemoryTools.java          # 给 memory-aware agent 注入 file_read/write/edit
└── FileBackedMemory.java     # 已在
```

### 2.2 TS 端（`aethercode-memory`，13 个文件）

```
aethercode-memory/src/
├── types.ts                  # MemoryEntry = Fact | Rule | Change | Breadcrumb
├── memory-store.ts           # 3-tier 缓存级联（LRU → SQLite → file）
├── session-store.ts          # session_messages 表
├── sqlite.ts                 # 版本化 migration runner
├── markdown.ts               # global memory 解析/序列化
├── project-markdown.ts       # project memory 解析/序列化
├── compression.ts            # LLM 压缩流程
├── lru-cache.ts              # LRU 缓存
├── jsonl-writer.ts / jsonl-fold.ts  # append-only journal
└── security/permissions.ts   # 读取权限
```

### 2.3 当前 Memory 关键行为

| 行为 | 实现 | 设计意图 |
|---|---|---|
| 每 query 召回 5 个 memory 文件 | `MemoryRecall.recall()` | "context window 限制下要选对" |
| USER > PROJECT > LOCAL 去重 | 迭代顺序 | 高优先级先 |
| Project 改动超 50 行触发 LLM 压缩 | `ProjectMemoryCompressor.maybeCompress()` | 防止项目 MEMORY.md 无限增长 |
| Session 10k token 触发子 agent 提取 | `MemoryExtractor.shouldExtract()` | 把长会话沉淀到 MEMORY.md |
| 已召回去重 | `AppState.surfacedMemories` (R19-B) | 防止重复注入 |
| 切 cwd 重建项目 store + 广播 | `NOTIFY_CWD_CHANGED` (R127) | 严格 session/cwd 隔离 |
| Task 级隔离 | `TaskMemoryStore` (R23-E) | 子 agent 互不可见 |
| 跨 session tag 共享 | `MemoryShare` (R26-E) | USER 范围 + tag 索引 |
| 跨机器共享 | `TeamMemorySync` (R7) | front-matter .md |
| 文件哈希快照 | `MemorySnapshot` (R4) | 同步差异 |

### 2.4 现状评分

| 维度 | 现状 | 距离前沿 |
|---|---|---|
| Scope 多层隔离 | ★★★★★ | 已成熟 |
| Entry 类型丰富度 | ★★★☆☆ | 缺 experience/working/sketchpad |
| 后端 (file/sqlite/cache) | ★★★★☆ | 健康 |
| 召回 (retrieval) | ★★★☆☆ | 仅有 lexical + LLM 旁路，缺 semantic、KG、衰减信号 |
| 演化 (evolution) | ★★★☆☆ | 仅有 consolidation + 简单 append，缺 forgetting / conflict resolution |
| 形成 (formation) | ★★★☆☆ | 仅有 summarization + 简单抽取，缺结构化、latent、distillation |
| 自动管理 | ★★☆☆☆ | 手动 threshold 触发 |
| 经验沉淀 | ★☆☆☆☆ | 缺 case-based / strategy-based 经验库 |
| 工作记忆 | ★☆☆☆☆ | 缺显式层 |
| 多模态 | ☆☆☆☆☆ | 文本 only |
| 信任 / 审计 | ★★☆☆☆ | 仅有 POSIX 权限 + 隔离 |
| 评测 / 基准 | ☆☆☆☆☆ | 无 LoCoMo / LongMemEval 集成 |

---

## 3. 缺口分析（按 ROI 排序）

| # | 缺口 | 痛点 | 优先级 | 实施成本 |
|---|---|---|---|---|
| **G1** | **缺 Strategy/Case-based Experiential Memory** | "R228 走的 tool_use_start quick fix 应该转成 formal experience library" — 同类经验找不到；项目复盘不能跨 session 复用 | **P0** | 中 |
| **G2** | **缺 Forgetting / Decay Policy** | 长期不访问的 fact / rule 永远不会被清，scope 越来越大，召回噪声增加 | **P0** | 低 |
| **G3** | **召回信号只有 lexical** | 语义相近但字面不同的记忆找不到（例如 "PowerShell build" vs "windows 编译"） | **P0** | 中 |
| **G4** | **缺 Working Memory 显式层** | 长 query 内没有 active scratchpad，靠 context window 硬塞 | **P1** | 中 |
| **G5** | **缺 Memory Audit Log** | 写 / 读 / 删 / compress 都没结构化日志，trust 落地难 | **P1** | 低 |
| **G6** | **缺 Token-level 2D 结构** | 没有图/树，无法做 multi-hop 关系推理 | **P2** | 高 |
| **G7** | **缺 Memory Generation** | 只 retrieve 不生成 — 综述指出的范式转移 | **P2** | 高 |
| **G8** | **缺 Multimodal** | 文本 only — 综述指出的下一阶段 | **P3** | 很高 |
| **G9** | **缺 Offline Sleep-like Consolidation** | 综述强调的 CLS 理论落地 | **P3** | 高 |
| **G10** | **缺 PII / Sensitive 标记** | trust 维度的硬要求 | **P1** | 低 |

**R230 范围**：本轮先做 G1（核心痛点）+ G2（最容易 + 高 ROI）+ G3（最高 ROI 召回升级）+ G5（trust 基础）+ G10（trust 基础）。G4 / G6 / G7 / G8 / G9 留到 R231+。

---

## 4. 设计方案：R230 第一批改造

### 4.1 G1 — Strategy/Case-based Experiential Memory

**目标**：让 agent 在新 query 时能复用历史 trajectory 的解法和抽象出来的策略。

**设计**：

```
new layer: EXPERIENCE  (Token-level 2D — 单层 plan)
scope:    USER / PROJECT (不下沉到 SESSION — 经验是跨 session 复用的)
kind:     trajectory (case-based)  /  strategy (strategy-based)
file:     <scope>/<agentType>/experience/<id>.md
          每个文件 front-matter：
          ---
          kind: trajectory | strategy
          created_at: ...
          source_session: <sid>
          source_query: <摘要>
          source_outcome: success | failure | partial
          utility: 0..1  (随使用次数增长)
          tags: [t-xxx, ...]
          links: [<其他 experience 文件 id>]
          ---
          <body: trajectory 摘要 / strategy 描述>
```

**入口**：
- `MemoryExtractor.extractExperience(session, kind)` — 在 `MemoryExtractor.extract()` 旁边新增"经验"提取路径
- `ExperienceStore.put(scope, kind, body, source)` — 写入 experience 文件
- `MemoryRecall.recallExperience(scope, currentInput, k=3)` — 召回 top-k 经验
- `ExperienceStore.recordUse(id)` — 命中后 utility++

**与现有层关系**：
- 写入走 `LayeredMemoryStore.appendExperience(scope, kind, body, source)`
- 读取走 `LayeredMemoryStore.listExperience(scope, k)` — 先 utility desc，再 age asc
- 不进入 system prompt 的 MEMORY.md 段（那是 factual），而是一段独立的 "## Past experience (auto-recalled)" 段

**Schema**：
- 经验文件是 markdown front-matter + body，**不算** token-level 1D（因为有 `links` 边）
- 后续 R231 可以加 `links` 自动建图 → 升到 2D plan

### 4.2 G2 — Forgetting / Decay Policy

**目标**：长期不用的 fact / rule / experience 按效用衰减；够低的被裁剪。

**政策**（参考综述 5.2.3 Forgetting）：

```
Forgetting Score (FS) ∈ [0, 1]   越低越"应被遗忘"
  FS = w1 * recency   + w2 * frequency  + w3 * utility
  recency  = exp(-Δdays / τ_recency)
  frequency = log(1 + access_count) / log(1 + max_access)
  utility  = 经验库有；fact/rule 用 importance 替代
  默认 w = (0.5, 0.3, 0.2)   τ_recency = 30 天
```

**触发**：
- 每次 `MemoryRecall.recall()` 之前 → 后台 `decayPass()` 异步跑一次（限速：每次最多 100 条）
- 命中阈值 `FS < 0.05` 持续 N 天的进入 "墓碑" 区（移到 `<scope>/.trash/<ts>-<key>.md`）
- 30 天后可被 prune（默认关闭，用户显式 `--memory-prune` 触发）

**配置**：
- `AetherCodeConfig.MemoryConfig.forgettingPolicy` — `{enabled, recencyTauDays, threshold, tombstoneDays}`
- 默认 enabled=true，其他保守
- 不影响 SESSION scope（会话结束就清空，不需要衰减）

**可观测**：
- `MemoryStats.forgetting`: `{decayedCount, tombstonedCount, prunedCount, lastDecayMs}`
- dashboard 上有"最近一次清理"显示

### 4.3 G3 — Recall 信号增强

**目标**：lexical 之外加 4 个信号，组合排序。

```
score(file) =
   w_lex   * lexicalScore   (existing)
 + w_tag   * tagMatchScore  (new: query tags ∩ entry tags Jaccard)
 + w_age   * ageDecay       (existing 30d linear → 改用 FS 风格 exp decay)
 + w_use   * useBoost       (new: entry 命中后下次分数 +20%)
 + w_kind  * kindPrior      (new: rule > fact > breadcrumb > change)
```

**新增**：
- `MemoryRecallScore` — 一个 dataclass（Java record / TS type）
- `MemoryRecall.recall()` 改用此 score
- 命中后 `entry.touch()` — `access_count++`, `last_accessed = now`
- 权重从 config 读，CLI / RPC 可改

**未做（保留 R231）**：
- 嵌入向量语义召回（依赖 embedding model，超出本轮范围）
- 知识图谱多跳（依赖 G6）

### 4.4 G4 — Working Memory 显式层（延后到 R231）

综述 4.3 单/多 turn working memory。R230 只搭骨架：

```
new: WorkingMemoryBuffer (per-session, in-memory only)
  - key: shortId, value: any small typed blob (text, kv, ref)
  - lifecycle: bound to one query (clear at end)
  - API: WorkingMemory.put/get/list/clear/snapshot
  - 接入点: QueryEngine 在 query 开始时 clear, 每 tool 结束后允许 agent 写
```

R230 仅做接口 + 测试。R231 接 QueryEngine 实际写入路径。

### 4.5 G5 + G10 — Audit Log + PII 标记（trust MVP）

**Audit log**：
- 文件 `<memoryBase>/audit.log` — JSONL
- 每条：`{ts, actor, action, scope, key, kind, sourceSessionId, before, after, decision}`
- action: read / write / delete / compress / forget / recall / share
- 写是 append-only，由 `MemoryAudit` 静态类统一记录
- TUI 上加 "View audit log" 按钮（延后到 R231，本轮仅落地后端）

**PII 标记**：
- `Fact` 和 `Rule` 加可选 `sensitivity: 'public' | 'internal' | 'sensitive' | 'pii'`
- 默认 `internal`
- `MemoryRecall.recall()` 在 query 是 USER scope 时会过滤 `pii` 除非显式 `includePii: true`
- 写入侧先标记，否则 LLM 提取时让模型自评（prompt 模板里加"评估 sensitivity"）

### 4.6 R230 暂不做（明确范围外）

- G6 Token-level 2D/3D 结构（需要 R231+）
- G7 Memory Generation（依赖结构化基础）
- G8 Multimodal（依赖 embedding / VLM 集成）
- G9 Sleep-like offline consolidation（依赖 G1+G2 落地）
- World model（不在范围）
- RL-driven memory（不在范围）

---

## 5. 实施路线

### 5.1 文件清单

**Java 端新增**：
```
aethercode-memory/src/main/java/org/aethercode/memory/
├── ExperienceStore.java          # G1 经验库
├── ExperienceRecord.java         # G1 record
├── ExperienceKind.java           # G1 enum
├── ForgettingPolicy.java         # G2 衰减政策
├── MemoryAudit.java              # G5 审计
├── Sensitivity.java              # G10 enum
└── WorkingMemoryBuffer.java      # G4 骨架
```

**Java 端修改**：
```
- MemoryEntry.java / types.ts:    +sensitivity, +accessCount, +lastAccessed
- MemoryRecall.java:              +多信号 score, +experience 召回, +PII 过滤
- MemoryExtractor.java:           +经验抽取路径（可选, 默认关）
- LayeredMemoryStore.java:        +appendExperience / listExperience / recordUse
- FileBackedMemory.java:          +touch() / decay() / forget() / audit
- AetherCodeConfig.java:          +ForgettingPolicyConfig, +RecallWeightsConfig
```

**TS 端新增 / 修改**：
```
aethercode-memory/src/
├── types.ts                      # +sensitivity, +accessCount, +lastAccessed
├── memory-store.ts               # +listExperience / putExperience / recordUse
├── experience-store.ts           # G1 TS 端镜像
├── forgetting-policy.ts          # G2 TS 端镜像
└── audit.ts                      # G5 TS 端
```

### 5.2 RPC 新增

| RPC | shape | 用途 |
|---|---|---|
| `appendExperience({scope, kind, body, source, tags?})` | `{ok, id, utility}` | 写经验 |
| `listExperience({scope, k?, includeLinks?})` | `{ok, entries[], count}` | 列经验 |
| `recordExperienceUse({id})` | `{ok, id, utility}` | 命中后 utility++ |
| `memoryStats({scope?})` | `{ok, totals: {fact, rule, change, breadcrumb, experience}, forgetting: {...}}` | 统计 + 衰减状态 |
| `decayMemory({scope?, force?})` | `{ok, decayed, tombstoned, pruned, ms}` | 手动触发衰减 |
| `viewAuditLog({sinceMs?, limit?})` | `{ok, entries[], count}` | 读审计 |
| `setSensitivity({scope, key, sensitivity})` | `{ok}` | 标 PII |

### 5.3 通知

| Notification | payload | 触发 |
|---|---|---|
| `NOTIFY_EXPERIENCE_ADDED` | `{scope, id, kind}` | appendExperience 成功 |
| `NOTIFY_MEMORY_DECAYED` | `{scope, decayedCount, tombstonedCount, ms}` | decay 跑完 |

### 5.4 实施顺序

R230 一个 round 内顺序（不是 R231+）：

1. **G2 Forgetting**（最简单，先建基础）
   - ForgettingPolicy 静态计算
   - FileBackedMemory 集成 decay
   - decayPass() 入口 + 限速
2. **G5 Audit + G10 PII 标记**（trust 基础）
   - MemoryAudit 单例
   - Sensitivity 枚举 + 写入检查
   - audit 写穿到所有 read/write/delete/compress
3. **G1 Experience 库**
   - ExperienceStore + record
   - LayeredMemoryStore.appendExperience
   - MemoryExtractor 走可选经验路径
4. **G3 Recall 增强**
   - 多信号 score
   - touch() 接入
   - 权重从 config 读
5. **G4 WorkingMemory 骨架**（接口 + 测试）
6. **aethercode-config + RPC + 通知 + TS 镜像**
7. **测试 + 报告**

### 5.5 测试矩阵

| 测试 | 数量目标 |
|---|---|
| `ForgettingPolicyTest` | 6 (recency, frequency, utility, composite, edge cases) |
| `MemoryAuditTest` | 4 (write/read/delete/recall 都打点) |
| `SensitivityTest` | 4 (filter, default, override) |
| `ExperienceStoreTest` | 8 (CRUD, dedup, links, utility growth) |
| `MemoryRecallScoreTest` | 8 (5 signal + weight + decay touch) |
| `WorkingMemoryBufferTest` | 4 (put/get/clear/snapshot) |
| `LayeredMemoryStoreR230Test` | 6 (新增方法 + 隔离保留) |
| `RpcR230Test` | 5 (新 RPC 形状) |
| TS: `experience-store.test.ts` | 6 |
| TS: `forgetting-policy.test.ts` | 4 |
| TS: `memoryR230.test.ts` | 8 |
| **Total** | **~63** |

### 5.6 风险与权衡

1. **decay 误删**：默认 `enabled=true` 但 `threshold=0.05` 极保守；不会"突发"清空。可以让用户一次性 `decayMemory({force: true})` 跑激进档。
2. **PII 标记漏标**：LLM 抽取默认 `internal`，漏标的 `pii` 仍可被召回。R231+ 加用户显式 `setSensitivity` UI + 自动检测。
3. **经验库膨胀**：经验文件比 fact 体积大（一个 trajectory 可能 1-2KB）。用 utility desc 排序，召回数量限 3。
4. **审计日志性能**：append-only JSONL + 异步 flush（每 1s 一次或 100 条一次）。不阻塞主流程。
5. **跨 scope 经验**：USER 经验对所有 project 可见吗？R230 默认 PROJECT scope 经验只在该 project 内召回，USER 经验是手工 pin 进来的通用经验。R231+ 加自动 promote 机制。
6. **R230 范围控制**：明确不做 G4 实际接入、G6/G7/G8/G9，避免 scope 蔓延。R231+ 单独 round。

---

## 6. 后续路线（R231+）

| Round | 主题 | 包含 |
|---|---|---|
| R231 | Memory 结构化与生成 | G6 Token-level 2D（KG）/ G7 Memory generation / G4 实际接入 |
| R232 | 评测与基准 | 集成 LoCoMo / LongMemEval 评测到 CI；建立 "memory quality" dashboard |
| R233 | 多模态 | 图像 / 音频 memory（依赖 embedding model 选型） |
| R234 | Sleep-like consolidation | 离线 consolidation 周期（CLS 理论落地）|
| R235 | Trust 完整化 | 审计 TUI / PII 自动检测 / 跨用户记忆共享的 ACL |

---

## 7. 引用

- Hu, Liu, Yue, Zhang et al. (2026). *Memory in the Age of AI Agents: A Survey — Forms, Functions and Dynamics*. arXiv:2512.13564v2
- AetherCode 内部设计：`design.md` §1 (Memory system)
- 已有 R-series 相关文档：R19-B (Memory recall system prompt), R127 (3-layer memory), R80 (CLI memory panel)

---

## 8. R231 — 把 Memory 装到 session 执行过程（分层分级更新）

R230 只造了零件（R230 落地了 G1/G2/G5/G10 + G4 骨架），没真正接进 session 流。R231 解决了"只建设不更新"的问题：把 `MemoryLifecycle` 编织进 `AetherCodeEngine.query()` 的 start / mid / end 三阶段，并按代价分层。

### 8.1 分层模型

| Tier | 触发 | 代价 | 写入目标 | 写入什么 |
|---|---|---|---|---|
| **Tier 0** | 每个 tool call | 免费 | audit | `kind="tool-call"`, `tool=<name>` |
| **Tier 1** | 每个 query | 免费 | audit + working buffer | `kind="buffer"` (start), `kind="query-end"` (end) |
| **Tier 1.5** | 召回命中每个文件 | 免费 | audit + lifecycle counter | `action="recall"`, `scope`, `key` |
| **Tier 1.7** | 任何 memory 写入 | 免费 | audit | `action="write"`, `scope`, `key`, `kind`, `sensitivity` |
| **Tier 2** | 每个成功 query + ≥1 tool call | 启发式，**无 LLM** | experience (case) | 自动 Case 记录：title=首条 user 消息，body=末条 assistant + 工具统计 |
| **Tier 3** | transcript 含策略关键词 或 同工具调用 ≥3 次 | 启发式 + 可选 LLM | experience (strategy) | 抽象洞察（LLM stub；R232 实装） |
| **Tier 4** | 后台线程每 5 分钟 | ForgettingPolicy | decay pass on user + project scope | 衰减评分低的项目 → tombstone |

### 8.2 关键设计点

1. **MemoryLifecycle 是 daemon 范围单例**，通过 `AetherCodeEngine.setMemoryLifecycle(...)` 注入（volatile 字段）。现有 256 个 SDK tests 不破。

2. **每 query 一次** `onQueryStart` → `onQueryEnd` 包裹整个 stream：
   - start: new `WorkingMemoryBuffer` (per-query) + audit + 触发周期 decay（如果到期）
   - end: extract Tier-2/3 经验 + 清 buffer + audit（带 success/failure decision）

3. **每个 tool call** `onToolCall(name)` — 喂下次 recall 的"recent tools"信号。R230 已经走这条，R231 是显式 hook 进 executor。

4. **每个 recall 命中** `onMemoryRecallHit(scope, key, id)` — 计数 + audit。R232 闭环：在同一处调 `FileBackedMemory.touch(id)`。

5. **周期 decay** 在 `runPeriodicDecay()` 跑：扫 user store + 当前 project store，过 `ForgettingPolicy.runDecayPass(false)`，默认 5 分钟一次，限速 100 条。

6. **Best-effort**：lifecycle 任何方法吞异常 log warn，**绝不**让 memory 故障 break query 流。

### 8.3 文件清单（R231 新增 / 修改）

**Java 新增：**
- `MemoryLifecycle.java` (23.5 KB) — 编排器
- `MemoryLifecycleTest.java` (14.0 KB) — 20 个 tests 覆盖 4 个 Tier

**Java 修改：**
- `LayeredMemoryStore.java` — +5 experience 方法（已在 R230），+`publicProjectStore(cwd)` 给 lifecycle 调
- `AetherCodeEngine.java` — +`memoryLifecycle` 字段（volatile）、+`setMemoryLifecycle` setter、+`Builder.memoryLifecycle`、+`onQueryStart`/`onMemoryRecallHit`/`onQueryEnd` 三个 hook 调用点
- `AetherCodeMethods.java` — +`memoryLifecycle` 字段 + setter（让 DaemonRunner 把 lifecycle stashed 上）
- `DaemonRunner.java` — build MemoryAudit + MemoryLifecycle + install 到 engine

**未做（明确 R232+）：**
- Tier 3 strategy extraction 的实际 LLM 调用（`maybeExtractStrategy` 暂返回 null）
- `onMemoryRecallHit` 真实 `touch()` 调用（需要 file handle wiring）
- TS 端 mirror
- RPC 暴露

### 8.4 测试结果

| 模块 | tests |
|---|---|
| aethercode-memory | 185 (was 165) — +20 MemoryLifecycleTest |
| aethercode-sdk | 256 — 不变（编译过 = wiring 有效） |
| aethercode-protocol | 263/264 — 唯一 fail 是 R183 vs R126 老测试冲突（pre-existing，与本轮无关） |
| aethercode-cli | 通过 |

### 8.5 一句话总结

R230 是"造"，R231 是"用"。MemoryLifecycle 编织到 query 流程后，每个 session 的 recall 命中、tool 调用、query 成功/失败、decay 周期都被自动记录，experience 库在每次成功 query 后自动增长（Tier 2 启发式，零 LLM 成本），陈旧项在后台被遗忘。**不再只建设不更新。**
