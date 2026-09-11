# Memory System

> AetherCode 跨 surface 的多层多 scope 记忆系统。
> 设计参考 arXiv 2512.13564v2 综述的 Forms × Functions × Dynamics 框架,以及 Ebbinghaus–Atkinson–Shiffrin 认知科学的遗忘曲线。
>
> **详细设计记录**: [`../round-notes/R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md`](../round-notes/R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md), [`R230-MEMORY-WRITE-READ-MAP.md`](../round-notes/R230-MEMORY-WRITE-READ-MAP.md), [`R244-1/2/3`](../round-notes/), [`R245-1/2/3/4/5`](../round-notes/)
>
> **关键代码**: `aethercode-memory/src/main/java/org/aethercode/memory/`

---

## 1. 5 种 Scope (R127 起)

`MemoryScope.java` 定义 5 个枚举,前 3 个是真正持久化的"层":

| Scope | 物理位置 | 生命周期 | 跨 session | 跨 project | 典型内容 |
|---|---|---|---|---|---|
| **USER** | `~/.aethercode/agent-memory/<agentType>/*.md` | 永久 | ✅ | ✅ | 用户偏好、编码风格、API 习惯 |
| **PROJECT** | `<cwd>/.aethercode/agent-memory/<agentType>/*.md` | 跟随 project | ❌ (单 project) | ❌ | 项目说明、修改记录、专有约定 |
| **SESSION** | `~/.aethercode/sessions.db` (SQLite, `session_memory` 表) | session 周期 | ❌ (单 session) | ❌ | 当前对话上下文、临时笔记 |
| **LOCAL** | `<cwd>/.aethercode/agent-memory-local/<agentType>/*.md` | 跟随本机 | ❌ | ❌ | 仅本机可见的项目级(老布局兼容) |
| **AUTO** | (运行时决议) | — | — | — | `resolve(cwd)`: cwd 在 git 仓 → PROJECT,否则 → USER |

**AUTO 的关键决议** (代码注释):
- `AUTO` 在 in-repo 工作时返回 **PROJECT**
- `AUTO` 在非 git 环境返回 **USER**
- **SESSION 永远不会被 `resolve()` 返回** — 因为它需要 sessionId 不是 cwd,调用方必须显式指定

**3 层 + 1 个兼容层 + 1 个 AUTO** 的设计目的:
- 3 层对应 arXiv 2512.13564v2 的 "user / project / session" 经典分层
- LOCAL 保留老的"双文件布局"兼容老项目
- AUTO 让上层不用关心 cwd 是不是 git 仓

---

## 2. ⭐ 4 阶编排 (MemoryLifecycle — R233 起)

`MemoryLifecycle.java` 是核心编排器,把 memory 操作**嵌入** session 生命周期,而不是静态配置。

### 2.1 5 个 Tier (不是 4 个!)

| Tier | 触发时机 | 成本 | 行为 | 写哪个 scope |
|---|---|---|---|---|
| **Tier 0** | per tool call | **免费** | `onToolCall(name)` 记录 tool name 到 WorkingMemoryBuffer,**无 DB 写** | — |
| **Tier 1** | per query (start/end) | **免费** | start 创建 WorkingMemoryBuffer + audit + bump recall hit + 检查 decay interval;end 清 buffer + audit + **可能触发 Tier 2/3** | — |
| **Tier 2** | per successful query (heuristic) | **免费 (no LLM)** | `extractCaseFromTranscript` 自动写 Case(末段 assistant turn + tool names),仅当 query 有 tool call 时 | EXPERIENCE (Case) |
| **Tier 3** | per pattern (heuristic + LLM, gated) | **LLM 1 次/session** | 命中策略 pattern(`always`/`never`/`remember`/`use X for Y`/同 tool ≥3 次)时,`maybeExtractStrategy` 调 LLM 抽抽象策略 | EXPERIENCE (Strategy) |
| **Tier 4** | background periodic | **免费** | `runPeriodicDecay` 5 分钟一次,跑 `ForgettingPolicy.runDecayPass` 在 USER/PROJECT store | — (衰减) |

### 2.2 关键代码段 (来自 `MemoryLifecycle.java`)

```java
public final class MemoryLifecycle {

    /** Default Tier-4 decay interval: 5 minutes. */
    public static final long DEFAULT_DECAY_INTERVAL_MS = 5L * 60 * 1000;
    /** Default cap on items per periodic decay pass. */
    public static final int DEFAULT_DECAY_MAX_ITEMS = 100;
    /** Default cap on Tier-2 case extraction body. */
    public static final int DEFAULT_CASE_BODY_MAX_CHARS = 2000;
    /** Default Tier-3 strategy extraction body cap. */
    public static final int DEFAULT_STRATEGY_BODY_MAX_CHARS = 1500;
    /** Minimum tool calls required before a query is "experience-worthy". */
    public static final int DEFAULT_MIN_TOOL_CALLS_FOR_EXTRACTION = 1;

    /** Default strategy pattern: phrases that suggest the user is teaching a rule. */
    private static final Pattern STRATEGY_PATTERN = Pattern.compile(
            "(?i)\\b(always|never|remember|keep in mind|note that|important:|rule:|"
                    + "going forward|from now on|use .+ for|in general|don't forget)\\b"
    );
}
```

### 2.3 Tier 3 策略匹配的 11 个 phrase

`(always|never|remember|keep in mind|note that|important:|rule:|going forward|from now on|use .+ for|in general|don't forget)`

匹配到任一 → 触发 LLM 抽策略。LLM 通过 `MemoryExtractor` 的 forked subagent,**at most one LLM call per session**(防雪崩)。

### 2.4 Stats 快照 (可观察)

```java
public record Stats(
    long queriesStarted,
    long queriesCompleted,
    long queriesFailed,
    long recallHits,
    long caseExtractions,        // Tier 2 触发次数
    long strategyExtractions,    // Tier 3 触发次数
    long decayPasses,            // Tier 4 跑了几次
    long decayItemsRemoved,      // Tier 4 删了几条
    long workingBufferClears     // Tier 1 end 触发次数
) {}
```

### 2.5 Best-effort 设计

> "every method swallows + logs its own exceptions. The agent loop must not break because the memory layer hiccupped."

Tier 2/3/4 全是 try-catch + log + continue,**不抛异常**。即使 LLM 挂了,agent loop 照常跑。

---

## 3. ⭐ 写读路径

### 3.1 写路径 (3 类入口)

```
┌─────────────────┐
│ Engine / Tool / │  ← engine hook (Tier 1 end)
│ Hook            │
└────────┬────────┘
         │ (提取 + 分类)
         ↓
┌─────────────────┐
│MemoryExtractor  │  ← LLM 抽 fact / change / note (Tier 3 触发)
│                 │  ← or 简单写 note (Tier 2)
└────────┬────────┘
         │ (持久化)
         ↓
┌─────────────────┐
│FileBackedMemory │  ← JSONL 追加 + 防御性拷贝
│  / SessionStore │
└─────────────────┘
         ↓
┌─────────────────┐
│MemoryAudit (log)│  ← 所有 write 事件审计
└─────────────────┘
```

**3 类入口**:
1. **Engine 自动** (Tier 1 end) — `MemoryExtractor` 调 LLM 抽新 fact/change
2. **Tool 主动** (Tier 2) — agent 显式调 `MemoryTools.write(scope, content)`
3. **Hook 触发** (Tier 3) — `MemoryLifecycle` 监听 strategy pattern 自动写

### 3.2 读路径 (4 阶段)

```
┌─────────────────┐
│  QueryEngine    │  ← 用户发起 query
└────────┬────────┘
         │ (Tier 1 start)
         ↓
┌─────────────────┐
│MemoryScope      │  ← 决议 scope: USER ∪ PROJECT ∪ SESSION
│ Resolver        │
└────────┬────────┘
         │
         ↓
┌─────────────────┐
│MemoryRecall     │  ← 选相关文件: scan → score → top 5
│  (R244+)        │
└────────┬────────┘
         │ (返回 RecalledFile[])
         ↓
┌─────────────────┐
│MemoryPrompt     │  ← 渲染到 system prompt 头部
│ Builder         │  ← USER 优先, PROJECT 次之, SESSION 最近
└─────────────────┘
```

**4 阶段**:
1. **Scope 决议** — `MemoryScope.resolve(cwd)` + 加 SESSION
2. **MemoryRecall** — 选 top 5 相关文件(`MAX_RECALL=5`,`MAX_FILE_CHARS=8000`)
3. **过滤** — `alreadySurfaced` 列表去重(防止重复注入)
4. **渲染** — 拼到 system prompt 头部

---

## 4. ⭐ MemoryRecall 细节 (R244+)

`MemoryRecall.java` — "which memory files matter right now" selector。

### 4.1 5 步 Pipeline

1. **Scan** memory 目录的 `*.md` 文件(排除 entrypoint 自身)
2. **Read manifest** — 每个文件读前 5 行作为 manifest (`MANIFEST_HEADER_LINES=5`)
3. **Score** — 朴素 lexical match against 当前 input + recent tool activity
4. **Top 5** — 保留 top 5;如果候选 > 15 (`CANDIDATE_THRESHOLD = 5*3`),调 side-query LLM disambiguate
5. **Read full** — 把选中的文件全读出来(每文件 max 8000 char)

### 4.2 关键常数

```java
public static final int MAX_RECALL = 5;                // 每次最多 5 个文件
public static final int MAX_FILE_CHARS = 8_000;        // 每文件 max 8000 char
public static final int MANIFEST_HEADER_LINES = 5;     // manifest 5 行
public static final int CANDIDATE_THRESHOLD = 15;      // 超过 15 个候选才调 LLM
```

### 4.3 为什么不用 embedding?

代码注释明说:
> "The scorer is intentionally tiny — no embeddings, no vector store. It works because memory files are small, the entrypoint already filters aggressively, and the model is strong enough to make sense of the manifest."

- 朴素 lexical 足够 → 避免 embedding 服务依赖
- entrypoint 已经预过滤 → 候选集小
- model 强 → 拿 manifest 也能判断相关性

### 4.4 RecalledFile 数据结构

```java
public static final class RecalledFile {
    public final Path path;        // 文件绝对路径
    public final String name;      // 文件名
    public final String manifest;  // 前 5 行(用于展示)
    public final String content;   // 全文 up to 8000 char
    public final double score;     // 0-1 评分
}
```

---

## 5. ⭐ ForgettingPolicy (R230 G2)

`ForgettingPolicy.java` — 模拟 Ebbinghaus 遗忘曲线的衰减算法。

### 5.1 Forgetting Score (FS) = 三信号加权

`FS ∈ [0, 1]`,**低分** = 好的遗忘候选。

| 信号 | 公式 | 默认权重 | 含义 |
|---|---|---|---|
| **recency** | `exp(-Δdays / τ)` | 0.5 | 时间衰减(最近用过的更值得留) |
| **frequency** | `log(accessCount + 1) / log(maxAccessCount + 1)` | 0.3 | 访问频次(常用更值得留) |
| **utility** | LLM/user 显式给的重要性 [0,1] | 0.2 | 显式标注的重要性 |

**默认 τ = 30 天**(在 project 记忆刷新周期内)。

### 5.2 三档阈值

| 阈值 | 默认值 | 行为 |
|---|---|---|
| `tombstoneThreshold` | **0.05** | 低于此且持续 `tombstoneDays` (默认 7 天) → 移到 `.trash/` (软删,可恢复) |
| `pruneThreshold` | **0.01** | 低于此 → 硬删(只发生在 `runDecayPass(..., force=true)` 时,**永远不自动**) |

### 5.3 关键设计:不自动硬删

代码注释:
> "Items below `pruneThreshold` are hard-deletable by an explicit `--memory-prune` invocation (**never automatic**)."

理由:用户数据,宁可堆着也别误删。需要时显式跑 `--memory-prune`。

### 5.4 调用时机 (Tier 4)

- 每 5 分钟一次 background pass (`DEFAULT_DECAY_INTERVAL_MS`)
- 每次最多处理 100 条 (`DEFAULT_DECAY_MAX_ITEMS`)
- 跑在 daemon 线程,不阻塞 query

---

## 6. ⭐ Bank Server (R244-R250) — 跨 surface 共享

### 6.1 是什么

`BankServer` 是把 memory 跨 surface (TUI / Desktop / CLI) 暴露的 HTTP 端点。**USER scope** 选 sync 到 bank,实现跨设备共享。

### 6.2 5 个 HTTP 端点

| Method | Path | 作用 |
|---|---|---|
| `GET` | `/bank/list?kind=fact` | 列条目 |
| `GET` | `/bank/recall?id=xxx` | 读单条 |
| `POST` | `/bank/upsert` | 写 |
| `POST` | `/bank/touch` | 更新 lastAccess (防衰减) |
| `POST` | `/bank/outcome` | 记录 self-eval |

### 6.3 3 个 client 实现(镜像同一接口)

| Client | 语言 | 来源 round | 用途 |
|---|---|---|---|
| `bank-client.ts` | TypeScript | **R244.3** | TUI 用 |
| `bank_client.rs` | Rust | **R249** | Tauri / Desktop 用 |
| `CachingBankClient` (装饰器) | TS | **R250+4** | LRU + TTL 缓存,2 surface 共享 |

### 6.4 安全 (R247 + R248)

- **R247**: Bearer Token 鉴权
- **R248**: TLS 1.3 加密传输

### 6.5 存储

- 本地文件: `~/.aethercode/bank/`
- 远程 HTTPS: 走 TLS + Token

---

## 7. ⭐ Self-Eval (R243-R245)

`MemoryAudit.java` 实现 self-eval 框架,每次 tool 调用后产生 5 个 metric:

| Metric | 范围 | 含义 |
|---|---|---|
| **confidence** | [0, 1] | 模型对结果的把握 |
| **drift** | [0, ∞) | 距离用户原始目标的偏离度 |
| **success** | bool | 是否成功 |
| **growth** | text | 学到的新东西(自由文本) |
| **outcome** | enum | 整体结果(outcome 上报) |

### 流程
1. tool call 后 audit hook 写入 bank
2. 周期性 `MemoryAudit` 审阅
3. 低 confidence 的 entry 进**反思池**(Reflection pool)
4. 反思池定期 review → 提升到 EXPERIENCE / 删除

---

## 8. 关键类索引 (按代码量排序)

| 类 | 文件 | 字节 | 作用 | round |
|---|---|---|---|---|
| `MemoryLifecycle` | `MemoryLifecycle.java` | 42,356 | 4 阶编排器 (Tier 0-4) | R233 |
| `SessionMemoryStore` | `SessionMemoryStore.java` | 16,774 | SESSION scope store (SQLite) | R127 |
| `LayeredMemoryStore` | `LayeredMemoryStore.java` | 14,264 | 3 层 scope 缓存层 | R127 |
| `TaskMemoryStore` | `TaskMemoryStore.java` | 13,750 | TASK scope (子 agent 隔离) | R230 |
| `FileBackedMemory` | `FileBackedMemory.java` | 11,450 | JSONL 后端 | R230 |
| `WorkingMemoryBuffer` | `WorkingMemoryBuffer.java` | 7,041 | Tier 0/1 进程内 LRU | R233 |
| `MemoryExtractor` | `MemoryExtractor.java` | 9,570 | LLM 抽 fact/change/note | R127 |
| `MemoryRecall` | `MemoryRecall.java` | 10,514 | 5 步 recall pipeline | R244 |
| `ForgettingPolicy` | `ForgettingPolicy.java` | 10,160 | 3 信号衰减算法 | R230 |
| `ExperienceStore` | `ExperienceStore.java` | 7,983 | Tier 2/3 experience 持久化 | R241 |
| `ProjectMemoryCompressor` | `ProjectMemoryCompressor.java` | 7,429 | Project scope 合并/总结 | R127 |
| `MemorySnapshot` | `MemorySnapshotSync.java` | 6,266 | 快照 (compact / session 重启) | R230 |
| `MemoryConsolidator` | `MemoryConsolidator.java` | 6,006 | Jaccard 合并去重 | R230 |
| `MemoryDeduplicator` | `MemoryDeduplicator.java` | 5,833 | 去重 (separate 路径) | R230 |
| `MemoryAudit` | `MemoryAudit.java` | 8,303 | self-eval 审计 | R245.2 |
| `MemoryPromptBuilder` | `MemoryPromptBuilder.java` | 3,581 | 渲染到 system prompt | R127 |
| `MemoryPaths` | `MemoryPaths.java` | 2,840 | 路径解析 + sanitize | R127 |
| `MemoryScope` | `MemoryScope.java` | 2,740 | 5 种 scope 枚举 | R127 |
| `MemoryEntrypoint` | `MemoryEntrypoint.java` | 2,366 | MEMORY.md 入口 | R127 |
| `ExperienceRecord` | `ExperienceRecord.java` | 2,969 | experience 记录结构 | R241 |
| `MemoryShare` | `MemoryShare.java` | 3,642 | 跨 agent 共享 | R243 |
| `TeamMemorySync` | `TeamMemorySync.java` | 5,591 | 团队多 agent sync | R243 |
| `Sensitivity` | `Sensitivity.java` | 1,479 | 敏感度标记 (是否 sync) | R244 |
| `ExperienceKind` | `ExperienceKind.java` | 1,530 | experience 类型 enum | R241 |
| `MemorySnapshot` | `MemorySnapshot.java` | 5,364 | 快照结构 | R230 |
| `MemoryTools` | `MemoryTools.java` | 949 | agent tool 暴露 | R127 |

---

## 9. 关键测试

```
FileBackedMemoryTest.java           (19 tests, R127)
FileBackedMemoryR230Test.java       (10 tests, 3 层 scope 读写)
LayeredMemoryStoreR127Test.java     (11 tests, 跨 session 隔离)
LayeredMemoryStoreR230Test.java     (6 tests,  scope 切换语义)
MemoryLifecycleR233Test.java        (8 tests, 4 阶编排)
MemoryConsolidatorTest.java         (7 tests, Jaccard 合并)
MemoryDeduplicatorTest.java         (3 tests,  去重)
ProjectMemoryCompressorR127Test.java (7 tests, 4-shot prompt)
ForgettingPolicyTest.java           (R230 三信号衰减)
MemoryRecallR244Test.java           (R244 5 步 recall)
MemoryAuditR245Test.java           (R245 self-eval)
```

---

## 10. 配置项

```yaml
# aethercode.yaml
memory:
  enabled: true
  # Tier 4 衰减
  decay_interval_ms: 300000        # 5 分钟
  decay_max_items: 100
  # Tier 2/3 抽取
  case_body_max_chars: 2000
  strategy_body_max_chars: 1500
  min_tool_calls_for_extraction: 1
  extract_case_on_success: true
  extract_strategy_gated: true
  # ForgettingPolicy
  tau_ms: 2592000000               # 30 天
  weight_recency: 0.5
  weight_frequency: 0.3
  weight_utility: 0.2
  tombstone_threshold: 0.05
  prune_threshold: 0.01
  tombstone_days: 7
  # MemoryRecall
  max_recall: 5
  max_file_chars: 8000
  candidate_threshold: 15
  # Bank (R244+)
  bank_enabled: false              # 默认本地, 显式开
  bank_url: http://localhost:7823
  bank_bearer_token: <from env>
  bank_tls: true                   # R248
```

---

## 11. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| 朴素 lexical recall (no embedding) | 无外部服务依赖, 启动快 | 召回精度低, 长 query 可能漏 |
| 5 段 (Tier 0-4) 而非 1 段 | 成本分布均匀, Tier 0-1 零成本 | 编排复杂, 调试要 trace 多层 |
| Tier 3 LLM gated 1 次/session | 防雪崩 | 错过的 pattern 永远不提 |
| 不自动硬删 (prune 需 --memory-prune) | 用户数据安全 | 长期会堆, 需手动清理 |
| Bank 3 client 镜像同一接口 | 跨 surface 一致性 | 3 套代码要同步维护 |
| 4-shot prompt 抽 fact/change/note | 质量高 | LLM 成本 + 延迟 |
| `synchronized` 单锁 | 简单 | 横向扩展难 |
| USER scope 跨 project | 上下文一致 | 不同域偏好可能冲突 |
| AUTO 默认 PROJECT (in git) | 不用每次指定 | 跨 repo 切换时可能错 |

---

## 12. 关键 round 引用

- **R127**: 引入 3 层 scope (USER/PROJECT/SESSION) + 4-shot LLM 抽取
- **R230**: Memory 综述驱动优化 + ForgettingPolicy (G2 三信号衰减)
- **R232**: MemoryExtractor 改成 lazy-wired (可降级)
- **R233**: MemoryLifecycle 4 阶编排 (Tier 0-4)
- **R241**: ExperienceStore 引入 (Tier 2/3 持久化)
- **R243**: Self-Eval (confidence + drift + success + growth) + Drift bank
- **R244.1/2/3**: Self-eval confidence 指标 + 跨 surface bank HTTP + TS bank client
- **R245.1-5**: TUI bank 集成 + Memory audit self-eval + Periodic decay + Confidence-aware drift + Welcome bank status
- **R247-249**: Bank server 鉴权 + TLS + Desktop Rust bank client
- **R250+4**: CachingBankClient 装饰器 (LRU + TTL 缓存)

详细过程见 `../round-notes/` 相应文档
