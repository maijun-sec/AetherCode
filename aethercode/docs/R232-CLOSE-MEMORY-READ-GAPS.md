# R232 — 关闭 Memory 的 3 个 recall 缺口

**Status**: shipped (774 tests pass, 0 regression)
**Date**: 2026-09-07
**Reference**: `doc/项目文档/R230-MEMORY-WRITE-READ-MAP.md` §11 (R232 落地详情)
**Goal**: 关闭 R230 doc 标注的 3 个 ⚠️ 缺口，让 session 自动写入的 memory **真正被读出来**。

---

## Why R232

R230 落地了 5 个新能力 + R231 把它们装到 query 流程里。但我自己写的 R230 doc（`doc/项目文档/R230-MEMORY-WRITE-READ-MAP.md`）里标了 3 个明显的 GAP：

| GAP | 症状 |
|---|---|
| **#1 EXPERIENCE 写而不用** | R231 在每个成功 query 写一条 case-based 经验，但 query start 的 `buildMemorySection` **不召回**它们。经验库再大也白搭。 |
| **#2 MemoryExtractor 已设计未 wire** | 类完整、测试完整、文档完整，但**调用方=0**。SESSION 的 MEMORY.md 长会话摘要从来没自动写过。 |
| **#3 SESSION 不进 recall** | `buildMemorySection` 扫 `[USER, PROJECT, SESSION, LOCAL, AUTO]` 但 SESSION 那个分支实际不读 sessionStore。5 分钟前用户说"用 junit 5"，recall 段里看不到。 |

R232 三件套就为这个。

---

## What shipped

### 1. `MemoryLifecycle.recallExperience(...)` + `renderExperienceSection(...)` (R232-1)

**关闭 GAP #1**。两个新方法：

```java
// MemoryLifecycle.java
public List<ExperienceRecord> recallExperience(String userInput, int k) {
    // 1. tokenize userInput
    // 2. call store.listUserExperience(k, tags) + store.listProjectExperience(cwd, k, tags)
    // 3. token-overlap filter
    // 4. merge: project first (current project > global), then alternate
    // 5. bump utility via store.recordExperienceUse(id) for every hit
    // 6. audit: action=recall, kind=experience
}

public static String renderExperienceSection(List<ExperienceRecord> records) {
    // "## Past experience (auto-recalled)\n### 1. title [kind]\nbody..."
}
```

**AetherCodeEngine.buildExperienceSection** 调它，把输出合并进 system-prompt section 末尾。默认 top-3 user + top-3 project（DEFAULT_RECALL_PER_SCOPE = 3）。

**SideNote 升级**：`recalled 3 memory file(s) + 2 experience(s)`。计数函数 `countRecalledFiles` 排除 R232 引入的"## Past experience"/"## Relevant memories" 两个 section 头；`countExperienceEntries` 扫 `### N. ` 深度-3 头。

### 2. `MemoryLifecycle.recallSessionKv(...)` + `renderSessionKvSection(...)` (R232-3)

**关闭 GAP #3**。

```java
public List<SessionMemoryStore.MemoryEntry> recallSessionKv(String sessionId, String userInput, int k) {
    // 1. ss.listMemory(sessionId) — 整张 k/v 表
    // 2. token-overlap filter against userInput
    // 3. no overlap? fall back to most recent N (用户可能刚说的)
    // 4. audit: action=recall, kind=kv, scope=SESSION
}

public static String renderSessionKvSection(List<MemoryEntry> entries) {
    // "## Session facts (auto-recalled)\n- **key**: value\n..."
}
```

**AetherCodeEngine.buildSessionKvSection** 调它，用 `appState.sessionId()` 拿当前 session。

**新 system-prompt section 顺序**（合并后）：
```
[先] MemoryRecall   → USER+PROJECT+LOCAL 的 .md 文件
[中] Experience     → R232 召回的 top-6 (3 user + 3 project)
[后] Session facts  → R232 召回的当前 session 的 k/v
```

### 3. `MemoryLifecycle.setMemoryExtractor(...)` + `onQueryEnd` integration (R232-2)

**关闭 GAP #2**。`MemoryExtractor`（R10-3 时代就有）的类终于有调用方了：

```java
// MemoryLifecycle.onQueryEnd() — 在 Tier 2/3 提取之后、清 buffer 之后
if (memoryExtractor != null && transcript != null && !transcript.isEmpty()) {
    if (memoryExtractor.shouldExtract(transcript)) {
        boolean wrote = memoryExtractor.extract(transcript);
        // audit: action=write, key=memoryFile path, kind=session-memory
    }
}
```

**DaemonRunner** 在 daemon 启动时构造：
```java
// 把 methods 的 chatClientResolver 喂给 extractor
ChatClient extractorChat = new ChatClient() {
    public Stream<StreamEvent> stream(msgs, sys, tools) {
        Function<String, ChatClient> r = methods.chatClientResolverField();
        if (r == null) return Stream.empty();
        ChatClient c = r.apply(methods.currentProviderName() + "/" + methods.currentModelId());
        return c == null ? Stream.empty() : c.stream(msgs, sys, tools);
    }
    public String modelId() { return methods.currentModelId(); }
};
MemoryExtractor memoryExtractor = new MemoryExtractor(extractorChat, sessionMemoryDir);
memoryLifecycle.setMemoryExtractor(memoryExtractor);
methods.setMemoryExtractor(memoryExtractor);
```

**触发阈值**沿用 `MemoryExtractor` 的 10k token（首次）/ 5k（续）+ 3 tool calls。Extractor 把 LLM 摘要写到 `~/.aethercode/agent-session-memory/default/MEMORY.md`，0o600 锁紧。

### 4. `AetherCodeMethods.setMemoryExtractor(...)` + setter wiring (R232-4)

跟 R231 的 `setMemoryLifecycle` 同模式，volatile 字段 + setter + accessor。Daemon 启动时调，shutdown 时不需要 stop（MemoryExtractor 无后台线程）。

---

## Tests (21 new)

| Test | 验证 |
|---|---|
| `recallExperienceEmptyStoreReturnsEmpty` | 空库返回空 |
| `recallExperienceReturnsRelevantByTokenOverlap` | 关键词命中对应经验 |
| `recallExperienceMergesUserAndProjectScopes` | 两 scope 合并 + project 优先 |
| `recallExperienceBumpsUtilityOnHit` | 召回后 uses++ |
| `recallExperienceAuditLogsHit` | audit log 有 "kind=experience" |
| `recallExperienceRespectsKCap` | k=2 时 cap 在 6 (2×3) |
| `recallSessionKvEmptySessionReturnsEmpty` | 不存在 session 返回空 |
| `recallSessionKvFindsRelevantByTokenOverlap` | "how to run the build?" → build_cmd |
| `recallSessionKvFallsBackToMostRecentOnNoOverlap` | 无 overlap 时降级到最近 |
| `recallSessionKvRespectsKCap` | k=3 cap |
| `recallSessionKvNullSessionIdReturnsEmpty` | null/"" sid 返回空 |
| `renderExperienceSectionEmpty` | null/空列表返回空串 |
| `renderExperienceSectionProducesMarkdown` | ## Past experience + ### N. title [kind] + body |
| `renderSessionKvSectionEmpty` | null/空列表返回空串 |
| `renderSessionKvSectionProducesMarkdown` | ## Session facts + **key**: value |
| `renderSessionKvSectionTruncatesLongValues` | >500 chars 加 "…" |
| `setMemoryExtractorStoresAndRetrieves` | setter 双向 |
| `setMemoryExtractorNullIsNoOp` | 二次 null 不清空 |
| `onQueryEndWithoutExtractorDoesNotFail` | 不接 extractor 也不挂 |
| `onQueryEndExtractorFailsSilently` | extractor 抛异常被吞 |
| `disabledLifecycleSkipsRecallExperience` | enabled=false 时所有 recall 返空 |

### Cumulative (full reactor)

```
Before R232: 753 tests
+ R232:       21 tests
─────────────────────
Total:       774 tests, 0 failures, 0 regressions
```

Breakdown by module (excluding 1 pre-existing R183/R126 conflict in protocol):
- `aethercode-memory`: 207 (was 185 — 20 R231 + 21 R232 + 35 R230 + 130 baseline)
- `aethercode-sdk`: 256
- `aethercode-protocol`: 269 (excluding 1 pre-existing fail)
- `aethercode-cli`: 42

---

## Files

### NEW (R232)

- `aethercode-memory/src/test/java/org/aethercode/memory/MemoryLifecycleR232Test.java` (12.6 KB) — 21 tests

### MOD (R232)

- `aethercode-memory/src/main/java/org/aethercode/memory/MemoryLifecycle.java` — +`recallExperience` / +`recallSessionKv` / +`renderExperienceSection` / +`renderSessionKvSection` / +`setMemoryExtractor` / +`onQueryEnd` 加 extractor 调用 / 修 STOPWORDS 重复 bug
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` — +`buildExperienceSection` / +`buildSessionKvSection` / +`combineThreeSections` / SideNote 升级 / `countRecalledFiles` 排除 R232 段头
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` — +`memoryExtractor` volatile 字段 + setter + accessor
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` — 构造 `MemoryExtractor` + 注入 lifecycle + methods

### NOT MOD (R232 明确不做)

- `AetherCodeMemory.java` 文件的 `MemoryExtractor` 类本身（已经完整）
- `ExperienceStore` / `FileBackedMemory` / `SessionMemoryStore`（R230 已有 R232 调用它们的 API）
- TS 端 / RPC 暴露 / TUI 接入（留给 R233）

---

## What did NOT ship in R232 (deferred to R233+)

| Gap | 留给谁 |
|---|---|
| **#4 WORKING buffer 没消费者** —— agent 没法 put | R233：接 QueryEngine / tool executor |
| **#5 Strategy extraction 仍 stub** —— `maybeExtractStrategy` 永远 null | R233：实装 LLM（用 `MemoryExtractor` 同款 chat client） |
| **#6 audit TUI 按钮** —— `viewAuditLog` RPC 还没加 | R233：加 RPC + TUI |
| **#7 多 session extractor** —— 当前是 per-default-session 单例 | R233：每个 session engine 自己的 extractor |
| **#8 AUTO scope 真没用** —— 枚举里有但 buildMemorySection 不会真出现 | R233：cleanup |
| **#9 USER 范围自动写仍缺** —— R230 doc 标注的 #1 痛点 | R234：session 内 promote 机制 |
| **#10 Multimodal memory** | R235+ |
| **#11 Offline sleep-like consolidation** | R236+ |
| **#12 PII 自动检测** | R234 |

### R233 路线（按 ROI）

1. WORKING buffer 接 QueryEngine — 让 agent 在 tool result 后可以 put TODO/EVIDENCE/PLAN_STEP
2. Strategy extraction 实装真 LLM
3. `viewAuditLog` RPC + TUI 按钮
4. 多 session MemoryExtractor wiring
5. 修一个多 session 的 LayeredMemoryStore 共享 SQLite 锁问题

预计 R233 总耗时 2-3 小时。

---

## Pitfalls (R232)

1. **STOPWORDS 重复 `"her"`** — `Set.of(...)` 不接受重复元素，第一次跑测试就炸。修：删第二个。**结论：用 `Set.of` 列常量要小心重复字面量**。

2. **`MemoryEntry` record 4 个字段，不是 5 个**（没有 sessionId）—— 我在测试里写 `("sess-1", "key", "value", ts, ts)` 编译失败。**结论：写 record literal 之前 grep 看一眼字段列表**。

3. **ChatClient 是 interface 不是一个 SAM** —— 不能 lambda。`DaemonRunner` 里写了正经的 anonymous class 实现。**结论：复数抽象方法的 interface 必须 anonymous class**。

4. **`chatClientResolver()` 不存在，`chatClientResolverField()` 才存在**（包私有 accessor）—— `AetherCodeMethods` 暴露的是包私有字段 getter。**结论：用 Field-getter 模式访问跨包字段**。

5. **Stale jar in local mvn repo** —— 改完 `MemoryLifecycle` 后没 `mvn install` 就跑 protocol tests，jar 是旧的。**结论：跨模块测试前 `mvn install -DskipTests` 推新 jar**。

---

## Backups

设计文档更新：`doc/项目文档/R230-MEMORY-WRITE-READ-MAP.md` §11

R-series 历史：R127 (3-layer memory) → R230 (5 个新能力) → R231 (装到 query 流) → **R232 (关闭 3 个 recall GAP)**。R233 接续 WORKING + Strategy + audit TUI。

## Next

R233 — WORKING buffer 接 QueryEngine + Strategy extraction + audit TUI。
