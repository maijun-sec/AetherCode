# R375 — 让运行控制面真的能用：三个动词，三个真正存在的 daemon 方法

> 轮次：R375 · 日期：2026-10-24 · 分支：`main`
> 上一轮：[R374 — `/update` 与 `/update-deps` 的三个诚实动词](R374-update-panel.md)

---

## 1. 缺陷：三个失败叠在一起

状态栏上的 Continue / Pause / Stop 三个按钮，和 `/pause` `/stop` `/continue`
三条斜杠命令，打的都是：

```ts
void client.request("task/resume", { id: state.sessionId });
void client.request("task/pause",  { id: state.sessionId });
void client.request("task/kill",   { id: state.sessionId });
```

**没有任何 `.catch`** —— `-32601` 变成一个未处理的 rejection，按钮**什么都不做**。

三个失败是叠在一起的，任何一个单独修都不够：

| # | 失败 | 证据 |
|---|------|------|
| 1 | **方法在这个 daemon 上不存在** | `TaskMethods`（aethercode-protocol）确实声明了 `task/resume` / `task/kill`，但它是一个**代理到独立 supervisor 进程**（`SupervisorClient`，自带 TCP loopback socket）的适配器，生产代码从不构造它；`task/pause` 连它的 12 个方法列表都没进（只有 `TaskPauseParams` 这个 DTO） | `aethercode-protocol/.../methods/TaskMethods.java`；`aethercode-cli/.../DaemonRunner.java` 里没有任何 `TaskMethods` |
| 2 | **参数是另一种 id** | 需要 task id，传的是 **session id** | 同上 |
| 3 | **拒绝被吞掉** | `void client.request(...)` 没有 `.catch` | 同上 |

R369 的诚实拒绝表还帮了倒忙：它告诉用户"the daemon has no task registry, so
there is no running task to pause" —— **这句话是假的**。daemon **有**任务注册表
（`TaskRegistry`，在 `listTasks` / `updateTaskStatus` 后面）；它没有的是
**supervisor**。而且用户真正想要的两个能力 —— 停掉正在跑的这一轮、暂停本会话的
自动续跑 —— **daemon 上本来就有**。

本地基准报告在 2026-10-21 就已经点名了这个缺陷：

```
- `/stop` 打的是 `task/kill`（长任务控制），不是"停当前轮" [SRC:src/commands.ts:64]
- `/continue` `/pause` 同属 `task/*` 家族 [SRC:src/commands.ts:62-64]
```

> `D:\tmp\agent-experience-benchmark\agent-experience-gap-analysis-2026-10-21.md:292-293`

---

## 2. daemon 上真正有的能力

| 能力 | 方法 | 注册点 | 传输层 |
|------|------|--------|--------|
| 停掉正在跑的这一轮 | `cancel {runId}` | `AetherCodeMethods.registerAll` → `dispatcher.register("cancel", …)` | stdio dispatcher **和** `HttpJsonRpcServer` 的 `case "cancel" ->` |
| 停掉本会话的自动续跑 | `setContinuationStopped {stopped, sessionId?}` | `dispatcher.register("setContinuationStopped", this::setContinuationStopped)` | 同上，两个传输都路由 |

`setContinuationStopped` 的三个默认行为是 TUI **依赖**的契约，R375 全部钉住了：

1. `sessionId` 空白 → 回落到 `engine.appState().sessionId()`（当前会话）。
   —— 这就是 TUI **故意不传 sessionId** 的原因：行渲染器在 daemon 第一次
   `getState` 之前，session id 一直是占位符 `"—"`，而 daemon 的空白检查
   **抓不到** `"—"`，会老老实实给一个叫 `"—"` 的会话设标志。
2. `stopped` 缺失 → 默认 **`true`**。—— 所以 "continue" 必须**显式**发
   `stopped:false`；什么都不发等于**暂停**。
3. `setContinuationStopped(sid, true)` 会**同时取消待触发的倒计时**，所以
   暂停是立刻生效的，不必等那 2 秒窗口。

还有一条对称性：daemon 在**用户下一次提交提示**时会清掉自己的标志
（`AetherCodeMethods.query` → `continuationDispatcher.setContinuationStopped(_targetSid, false)`），
所以 TUI 的本地镜像**在同一个时刻**清掉 —— 两边不会漂移。

---

## 3. 新的映射：一个动词，一个方法

| 动作 | daemon 方法 | 含义 |
|------|-------------|------|
| **stop** | `cancel {runId}` | 打断当前这一轮（R721 硬取消：thread interrupt + destroyForcibly + 5s Thread.stop 兜底） |
| **pause** | `setContinuationStopped {stopped:true}` | 停掉本会话的**自动续跑** |
| **continue** | `setContinuationStopped {stopped:false}` | 让自动续跑恢复 |

### 为什么"Pause"是这个意思

"Pause" 在业界就是**会话级暂停**：会话保留状态，**自动的那部分**停下来。
Claude Code 在用量限额时"留在会话里等重置并自动继续"，界面上是
`Continuing automatically at 3:45pm · esc to cancel` —— 那个"停掉自动续跑"
的能力，正是 daemon 的 `setContinuationStopped`。
> `D:\tmp\agent-experience-benchmark\agent-experience-gap-analysis-2026-10-21.md:299`

而 "Stop" 是**打断正在跑的这一轮**，这正是 Esc 早就在做的事（R363）——
所以按钮和按键现在**共用一个实现**，而不是两套会漂移的实现。

### 一个实现，三个面

R375 之前有**两套**实现（斜杠命令一套、按钮一套），而且按钮那套指向一个
不存在的方法。现在：

- `src/runControl.ts`（新）—— 唯一的词汇表：动词 → 方法 → 参数 → 文案。
- `src/tui.tsx` —— `stopActiveRun()` / `pauseAutoContinue()`，各一份。
- `src/line.ts` —— 同名同职责，各一份。
- `src/components/StatusBar.tsx` —— 三个按钮调的就是上面那两个函数。
- `src/commands.ts` —— **只负责说方法名**，run id 由宿主（知道当前 run 的那一层）补。

`src/runControl.ts` 里还住着 `runControlState(status, paused)` —— 之前
`StatusBar.tsx` 有一份私有的 `mapSessionState`，那是"这个会话暂停了吗"的
**第二个答案**，正是本轮要消灭的东西。

### 文案规则

- 成功**不越过 daemon 已经确认的事**：stop 的成功文案是 "stopping run X…"，
  不是 "stopped" —— 真正确认它停下来的，是这一轮自己的 `run_end`。
- 失败**必须说原因**：不能只说 "failed"，用户得知道是 -32601 还是超时。
- **"没有可停的 run"不是失败**："nothing broke — there was simply no run"，
  所以文案是 `no run is in flight (the daemon did not report a run id)`，
  两个渲染器共用同一句（`runNothingInFlight()`）。
- **幂等就直说**：再点一次"暂停"不发请求，而是回一句
  `auto-continue is already paused for this session`。

---

## 4. 一个之前没人发现的死 UI

`SessionControl.validActions(state)`：

```ts
case "running":   return new Set(["pause", "stop"]);   // ← 没有 continue
case "paused":    return new Set(["continue", "stop"]);
```

而 `mapSessionState()` 之前**永远返回 "running"**（TUI 的 status 字符串里没有
"paused" 这个值）。结论：**Continue 按钮是永久灰的**。

也就是说，即使 `/pause` 当时能工作，用户也没有任何办法回到"已暂停"状态去点
Continue —— 一个自锁的闭环。本轮补上 `continuationPaused` 这个本地镜像之后，
链路才第一次打通：

```
runControlState("running", paused=true) → "paused"
  → validActions("paused") → {continue, stop}
  → Continue 可点
```

R375 的门禁 **D1** 直接跑这条链：`validActions(runControlState("running", true))`
必须包含 `continue`；**D2** 钉住反面（running 时不能有 continue），免得 D1
靠"全都允许"蒙混过关。

---

## 5. 门禁

### 新增：`scripts/test/r375-run-control.test.mjs`（29 个测试）

分四层，因为**任何一层的缺陷对其它层都是不可见的**：

| 层 | 覆盖 | 断言 |
|----|------|------|
| **A** | daemon 注册点 | 两个传输都注册/路由；`task/*` 确实没被 `DaemonRunner` 接线；`TaskMethods` 确实是 supervisor 代理且没有 `task/pause`；三个默认行为（当前会话回落 / `stopped` 默认 true / 暂停立刻取消倒计时）；新提示清标志 |
| **B** | 共享词汇表（编译后**运行**） | 动词 → 方法；参数形状（stop 必带 runId、pause/continue **不带** sessionId）；文案（成功不越权、失败说原因、幂等直说、"没有 run"不是失败）；状态映射 |
| **C** | 三个面共用一个实现 | 两个渲染器各只有**一份** `stopActiveRun` / `pauseAutoContinue`；状态栏按钮的 props 指向那两个函数；命令表只说方法名；**没有任何源文件再命名 `task/*`** |
| **D** | Continue 到底可不可点 | 端到端跑 `validActions(runControlState(...))` |

外加 E（本地镜像：reducer 记录 + 提交时清除 + **切换会话时清除**，含**第二条独立
推导**的源码钉）和 F（失败必须可见：catch → 错误 toast / 红字）。

> 镜像的清除时机必须和 daemon 一样：daemon 在**用户下一次提交提示**时清自己的
> 标志，所以 TUI 在 `submit` 时清；而这个标志是**按会话**的，所以
> `adoptSession`（切换会话）也必须清 —— 否则状态栏会对着一个**从没被设过标志**
> 的新会话说"已暂停"。

### 新增：`scripts/test/r375-mutate.mjs`（38 个用例，全部抓住）

变异分四类：方法名、参数、文案、接线；外加 daemon 侧的 7 个（注册、传输、
两个默认值、倒计时、清除标志、supervisor 接线）。

**6 个 GATE 变异**证明每个关键性质都有**第二条独立推导**：

| 变异 | 谁抓住了 |
|------|---------|
| 中和"动词→方法"断言 + 把 stop 指向 `task/kill` | C7（不可服务方法扫描） |
| 中和"不可服务方法扫描" + 同样指向 `task/kill` | B1（动词→方法） |
| 中和"paused 状态"断言 + 删掉 paused 分支 | D1（Continue 可达性） |
| 中和"Continue 可达性"断言 + 删掉 paused 分支 | B6（状态映射） |
| 中和"按钮接线"断言 + Pause 按钮打 `task/pause` | C7 |
| 中和 E2 的行为断言 + submit 不清镜像 | E2 的源码钉 |

> 这就是 R374 的 Lesson 1177：**一个性质只有一条推导时，那条断言可以被悄无声息地废掉。**

### 改写的三个旧门禁（不是改绿，是改成新真相）

| 门禁 | 原来钉的 | 现在钉的 |
|------|---------|---------|
| `r363-cancel.test.mjs` | `cancelActiveRun` 这个名字 | `stopActiveRun`（R375 改名）+ `RUN_STOP_METHOD`（方法名本身由 R375 门禁钉住） |
| `phase-6-t6-17.test.mjs` | `/continue` **拒绝** `task/resume` | `/continue` → `setContinuationStopped {stopped:false}`；`/pause` → `{stopped:true}`；`/stop` → `cancel` |
| `r369-honest-commands.test.mjs` | 5 条拒绝项（含 3 条 `task/*`） | 2 条真正缺失的（`rewind` / `session/tokens`）+ **新增**："TUI 不得命名 daemon 服务不了的方法" |

> `phase-6-t6-17` 原来那两条断言**保护的是"这条命令已知是坏的"** ——
> /help 还在宣传它，而诚实的拒绝比一个能用的命令更糟。R375 把它变成能用的命令，
> 断言也就变成钉"能用的形状"。

### 顺手修掉的一个历史欠账

`phase-6-t6-17` 的补全断言从 **R371** 起就是红的：R371 加了 `/context`，
但断言里的 `/con` 候选列表没跟着更新。它一直混在 46 条基线失败里。
R375 把它改成 `["consents", "context", "continue"]`，基线因此 **46 → 45**。

---

## 6. 验证

| 项 | 结果 |
|----|------|
| `tsc --noEmit`（项目，`strict: true`） | 0 错 |
| 门禁式 `tsc … src/tui.tsx`（不带 tsconfig，`strictNullChecks: false`） | 0 错 |
| `r375-run-control.test.mjs` | **29 / 29** |
| `r375-mutate.mjs` | **38 / 38 抓住**，0 个 no-op |
| r362–r375 全部 **12** 个变异电池 | 全绿 |
| 全量 `npm test` | **1342 / 1297 / 45** |

### 基线对账

R374 的基线是 **1312 / 1266 / 46**。R375 应该是：

- 测试数 **+30**（`r375-run-control` 29 个 + `r369` 新增的那 1 个）；
- 失败数 **46 − 1 = 45**（修掉了 `T-6-17: tab-completion`）；
- 通过数 1266 + 30 + 1 = **1297**。

实测正是 **1342 / 1297 / 45**。逐条对账（把 R374 的干净检出跑一遍全量，
把失败**名字**抓下来再比）：

| | 名字 |
|---|---|
| 新增失败 | **0 条** |
| 不再失败 | `T-6-17: tab-completion`（R371 留下的漂移，本轮修掉） |
| 不再失败 | `R33: dist/ac-tui.js is fresh` / `R35: dist/ac-tui.js is fresh…`（这两条只在 worktree 里失败——它没有 `dist/`，是**检出方式的产物**，不是 R375 的功劳） |

第一次跑出来是 **48**，多出的 3 条是下一节那个事故，不是产品缺陷。

### 基线对账方法

R374 的 46 条失败里，有一条（`T-6-17: tab-completion`）正好是 R375 要动的
文件，而剩下的失败里有一批是 StatusBar 的源码钉。为了不靠"我记得"来判断，
R375 用 `git worktree` 拉了一份 **R374 的干净检出**（`D:\tmp\aethercode-r374-baseline`），
在**同一台机器、同一份 node_modules**下跑了一遍全量，把 46 条失败的名字抓下来，
再和 R375 的结果**逐条对账**。

### 对账时抓到的一个真事故：被杀的变异电池

第一次全量对账时，R375 自己的门禁红了 3 条（B1 / C7 / r369 的同名扫描）。
原因不是产品坏了 —— 是**上一条命令把变异电池掐死了**：

```powershell
node scripts/test/r375-mutate.mjs 2>&1 | Select-Object -First 2   # ← 元凶
```

`Select-Object -First N` 会**停掉上游进程**（Windows 上是直接杀）。电池死在
第一个用例中间，`restore` 没跑完，`src/runControl.ts` 里留下了
`RUN_STOP_METHOD = "task/kill"` —— 接下来的 `npm test` 读到的就是一个
**故意被改坏的产品**，三条失败属于"谁都不负责"。

这暴露了一个**结构性问题**，不是一次手滑：

> **变异电池把产品源码改在原地，它被杀掉就会把变异留在树上。**
> 而 `git checkout --` 不能用来恢复（它回到 HEAD，会吞掉所有未提交的修复，
> Lesson 1171 已经咬过这个仓库四次）。

R375 因此加了一层**共享护栏**（`scripts/test/mutation-snapshot.mjs`），
**12 个电池全部接上**：

1. 第一次改动之前，把每个可能被改的文件的原始字节写进一个 snapshot 文件；
2. 电池**启动时**如果发现 snapshot 存在，**先从它恢复**，再读基线；
3. 跑完清掉 snapshot。

关键在于第 2 步发生在**读基线之前** —— 否则"原始内容"就是被改坏的那份，
电池会把变异**固化成基线**。而用 snapshot 文件而不是
`process.on("exit")`，是因为**被直接杀的进程一个 handler 都不会跑**。

护栏本身被**真实验证**过：真的启动一个电池、4 秒后杀掉它、确认树上留下了
snapshot、然后重跑同一个电池 —— 它打印
`recovered 12 file(s) from an interrupted mutation run` 并把树恢复原状。
另外还验证了更狠的情形：**树已经脏了**再跑电池，它先自愈，
而不是把 `"task/kill"` 当成基线。

**推论：任何"在原地改源码"的工具，都必须能在被杀之后自愈。**
它的失败模式（留下一个半成品的世界）比它本身的失败更贵。

---

## 7. 本轮不做的事（明确的边界）

- **`todo_continuation_countdown` 通知**：daemon 会推一个带"Stop auto-continue"
  按钮的倒计时通知，TUI 至今**整个丢掉**。渲染它是独立的 UI 设计任务，
  不塞进本轮。
- **`TaskPanel.tsx` / `src/rpc/queries.ts` / `src/rpc.ts`**：仍然在注释和死代码里
  提到 `task/*`。它们**没有被挂载**、**没有被引用**，r366 门禁也不扫它们。
  记为后续项。
- **`agent/setDefault` / `effort/set` / `rewind` / `session/tokens`**：这 4 条
  `BACKEND_MISSING` 仍然缺真正的 daemon RPC，本轮不动。
- **视觉验收**：80 列、真实 Ink 交互下三个按钮的实际观感，仍然没有自动化。

---

## 8. 教训

**Lesson 1180 — 一个"已知是坏"的断言，锁住的是坏的现状，不是好的契约。**
`phase-6-t6-17` 断言 `/continue` 会拒绝 `task/resume`，理由是"这个方法不
存在"。这条断言是绿的，而产品是坏的。诚实拒绝是**比坏掉更好的产品**时才会
这么做 —— 如果后端其实**有**对应能力、只是名字错了或者概念错了，那么正确
的动作是**去把命令接对**，不是把"接不对"写进断言里。问的问题应该是
"这个能力到底存在吗"，而不是"这个方法名存在吗"。

**Lesson 1181 — 拒绝表能说"方法不存在"，说不了"按钮是坏的"。**
R369 的 `BACKEND_MISSING` 表是一份**方法清单**，不是一份**按钮健康度**清单。
把一个坏按钮登记成"缺方法"，等于把缺陷归档成了知识。本轮把三个 `task/*`
条目删掉，改成一条**结构性不变量**：任何能发 RPC 的源文件都不得再命名一个
daemon 服务不了的方法。

**Lesson 1182 — 死 UI 的成因可能在下游，不在上游。**
`/pause` 打错方法名，看起来是"接线错了"。真正的死锁在
`SessionControl.validActions("running")` 不含 `continue`，而
`mapSessionState()` 永远返回 "running"。**一个不可达的状态让另一个修复
完全无效。** 所以门禁要端到端跑那条链（`validActions(runControlState(...))`），
而不是分别断言两个部件。

**Lesson 1183 — 门禁断言"名字"会漏掉"改名"这件事。**
R375 把 `cancelActiveRun` 改名为 `stopActiveRun`，`r363-cancel` 的 6 条断言
当场变红 —— 它们钉的是**名字**，不是**契约**。要么改断言去钉契约（本轮：
钉 `RUN_STOP_METHOD` 这个共享常量，方法名本身由新门禁钉住），要么别改名。
**改名和改断言是同一个动作。**

**Lesson 1184 — 变异插注释，门禁看不见，也**应该**看不见。**
"A3: daemon 没有接线 TaskMethods" 这条断言读的是**去注释后**的源码。往
`DaemonRunner.java` 里插一行 `// TaskMethods ...` 注释，变异"生效"了（文本
变了）而门禁纹丝不动 —— 因为注释不是接线。变异用例必须插**真的代码**。
这条差点被当成"门禁有洞"，其实是**门禁对**。

**Lesson 1185 — 失败文案不能顺着失败路径长。**
"没有可停的 run"如果复用失败分支，就会变成 "stop failed: no run is in
flight" —— 用户读到的是"出错了"，而实际上什么都没发生。两个渲染器各自
写文案时，这个偏差立刻出现。共享文案模块（`runNothingInFlight()`）是
唯一可靠的解法。

**Lesson 1186 — 本地镜像的清除时机，必须和 daemon 的**同一个**时刻。**
daemon 在用户下一次提交提示时清自己的标志，镜像就在 `submit` 时清；
daemon 的标志是**按会话**的，镜像就在 `adoptSession` 时清。少清一个地方，
状态栏就会对着一个"从没被设过标志"的会话说"已暂停"。

**推论：镜像类状态要问三个问题** —— 它在**哪里**被设（daemon 哪条路径）、
在**哪里**被清（daemon 哪条路径）、**归属**是谁（会话 / 全局 / 本地）。
三个问题各有一个答案，镜像才不会漂移。

**Lesson 1187 — 任何"在原地改源码"的工具，都必须能在被杀之后自愈。**
变异电池把产品源码改在原地；它被杀掉（`| Select-Object -First N` 会**停掉
上游进程**）就会把变异留在树上，下一次 `npm test` 读到一个**故意被改坏的
产品**，三条失败属于谁都**不负责**。`git checkout --` 不能用来兜底（回到 HEAD，
会吞掉未提交的修复，Lesson 1171）。

**修法不是"小心一点"，是结构性的**：改动前把原始字节写进 snapshot 文件，
电池**启动时**先从 snapshot 恢复**再读基线**（顺序反了就会把变异固化成
基线），跑完清掉。用 snapshot 文件而不是 `process.on("exit")`，是因为
**被直接杀的进程一个 handler 都不会跑**。

**推论：护栏本身要被真实验证。**
"真的启动一个电池 → 杀掉 → 确认树脏了 → 重跑 → 确认它自愈"，
以及更狠的"树已经脏了 → 跑电池 → 确认它先自愈而不是把脏内容当基线"。
两件事都做过，才算这个护栏存在。
