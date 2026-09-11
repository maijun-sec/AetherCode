# R233 — 关闭 WORKING 工具 + Strategy LLM + viewAuditLog RPC

**Status**: shipped (807 tests pass, 0 regression)
**Date**: 2026-09-07
**Reference**: `doc/项目文档/R230-MEMORY-WRITE-READ-MAP.md` §11.6
**Goal**: 收尾 R230 设计里最后两个未 wire 能力（WORKING buffer 工具化、Strategy LLM 提取），并补上 `viewAuditLog` RPC 让 audit 日志能从 TUI/外部进程访问。

---

## Why R233

R230 引入 5 个新组件（`Sensitivity`, `ForgettingPolicy`, `MemoryAudit`, `ExperienceStore`, `WorkingMemoryBuffer`），R231 把它们装到 `MemoryLifecycle` 4-tier orchestrator 上，R232 关闭了 3 个 recall 缺口（EXPERIENCE / SESSION k/v / MemoryExtractor wire）。

R232 doc 留了 2 个 R233 该做的事：

| 缺口 | 症状 |
|---|---|
| **#4 WORKING buffer 没消费者** | R231 给每 query 创建 + 清理 buffer，但 **agent 没法 put**。没人往里写。`context window` 还是主战场。 |
| **#5 Strategy extraction 仍 stub** | `maybeExtractStrategy(transcript, now)` 永远返回 `null`，等真 LLM 接上。 |
| **#6 audit TUI 按钮** | `viewAuditLog` RPC 还没加；TUI 想"View audit log" 没接口。 |

R233 三件套解决 #4 + #5 + #6。

---

## What shipped

### 1. `WorkingMemoryTools` — 4 个 tool，让 agent 主动维护自己的 working memory (R233-1)

**关闭 #4**。新文件 `aethercode-memory/src/main/java/org/aethercode/memory/tools/WorkingMemoryTools.java`（11.4 KB），4 个 tool：

| Tool | 作用 |
|---|---|
| `wm_put(kind, content, meta?)` | 加一条 entry 到当前 per-query buffer；`kind ∈ {TEXT, KEY_VALUE, REFERENCE, PLAN_STEP, EVIDENCE, TODO}` |
| `wm_get(id)` | 按 id 读单条 entry（含 kind / createdAt / meta） |
| `wm_list()` | 渲染整个 buffer，输出跟 system prompt 的 "Working memory" section 一样 |
| `wm_clear()` | 清空整个 buffer（agent 自己判断要重置时调用） |

**跨模块 wiring** — 关键设计是用 `Object` 装箱避开 `aethercode-core` → `aethercode-memory` 的循环依赖：

```java
// StreamingToolExecutor (aethercode-core) — 不知道 buffer 是什么类型
private java.util.function.Supplier<Optional<Object>> workingMemorySupplier;

public StreamingToolExecutor withWorkingMemorySupplier(
        java.util.function.Supplier<Optional<Object>> supplier) {
    this.workingMemorySupplier = supplier;
    return this;
}

// in buildExtras() — 给 CallContext.extras 注入
if (workingMemorySupplier != null) {
    Optional<Object> buf = workingMemorySupplier.get();
    if (buf != null && buf.isPresent()) {
        extras.put("working_memory", buf.get());
    }
}
```

```java
// AetherCodeEngine (aethercode-sdk) — 知道 lifecycle 类型
this.streamingToolExecutor.withWorkingMemorySupplier(() -> {
    MemoryLifecycle lc = this.memoryLifecycle;
    if (lc == null) return Optional.empty();
    return lc.currentBuffer().map(buf -> (Object) buf);
});
```

```java
// WorkingMemoryTools (aethercode-memory) — cast 回去
public static WorkingMemoryBuffer currentBuffer(Tool.CallContext ctx) {
    Object o = ctx.extras().get(EXTRAS_KEY);
    if (o instanceof WorkingMemoryBuffer b) return b;
    return null;
}
```

**容错**：没 wire 时工具不抛异常，返回结构化 `"no working buffer active (lifecycle not wired)"` —— 缺 wiring 不会让 agent crash，也不会污染 system prompt 段。

**`Main.java` 默认 pool**：`pool.addAll(WorkingMemoryTools.all())`，CLI / TUI / daemon 全部直接能用。

### 2. `MemoryLifecycle.maybeExtractStrategy(...)` — 真接 LLM (R233-2)

**关闭 #5**。`setStrategyChatClient(ChatClient)` setter + 重写 `maybeExtractStrategy(transcript, now)`：

```java
// MemoryLifecycle.java
private volatile ChatClient strategyChatClient; // R233-2: optional, lazy-wired

public void setStrategyChatClient(ChatClient chatClient) {
    if (chatClient == null) return; // null is no-op
    this.strategyChatClient = chatClient;
}

public ExperienceRecord maybeExtractStrategy(List<Message> transcript, Instant now) {
    if (strategyChatClient == null) return null;
    if (transcript == null || transcript.isEmpty()) return null;

    int n = Math.min(6, transcript.size());
    StringBuilder convo = new StringBuilder();
    for (int i = transcript.size() - n; i < transcript.size(); i++) {
        Message m = transcript.get(i);
        if (m == null) continue;
        convo.append("[").append(m.role()).append("] ")
             .append(m.textContent().strip().replace("\n", " "))
             .append("\n");
    }
    if (convo.length() < 30) return null; // 太琐碎

    String response;
    try {
        response = runStrategyChat(buildStrategyPrompt(convo));
    } catch (Exception e) {
        LOG.warn("strategy chat call failed: {}", e.getMessage());
        return null;
    }
    if (response == null || response.isBlank() || "NONE".equalsIgnoreCase(response.trim())) {
        return null;
    }
    // ...build ExperienceRecord(STRATEGY) + audit
}

private String runStrategyChat(String prompt) {
    if (strategyChatClient == null) return null;
    List<Message> req = List.of(Message.userText(prompt));
    StringBuilder out = new StringBuilder();
    Stream<StreamEvent> stream = strategyChatClient.stream(req,
        "You distil transferable strategies from agent sessions. Be concise.",
        List.of());
    for (Iterator<StreamEvent> it = stream.iterator(); it.hasNext(); ) {
        StreamEvent ev = it.next();
        if (ev instanceof StreamEvent.TextDelta td) {
            out.append(td.text());
        } else if (ev instanceof StreamEvent.RunEnd) {
            break;
        }
    }
    return out.toString();
}
```

**关键不变量**：
- `setStrategyChatClient(null)` 是 no-op（daemon-scoped 寿命，装上不卸）
- `maybeExtractStrategy` **从不抛**（catch-all + 短 transcript gate + `"NONE"` 拒绝）
- heuristic gate（`STRATEGY_PATTERN` 关键词 OR 同 tool 调 ≥3 次）保留在外层调用方 `onQueryEnd` 上，这里只管"调 LLM → 写 record"
- 超过 `strategyBodyMaxChars`（默认 1500）截断加 `…`，防止单条 strategy 撑爆后续 system prompt
- prompt 限制 200 字符内 + 可泛化；model 太琐碎时返回字面 `"NONE"` 拒绝

**`DaemonRunner` 装配**：
```java
memoryLifecycle.setMemoryExtractor(memoryExtractor);
memoryLifecycle.setStrategyChatClient(extractorChat);  // R233-2
methods.setMemoryChatClient(extractorChat);             // R233-3
```

### 3. `viewAuditLog` RPC — audit log 从 TUI / 外部进程可读 (R233-3)

**关闭 #6**。`AetherCodeMethods.viewAuditLog(Object params)` 注册到 dispatcher：

```java
public Object viewAuditLog(Object params) {
    Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : Map.of();
    long sinceMs = p.get("sinceMs") instanceof Number n ? n.longValue() : 0L;
    int limit = p.get("limit") instanceof Number n ? n.intValue() : 100;
    if (limit <= 0) limit = 100;
    if (limit > 1000) limit = 1000;

    MemoryAudit audit = MemoryAudit.current();
    if (audit == null) {
        return Map.of("ok", true, "count", 0, "entries", List.of(),
                "path", "", "note", "audit not initialised");
    }
    try {
        int read = Math.max(limit * 4, 200);
        List<String> raw = audit.readRecent(read);
        List<String> filtered = new ArrayList<>();
        for (String line : raw) {
            if (line == null || line.isBlank()) continue;
            if (sinceMs > 0) {
                int i = line.indexOf("\"ts\":\"");
                if (i >= 0) {
                    int j = line.indexOf('"', i + 6);
                    if (j > i) {
                        String ts = line.substring(i + 6, j);
                        try {
                            Instant inst = Instant.parse(ts);
                            if (inst.toEpochMilli() < sinceMs) continue;
                        } catch (Exception parseEx) {
                            // unparseable — include anyway
                        }
                    }
                }
            }
            filtered.add(line);
            if (filtered.size() >= limit) break;
        }
        return Map.of("ok", true, "count", filtered.size(),
                "entries", filtered, "path", audit.logFile().toString());
    } catch (IOException e) {
        LOG.warn("viewAuditLog failed: {}", e.getMessage());
        return Map.of("ok", false, "reason", e.getMessage());
    }
}
```

**Shape**：
```
viewAuditLog({ sinceMs?: number, limit?: number })
  -> {
       ok: true,
       count: N,
       entries: ["...json line 1...", "...json line 2...", ...],
       path: "<absolute path to audit.log>"
     }
```

**Best-effort filter**：扫 tail-ward `limit*4` 行（最少 200），逐行 parse `"ts":"..."` 字段做 sinceMs 比较；解析失败保留。`limit` 上限 1000 防止恶意调用拉爆进程。

**`MemoryAudit.readRecent(N)`** 是 R230 留的 API：tail-ward 读 N 行，不动游标。`MemoryAudit.current()` 拿 daemon 启动时 `MemoryAudit.setInstanceForTesting` 装的单例。

---

## 测试

| 模块 | tests | R233 增量 |
|---|---|---|
| aethercode-memory | 240 (was 207) | +21 `WorkingMemoryToolsTest` +13 `MemoryLifecycleR233Test` -1 (one test rebased to match setter contract) |
| aethercode-sdk | 256 | 不变（编译过 = wiring 有效）|
| aethercode-protocol | 269 (排除 R126 老 fail) | 不变 |
| aethercode-cli | 42 | 不变 |

**总 807 tests pass, 0 regression**。

### 新测试覆盖

`WorkingMemoryToolsTest.java`（21 tests）：
- `wm_put` happy path / 拒绝空 content / 拒绝未知 kind / 接受 meta / 默认 TEXT kind
- `wm_get` 命中 / not found / 拒绝空 id
- `wm_list` 渲染 / 空 buffer / 没 wire
- `wm_clear` 清空 / 空 buffer cleared 0 / 没 wire
- `all()` 返回 4 个 tool 且 name 顺序对
- `currentBuffer()` null when extras missing / null when key missing / 命中 / wrong type null

`MemoryLifecycleR233Test.java`（13 tests）：
- `setStrategyChatClient` 存 / 取 / null no-op（合同保证）
- `maybeExtractStrategy` 短路：no chat client / empty / null / 短 transcript（< 30 char）
- LLM 合同：返回 `"NONE"` / 大小写不敏感 `"none"` / 空字符串
- LLM 合同：返回有效 strategy → 产出 `ExperienceRecord{KIND=STRATEGY, title 前缀 "strategy:", body 含 LLM 输出, sourceSessionId=sess-r233}`
- 长 LLM 输出被 `strategyBodyMaxChars` 截断加 `…`
- LLM 抛异常 → 静默返回 null（不破 query）
- 写 audit（`"kind":"strategy"` + record id）

---

## 改动清单

| 类型 | 文件 |
|---|---|
| 新增 | `aethercode-memory/src/main/java/org/aethercode/memory/tools/WorkingMemoryTools.java` (11.4 KB) |
| 新增 test | `aethercode-memory/src/test/java/org/aethercode/memory/tools/WorkingMemoryToolsTest.java` (10.6 KB) |
| 新增 test | `aethercode-memory/src/test/java/org/aethercode/memory/MemoryLifecycleR233Test.java` (10.9 KB) |
| 修改 | `aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java` (加 `workingMemorySupplier` field + setter + extras 注入) |
| 修改 | `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` (builder 末尾 `withWorkingMemorySupplier(...)` 装到 executor) |
| 修改 | `aethercode-cli/src/main/java/org/aethercode/cli/Main.java` (`pool.addAll(WorkingMemoryTools.all())`) |
| 修改 | `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` (`setStrategyChatClient(extractorChat)` + `methods.setMemoryChatClient(extractorChat)`) |
| 修改 | `aethercode-memory/src/main/java/org/aethercode/memory/MemoryLifecycle.java` (加 `setStrategyChatClient` + 真 `maybeExtractStrategy` LLM impl + `runStrategyChat` + `buildStrategyPrompt`) |
| 修改 | `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` (加 `setMemoryChatClient` + `viewAuditLog` + dispatcher 注册 + `import java.io.IOException`) |
| 修改 doc | `doc/项目文档/R230-MEMORY-WRITE-READ-MAP.md` §11.6 (R233 结果) + §11.7 (R234+ 限制) |

---

## Lessons / 收尾说明

1. **`Object` 装箱破循环依赖**：`aethercode-core` 不能依赖 `aethercode-memory`，但 executor 要给工具的 `CallContext.extras` 注入 buffer。`Supplier<Optional<Object>>` 是干净的桥 —— 装箱时丢类型，取出时 `instanceof WorkingMemoryBuffer` 还原。`aethercode-core` 完全不知道 `WorkingMemoryBuffer` 存在，测试能传任意 `Object` mock。

2. **同步 drain stream 写小工具**：R233-2 的 `runStrategyChat` 没用 reactive / future / 回调，就是个 `for (Iterator it = stream.iterator(); ...)`。代码 17 行，逻辑清楚：append `TextDelta.text()`，遇 `RunEnd` break。async machinery 用在这种一次性的 LLM call 上是 over-engineering。

3. **`null` setter 是 no-op，不是 clear**：跟 R232 的 `setMemoryExtractor` 一致 —— daemon-scoped 状态一旦装上就不卸（避免误操作清空后再触发 fallback）。测试要 follow 合同，不能"假设 null 会清"。

4. **Best-effort filter 优先用 parse 失败保留**：audit log 的 sinceMs 过滤不是关键路径，parse 失败不能丢行 —— 用户更想知道"我多打了 audit 行"而不是"我的过滤器悄悄丢了"。`catch (Exception parseEx) { /* include */ }` 注释清楚。

5. **TUI 按钮跟后端 RPC 分两步走**：R233 上 RPC，后端能跑就行；TUI 按钮是 R234 任务。避免一个 round 同时碰 desktop + daemon + 协议。

---

## R234+ 候选

- **TUI 按钮**：`viewAuditLog` 后端就绪，桌面 "View audit log" 按钮是 desktop 端任务
- **多 session MemoryExtractor**：当前是 per-default-session 单例，多 session 时需要 session-scoped extractor
- **AUTO scope cleanup**：枚举里有但 `buildMemorySection` 不会真用，留 design-only
- **Offline consolidation (sleep)**：arXiv 综述 §7.8 CLS 理论，R235+ 大主题
- **Multimodal memory**：arXiv §7.4，R234+ 探索
