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
---

# R750d — 错误结果分类收口，以及一次被误诊的调度器竞态

日期: 2026-10-22
范围: `StreamEvent` / `ClaudeStreamJsonEmitter` / `AetherCodeMethods` / `TaskScheduler`

R750d 原计划只有一件事：把「这次 run 算不算失败」的定义收口到一个地方。
实际做下来是两件事 —— 第二件是从第一件的验证过程中**顺手挖出来的真 bug**，
而且我一开始**诊断错了**。这一段记录那个误诊过程，因为它比修复本身更值得留。

---

## Part 1 — 错误分类只由 `StreamEvent` 定义一次

### 问题

engine 自己在 `tryAdvance` 的 outer catch 里产出终止事件：

    RunEnd(stopReason="runtime_exception: <class>: <msg>")

但 `AetherCodeMethods.eventToMap(RunEnd)` 只把 `stopReason` 原样放进 map，
**没有错误位**。协议消费者拿到的就是一个"看起来正常"的终止帧。

而 `ClaudeStreamJsonEmitter` 那边有自己一份 `OK_KINDS` 白名单，
用的是 `!OK_KINDS.contains(kind)`。白名单的性质是**对新情况 fail-closed 成 error**：
以后任何人加一种新的 stopReason，没同步进这个集合，就会被判成失败。
两份分类逻辑还给出了**互相矛盾**的答案 —— `cancelled`（用户按 Esc）
在 emitter 里被判 `is_error: true`。

### 修法

- `StreamEvent.isErrorOutcome()` —— 唯一的错误分类法，基于已有的
  `bareKindOf()`，所以 `"runtime_exception: ..."`（装饰过的长串）和
  `"runtime_exception"`（裸串）判定一致。
- 判定为**失败**：`runtime_exception` / `error` / `no_run_end` /
  `consumer_timeout` / `same_error_hard_stop` / `loop_detected` / `stale_force_end`
- 判定为**非失败**：`end_turn` / `stop` / `tool_use` / `tool_calls` /
  `cancelled` / `awaiting_user_decision` / `empty_input`
- `eventToMap(RunEnd)` 在失败时补 `isError:true` + `error` 字段。
- `ClaudeStreamJsonEmitter` 删掉 `OK_KINDS`，改问 `re.isErrorOutcome()`。

### 一个必须说清的区分

`cancelled` 同时是：

- **deterministic hard stop**（`isDeterministicHardStopOf` → true）——
  下游任何自动续跑路径都不许复活它（R743 的教训）；
- **不是 error outcome** —— 用户自己按的 Esc，不是引擎故障。

这两个集合**故意不同**。把它们合并正是 R743 警告过的错误。
R750d 顺手把旧测试 `runEnd_cancelled_isClassifiedAsError` 改成
`runEnd_cancelled_isNotClassifiedAsError`，并加断言 `subtype == "success"`
且 `aethercode_stop_reason == "cancelled"` —— 让调度器仍然能区分
"取消"和"真跑完了"，而不只是看到 `is_error: false`。

---

## Part 2 — `TaskScheduler.awaitIdle` 根本不是 barrier（误诊实录）

### 怎么发现的

跑全 reactor 时冒出一个新的红灯：
`TaskSchedulerBridgeTest.bridge_runsTaskAndUpdatesRegistryToCompleted`
断言 `ran == 1` 读到 0。单跑 5 次全绿，看 git log 也没有相关改动。

### 第一次诊断：**错的**

我读 `workerLoop`，看到出队顺序是

    queue.take() -> pendingById.remove(id) -> runItem()

而 `running.add` / `activeCount.incrementAndGet` 在 `runItem()` 里。
于是认定：**存在一个窗口，id 不在 `pendingById`、不在 `running`、
`activeCount` 也没算它**，而 `awaitIdle` 判定 idle 的条件恰好是
「三者为空的合取」—— 所以它会提前返回 true。

听起来非常合理。于是我"修"了：把 `running.add` / `incrementAndGet`
提到 `pendingById.remove` 之后、`runItem()` 之前。

然后写了个变异脚本，8 个 busy loop 压 CPU，两个变体都跑：

    FIXED        => 5/0
    REVERTED-BUG => 5/0      <-- 变异后依然全绿

**两边都绿，等于什么都没证明。**（Lesson 1117）
如果我当时看到 REVERTED 变红，就会自信地提交一个错误的修复 ——
而实际上它连 bug 都没抓到。

### 第二次诊断：找到真正的窗口

新写的 barrier 测试（300 轮，每轮 schedule 一次然后 awaitIdle 一次）
直接对着不变量打，在**修复后**的代码上依然会红：

    awaitIdle returned true before round 127 had executed its body
    ==> expected: <128> but was: <127>

这才说明我的修复**没有真正解决问题**。真正的窗口在 `schedule()` 里：

    schedule():  pendingById.put(id, item);   <-- 先写这里
                 queue.put(item);             <-- 后写这里
    workerLoop: queue.take() -> pendingById.remove -> running.add

`awaitIdle` **从来没查过 `pendingById`**，而 `pendingById` 恰恰是最先被写入、
最后被 worker 移除的那个结构。所以「已被接受但还没进队列」这个窗口，
三个被检查的字段同时为空。

### 第三次：加一个字段仍然不够

把 `pendingById.isEmpty()` 加进合取 —— 上面两个窗口关了，
但**第三个窗口**还在：`pendingById.remove()` 和 `running.add()` 之间。
连跑 6 次，**3 次红**。

> **Lesson 1120 — N 个集合逐个读，永远拼不出一致的快照。**
> `isEmpty()` 是原子的，但「读四个字段的合取」不是 observeable-consistent 的：
> 任何与 worker 的交错都可能让它们同时为空。补一个字段只是把窗口从
> A 挪到 B，不是修好。

### 真正的修法：一个字段

新增 `AtomicInteger outstanding`：

- `schedule()` 在**任何集合变可见之前** `incrementAndGet()`
  （宁可短暂高报 —— 那只多一次 park 迭代；低报才是破坏 barrier）
- worker / `cancel()` 在各自**唯一**的收尾路径上 `decrementAndGet()`，且
  **在终止事件 emit 之后**（否则 awaitIdle 可能在 COMPLETED 通知之前返回，
  调用方读到过期状态）
- `awaitIdle` 只读这一个字段

三次递减点必须**互斥**，这是实现里最容易出错的地方：

| 路径 | 条件 | 谁递减 |
| --- | --- | --- |
| `cancel()` | `pendingById.remove(id) != null`（我赢了竞争） | `cancel()` |
| worker 出队 | `wasPending == true` 且已取消 | worker |
| `runItem` finally | 已被 worker 认领 | `runItem` |

第一版我让 worker 无条件递减，结果 **cancelled 的 item 仍然留在队列里**，
于是 `cancel()` 减一次、worker 再减一次 → 计数漂移 →
三个既有测试的 `awaitIdle` 全部超时失败：

    TaskSchedulerBridgeTest.bridge_cancelledPendingTaskSetsKilled  FAIL
    TaskSchedulerTest.cancel_pendingTaskDoesNotRun                FAIL
    TaskSchedulerAwaitIdleR750Test.awaitIdle_returnsTrueAfterQueuedTaskIsCancelled  FAIL

这正是"修 barrier 反而制造 hang"的典型形态。计数必须**每个被接受的
task 恰好减一次**。

### 最终变异验证

    FIXED        AwaitIdle 4/0  Bridge 5/0  SchedulerTest 10/0   failing methods: 0
    REVERTED-BUG AwaitIdle 1F   Bridge 5/0  SchedulerTest 10/0   failing methods: 1

新测试对缺陷有真实判别力，且**两个既有套件在两个变体下都不受影响**
（说明新测试不是靠运气、也不是既有测试变弱了）。

---

## 教训

- **Lesson 1119 — 变异测试两个变体都绿 = 你测的不是你以为的东西。**
  我第一次"修复"后跑变异，FIXED 和 REVERTED 都是 5/0。如果当时把
  "变异后仍然绿"读成"我的修复是对的"，就会提交一个连 bug 都没抓到的补丁。
  **两个变体必须给出不同答案，否则先怀疑测试，再怀疑修复。**

- **Lesson 1120 — N 个集合逐个读，拼不出一致的快照。**
  补字段只是把窗口挪个位置。第一版加 `pendingById` 之后仍然 3/6 红。
  屏障类谓词（idle / done / stopped / settled）必须建立在**单个**可原子读的
  计数或状态字上，而不是多个容器的合取。

- **Lesson 1121 — 我连续两次推断被事实推翻时，停下来去写会失败的测试，
  而不是继续推理。**
  第一次"修复"没解决问题，这个事实是 barrier 测试告诉我的 —— 不是读代码读出来的。
  直接把不变量写成测试（300 轮对拍），比继续读 `workerLoop` 快得多。

- **Lesson 1122 — 修 barrier 很容易变成制造 hang。**
  三次递减点必须互斥，否则计数漂移，症状从"提前返回"变成"永远超时"——
  而且会打到**原本绿色的既有测试**上。看到 cancel/worker 都递减时就该立刻意识到。

- **Lesson 1123 — 一个测试自己也会 flaky，而且会骗人两次。**
  我新写的 barrier 测试在修复前红、修复后仍红（3/6），第一版 watcher 甚至
  在正确代码上就红（因为我去对拍 `registry.list()`，而 COMPLETED 状态
  是**经 bridge listener 晚一点**才到 registry 的 —— 那测的是 bridge 延迟，不是调度器记账）。
  变异测试的价值正在于此：**同一个测试必须在两个方向上都被验证过**。

- **Lesson 1124 — hard-stop 与 error 是两个不同的集合。**
  `cancelled` 是 deterministic hard stop（不许自动续跑）但不是 error
  （不是用户没要的故障）。把两者合并会让用户按 Esc 变成一条错误告警。

---

## Part 3 — 顺带挖出来的一个真 bug：`TaskRegistry.updateStatus` 丢更新

诊断 `awaitIdle` 的时候，一个探针顺手发现：3000 轮顺序调度里有 4-11 次
「任务跑完了但 registry 还停在 PENDING」。

### 根因

`updateStatus` 是 `get()` 然后 `put()` —— **非原子的 read-modify-write**。
两个线程对**同一个** task 做状态迁移时，都读到同一个 `prev`，
后写的 `put` 覆盖先写的，于是旧状态被"复活"。

实际并发来源是常态路径：scheduler 的 bridge 写 `RUNNING` / `COMPLETED`，
而任务体自己可能也在写状态。

### 修法

整个 read-decide-write 走 `ConcurrentHashMap.compute`，per-key bin 锁
把迁移串行化。`notify()` 特意放在 compute 块**外面** —— 绝不能持有
bin 锁去跑任意的 listener 代码。

### 第一版修复打破了三套既有测试

我把 `notify` 加了条件（"状态真的变了才通知"），并且返回了错误的 `Task`。
结果三个既有套件变红：

- `TaskRegistryTest.listener_firesOnEveryChange`
  —— listener 在**每一次**调用都要触发，包括因为已 terminal 而被忽略的那次；
- `PersistentTaskRegistry`
  —— 每次调用追加一行日志，被抑制的 notify 会**静默丢掉持久化记录**；
- `restore_terminalStateIsSticky`
  —— 依赖返回的 `Task` 是迁移**之后**的状态。

**一个"改进"打破三条既有契约，就不是改进。**
修 bug 之前先读既有测试，它们才是契约的说明书。

---

## Part 4 — 我自己写的门禁也会是假的（Lesson 1125）

这一轮最有价值的产出不是修复，是三个"我以为验证了、其实没有"的发现。

### 4.1 变异两个方向都绿

第一版 `awaitIdle` 修复的变异脚本输出：

    FIXED        => 5/0
    REVERTED-BUG => 5/0      <-- 变异后依然全绿

**两边都绿。**（Lesson 1119）
如果当时把"变异后仍然绿"读成"我的修复是对的"，
就会提交一个连 bug 都没抓到的补丁 —— 而实际上它连真正的窗口都没碰到。

### 4.2 补一个字段只是把窗口挪个位置

给谓词加上 `pendingById.isEmpty()`，关掉了两个窗口，
但 `pendingById.remove` → `running.add` 之间还有第三个。
连跑 6 次，**3 次红**。

**Lesson 1120 — N 个集合逐个读，拼不出一致的快照。**
最终修法是单个 `AtomicInteger outstanding`。

### 4.3 我自己写的测试也会骗我，而且骗了两次

第一版 barrier 测试在"修复后"的代码上仍红。第一版 watcher 更糟：
它在**正确代码上就红** —— 因为我拿 `registry.list()` 去对拍，
而 `COMPLETED` 状态是经 bridge listener **晚一点**才到 registry 的。
那测的是 bridge 延迟，不是调度器记账。

改写成对 `outstanding` 的断言后仍然 flaky（3/8），因为
「awaitIdle 返回 true」和「测试重新读 `outstanding`」是两次独立观察，
中间下一轮的 `schedule()` 可以合法地插进来 +1。

一个事件轨迹探针给出了最终答案：失败样本里那个 task 只发了
`QUEUED`（有时 `QUEUED`+`RUNNING`），而 `outstandingCount()` 读到 1 ——
**那是属于后面某一轮的任务，循环早就往前走了**。
用 volatile 握手试过，计数器精确但状态仍是 PENDING，排除了可见性。

结论：**barrier 只保证「调用之前提交的工作」已执行**。
断言一个 scheduler 并不拥有的可观测量，换来的是一个重试也修不好的竞态。

### 4.4 最终的诚实状态

两个变异脚本都显示：把谓词改回旧的、或把 `compute` 改回 `get/put`，
**这一轮的测试仍然是绿的**。原因是那两个窗口都只有几条指令宽，
测试无法可靠地落进去。

所以这些测试的 javadoc 里明确写了这一点，而不是假装它们守着那个修复。
它们真正守住的是**结构性属性**（单一权威计数器、每个 task 恰好减一次、
`updateStatus` 的 null 契约），而这些属性在开发过程中**大声地失败过** ——
cancel 路径重复递减把 `awaitIdle` 卡死，以及 `updateStatus` 丢写入。

**Lesson 1125 — 变异不能把你的测试变红，就说明它不是你以为的那个门禁 ——
哪怕那个测试是你自己刚写的。** 此时该做的是在测试里写明这个限制，
而不是改个名字暗示它守着那个修复。一个名字里写着"回归门禁"、
实际不设防的测试，比没有测试更糟。

---

## 教训

- **Lesson 1119 — 变异测试两个变体都绿 = 你测的不是你以为的东西。**
- **Lesson 1120 — N 个集合逐个读，拼不出一致的快照。补字段只是挪窗口。**
- **Lesson 1121 — 连续两次推断被事实推翻时，去写会失败的测试，别继续推理。**
- **Lesson 1122 — 修 barrier 很容易变成制造 hang：递减点必须互斥。**
- **Lesson 1123 — 我自己写的测试也会在两个方向上骗我；正反都要验证。**
- **Lesson 1124 — hard-stop 与 error 是两个集合，合并会把用户按 Esc 变成告警。**
- **Lesson 1125 — 变异不能变红的测试不是门禁，哪怕是你自己刚写的。**
  该做的是在 javadoc 里写清限制，而不是改名字假装。
- **Lesson 1126 — 一个"改进"打破三条既有契约，就不是改进。**
  修 bug 前先读既有测试，它们是契约的说明书。
- **Lesson 1127 — `BUILD SUCCESS` 不等于改动进了产物。**
  R750d 的 null 守卫在源码里、在 `target/classes` 里，但**不在打包出的 jar 里** ——
  增量编译因为只有 lambda 体变了而没有重编 `TaskRegistry`。
  `javap -p -c` 看模块 classes 有守卫，jar 里没有那些字符串，
  `mvn clean package` 之后才对。**永远 grep 最终产物的字符串常量池。**
- **Lesson 1128 — barrier 只保证「调用前提交的工作」。**
  断言 scheduler 不拥有的可观测量，换来的竞态重试也修不好。
