# R750 — stream-json 协议层四个真 bug：`tool_use` 丢失、重复 `tool_use`、重复 init、`no_run_end`

日期: 2026-10-22
触发: R747 接上 Claude Code stream-json 协议后，端到端跑出来 tool 调用在 wire 上对不上。

四个 bug，其中两个只有**真实跑 jar** 才看得见，source-pin 测试全部是绿的。

---

## 背景：Spliterator 契约有两半，R750 只修了一半

`QueryEngine.tryAdvance` 实现的是 `Spliterator.tryAdvance`。它的契约是：

> **每次调用，最多向 action 交付一个事件；返回 `true` 表示确实交付了一个。**

R750 之前，这个方法在一次调用里最多调了 23 次 `action.accept(...)`。
生产侧 `StreamConsumerGuard.consume()` 用一个**独立 probe 线程**调 `it.hasNext()`，
而 `hasNext()` 内部同样驱动 `tryAdvance`（用它自己那个一次性的 action）。
于是这一轮里除最后一个事件外，全被 probe 的 action 吞掉。

**但契约还有另外一半，方向相反：返回 `true` 却一个事件都没交付，同样是违约。**
这一半 R750 最初没发现，是端到端跑出来的（见 Part 4）。

---

## Part 1 — `tool_use` 丢失（R750a）

### 症状

stream-json 输出里 `tool_result` 有，`tool_use` 没有 → orphan tool_result。
provider 侧对应 `400 invalid params, tool result's tool id (call_X) not found (2013)`。

### 根因

上面说的 probe 线程吞事件。

### 修法

- 内部 emit 全部走 `pendingEvents` 缓冲（`emit()` 入队，`drainOne()` 每次只出一个）。
- `tryAdvance` 入口优先排空缓冲，13 个 `return` 点在返回前排空。
- `emitSubTaskTransitions(action)` 原来直接写裸 `action`（会一次发多个
  `SubTaskStart/End`），改为传入 `stEvent -> emit(stEvent)`。

---

## Part 2 — 重复 `tool_use`（R750a，新发现）

### 症状

每个工具调用在 wire 上出现**两次** `tool_use`，id 相同。

### 根因

`ToolUseStart` 有两个发射点，描述的是同一次调用：

1. 模型流分支（`ev instanceof StreamEvent.ToolUseStart`）—— 让工具卡片即时出现；
2. `StreamingToolExecutor.Event.Started` —— 执行器边界再报一次。

`ClaudeStreamJsonEmitter` 对每个 `ToolUseStart` 都写一帧，所以就是两帧。

### 修法

保留模型流那次（即时性更好），执行器那次用 `announcedToolUseIds` 去重，
仅在 id 未被广播过时补报（覆盖 todo controller 注入的工具等没有模型流的情况）。

**这个 bug 是新写的行为测试自己抓出来的**，之前的 e2e 只检查 orphan，
没检查重复 —— 「无 orphan」和「无重复」是两个不同的断言。

---

## Part 3 — 重复 `system/init`（R750a）

`RunStart` 原来位于每个 LLM turn 都会进入的 `currentLlmStream == null` 分支，
一轮工具调用 = 2 个 turn = 2 帧 init。加 `runStartEmitted` latch，每 run 只发一次。

---

## Part 4 — `no_run_end`（R750b，契约的另外一半）

### 症状

端到端每轮都以
`terminal_reason: no_run_end: engine stream ended without RunEnd` 收尾，
`subtype: error`。工具批次跑完后，**turn 2 一个事件都没有**，Spliterator 直接耗尽。

### 根因

工具批次跑完后回到 turn-start 分支。这个分支**故意不产生 consumer 可见事件**
（`RunStart` 是 run-scoped 的，turn 2 不该有；LLM 调用本身不是事件），
但它以一个裸 `return true;` 结束。

`return true` 承诺「action 收到了一个元素」，但这里一个都没给。
`Spliterators.AbstractSpliterator` 的迭代器因此错位：

1. `hasNext()` 缓存「true」
2. `next()` 无元素可返回
3. 下一次 `hasNext()` 重新进入 `tryAdvance`，此时缓冲为空 → 返回 `false`
4. consumer 读作 CLEAN DRAIN → **在终止 RunEnd 之前退出**

turn 1 之所以看起来正常，是因为 `runStartEmitted` 往缓冲里放了 RunStart，
`drainOne` 正好交出一个。turn 2 没有 RunStart（这是**正确**行为），缓冲为空，
裸 `return true` 就暴露了。

### 修法

把方法体包进 `engineLoop: while (true)`，
所有「本轮没有 consumer 可见事件」的分支改为：

```java
if (deliveredOrContinue(action)) return true;
continue engineLoop;
```

`deliveredOrContinue` 只在真的交付了一个事件时返回 true；
否则回到循环顶部重新检查 `finished` 和 cancel 中断标志，
必要时阻塞在 `currentLlmIt.next()` 直到模型真的产出东西。

---

## 验证

### 单测

`QueryEngineEventDeliveryR750Test`（7 个行为测试）——
**故意用 `while (it.hasNext()) out.add(it.next())` 而不是 `forEach`**：
`forEach` 会把真实 action 传给 `tryAdvance`，看不到 over-emission；
`hasNext/next` 循环才是 `StreamConsumerGuard` 的生产形状，是能区分两种违约的唯一形状。

变异测试：
| 变异 | 转红 |
|---|---|
| `emit()` 改回直通（回到 R750 前） | 3 个，症状与原始 bug 一致（只剩 RunEnd） |
| 去掉 `announcedToolUseIds` 去重 | 2 个（重复 tool_use） |
| turn-start 改回裸 `return true` | 5 个 |

### 端到端（真 jar，同 prompt 同 README）

```
baseline : init=2  tool_use=0  tool_result=1   result=error  (no_run_end)
fixed    : init=1  tool_use=1  tool_result=1   result=success
```
连跑 4 次稳定一致。frame 序列：`init` ×1 → turn1 thinking + tool_use → tool_result
→ **turn2 流 74 个 assistant 帧**（修复前是 0 个）→ `result/success`。

jar 内 marker 已验证：`announcedToolUseIds` / `pendingEvents` / `drainOne` /
`deliveredOrContinue` / `runtime_exception`。

### 全量

- `aethercode-core`: 1511 tests / 0 failures
- 全 reactor: 仅 `QueryErrorNotificationTest.query_catchEmitsRunEndAndErrorLog` 红，
  已 stash 验证为 **pre-existing**（AGENTS.md 已记录，见 R735a 冲突）。

---

## 教训

- **Lesson 1107 — source-pin 测试的固定字符窗口会静默失效。**
  `WINDOW = 4000` 在我加了一段注释后就不再覆盖它本来要断言的那行代码了，
  测试还在「审计」，但窗口已经截断在断言之前。**真的缺 RunEnd** 和
  **只是注释变长** 在输出上无法区分 —— 和 Lesson 1057 是同一个失效模式。
  修法：用结构边界（catch 块结尾）而不是魔数切窗口。

- **Lesson 1108 — Spliterator 契约有两半，反方向的违约更难发现。**
  「一次给太多」会丢事件；「返回 true 却什么都没给」会让迭代器错位、
  表现为提前耗尽。修好一半之后，另一半才暴露出来。
  **任何 `tryAdvance` 的结束分支都必须问：这一轮到底交付事件了吗？**
  答案是否，就不能返回 true。

- **Lesson 1109 — 「无 orphan」和「无重复」是两个独立断言。**
  Part 2 的重复 `tool_use` 通过了所有原有的 orphan 检查。
  协议类测试要同时断言 count 和 pairing。

- **Lesson 1110 — 只有真实跑 jar 才看得见的 bug，source-pin 测试一定是绿的。**
  这四个 bug 里，Part 2 / Part 3 / Part 4 全都是端到端跑出来的。
  反过来，Part 4 的回归测试也能用单测守住（变异后 5 个转红）——
  前提是测试用**生产的消费形状**（`hasNext`/`next`），而不是更方便的 `forEach`。