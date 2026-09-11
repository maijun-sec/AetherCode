# R230 — Memory 系统综述驱动优化

**Status**: first-batch shipped (Java 端), design-only for TS/RPC 层
**Date**: 2026-09-07
**Reference**: arXiv:2512.13564v2 *"Memory in the Age of AI Agents: A Survey"*
**Design doc**: `doc/项目文档/R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md` (20.4 KB)
**Tests**: 165 total in aethercode-memory module, **35 new + 130 existing = 0 failures, 0 regressions**

---

## Why R230

把 AetherCode 现有 Memory 系统（3 层 scope / 4 种 entry / 5 种 source / file+SQLite+LRU 三级后端；R10/R19B/R23-E/R26-E/R80/R92/R127 累积）放进 arXiv:2512.13564 综述的 Forms×Functions×Dynamics 三维坐标系里看，能数出 10 个明显缺口。R230 是按 ROI 排序的第一批改造：

| Gap | 综述对应 | 实施情况 |
|---|---|---|
| G2 Forgetting / Decay | §5.2.3 | ✅ `ForgettingPolicy` (recency + frequency + utility, 默认 τ=30d) |
| G5 Memory Audit | §7.7 (Trustworthy) | ✅ `MemoryAudit` (append-only JSONL) |
| G10 Sensitivity / PII 标记 | §7.7 (Trustworthy) | ✅ `Sensitivity` 枚举 + `FileBackedMemory.add(...,sensitivity)` + `setSensitivity` + `touch` |
| G1 Experience layer | §4.2 (Experiential) | ✅ `ExperienceKind` + `ExperienceRecord` + `ExperienceStore` (per-file JSON) + `LayeredMemoryStore` 接入 |
| G4 Working Memory 骨架 | §4.3 (Working) | ✅ `WorkingMemoryBuffer` (in-memory, LRU eviction, snapshot/restore) — QueryEngine 接入留 R231 |

R230 不做：G3 Recall 增强（多信号 score）、G6 Token-level 2D（KG）、G7 Memory generation、G8 Multimodal、G9 Sleep-like consolidation。

---

## What shipped

### 1. `ForgettingPolicy` (G2)

`aethercode-memory/src/main/java/org/aethercode/memory/ForgettingPolicy.java` (9.9 KB)

```java
FS = w_recency * exp(-Δdays / τ)        // 0.5
   + w_frequency * log(1+accesses)/log(1+max)  // 0.3
   + w_utility * utility                // 0.2   (parsed from "utility=0.9" tag, else 0.5)
```

- `runDecayPass(store, force)` — moves items below `tombstoneThreshold` (default 0.05) to trash; `force=true` 收紧到 0.025
- `prune(store, now)` — hard-delete below `pruneThreshold` (default 0.01)
- weights 在构造时自动归一化（防止配置错乱）
- 保留 6-arg 兼容性构造器；老调用方不破

### 2. `MemoryAudit` (G5)

`aethercode-memory/src/main/java/org/aethercode/memory/MemoryAudit.java` (8.5 KB)

- 全局单例（`MemoryAudit.enable(memoryBase)` 初始化；`current()` 拿到）
- append-only JSONL：`<memoryBase>/audit.log`，每行一个 JSON 对象
- `record(actor, action, scope, key, kind, sourceSessionId, decision, meta)` 入口
- 限速：每 100 条或 1s 一次 flush
- Best-effort：写失败 log warn 不抛异常
- JVM flag `aethercode.memory.audit.enabled=false` 全局关
- `readRecent(N)` 给未来 TUI 的 "View audit log" 按钮

Action 枚举：`read / write / delete / compress / decay / recall / share / setSensitivity / tombstone / prune`
Decision 枚举：`allow / deny / filtered-pii / tombstoned`

### 3. `Sensitivity` + `FileBackedMemory` 增强 (G10 + G3)

`aethercode-memory/src/main/java/org/aethercode/memory/Sensitivity.java` (1.5 KB)

- 4 值：`PUBLIC / INTERNAL / SENSITIVE / PII`
- `PII / SENSITIVE` 在 `isHiddenByDefault()` 过滤时隐藏
- `parseOrDefault(s)` 兜底 INTERNAL

`FileBackedMemory` 改动 (兼容 6-arg 旧 record shape)：

- `add(content, scope, tags, sensitivity)` 新重载
- `add(content, scope, tags)` 仍存在，默认 INTERNAL
- `touch(id)` 增 accessCount + 更新 lastAccessedAt（recall 命中时调）
- `setSensitivity(id, sensitivity)` 用户/LLM 标记 PII
- `MemoryItem` record 扩到 9 字段（id/content/scope/tags/createdAt/updatedAt/**sensitivity/accessCount/lastAccessedAt**）；6-arg 旧构造器保留

### 4. `ExperienceKind` / `ExperienceRecord` / `ExperienceStore` (G1)

`aethercode-memory/src/main/java/org/aethercode/memory/Experience*.java` (12.5 KB total)

- `ExperienceKind` = `CASE / STRATEGY / SKILL`（对应综述 §4.2 三个子分类）
- `ExperienceRecord` 9 字段 record（含 id/kind/title/body/createdAt/sourceSessionId/sourceQuery/sourceOutcome/utility/uses/tags/links）
- `withUse()` 自增 utility（渐进 u' = u + (1-u)*0.1）
- `ExperienceStore` 一文件一 record 的 JSON 持久化（`<scope>/experience/<id>.json`）
- `topK(k, tagFilter)` 排序：`utility desc → uses desc → createdAt desc`
- 线程安全（ReentrantReadWriteLock + per-record persist）

### 5. `LayeredMemoryStore` 接入

`LayeredMemoryStore.java` 加 5 个新方法（不改老方法签名）：

```java
appendUserExperience(kind, title, body, sourceSessionId, sourceQuery, sourceOutcome, tags, links)
appendProjectExperience(cwd, ...)
listUserExperience(k)
listProjectExperience(cwd, k)
recordExperienceUse(cwd, id)
```

USER scope 经验库路径：`<memoryBase>/agent-memory/<agentType>/experience/`
PROJECT scope 经验库路径：`<cwd>/.aethercode/agent-memory/<agentType>/experience/`

### 6. `WorkingMemoryBuffer` (G4 骨架)

`aethercode-memory/src/main/java/org/aethercode/memory/WorkingMemoryBuffer.java` (7.0 KB)

- 单 session+query 生命周期，`(sessionId, queryId)` 标识
- 6 种 entry kind: `TEXT / KEY_VALUE / REFERENCE / PLAN_STEP / EVIDENCE / TODO`
- LRU 淘汰，默认 50 条
- `put/get/list/remove/clear/snapshot/restore/render` 全套
- `render()` 输出 markdown section，R231 接入 QueryEngine
- `Snapshot` record 用于跨 thread hand-off

---

## Tests (35 new)

| File | Tests | Coverage |
|---|---:|---|
| `SensitivityTest` | 6 | parse, hiddenByDefault, enum size |
| `ForgettingPolicyTest` | 9 | defaults, recency decay, frequency, utility tag, score, threshold, decayPass, weight normalisation, negative weights |
| `MemoryAuditTest` | 5 | write line per event, schema fields, disabled is no-op, readRecent ordering, action enum |
| `ExperienceStoreTest` | 8 | round-trip, file-per-record persistence, use lifts utility, topK sort, tag filter, remove, statsByKind, unknown id |
| `WorkingMemoryBufferTest` | 8 | put/get, touch, list/clear, eviction, render, snapshot/restore, empty render, meta |
| `FileBackedMemoryR230Test` | 10 | add-with-sensitivity, default INTERNAL, touch bumps, touch unknown, setSensitivity, setSensitivity unknown, setSensitivity null, sensitivity survives reopen, touch persists, legacy 3-arg add |
| `LayeredMemoryStoreR230Test` | 6 | user round-trip, project cwd-scoped, recordUse across scopes, project persists, topK, unknown id |

### Cumulative (aethercode-memory module)

```
Before R230: 130 tests
+ R230:       35 tests
─────────────────────
Total:       165 tests, 0 failures, 0 regressions
```

Surefire 跑 47 秒（vs 之前 ~30 秒，多出来的 17 秒主要是新增测试和 FileBackedMemory 新加的 9-arg record 反射读盘成本）。

---

## Files

### NEW (R230)

- `aethercode-memory/src/main/java/org/aethercode/memory/Sensitivity.java` (1.5 KB)
- `aethercode-memory/src/main/java/org/aethercode/memory/ForgettingPolicy.java` (9.9 KB)
- `aethercode-memory/src/main/java/org/aethercode/memory/MemoryAudit.java` (8.5 KB)
- `aethercode-memory/src/main/java/org/aethercode/memory/ExperienceKind.java` (1.5 KB)
- `aethercode-memory/src/main/java/org/aethercode/memory/ExperienceRecord.java` (3.0 KB)
- `aethercode-memory/src/main/java/org/aethercode/memory/ExperienceStore.java` (8.0 KB)
- `aethercode-memory/src/main/java/org/aethercode/memory/WorkingMemoryBuffer.java` (7.0 KB)
- `doc/项目文档/R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md` (20.4 KB) — design doc

### NEW (R230 tests)

- `aethercode-memory/src/test/java/org/aethercode/memory/SensitivityTest.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/ForgettingPolicyTest.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/MemoryAuditTest.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/ExperienceStoreTest.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/WorkingMemoryBufferTest.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/FileBackedMemoryR230Test.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/LayeredMemoryStoreR230Test.java`

### MOD (R230)

- `aethercode-memory/src/main/java/org/aethercode/memory/FileBackedMemory.java` — +touch / +setSensitivity / +4-arg add with sensitivity, MemoryItem 9 字段
- `aethercode-memory/src/main/java/org/aethercode/memory/LayeredMemoryStore.java` — +5 experience methods, +2 caches

---

## What did NOT ship in R230 (deferred to R231+)

These are listed in the design doc §6 as R231+ roadmap:

1. **G3 MemoryRecall 多信号 score** — `MemoryRecall.java` 仍是 lexical + LLM 旁路消歧，没加 tag/age/use/kind 加权和 PII filter
2. **G4 WorkingMemory 实际接入 QueryEngine** — `WorkingMemoryBuffer` 骨架就绪，但 QueryEngine 还没在 query 周期内创建/清空 buffer
3. **G6 Token-level 2D/3D 结构** — Experience 暂为 flat 列表（one file per record），`links` 字段留了但没建图
4. **G7 Memory generation** — 没做 `MemoryGenerator`（重写 vs 检索的范式转移）
5. **TS 端 mirror** — `aethercode-memory/src/*.ts` 还没加 `experience-store.ts` / `forgetting-policy.ts` / `audit.ts`；desktop 端 store + MemoryPanel 也没接
6. **RPC 暴露** — `AetherCodeMethods.java` 还没加 `appendExperience / listExperience / recordExperienceUse / memoryStats / decayMemory / viewAuditLog / setSensitivity` 这 7 个 RPC + `NOTIFY_EXPERIENCE_ADDED` / `NOTIFY_MEMORY_DECAYED` 2 个 notification
7. **MemoryExtractor 接入经验路径** — 经验抽取 LLM 模板还没写

R231 直接接续：先做 TS mirror + RPC 暴露（暴露优先于功能完善），然后 G3 Recall 增强，最后 G4 QueryEngine 接入。

---

## Pitfalls (R230)

1. **Windows 路径大小写不敏感** — 第一版测试用 `"/proj/a"` 和 `"/proj/b"` 做 cwd，命中了 Windows 大小写不敏感导致 6 个老文件被读到。修：测试改用 `@TempDir` 真实子目录。结论：**memory 系统的测试 cwd 必须用真路径，不能写 magic string**。

2. **MemoryItem record 加字段破坏 6-arg 调用方** — Java record 加字段会让所有构造点编译失败。修：保留 6-arg 兼容构造器，Jackson 反序列化时缺字段走 default。**结论：扩展 record 时必须给兼容构造器，且 Jackson-friendly**。

3. **ForgettingPolicy 默认 utility 0.5 让 stale items 评分 ≈ 0.25（>0.05 threshold），不会自动 tombstone** — 这是设计选择（中性 utility = 不急于删除），但容易让用户觉得"什么都没发生"。修：把 decayPass 调成 `force=true` 才能激进清扫；默认安全。**结论：decay 政策必须有 force 开关**。

4. **`MemoryAudit` 全局单例 + 测试隔离** — 用了 `setInstanceForTesting` 模式；生产路径用 `enable(memoryBase)`，测试用 `new MemoryAudit(tempPath)`。**结论：单例要留 testing seam**。

5. **TS 端镜像未做** — R230 是纯 Java 端。TS 端需 R231 补，否则 desktop 端 MemoryPanel 看不到新东西。

---

## Backups

设计文档备份：`doc/项目文档/R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md` (20.4 KB) — 是 R230 的"为什么"和"做什么"。

Java 端代码备份：git history（这次没单独出 release package；Java 端只是 R230 的第一波，TS 端 + RPC 暴露 + 真正接入 query 流程是 R231）。

## Next

R231 路线（按 ROI）：
1. **TS 端镜像**（estimated 30 min）：`experience-store.ts` / `forgetting-policy.ts` / `audit.ts`
2. **RPC 暴露**（estimated 30 min）：7 个 RPC + 2 个 notification；desktop store 接入
3. **G3 MemoryRecall 多信号 score + PII filter**（estimated 45 min）：改 `MemoryRecall.java` 的 scorer，加 PII 过滤
4. **G4 WorkingMemory 接入 QueryEngine**（estimated 60 min）：在 query() 开始时 new buffer，每 tool result 后允许 agent 写，query 结束清空

预计 R231 总耗时 2-3 小时。
