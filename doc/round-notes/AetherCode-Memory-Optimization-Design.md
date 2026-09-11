# AetherCode Memory 优化设计方案

> **配套材料**:
> - 系统理解: `doc/项目文档/AetherCode-Memory-System-Understanding.md` (同目录, 19.5 KB)
> - 参考文献: `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf`
> - 翻译: `reference/papers/2512.13564v2_中文翻译.md`
> **生成时间**: 2026-09-07
> **作者**: Mavis
> **方案版本**: R-MEM-V1

---

## 一、设计原则

本方案遵循以下原则（参考文献 §7.2 自动化管理 + §5.3 检索 + AetherCode 现有架构）：

1. **小步快跑** — 每个优化独立可发布，不阻塞主线
2. **向后兼容** — 新增不破坏旧 facts/rules/changes/breadcrumbs 字段
3. **测试驱动** — 每个新模块配 source-only + vitest 测试 (R193/R228 模式)
4. **离线优先** — 优先本地 embedding 模型，远程 API 走可插拔 adapter
5. **安全优先** — F7 可信记忆作为底层约束，所有新操作必须过 SecureFilePermissions

---

## 二、整体路线图 (按优先级)

| 阶段 | 内容 | 优先级 | 预计工作量 | 依赖 |
|------|------|--------|-----------|------|
| **R-MEM-1** | 向量检索 (F1) | **P0** | M | — |
| **R-MEM-2** | 演化机制：整合 + 遗忘 (5.2 + 5.5) | **P0** | M | — |
| **R-MEM-3** | Subagent 共享记忆 (F5) | **P0** | S | — |
| **R-MEM-4** | 技能记忆 (5.4) | P1 | M | R-MEM-1 |
| **R-MEM-5** | 更新机制 (5.6) | P1 | M | R-MEM-2 |
| **R-MEM-6** | 增强可信 (5.7) | P2 | M | R-MEM-1 |
| **R-MEM-7** | 自动化记忆管理工具 (F2) | P3 | L | R-MEM-1,2,3,4,5 |

> **本方案只展开 P0 三个 (R-MEM-1/2/3) + R-MEM-4 (P1 头一个)**。其余给详细 spec 但留待下轮。

---

## 三、R-MEM-1: 向量检索 (F1 — 检索 vs. 生成)

### 3.1 目标

为 `memory/find` 增加**自然语言查询**能力。给定 "上次我怎么处理 CWE 416 的？" 能返回 top-k 语义最相关的 facts，而非要求用户提供精确 key 前缀。

### 3.2 设计

**存储方案**: SQLite `facts_vec` 表 (sqlite-vec 扩展)

```sql
CREATE VIRTUAL TABLE facts_vec USING vec0(
  embedding float[384]  -- all-MiniLM-L6-via 输出维度
);

-- 关联表
ALTER TABLE facts ADD COLUMN embedding_model TEXT;  -- 跟踪 embedding 版本
```

**模块结构**:
```
aethercode-memory/src/
├── embedding/
│   ├── index.ts        # EmbeddingProvider 接口 + 默认实现
│   ├── local.ts        # ONNX Runtime + all-MiniLM-L6 (offline)
│   ├── remote.ts       # OpenAI/Cohere adapter
│   └── embedding.test.ts
├── vec-store.ts        # sqlite-vec 包装：upsert / search / delete
└── rpc.ts              # 加 memory/find (semantic search)
```

**Embedding Provider 接口**:
```typescript
export interface EmbeddingProvider {
  readonly modelId: string;        // 用于 stale-check
  readonly dim: number;
  embed(text: string): Promise<Float32Array>;
  embedBatch(texts: string[]): Promise<Float32Array[]>;
}
```

**默认实现** (R-MEM-1.1):
- 启动时检测 `~/.aethercode/embedding-model.bin` (预下载 25 MB)
- 缺失则提示用户首次联网下载 (一次性)
- 之后完全离线 (F7 隐私要求)

**wire shape (memory/find RPC)**:
```typescript
// Request
{
  scope: 'global' | 'project' | 'session',
  query: string,                  // 自然语言
  topK?: number,                  // 默认 10
  threshold?: number,             // 0.0-1.0 相似度阈值, 默认 0.5
  filters?: { kind?: MemoryKind, tags?: string[] },
  cwd?: string,                   // project scope 必填
  sessionId?: string              // session scope 必填
}

// Response
{
  ok: true,
  results: Array<{
    entry: MemoryEntry,
    score: number,                // 余弦相似度
    snippet: string               // 截取最相关段落
  }>,
  totalCandidates: number,
  queryEmbeddingMs: number,
  vecSearchMs: number,
  modelId: string                 // 客户端可校验
}
```

**desktop store 新字段**:
```typescript
// aethercode-desktop/src/store/index.ts
memoryFind: (params: MemoryFindParams) => Promise<MemoryFindResult>;
memoryFindBusy: boolean;
lastMemoryFindMs: number;
```

**desktop UI**: 在 MemoryPanel 加搜索框 + 语义搜索 tab (R-MEM-1.2 后续)

### 3.3 与现有 memory/get 的关系

- `memory/get`: 返回全部条目 (用于 UI 列表)
- `memory/find`: 返回 top-k 相似 (用于模型上下文注入)

两者不互斥，可同时使用：get 用于"列出所有 facts" UI，find 用于"找到相关的 N 条"。

### 3.4 测试

- embedding provider mock (deterministic random vector for testing)
- vec-store 单元测试 (insert/search/delete/dim mismatch)
- RPC 集成测试 (调用方传入 query，验证 top-k)
- **预计 +25 vitest cases**

### 3.5 兼容性 / 降级

- 若 sqlite-vec 不可用 (老 sqlite 版本) → 降级到 LIKE 全表扫描
- 若 embedding model 未下载 → 降级到 `memory/get` 客户端过滤
- 旧 facts 无 embedding → 首次 find 时懒加载 embedding (不阻塞现有流程)

### 3.6 文件改动清单

**新增**:
- `aethercode-memory/src/embedding/index.ts`
- `aethercode-memory/src/embedding/local.ts`
- `aethercode-memory/src/embedding/remote.ts`
- `aethercode-memory/src/embedding/embedding.test.ts`
- `aethercode-memory/src/vec-store.ts`
- `aethercode-memory/src/vec-store.test.ts`
- `scripts/download-embedding-model.sh` (跨平台)

**修改**:
- `aethercode-memory/src/sqlite.ts` — 第 4 个 migration: facts_vec
- `aethercode-memory/src/rpc.ts` — 加 `memory/find` (T-077 风格)
- `aethercode-memory/src/index.ts` — re-export
- `aethercode-desktop/src/store/index.ts` — `memoryFind` action
- `aethercode-desktop/src/lib/methods.ts` — `memoryFind` 客户端
- `aethercode-tui/src/components/MemoryPanel.tsx` (R80) — 加搜索按钮

**预计总改动**: 6 新 + 5 改 = 11 文件

---

## 四、R-MEM-2: 演化机制 (Consolidation + Forgetting)

### 4.1 目标

解决当前 memory.md 无限增长、过期信息不清理、相似 facts 重复的痛点。

### 4.2 设计

引入**两条新管道**：

**管道 A: Consolidation (整合)**
- 触发: 写入新 fact 后 5 分钟 OR 显式 `memory/consolidate` RPC
- 算法:
  1. 找出 key 相似 (e.g. 共享前缀或 80% 余弦相似) 的现有 facts
  2. 调 LLM 合并为 1 条新 fact (走 LLM 4-shot)
  3. 旧 facts 标记 `consolidated_into = <new_id>`
  4. 新 fact 写入
- 失败回滚: 旧 facts 保持原状

**管道 B: Forgetting (遗忘)**
- 触发: 每周 OR 显式 `memory/forget` RPC OR 手动
- 三标准 (来自文献 §5.2.3):
  1. **过期** (TTL): fact 自带 `expires_at` 字段 (可空)，过期则候选
  2. **低访问**: `access_count == 0` AND `last_accessed_at < now - 90d`
  3. **低价值**: user/llm 显式标 `value: 'low'` 的 facts
- 算法:
  1. 候选集 = 满足任一标准
  2. (可选) 调 LLM 确认"删除会丢什么？"
  3. 软删除：移到 `.trash/` 目录而非真删 (30 天可恢复)
  4. 30 天后真删

**Fact 类型扩展**:
```typescript
interface Fact {
  // ... 现有字段
  readonly accessCount: number;          // 检索命中次数
  readonly lastAccessedAt: number;       // 最后访问时间
  readonly expiresAt: number | null;     // 显式过期时间
  readonly valueTag: 'normal' | 'low';   // 价值标签
  readonly consolidatedInto: string | null; // 被合并到哪条
}
```

**新增 RPC**:
- `memory/consolidate` (params: scope) — 立即触发整合
- `memory/forget` (params: scope, dryRun?: boolean) — 立即触发遗忘
- `memory/stats` (params: scope) — 返回 access stats + 待清理 candidates 数

**CLI 新子命令**:
- `aethercode memory consolidate --scope=project` 
- `aethercode memory forget --scope=project --dry-run`
- `aethercode memory stats --scope=project`

### 4.3 与现有 Compression 的关系

- **Compression (现有)**: 周期性整体重写 → 处理 project_changes 50 条阈值
- **Consolidation (新)**: 写入触发局部合并 → 处理相似 facts
- 两者互补：Compression 是大扫除，Consolidation 是小整理

### 4.4 测试

- 合并算法：3 条相似 fact → 1 条新 + 2 条标记 consolidated_into
- 过期扫描：clock-mock 验证 expires_at
- 软删除：trash 目录创建，30 天后真删
- **预计 +20 vitest cases**

### 4.5 兼容性

- 旧 facts 无新字段 → migration 阶段填充默认值 (`accessCount=0`, `valueTag='normal'`, ...)
- 旧 RPC 调用方不受影响
- 软删除 30 天恢复期给用户充分的回退窗口

### 4.6 文件改动清单

**新增**:
- `aethercode-memory/src/evolution.ts` (主逻辑)
- `aethercode-memory/src/evolution.test.ts`
- `aethercode-memory/src/forgetting.ts` (单独抽出)
- `aethercode-memory/src/forgetting.test.ts`

**修改**:
- `aethercode-memory/src/types.ts` — Fact 扩展 4 字段
- `aethercode-memory/src/sqlite.ts` — migration v5 (新字段) + v6 (consolidated_into 索引)
- `aethercode-memory/src/memory-store.ts` — read/write 时更新 accessCount/lastAccessedAt
- `aethercode-memory/src/rpc.ts` — 加 memory/consolidate, memory/forget, memory/stats
- `aethercode-memory/src/cli.ts` — 加 3 个子命令

**预计**: 4 新 + 5 改 = 9 文件

---

## 五、R-MEM-3: Subagent 共享记忆 (F5)

### 5.1 目标

让 AetherCode 的 subagent 能跨调用保留关键信息，结束"每次 subagent 启动都从零开始"的现状。

### 5.2 设计

**核心思想**: 引入"团队级共享记忆" + "角色级私有记忆"的双层结构。

**AetherCode 现状** (从调研):
- Subagent 通过 `aethercode-protocol/.../TaskMethods.java` 启动
- 无独立 memory 概念，每次 subagent 启动 = 一个新的 engine instance + 新 transcript
- 主 agent 只能通过 "spawn subagent → 等结果" 模式

**目标架构**:
```
主 Agent
├── PROJECT memory (read-only 共享)
├── SESSION memory (private)
│
└── Subagent #1
    ├── PROJECT memory (read-only 共享)
    ├── TEAM memory (read+write, 跟主 agent 共享)
    └── PRIVATE memory (read+write, subagent 自己)

→ Subagent 完成后, 选择性把 PRIVATE 提升到 TEAM
```

**实现**:
1. **Subagent 启动参数扩展**:
   ```typescript
   interface SubagentSpawnParams {
     // ... 现有
     sharedMemory?: 'none' | 'team' | 'project';  // 默认 'team'
     privateMemoryScope?: 'session' | 'none';     // 默认 'session'
     returnMemory?: string[];                     // 完成后回写哪些 keys
   }
   ```

2. **共享层 SQLite schema**:
   - 复用现有 project_db, 加 access_control 字段
   - 共享 read 默认开启, write 需要显式声明

3. **主 agent API**:
   - `memory/shareToSubagent(scope, keys)` — 主动共享
   - `memory/promoteFromSubagent(scope, facts)` — 回写 (subagent 完成后)

4. **Subagent API**:
   - 启动时收到 sharedMemory context (类似 cwd)
   - 写入 TEAM 时立即同步到主 agent

5. **冲突处理**:
   - 多 subagent 并行写同一 key → last-write-wins + 警告
   - 主 agent 决定保留哪个

### 5.3 wire shape (memory/shareToSubagent RPC)

```typescript
// Request
{
  sessionId: string,        // 哪个 subagent session
  entries: MemoryEntry[],   // 要共享的条目
  access: 'read' | 'read-write',
  ttl?: number              // 共享多久 (默认 session 结束)
}

// Response
{ ok: true, shared: number }
```

### 5.4 desktop store 变化

```typescript
// aethercode-desktop/src/store/index.ts
subagentSharedMemory: Record<string, MemoryEntry[]>;  // sessionId -> shared facts
shareMemoryToSubagent: (params: ...) => Promise<void>;
promoteMemoryFromSubagent: (params: ...) => Promise<void>;
```

### 5.5 风险

- **隐私泄露**: TEAM 共享 = 主 agent 能看到 subagent 写的所有事。需要显式声明 + UI 高亮
- **上下文污染**: 共享太多 fact 反而拖累 subagent。需 size 限制 (e.g. < 4 KB per share)
- **循环引用**: subagent 写 → 主 agent 读 → 触发新 subagent → ... 需检测

### 5.6 测试

- spawn subagent with sharedMemory='team' → 双方都看到
- 完成后 promote → 主 agent 收到
- 隐私场景: 显式未共享的 fact → subagent 看不到
- **预计 +15 vitest cases**

### 5.7 文件改动清单

**新增**:
- `aethercode-memory/src/team-store.ts`
- `aethercode-memory/src/team-store.test.ts`
- `aethercode-memory/src/subagent-bridge.ts` (跟 aethercode-protocol 的 hook)

**修改**:
- `aethercode-memory/src/rpc.ts` — memory/shareToSubagent, memory/promoteFromSubagent
- `aethercode-protocol/.../TaskMethods.java` — spawn 参数扩展
- `aethercode-desktop/src/store/index.ts` — subagentSharedMemory + actions

**预计**: 3 新 + 3 改 = 6 文件

---

## 六、R-MEM-4: 技能记忆 (Skill-based Memory)

### 6.1 目标

让 memory 不仅记录"是什么"，还能记录"能做什么"——把 skill/MCP/executable 引用纳入可检索的记忆库。

### 6.2 设计

**新类型**:
```typescript
interface Skill {
  readonly kind: 'skill';
  readonly id: string;             // skill 注册表的 ID
  readonly name: string;           // "ReadFile"
  readonly signature: string;      // 函数签名 / MCP 协议名
  readonly description: string;    // 何时使用
  readonly successCount: number;   // 成功调用次数
  readonly lastSuccessAt: number;
  readonly lastFailureAt: number | null;
  readonly tags: ReadonlyArray<string>;
  readonly source: 'auto-detected' | 'user-registered' | 'imported';
  readonly scope: MemoryScope;     // global 共享 / project 私有
}
```

**自动捕获机制**:
- ToolRegistry 每次 tool_result.ok === true → 后台记录 Skill{ successCount++ }
- ToolRegistry 每次 tool_result.error → 记录 lastFailureAt
- 用户可手动 "pin" 重要 skill

**RPC**:
- `memory/findSkill` (params: query) — 类似 find 但只返回 skills
- `memory/promoteSkill` (params: id, scope) — 把临时成功的 skill 提升到长期 skill memory

**wire shape**:
```typescript
// Request: 类似 findSkill
{
  query: string,
  topK?: number,
  scope?: MemoryScope,
  minSuccessRate?: number  // 0.0-1.0, 过滤低成功率
}

// Response
{
  ok: true,
  skills: Array<{
    skill: Skill,
    score: number,
    successRate: number  // successCount / (successCount + failureCount)
  }>
}
```

### 6.3 与 SkillRegistry 的关系

当前 `aethercode-core/.../skill/SkillRegistry.java` 是 LLM 用的 tool registry。

R-MEM-4 不是替代它，是**双写**：
- SkillRegistry: 注册表 (启动时扫 skill/ 目录)
- MemoryStore: 成功的 skill 写入 memory (每次 tool_result 触发)

Memory 是"我用 skill X 在 Z 场景下成功了"的经验记录，registry 是"skill X 存在"的元数据。

### 6.4 测试

- 写入 skill → 检索
- 失败后成功率下降 → 排序变化
- 自动捕获: mock tool_result stream → 验证 skill 写入
- **预计 +12 vitest cases**

### 6.5 文件改动清单

**新增**:
- `aethercode-memory/src/skill-store.ts`
- `aethercode-memory/src/skill-store.test.ts`
- `aethercode-memory/src/skill-capture.ts` (hook 进 tool_result 流程)

**修改**:
- `aethercode-memory/src/types.ts` — 加 Skill type
- `aethercode-memory/src/rpc.ts` — memory/findSkill
- `aethercode-protocol/.../TaskMethods.java` (或 SkillMethods) — tool_result 触发 capture
- `aethercode-tui/src/components/MemoryPanel.tsx` (R80) — 加 Skills tab

**预计**: 3 新 + 4 改 = 7 文件

---

## 七、测试策略

### 7.1 测试金字塔

```
                  ╱╲
                 ╱  ╲
                ╱ E2E╲          端到端 (R-MEM-1 后跑, 5+ cases)
               ╱______╲
              ╱        ╲
             ╱ Integration╲     跨模块 (RPC + store + UI, 30+ cases)
            ╱______________╲
           ╱                ╲
          ╱   Source-only +  ╲   Vitest + 静态检查 (300+ cases)
         ╱     Unit tests     ╲
        ╱______________________╲
```

### 7.2 每个 R-MEM-* 验收标准

- [ ] 全部新文件 + 修改文件过 tsc strict
- [ ] 新增 vitest 全部通过
- [ ] 全量 vitest 0 退化 (R-MEM-1 预计 1100/1100, R-MEM-2 预计 1170/1170, ...)
- [ ] 向后兼容: 现有 facts/rules/changes/breadcrumbs 加载无错
- [ ] 离线场景: 拔网线跑测试, 不依赖外部 API
- [ ] 性能: 1000 条 facts 下 memory/find 响应 < 200ms

### 7.3 端到端测试场景

R-MEM-1 (向量检索):
- 创建 100 条 facts → memory/find "编码规范" → 验证 top-3 是相关条目
- 离线模式 (无 embedding model) → 降级到 memory/get 客户端过滤
- embedding model 损坏 → 自动重新下载

R-MEM-2 (演化):
- 写入 10 条相似 facts → 触发 consolidate → 验证合并
- 标记 5 条 expires_at=过去 → 触发 forget → 验证移到 .trash
- 恢复: 从 .trash 拉回 (30 天窗口内)

R-MEM-3 (subagent 共享):
- spawn subagent with sharedMemory='team' → 主 agent 写入 → subagent 立刻看到
- subagent 写 5 条 private → 完成后 promote → 主 agent 收到
- 跨会话: 旧 session 共享的 fact 在新 session 不可见 (除非显式提升到 project)

R-MEM-4 (skill):
- mock tool_result 成功 10 次 → memory 自动有该 skill
- 失败 3 次后 → 成功率 < 0.8 → findSkill 时被降权
- 手动 promote 临时 skill → 永久

---

## 八、发布策略

每个 R-MEM-* 独立发版 (类似 R193 / R228 / R229 模式):

```
R-MEM-1 (v0.2.54)
├── aethercode-memory: +6 文件, vitest +25
├── aethercode-desktop: 3 改, vitest +10
├── aethercode-tui: 1 改, vitest +5
└── jar SHA: 55,585,378 + 1.2 KB (新模块)
```

桌面 exe 也会重新打 (jar 嵌入)。

---

## 九、风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| sqlite-vec 编译失败 (Windows 工具链) | M | R-MEM-1 阻塞 | 备选 sqlite-vss 或外置 LanceDB |
| Embedding 模型下载失败 (网络) | L | 首次体验差 | 提供手动下载 + 校验和 |
| Subagent 共享引入死锁/循环 | L | 性能/正确性 | 显式 size 限制 + 循环检测 |
| 演化机制误删重要 fact | M | 用户体验差 | 软删除 + 30 天恢复 + dryRun 模式 |
| Skill 捕获误报 (false positive) | M | 内存噪声 | 最小成功次数阈值 (e.g. ≥ 3) |

---

## 十、与其他规划的关系

- **R226 (Welcome tile)** — 跟本方案无关
- **R228 (Plan tab)** — 已经完成，可受益于 R-MEM-1 (Plan 用 memory/find 拉相关项目记忆)
- **R230 候选 (修 promote-jar.py)** — 优先级 P0，独立
- **F4 (多模态)** — AetherCode skill 系统有"输出图像" 工具 (R73+)，可在 R-MEM-4 之后单独做

---

## 十一、下一步 (本轮要做的)

按用户确认后，**先做 R-MEM-1 (向量检索)** —— 对开发体验影响最大、有具体技术选型、可独立发版。

完成后写 `aethercode-memory/docs/R-MEM-1-VECTOR-SEARCH-2026-09-08.md` 报告，更新 memory。

后续 R-MEM-2/3/4 等用户单独批准后再开。

---

> **审批请求**: 这份方案覆盖 R-MEM-1/2/3/4 四个 R-MEM round。是否按此推进？还是只先做 P0 (R-MEM-1)？
