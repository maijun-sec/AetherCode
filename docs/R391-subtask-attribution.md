# R391 — 子任务的产出归属、标题冻结、与“被删掉的项”

> 状态：代码已落地，测试已绿，**尚未部署**（jar / 渲染层仍是 R390 的构建）。
> 证据来源：运行中的桌面端（WebView2 CDP 探针）+ 该 session 的真实 transcript。

## 摘要

用户在 R390 部署后又报了三个现象：

| 图 | 现象 | 本轮定位 |
|---|---|---|
| 1 | 一行工具条后面黏着一串 `done done done …` | **不在桌面端**（桌面端 DOM 里既没有多对勾也没有 `done` 串）。它来自 TUI 那条渲染线（用户 R389 在途工作），本轮不动。 |
| 2 | 任务被拆成 7 个步骤，但**所有产出都挤在第一个块里**，后面的步骤只有标题 | 缺陷 1（step 从来没有归属到任何子任务） |
| 3 | 最后一张卡片底部挂着一个 `结果 skipped / skipped: sub-task removed from todo list`，**跟最后一个步骤毫无关系** | 缺陷 2（标题冻结在首次措辞）+ 缺陷 3（被删掉的项留下“skipped”幽灵卡） |

## 证据（不是推理，是实测）

### 现场：运行中渲染层的 DOM

用 WebView2 的 DevTools 端点（`--remote-debugging-port=9222`）直接读渲染层：

```
block 0  cls="agent-message preamble"                  len=674
block 1  cls="agent-message preamble"                  len=15696   ← 全部产出在这
          h2 = ["交付内容", "UT（254 个用例，全绿）", "过程中修掉的一个真实 bug", "验证结果"]
block 2  cls="agent-message has-subtask"  len=40    h2=["搭建 Maven 项目骨架（pom.xml / 目录 / .gitignore）"]
block 3  cls="agent-message has-subtask"  len=52    h2=["实现核心抽象层（PrimArray 适配器 + SortAlgorithm/SortOrder API）"]
block 4  cls="agent-message has-subtask"  len=30    h2=["实现 9 种排序算法（int/short/long 全支持）"]
block 5  cls="agent-message has-subtask"  len=17    h2=["实现 Sorters 注册表/门面"]
block 6  cls="agent-message has-subtask"  len=26    h2=["编写完整 UT（契约/边界/极值/注册表/性能冒烟）"]
block 7  cls="agent-message has-subtask"  len=24    h2=["运行 mvn test 直到全绿并复核 diff"]
block 8  cls="agent-message has-subtask"  len=75    h2=["补 README（中文）说明算法清单与用法"]
                                                       "▾ 结果 skipped / skipped: sub-task removed from todo list"
```

⇒ **7 张子任务卡片全部只有标题**（17–75 字符），**15 696 字的产出全在一个 preamble 块里**。
⇒ 图 1 的样式（`∪ ✓✓✓✓✓✓ file_write · … done done …`）在桌面端 DOM 里**完全不存在**。

### transcript：模型其实推进了 4 次计划

`26ee7d8d-f07b-4e11-97ea-a7dc13020794.jsonl`（213 行）里 4 次 `todo_write`：

| 行 | 项数 | 内容 |
|---|---|---|
| 17 | 7 | 搭建 Maven 项目骨架(in_progress) / 实现核心抽象层 / 实现 9 种排序算法 / 实现 Sorters 注册表 / 编写完整 UT / 运行 mvn test / 补 README |
| 71 | **5** | 搭建 Maven 项目骨架(completed) / 实现 9 种排序算法(completed) / 编写完整 UT(completed) / 跑通 mvn test(**in_progress**) / 补 README |
| 167 | 6 | …前 5 项 completed / mvn clean test 从零全量验证(in_progress) |
| 210 | 6 | 全部 completed |

⇒ 模型**改写了计划**：7 项 → 5 项（合并了两项），措辞也变了。

### daemon 日志：sub-task 事件序列

```
11:32:01 R740: flat-todo synthesised SubTaskStart taskId=0 subTaskId=auto-0 status=in_progress content='搭建 Maven 项目骨架…'
11:32:01 R740: … taskId=1 subTaskId=auto-1 status=pending content='实现核心抽象层…'
   … auto-2 … auto-3 … auto-4 … auto-5 … auto-6
11:35:17 (todo_write，5 项)  ← 7 项缩成 5 项
11:48:43 R740: … taskId=5 subTaskId=auto-5 status=in_progress content='mvn clean test 从零全量验证 + 复核最终文件清单'
11:50:04 (todo_write，6 项全 completed)
```

---

## 三个缺陷

### 缺陷 1：step 从来没有归属到任何子任务（图 2 的主因）

桌面端 `store/index.ts`：

- `run_start` 开一个新 step，用 `s.currentSubTaskId` 盖章。
  **但 `run_start` 发生在模型声明计划之前** ⇒ 那时 `currentSubTaskId` 恒为 `null`。
- 之后每一个新 step（`text_delta` 拆出来的）只**继承上一个 step 的 owner**
  （`prev?.subTaskId ?? null`）⇒ 一路 `null` 到底。
- `MessageList` 的分组：`if (s.subTaskId) bySub[...] else pre.push(s)`
  ⇒ **全部 step 落进 `pre[]`（preamble 块）**，7 张卡片全空。

⇒ 这就是“所有步骤都在第一个的步骤里面输出了”的机制。严格说不是“第一个卡片”，是**第一个块**——它在时间线上排在 7 张卡片**之前**，所以看起来像内容被塞进了第一个标题。

### 缺陷 2：卡片的标题冻结在首次措辞

- daemon 侧：`emitSubTaskTransitions` 只跟踪子任务的 **status**，不跟踪**文本**。
  而 flat todo 的子任务 id 是**位置**（`auto-{t}`）⇒ 模型改了措辞 ⇒ 内容变了 ⇒ **不产生任何事件** ⇒ 卡片标题停在旧措辞。
- 桌面端侧：`sub_task_start` 对**已存在**的卡片只更新 `status`，**不更新 `content`** ⇒ 即使 daemon 发了新文本，界面也不改。

⇒ 模型第 3 次把第 6 项从「运行 mvn test 直到全绿并复核 diff」改成「mvn clean test 从零全量验证 + 复核最终文件清单」，
卡片标题仍是旧的那句。

### 缺陷 3：被模型删掉的项留下“skipped”幽灵卡（图 3）

daemon 对“本轮 todo 列表里不再出现”的子任务发
`SubTaskEnd(…, "skipped", "sub-task removed from todo list")`。
桌面端把它当**普通的终态**处理 ⇒ 卡片保留 + 渲染一个 `结果 skipped` 页脚。

⇒ 而模型其实只是**合并/改写**了计划，那件事还在计划里（换了个说法），
所以最后一张卡片上的 “skipped” 跟最后一个步骤毫无关系——**它属于一个已经被改写掉的旧条目**。

---

## 修复

### daemon（`aethercode-core`）

1. **两份状态机合并成一份**：`emitSubTaskTransitionsFor(...)` 同时服务
   “模型显式声明的 `subtasks[]`”与“合成的 flat todo”。
   （此前两份手写副本已经漂移：只有 flat 那份在意文本变化。）
2. **文本纳入跟踪**：新增 `lastSubTaskContent`，与 `lastSubTaskStatus` 同键同生命周期。
   文本变了 ⇒ 重发一条 `SubTaskStart`，携带**新文本 + 旧 status**
   （**重新措辞 ≠ 重新打开**，不能把已完成的项翻回 in_progress）。
   文本与 status 同时变且变为 `in_progress` ⇒ 只发**一条** Start（同时带新文本与新 status）。

### 桌面端（`aethercode-desktop`）

3. **step 归属跟随当前子任务**：`text_delta` 新建 step 时
   `parentSubTaskId = s.currentSubTaskId ?? prev?.subTaskId ?? null`
   —— 当前子任务优先；子任务刚结束、下一个还没开始的间隙沿用上一个，避免产出散落。
4. **卡片标题跟随计划**：`sub_task_start` 对已存在的卡片同时更新 `content`。
5. **被删掉的项不再留“skipped”幽灵卡**：`sub_task_end` 收到
   `summary === 'sub-task removed from todo list'` ⇒ **移除该卡片**（而不是标 skipped）。
6. **孤儿 step 不许凭空消失**：分组逻辑抽成导出纯函数
   `groupStepsBySubTask(steps, subTasks)`；指向已移除卡片的 step **回落到 preamble 桶**，
   产出不会因为卡片被删而从聊天里蒸发。

---

## 效果预测（对照本 session 的真实 4 次 todo_write）

| | 修复前 | 修复后 |
|---|---|---|
| 产出分布 | 15 696 字全在 preamble 块 | 随计划推进落到对应卡片 |
| 卡片标题 | 全部是第 1 次 todo 的措辞 | 每次计划改写都更新 |
| 最后的 “结果 skipped” | 挂在最后一张卡片上 | 卡片被移除，不再出现 |
| 卡片数量 | 7 张（6 张空） | 随计划长度变化，多余的移除 |

### 仍然存在的一个**残留现象**（不是渲染缺陷）

模型**没有及时推进 todo 状态**：从第 1 次 `todo_write`（11:32）到第 2 次（11:35）
之间的 3 分钟里，它把骨架、抽象层、9 个算法、UT 全做完了，却只把第 1 项标成 in_progress。
⇒ 这 3 分钟的产出**确实**只能归到第 1 个子任务上——引擎没有信息把它拆开。

⇒ 这是**模型行为**，不是渲染 bug。要让它更贴合直觉，需要的是模型及时推进 todo
（工具契约 / 提示词层面），而不是渲染层猜测归属。本轮不做猜测式归属。

---

## 测试

| 文件 | 用例 | 内容 |
|---|---|---|
| `aethercode-core/.../QueryEngineSubTaskR391Test.java` | 7 | 文本改写⇒重发 Start（旧 status）；文本+status 同变⇒两者都发；文本变+转 in_progress⇒只发一条；声明式形状同样适用；列表未变⇒零事件；被删项⇒`removed` 摘要；删中间项⇒后续项上移并改标题 |
| `aethercode-core/.../QueryEngineSubTaskR391ReplayTest.java` | 2 | **用该 session 真实的 4 次 `todo_write`** 跑引擎 + 桌面端 reducer 的缩微版 ⇒ 断言最终 6 张卡片、标题全是当前措辞、无 `skipped` 卡片 |
| `aethercode-desktop/src/__tests__/subTaskAttributionR391.test.ts` | 6 | 4 条不变量 pin（归属取当前子任务 / 标题跟随计划 / 被删项移除卡片 / 孤儿 step 回落）+ 2 条 `groupStepsBySubTask` 行为测试 |
| `aethercode-desktop/src/__tests__/subTaskAttributionR391E2E.test.ts` | 5 | **驱动真实 store**：通过 `AetherCodeRpc` 的 handler Map 注入真实 daemon 事件序列（run_start → think → tool → sub_task_start → tool → think → …），断言 step 的归属、标题更新、卡片移除、孤儿 step 回落 |

- Java 定向：`BUILD SUCCESS`（既有 `QueryEngineSubTaskTest` 12 例、`QueryEngineSubTaskFlatTodoR740Test` 6 例全绿）。
- **Java 全量**：`BUILD SUCCESS`，24 模块，attempt 1，578 s。
- **desktop 全量**：131 文件 / **1430 例**全绿。

### 变异验证（证明这些测试真的在测东西）

| 变异 | 预期红 | 实测 |
|---|---|---|
| 把归属改回 `prev?.subTaskId ?? null`（R391 修复前的样子） | 归属类用例红 | **2 个红** ✓ |
| 把 `removedFromPlan` 改成 `false`（卡片不再被移除） | 移除类用例红 | **2 个红** ✓ |

两次变异都已**逐字恢复**并用 `git diff --stat` + 源文件行号核对过
（`store/index.ts:3159` 归属、`store/index.ts:3074` 移除）。

---

## 部署

已部署进运行中的桌面端（用户确认「现在部署」）：

| 产物 | 位置 | 标识 |
|---|---|---|
| 渲染层 exe | `dist/aethercode-green-r388/aethercode-desktop.exe` | SHA256 `8F1EEC87…`，6654464 bytes |
| daemon jar | `dist/aethercode-green-r388/resources/aethercode.jar` | 56935399 bytes |
| TUI 自带 jar | `dist/aethercode-green-r388/tui/aethercode-0.3.0.jar` | 同一份 |

旧件保留为 `*.bak-r391`（`*.bak-r390` 是 R390 之前的原件）。

### 部署时发现的一个**独立缺陷**（会话库跟着项目走）

daemon 的 `--sessions-dir` 是 `<cwd>/.aethercode/sessions`，而 `cwd` 来自
**持久化的「上次打开的项目」**（`~/.aethercode/desktop-state.json` → `lastCwd`）
—— 与进程启动目录无关（R390 的 Lesson 1430）。

⇒ 用户 12:38 把项目切到了 `D:\tmp\abc_1` ⇒ 重启后 daemon 的会话库指向
`D:\tmp\abc_1\.aethercode\sessions`（今天新建的空壳），
而**全部历史会话**（`61bfe997` 4783 msg、`26ee7d8d` 213 msg 等 6 个）
仍在 `RAG\.aethercode\sessions` ⇒ **切项目 = 会话列表整个消失**。

处置（保守，不删用户数据）：
1. 把 RAG 会话库的 6 个 transcript + 索引 + 3 个 per-session 目录**复制**到
   `abc_1\.aethercode\sessions`（RAG 那份原样保留）；
2. 把索引里 5 行的绝对 `raw_path` **改指到 abc_1**（否则 transcript 仍写回旧目录）；
3. 重启桌面端验证：`listSessions` 返回 `26ee7d8d`（213 msg、真实标题），
   耗时 **73 ms**；其余 5 个在**回收站**（`trashed_at` 非空，是用户自己在 13:29 清的，不是迁移造成的）。

⇒ **待办（未做，属下一轮）**：`--sessions-dir` 应与 cwd 解耦，
否则「切项目」会一直带走会话列表。

---

## 教训

**Lesson 1433 — 一个「快照」在建立它的时刻就已经过期了。**
`run_start` 给 step 盖的 `currentSubTaskId` 戳，在盖戳的那一刻必然是 null
（计划还没被声明）。一个从诞生起就是 null 的戳，**看起来**像归属信息，实际上是「没有归属」的永久烙印。

**Lesson 1434 — 归属要跟随「当前」，不是跟随「上一次」。**
step 继承上一个 step 的 owner，等于把一个**过期的快照**复制 N 次。
问「现在归谁」，而不是「刚才归谁」。

**Lesson 1435 — 位置 id + 只跟踪状态 = 标题冻结。**
当 id 是**位置**而实体是**内容**时，只跟踪状态不足以描述实体；
内容一变，你跟踪的那份描述就和现实脱节了。**id 的形状决定了还要跟踪什么。**

**Lesson 1436 — 「被删掉」和「失败」是两件事，渲染层不该混为一谈。**
daemon 用同一个 `SubTaskEnd` 承载两种语义（terminal status / 离开计划），
渲染层把两者都画成 “skipped”，于是用户看到一个与上下文无关的失败标记。
**同一个字段承载两种语义时，接收方必须按语义分流，而不是按字段分流。**

**Lesson 1437 — 两份手写的状态机，终会漂移。**
「显式 sub-tasks」与「合成 flat todo」各有一份 `prev == null / !prev.equals(status)` 判断，
只有其中一份在意文本变化。⇒ 合并成一份发射器，两种形状共用。

**Lesson 1438 — 卡片被删掉时，它的 step 不能跟着蒸发。**
把 step 按 `subTaskId` 分桶时，必须处理「桶的键已经不存在」的情况；
否则删卡片的副作用是**静默丢失产出**。

**Lesson 1439 — 渲染层不是万能：它不知道模型当时在想什么。**
模型没推进 todo，引擎就没有信息把 3 分钟的产出拆到 6 个子任务上。
**渲染层可以修「错误的呈现」，不能凭空造出「不存在的信息」。**
把这两件事分开，才能既修好渲染，又不去伪造归属。

**Lesson 1440 — 「切项目」不该带走会话列表。**
daemon 的会话库位置 = `<cwd>/.aethercode/sessions`，而 cwd 来自「上次打开的项目」。
⇒ 切项目 = 换一个会话库 = 旧会话整个消失。**存储位置与「你在哪个项目里工作」是两件事，
把它们绑在一起，就是把用户的上下文绑死在一次目录切换上。**
