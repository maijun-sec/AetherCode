# Context Compact

> 把 LLM 对话历史压缩以节省 token 的系统,默认走 **8 段式**(`StructuredCompactor8`)。
>
> **关键代码**: `aethercode/aethercode-compact/src/main/java/org/aethercode/compact/`
>
> **核心接口**: `aethercode-core/src/main/java/org/aethercode/core/compact/Compactor.java`

---

## 1. 架构总览

```
LLM 对话增长
  ↓
[token 超阈值]
  ↓
CompactGate 判定 ← threshold = contextWindow - buffer
  ↓
AutoCompact 包装 (默认走 StructuredCompactor8)
  ↓
┌──────────────────────────┐
│ StructuredCompactor8     │ ← 默认, 八段式 Markdown 摘要
│  (R136.5)                │
└──────────────────────────┘
  或
┌──────────────────────────┐
│ StructuredCompactor      │ ← 7 段式 (Claude Code 风, R83)
└──────────────────────────┘
  或
┌──────────────────────────┐
│ SlidingWindowCompactor   │ ← 备选, 丢老 turn
└──────────────────────────┘
  ↓
输出 List<Message> (压缩后) ← kind=structured-summary-8 marker
  ↓
spliceSummary 接回 transcript
  ↓
发回 LLM
```

**判定 → 压缩 → 接入** 三步走,全部在 LLM turn 之间完成(绝不 mid-turn,因为 transcript 是下一轮 LLM 的 source of truth)。

---

## 2. 5 个核心类

| 类 | 文件 | 字节 | 作用 | 来源 round |
|---|---|---|---|---|
| `CompactGate` | `CompactGate.java` | 7,700 | 判定 + 计数 + circuit breaker + splice | R83 |
| `AutoCompact` | `AutoCompact.java` | 5,774 | 入口 + 默认策略包装 + free-form 摘要 | R83 |
| `StructuredCompactor` | `StructuredCompactor.java` | 11,726 | **7 段式** 压缩 (Claude Code 风) | R83 |
| `StructuredCompactor8` | `StructuredCompactor8.java` | 11,493 | **8 段式** 压缩 (Claude 7 + OpenCode 1) | R136.5 |
| `SlidingWindowCompactor` | `SlidingWindowCompactor.java` | 6,971 | 备选, 丢老 turn | R83 |
| `AutoCompactAdapter` | `AutoCompactAdapter.java` | 2,855 | 适配器, 桥接 `Compactor` 接口 | R83 |

---

## 3. ⭐ 八段式: R136.5 的核心设计

### 3.1 八段式从哪来

**灵感来源**: Claude Code 的 7 段布局 + OpenCode 的 5 段布局 → 融合成 8 段。

| 段 | 来源 | 名字 | 作用 |
|---|---|---|---|
| 1 | Claude 1 | **Goal** | 用户原始诉求,一句话 |
| 2 | Claude 2 | **Progress** | 已完成的事 + 关键结果 |
| 3 | **OpenCode 1 (NEW)** | **Active Constraints** | 环境/API/权限/预算约束(7 段缺失) |
| 4 | Claude 3 | **Decisions** | 非显而易见的决定 + 为什么 |
| 5 | Claude 4 | **Files Touched** | 读/写/编辑的文件路径 + 一句话描述 |
| 6 | Claude 5 | **Open Questions** | 仍不确定或阻塞的事 |
| 7 | Claude 6 | **Current State** | 当前执行到哪 |
| 8 | Claude 7 | **Next Steps** | 下一步计划,有序 |

### 3.2 ⭐ 为什么第 3 段 "Active Constraints" 至关重要

7 段版的痛点:某些状态**不属于任何现有段**,被无声丢失。

**具体反例** (来自 `StructuredCompactor8.java` 注释):

> 在 7 段版里,一条约束"daemon 的 loop detector 处于 complex 模式,模型还有 20 turn 才触发警告"会被丢掉——
> 它不是 Decision(模型没选它)、不是 File Touched(没改文件)、不是 Next Step(不是计划要做的)。
> 没有 Active Constraints 段,这条信息就是无家可归。

类似的:
- "不许 `rm -rf`"(denied command)
- "max output 200KB / file"(size limit)
- "API rate limit 60 rpm"(quota)
- "auto-approve 当前是 off"(mode state)
- "current todo state: 3/5 done"

这些**必须保留**否则下一轮 compacted run 模型就违反约束。

**8 段版的 prompt 模板** (来自 `StructuredCompactor8.summarise()`):

```java
prompt.append("## Active Constraints\n");
prompt.append("(bullet list — environmental, API, permission, budget, or detector ");
prompt.append("constraints the model must respect to keep working. Include: ");
prompt.append("active detector mode, auto-approve state, max output tokens, ");
prompt.append("denied commands, rate limits, current todo state.)\n\n");
```

### 3.3 与 7 段式的关键差异

| 维度 | 7 段 (`StructuredCompactor`) | 8 段 (`StructuredCompactor8`) |
|---|---|---|
| Section 数 | 7 | 8 |
| Active Constraints | ❌ 无 | ✅ 段 3 |
| `kind` marker | `structured-summary` | `structured-summary-8` |
| 链式 compaction | 旧摘要 + 新摘要不可区分 | 链上 8 段能继续被压缩 |
| 默认使用 | 兼容老版本 | R250+ 默认 |

`kind` marker 是关键 — 下一轮 compaction pass 看到 `structured-summary-8` 就知道是 8 段,可以链式压缩;看到老的 `structured-summary` 就知道是 7 段走兼容路径。

---

## 4. ⭐ 完整 prompt 模板 (8 段)

直接来自 `StructuredCompactor8.summarise()`:

```
You are a context-compaction assistant. Produce a Markdown summary
of the conversation below in EXACTLY these 8 sections, in this order,
with these headings (use the exact '## ' prefix shown):

## Goal
(one sentence — the original user request)

## Progress
(bullet list — what was done, with the key results / findings)

## Active Constraints
(bullet list — environmental, API, permission, budget, or detector
constraints the model must respect to keep working. Include:
active detector mode, auto-approve state, max output tokens,
denied commands, rate limits, current todo state.)

## Decisions
(bullet list — non-obvious choices the model made and why)

## Files Touched
(bullet list of file paths — read, written, edited;
one-line description of the change per file)

## Open Questions
(bullet list — things still uncertain or blocked)

## Current State
(1-2 sentences — where execution is right now)

## Next Steps
(bullet list — what the model plans to do next, in order)

Keep each section short but complete. Preserve: explicit user
preferences, constraints, Active Constraints, and concrete file
paths / function names / values that would be expensive to re-derive.
Drop: verbose tool output, repeated error text, anything that can be
re-read from the file system.

Conversation:
- [user] ...
- [assistant] ...
- [tool] ...
…
```

**System prompt**:
```
You are a context-compaction assistant. Produce only the 8-section
Markdown summary, no preamble or explanation.
```

**关键设计点**:
1. **每段标题固定** `## Goal` 等 — 模型不会把段名写错
2. **括号内**是提示,不是占位符 — 模型填具体内容
3. **Keep each section short but complete** — 避免段太长,但 Active Constraints 必填
4. **Drop 列表** — 明确告诉模型什么可以丢(verbose tool output 等)
5. **Preserve 列表** — 明确告诉模型什么必须留(file paths 等)

---

## 5. ⭐ Token budget (R136.4)

| 维度 | R83 老值 | R136.4 新值 | 原因 |
|---|---|---|---|
| `DEFAULT_CONTEXT_WINDOW` | 200,000 (Claude 3) | **1,000,000** | 匹配 MiniMax M3 / Gemini 2.5 Pro 规格 |
| `DEFAULT_BUFFER` | 13,000 | **64,000** | 给 1M-context 模型的 thinking trace 留空间 |
| `DEFAULT_MAX_INPUT_TOKENS` | 80,000 | **900,000** | 一次性从近满 transcript 压 |

**计算例子** (1M context, 64K buffer):
- 阈值 = `1_000_000 - 64_000 = 936_000` tokens
- 超阈值时 `shouldCompact()` 返回 true
- `compact()` 调 LLM 用 900K tokens 作 prompt input budget
- LLM 输出 ≈ 32K tokens summary
- 新 transcript = summary (32K) + tail (4 turns) ≈ 35K total

**buffer 不是 dead weight** — 它是给 summary LLM 自己的"呼吸空间":输出 512K 留 64K,summary 永远不会因为模型想多 thinking 而被截断。

---

## 6. ⭐ Circuit Breaker (3 次连续失败熔断)

```java
private int consecutiveFailures = 0;
public static final int MAX_CONSECUTIVE_FAILURES = 3;
private boolean circuitOpen = false; // CompactGate 特有

public boolean shouldCompact(List<Message> messages) {
    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return false;  // StructuredCompactor
    if (circuitOpen) return false;                                       // CompactGate
    // ... token check
}

public List<Message> compact(List<Message> messages) {
    try {
        String summary = summarise(messages);
        consecutiveFailures = 0;   // 成功 → 重置
        return List.of(summaryMsg);
    } catch (Exception e) {
        consecutiveFailures++;     // 失败 → 累加
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            circuitOpen = true;    // 3 次失败 → 熔断,本 session 不再尝试
        }
        return null;               // 失败 → 返回 null,保留原 transcript
    }
}
```

**为什么是 3 次**:
- 1 次失败: 可能是 LLM 临时抽风,再试
- 2 次失败: 可能是 prompt 边界 case,再试
- 3 次失败: 99% 是系统性问题 (LLM 全挂 / prompt 永远触发限流 / 输入超过 max),再试也是浪费 token

**熔断后行为**: `shouldCompact()` 直接返回 false,**整个 session 不再尝试 compact**,transcript 持续增长直到 LLM 真挂掉。
- 优点: 避免雪崩
- 缺点: 真的有问题时 transcript 会爆,需要上层监控 transcript 长度

---

## 7. ⭐ 失败降级路径

```
compact() 调用
  ↓
ChatClient.stream() 返回 Stream<StreamEvent>
  ↓
遍历 stream
  ├─ TextDelta → 累加到 StringBuilder
  ├─ RunEnd    → 检查 finalBlocks(),若 TextBlock 则采纳,break
  └─ 其他      → 忽略
  ↓
若 out.length() == 0
  └─ 抛 IllegalStateException → consecutiveFailures++ → circuit breaker
  ↓
若 ChatClient.stream() 抛异常 (timeout / rate limit / 401)
  └─ catch → consecutiveFailures++ → 返回 null → 上层用原 transcript
```

**降级到 SlidingWindow**: 紧急情况(>95% 阈值)上层可手动切换 `SlidingWindowCompactor`,不调 LLM,直接丢老 turn。

**最坏情况**: 3 个 compactor 全挂 → 保留原始 transcript,接受"transcript 超长,LLM 可能 OOM"的风险。

---

## 8. ⭐ spliceSummary — 把 summary 接回 transcript

`CompactGate.spliceSummary()` 是关键胶水:

```java
public List<Message> spliceSummary(List<Message> original, String summary) {
    int keepTail = Math.min(4, original.size());    // 保留最近 4 turn
    int cutFrom = original.size() - keepTail;
    List<Message> out = new ArrayList<>();

    // 1. 保留 leading system message
    for (int i = 0; i < cutFrom; i++) {
        if (original.get(i).role() == Role.SYSTEM) {
            out.add(original.get(i));
        }
    }

    // 2. 插入 summary 作为 user 消息 (有 marker)
    out.add(new Message(
            null, Role.USER,
            List.of(new ContentBlock.TextBlock(
                    "[Conversation compacted — earlier turns replaced by the summary below]\n\n" + summary)),
            null,
            Map.of("summary", true)   // metadata 标记
    ));

    // 3. 追加保留的 tail (最近 4 turn)
    for (int i = cutFrom; i < original.size(); i++) {
        out.add(original.get(i));
    }
    return out;
}
```

**结果**: 1 个 system + 1 个 summary + 4 个 recent = 6 条 messages,总 token 数 ≈ 32K (summary) + 几 K (tail) = 35K。

---

## 9. RPC 端点

| Method | 作用 | 参数 |
|---|---|---|
| `compact_run` | 同步触发压缩 | `CompactRunParams` (强制标志 + 策略) |
| `compact_status` | 查询压缩状态 | `CompactStatusParams` (空) |

返回 `CompactRunResult` / `CompactStatusResult`:

```json
{
    "before_tokens": 125000,
    "after_tokens":  65000,
    "saved_pct":     48.0,
    "strategy":      "structured-v8",
    "duration_ms":   340
}
```

---

## 10. QueryEngine 集成 (R140)

`QueryEnginePreFlightCompactR140Test` 验证:
- query 开始前检查 token 阈值
- 超阈值时先 `compact_run` 再 query
- 压缩失败 (LLM timeout) → 降级到 SlidingWindow
- 完全失败 → 报错 + 保留原始 history

**集成点** (`QueryEngine.java`):
```java
if (compact.shouldCompact(messages)) {
    Result r = compact.compact(messages);
    if (r.wasCompacted()) {
        messages = r.messages();   // 替换为新 transcript
    }
}
// 继续用 (可能是新的) messages 调 LLM
```

---

## 11. 测试覆盖

```
StructuredCompactor8Test.java  (3,591 bytes, R136.5)
  ├─ defaultContextWindowIs1M              ← 验证 1M/64K/900K
  ├─ shouldCompactTriggersAbove...         ← 阈值逻辑
  ├─ compactProducesAssistantSummary...    ← kind=structured-summary-8 marker
  └─ emptyMessagesReturnsNull              ← 边界

StructuredCompactorTest.java  (9,794 bytes, R83, 7 段)
  ├─ 7 段 prompt 完整性
  ├─ failure → consecutiveFailures++
  └─ circuit breaker 3 次熔断

CompactGateTest.java          (3,461 bytes, R83)
  ├─ spliceSummary
  ├─ circuitOpen 状态
  └─ shouldCompact 阈值

SlidingWindowCompactorTest.java  (10,764 bytes, R83)
  └─ 滑动窗口逻辑

AutoCompactAdapterTest.java   (6,007 bytes, R83)
  └─ 适配器 / 接口桥接
```

---

## 12. ⭐ 配置项

```yaml
# aethercode.yaml
compact:
  enabled: true
  default_strategy: structured-v8   # structured | structured-v8 | sliding-window
  force_threshold_pct: 95
  should_threshold_pct: 80
  context_window_tokens: 1_000_000  # 匹配 MiniMax M3 / Gemini 2.5 Pro
  buffer_tokens: 64_000             # 给 summary 留 thinking 空间
  max_input_tokens: 900_000         # 一次能压 1M - 64K
  checkpoint_every_n_turns: 10      # 每 10 turn 自动 summary checkpoint
  structured_v8_summary_every_n_turns: 8  # v8 独有:每 8 turn 强制 summary
  sliding_window_keep_turns: 20     # SlidingWindow 兜底时保留 20 turn
  circuit_breaker_max_failures: 3   # 3 次连续失败熔断
```

---

## 13. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| 默认走 8 段式 | 完整保留 Active Constraints,长任务不丢约束 | LLM 调用延迟 + 成本 |
| 95% 阈值用 SlidingWindow 兜底 | 紧急时也能压缩 | 丢早期上下文 |
| 每 8/10 turn checkpoint | 长期会话可读 | LLM 总结可能有信息损失 |
| Token 估算用 4 chars/token heuristic | 快速 | 不精确,可能在 80% 之前就压缩 |
| 3 次失败熔断 | 避免雪崩 | 真的有 bug 时 transcript 会爆 |
| Buffer 64K | 1M 模型的 thinking 不会被截 | 浪费 6.4% context |

---

## 14. 关键 round 引用

- **R83**: 引入 7 段式 + CompactGate + SlidingWindow
- **R136.4**: token budget 升级 (200K→1M, 13K→64K, 80K→900K)
- **R136.5**: 引入 8 段式,加 Active Constraints
- **R140**: QueryEngine 集成 pre-flight compact
- **R250+**: 默认 strategy 升级到 v8, gate 阈值重新校准
- 详细过程见 `../round-notes/` 相应文档
