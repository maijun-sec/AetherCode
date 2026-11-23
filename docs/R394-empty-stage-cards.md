# R394 — 空卡片不是阶段：聊天框不再把整份计划画成空标题

> 2026-11-23. 用户的原话（附截图）：
>
> > 我刚刚跑了个新的，又回去了。我跑初始任务的时候，显示已经优化了，
> > 但是我修改这个任务时，有变成这种分多个大阶段，然后只在第一个显示了，
> > 要不，就不要在对话框初始就显示全部的框架了
>
> 截图里，聊天区是：一大块正文（README 自检表 + 未验证项），
> 底下跟着**七个只有标题、没有内容的大标题**。

---

## 0. 结论先说

一张阶段卡片代表的是**这一段的产出**，不是一个计划项。
**没有产出的阶段不进聊天框** —— 计划在右侧面板的 **Plan** 标签页里
（`AgentTasksPanel`），聊天区不必再把整份框架重复画一遍。

阶段会**随着产出逐个出现**：先有产出的阶段先出现，没有产出的阶段不占位。

---

## 1. 现场：那块 DOM 长什么样

只读 CDP 探针（不碰 `ensure_daemon`）读运行中的桌面端：

```
total blocks=9
[0] cls=agent-message  preamble   len=1856
[1] cls=agent-message  preamble   len=13392 | h=["…","本轮改动","验证情况"]
[2] cls=agent-message  has-subtask | len=22 | h=["实现 SortOrder 枚举与排序方向参数"]
[3] cls=agent-message  has-subtask | len=41 | h=["新增 ByteArrayView / DescendingArrayView 视图"]
[4] cls=agent-message  has-subtask | len=52 | h=["ArraySorter / SortAlgorithm / Sorters 接入四类数组 × 升降序重载"]
[5] cls=agent-message  has-subtask | len=44 | h=["扩展测试支撑：PrimitiveOps.ByteOps、Views.of(byte[])"]
[6] cls=agent-message  has-subtask | len=34 | h=["新增 SortOrderContractTest 覆盖升序/降序契约"]
[7] cls=agent-message  has-subtask | len=30 | h=["更新 README（概览、快速开始、目录树、测试与实现要点）"]
[8] cls=agent-message  has-subtask | len=18 | h=["全量测试与 JaCoCo 覆盖率复核"]

blocks=9 | with heading=8 | heading-but-thin=7
```

**七张卡片，每一张都只有标题**（18–52 字符），而真正的产出（13392 字符）
全在**没有归属**的那一块里。

---

## 2. 为什么那七张卡是空的（不是渲染坏了，是**顺序**坏了）

读那个会话的 transcript（`ecce07c1-….jsonl`，511 行）：

```
last user msg at line 347 of 511
todo_write at line 508  -> {"todos":[{"content":"实现 SortOrder 枚…
```

★ 这一轮里模型只调了一次 `todo_write`，而且在 **line 508** ——
也就是**这一轮的最后**（7 项全部 `completed`）。

于是：

1. 所有工作步骤都是在**计划存在之前**打开的 ⇒ 没有任何一步能被归属到某个阶段
   （R391 的归属规则是「步骤归属到它被打开时正在跑的阶段」，那时候没有阶段在跑）；
2. 工作全部留在 preamble 桶里 ⇒ 那**一大块**正文；
3. `todo_write` 到达后，daemon 把这 7 个扁平 todo 合成为 7 张
   `auto-{idx}` 卡片（R740）⇒ **7 张一个 step 都没有的卡片**。

★ 为什么「初始任务」是好的 ★ 那一轮模型**先**声明计划再干活，
所以每一步都能归属到阶段 ⇒ 每个阶段有内容 ⇒ 卡片是「工作的容器」。
**区别不在渲染，在于计划声明的时机。**

---

## 3. 修法

`aethercode-desktop/src/components/MessageList.tsx`：

```ts
for (const st of subTasks) {
  const own = bySub[st.id] ?? [];
  const ts = own[0]?.startedAt ?? st.startedAt;
  const isLive = isStreaming && st.id === currentSubTaskId && !st.summary;
  // ★ R394: no output ⇒ no card. ★
  if (own.length === 0 && !isLive && st.status !== 'failed') continue;
  events.push({ kind: 'subtask', ts, subTask: st, steps: own, isLive });
}
```

两个例外，都是**不能藏**的东西：

| 情况 | 为什么仍然显示 |
|------|----------------|
| **正在跑**的阶段（live） | 流式光标要有地方待着；藏起来它会落在别的块上 |
| **失败**的阶段 | 用户看不见的失败，是用户没法处理的失败 |

`skipped` 的阶段仍然**不显示** —— R391 已经定了这条：模型从计划里删掉的项
不留卡片（而不是留一张「skipped」幽灵卡）。

### 3.1 ★ 顺带把时间线构建抽成了纯函数 ★

原来这段逻辑在一个 `useMemo` 里，没法直接测。R394 把它抽成导出的
`buildTimeline(messages, steps, subTasks, currentSubTaskId, isStreaming)`，
组件里只调它。

> 这是 R391 已经吃过一次亏的地方：**规则要能直接测**，
> 而不是只能靠在源码里找字符串。

---

## 4. 测试

| 文件 | 例数 | 覆盖 |
|------|------|------|
| `src/__tests__/subTaskStagesR394.test.ts` | 7 | **真实形状**（一大块工作 + 7 张空卡 ⇒ 只剩 1 块）/ 有产出的阶段仍显示 / live 阶段仍显示 / failed 阶段仍显示 / skipped 阶段仍隐藏 / 阶段随产出逐个出现 / 用户气泡仍在 |

### 4.1 变异验证

把规则短路掉（`if (false && …) continue`）：

```
7 tests | 3 failed | 4 passed
```

红的 3 条：

```
the real shape — one work blob + seven empty stages — renders ONE block
a SKIPPED stage stays hidden — R391 already decided that
the stages reveal themselves AS they produce output
```

**理应不红**的 4 条（护栏，不是漏网）：有产出的阶段 / live 阶段 / failed 阶段 /
用户气泡与正文仍在时间线里。

---

## 5. ★ 顺带修掉一条「按位置 pin」的旧断言 ★

`src/store/queryIdR278.test.ts` 第三条原来是**字符串切片 pin**：
它在 `MessageList.tsx` 里从 `const timeline = useMemo` 切到 deps 列表，
然后要求切出来的片段里含有 `preByQuery` 那段分组代码。

R394 把时间线搬进了 `buildTimeline`，切片里就没有那段代码了 ⇒ **这条 pin 变红** ——
**在一个让代码变好的重构上变红**（R384 的 Lesson 1233：
**pin 位置会在重构上误红，pin 不变量才不会**）。

所以它被改成**行为测试**：两个 prompt、两个 preamble 块、各自的时间戳，
并断言顺序是 `user, preamble, user, preamble`（这才是「第二个 prompt 漏进内容」
那个 bug 的修复本身）。

并对这条新 pin 做了变异：把分组退回单桶 ⇒ **恰好 1 红**（另 2 条是无关的
源码 pin，理应不红）。

### 5.1 ★ 真机 A/B（同一句提示、同一个项目） ★

单元测试证明规则，但「模型到底会不会在最后写计划」不是单测能造的。
所以在**真机上**跑了同一句提示（6 步真任务，明确要求用 `todo_write` 跟踪），
两个版本各跑一次，读**真 DOM**：

| 版本 | stageCards | 其中**空卡** | 看到的形状 |
|------|-----------|-------------|-----------|
| 旧版（R393，无 R394） | **6** | **5** | 6 个大标题，只有 1 个下面有内容 —— **和用户截图一模一样** |
| 新版（R394） | **1** | **0** | 只有拿到产出的那 1 张阶段卡 |

⇒ 同一句提示、同一批计划项：**空的那 5 张不再画**。

（读数脚本：`.aethercode/r394-live-repro2.mjs`；形状读数脚本：
`.aethercode/r394-cancel.mjs`。）

---

## 6. ★ 顺带撞到的一个**别的**缺陷（未修，需要你拍板） ★

部署 R394 时撞到：**桌面端起来了，但 daemon 没了**，界面显示 `● Disconnected`。

查 `%TEMP%\aethercode-daemon-port22888.log`：daemon 正常启动、装配到
`task push attached`，然后**没有任何异常地消失**。

根因在桌面端（`aethercode-desktop/src-tauri/src/lib.rs`）：

```rust
// DAEMON_HEALTH_TIMEOUT_MS = 15s
while Instant::now() < deadline {
    if is_healthy(port).await { return Ok(child); }
    ...
}
let _ = child.kill();          // ← 15 秒还没健康，就杀掉
Err(format!("port {}: never came up", port))
```

**15 秒的健康窗口**在机器有负载时不够：当时你的 Plumb 工程正在跑
`mvn package` + surefire，daemon 从 spawn 到 `/healthz` 响应超过了 15 秒，
于是桌面端**把它杀了** —— 而这个失败对用户是**静默的**（界面只显示
"Disconnected"，日志里也只有一行 spawn banner）。

★ 建议（**我没有动**）★
① 把 `DAEMON_HEALTH_TIMEOUT_MS` 提到 60s；
② 杀掉 daemon 之后，桌面端要**说人话**（「daemon 启动超时」而不是
「Disconnected」）；
③ 更根本的：桌面端起不来 daemon 时**不该假装连上了**。

---

## 7. 教训

**Lesson 1456 — 一张卡片代表的是「那一段的产出」，不是一个计划项。**
没有产出的阶段不进聊天；计划有它自己的家（右侧 Plan 标签页）。
★ 一个元素该不该出现在屏幕上，取决于**它代表什么**，不取决于**它被声明了没有**。

**Lesson 1457 — 归属的前提是「计划先于产出」。**
模型在**最后**才写计划 ⇒ 产出早于计划 ⇒ 无产出可归属 ⇒ 阶段只能是空壳。
⇒ 这类「顺序型缺陷」不能靠渲染层修好，只能靠**规则**修（空壳不画）；
但**必须能区分**「这个阶段真的没干活」和「这个阶段只是还没被归属」。

**Lesson 1458 — 「更好的重构」会让按位置 pin 的断言变红。**
R278 那条 pin 切的是源码片段，R394 把逻辑搬进导出函数，它就红了 ——
**它红得毫无道理**（行为没变，而且变得可测了）。
⇒ 规则一旦可测，就把 pin 换成**行为断言**：那既不会因重构误红，
也不会像字符串 pin 那样「在源码里找得到就算过」。

**Lesson 1459 — 「杀掉还没就绪的子进程」是一条静默的失败路径。**
桌面端给 daemon 15 秒健康窗口，超时就 `child.kill()` —— 用户只看到
`● Disconnected`，日志里只有一行 spawn banner，没有任何错误。
★ 一个**超时**如果不告诉用户，它和「没启动」在用户眼里是同一件事。
⇒ 超时要有话说；而且这个超时值要按**最坏负载**来定，不是按空闲机器。

**Lesson 1460 — 单测证明规则，真机证明「模型会不会那样做」。**
「模型在**最后**才写计划」这件事，单测造不出来（假客户端不写 todo）。
所以 R394 的最终证据是**真机 A/B**：同一句提示，两个版本，
真 DOM 读数 6 张卡 / 5 张空 ⇒ 1 张卡 / 0 张空。
