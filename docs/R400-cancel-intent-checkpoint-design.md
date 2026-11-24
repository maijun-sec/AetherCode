# R400 — 取消、意图路由与 checkpoint：现状盘点与设计

> **状态：R400（取消）已实施并验证；R401（意图路由）/ R402（checkpoint 菜单）/
> R403（checkpoint 落盘）待实施。**
>
> 本文的前两节描述的是**实施前**的现状，证据索引里的行号对应 R399 的树；
> 改动后的位置见每节末尾的「R400 落地」小节。
>
> **触发**：用户报告两个致命问题 ——（1）点了停止按钮取消不生效，隔一会儿自己又继续跑；
> （2）一个概念性问题被当成代码任务，生成了一堆莫名其妙的代码。并进一步追问
> checkpoint / 状态持久化 / hook / 意图识别是否具备、如何设计。
>
> **用户已拍板**（见 §5）：概念问题默认判 RESEARCH、profile 用「收窄工具表」而不是 deny、
> checkpoint 能力放在 daemon、入口放在 UI。

---

## 0. 一页结论

| 主题 | 结论 |
|---|---|
| **取消为什么没用** | 桌面端 `rpc.cancel()` **不带 runId**，daemon 强制要求 runId ⇒ 回 `-32602`，而桌面端 `catch {}` 把它吞了。引擎的四层硬取消**一次都没执行**。 |
| **为什么"自己又继续跑"** | 不是独立 bug，是取消失效的**次生结果**：run 没停 ⇒ stopReason 不是 `cancelled` ⇒ `TodoContinuationHook` 的 `isDeterministicHardStopOf` 闸门放行 ⇒ 2 秒后自动续跑。桌面端既不处理倒计时通知，也没有叫停续跑的接口。 |
| **checkpoint 有没有** | **有，而且规格对齐 claude code**：`checkpoints/list` / `checkpoints/restore`（代码 / 对话 / 两者）/ `checkpoints/summarize` + `rewind`，daemon 侧全接好。但 —— **纯内存**（daemon 重启全丢）、**只有 TUI 有菜单**（桌面端零引用）、**只有 turn 边界一个打点**（中途打断不产生 checkpoint）。 |
| **意图识别有没有** | **没有**。但 AetherCode 已经有两个几乎现成的零件：`SubagentRole.RolePreset`（= systemPrompt + allowedTools + deniedTools，且 `filterTools` 是**结构性裁剪** —— 模型根本看不到被禁的工具）和 `buildSystemForTurn` 的 per-turn 指令槽（R737 的语言指令已经把这套机制跑通了）。 |
| **hook 契约** | 有三套并存，其中 `aethercode-code` 那套最完整但**是孤岛**；`aethercode-hooks` 的 `USER_PROMPT_SUBMIT` / `STOP` 是**死枚举**（引擎从不触发 ⇒ `settings.json` 里写了不生效）。 |
| **workflow** | 两套系统并存，daemon 只跑 core 那套。**所有现有路由面都是「用户显式选择」驱动的，没有任何一个是 prompt 内容驱动的。** |

---

## 1. 取消链路（问题 1）

### 1.1 断点：四连

```
① aethercode-desktop/src/lib/methods.ts:739
     cancel(): Promise<{ok:true}> { return this.call('cancel'); }   ← 不传任何参数
② aethercode/aethercode-protocol/.../AetherCodeMethods.java:5322
     String runId = stringOrThrow(p, "runId");                      ← 强制要求
③ AetherCodeMethods.java:9193-9197
     if (v == null) throw invalidParams("missing required string field: " + key);
④ aethercode-desktop/src/store/index.ts:5594
     try { await rpc.cancel(); } catch {}                           ← 静默吞掉
```

**实机验证**（打正在运行的 daemon）：

```
$ node .aethercode/r400-probe.mjs cancel-noparams
--- cancel with NO params (what the desktop sends) ---
{ "error": { "code": -32602,
             "message": "Invalid params: missing required string field: runId" } }
--- cancel with a bogus runId (shape the daemon wants) ---
{ "result": { "cancelled": false, "reason": "no such runId" } }
```

**为什么桌面端没有 runId 可发**：

- daemon 的 `query` 实际返回 `{runId, accepted}`（`AetherCodeMethods.java:4426-4429`）；
- 桌面端 `lib/methods.ts:738` 的类型写的是 `{ok:true; messageId:string}` —— **类型里就没有 runId**，是过时的声明；
- `store/index.ts:5473` `await rpc.query(input, {...})` 的返回值**被直接丢弃**；
- store 里不存在 `activeRunId` 之类的状态。

引擎侧是好的：`QueryEngine.java:2270-2272` 的 `Thread.interrupted()` 检查点、
`stopReason = "cancelled"`、`RunEnd("cancelled")` 全部就位 —— 只是永远收不到那个 interrupt。

R721 的四层硬取消（`AetherCodeMethods.java:5341-5434`：future flag → `thread.interrupt()`
→ `ProcessHandle.destroyForcibly()` → 5 秒 `Thread.stop()` 兜底）**全部在参数校验之后**，一次都没执行。

### 1.2 续跑链（次生结果）

```
AetherCodeMethods.java:4312-4316  appState.fireSessionIdle(idleEv)
TodoContinuationHook.java:237     onSessionIdle(sessionId, runId, stopReason, todoList)
TodoContinuationHook.java:272     filterIncomplete(todoList)          ← 还有未完成 todo 才继续
TodoContinuationHook.java:284     isContinuationStopped(sessionId)    ← 桌面端从不来叫停 ⇒ 永远 false
TodoContinuationHook.java:393-398 isDeterministicHardStop(sr)         ← 拦 'cancelled' / 'loop_detected' / 'same_error_hard_stop'
TodoContinuationHook.java:464     scheduler.schedule(..., 2, SECONDS) ← 2 秒倒计时
TodoContinuationHook.java:504     dispatchContinuation(...)
EngineContinuationDispatcher:110   tryAcquireSessionLockForContinuation(sessionId)
EngineContinuationDispatcher:155   engine.query(prompt)               ← 真正重新发起 LLM 调用
```

**桌面端在这条链上缺三样**（全仓 0 命中）：

| 缺什么 | 后果 |
|---|---|
| 不处理 `todo_continuation_countdown` 通知 | 2 秒窗口里**没有任何提示**，用户不知道续跑要来了 |
| 从不调用 `setContinuationStopped` | **没有任何办法叫停续跑** |
| `cancel` 失败被 `catch {}` 吞掉 | 用户**看不到任何失败信号** |

### 1.3 桌面端 vs TUI 对照

| 维度 | 桌面端 | TUI |
|---|---|---|
| 存 runId | **完全没有** | `state.activeRunId`（`state.ts:446`） |
| runId 来源 | 无 | query 响应 + `stream_event` 通知 + `ask_permission` 通知（三个来源） |
| cancel 参数 | `{}` | `{runId}` |
| daemon 结果 | `-32602`，被吞 | 命中 `inFlight`，四层硬取消全执行 |
| 失败可见性 | 静默 | toast：`stop failed: <reason>` |
| run_end 的 stopReason | 自然结束原因 | `cancelled` |
| 倒计时通知 | 不处理 | `Continuing automatically at … · esc to cancel` |
| 叫停续跑 | 无 | Esc → `setContinuationStopped{stopped:true}` |
| 取消后台 subagent | `subagentCancel{jobId}` ✅ | ✅ |

**唯一的破窗**：后台 subagent 的取消是好的 —— 因为它**带 jobId**。这直接证明修复方向是
「把标识带上」，而不是「引擎不行」。

### 1.4 设计

**daemon 侧（`AetherCodeMethods.cancel`）**

1. `runId` 由**必填改为可选**。缺省时的回落顺序：
   `sessionId` 参数 → engine 当前会话（`engine.appState().sessionId()`）→ 该会话在
   `sessionRunLock`（`AetherCodeMethods.java:566`，`Map<sessionId, runId>`）里登记的当前 run。
   找不到 ⇒ 保持现有 `{cancelled:false, reason:"no such runId"}`（向后兼容）。
   > 契约问题需要拍板：**「cancel 的默认目标」是「该会话当前 run」还是「该客户端最近一次
   > query 的 run」**。前者 daemon 能自己回答（`sessionRunLock`），后者要客户端配合。
   > 建议前者 —— daemon 是归属方，它知道谁在跑。
2. cancel 成功后**顺带停掉自动续跑**：`setContinuationStopped(sid, true)` +
   `cancelPendingContinuationCountdown(sid)`。语义 =「按停止 = 停一切」。
   下一次用户 prompt 会由 `AetherCodeMethods.java:3983-3990` 的既有逻辑把标志复位。
   > 这条**必须放在 daemon 侧**：桌面端若分两次 RPC 调用，2 秒倒计时可能在两次之间开火。

**桌面端侧**

3. `lib/methods.ts`：`cancel(opts?: {runId?: string; sessionId?: string})`；
   同时修正 `query()` 的过时返回类型（`{ok, messageId}` → `{runId, accepted}`）。
4. `store/index.ts`：`sendMessage` 里把 `r.runId` 存进 store（新增 `activeRunId`）；
   `cancelQuery` 带 `{runId, sessionId}`；**取消失败不再静默** —— 落一条 system message。
5. `store/index.ts`：处理 `todo_continuation_countdown` 通知 ⇒ 渲染倒计时条 +
   「停止自动续跑」按钮 ⇒ `setContinuationStopped{stopped:true}`。

**不变式（写进测试）**

- I1：`cancel` 不带 runId 时能取消该会话当前 run。
- I2：cancel 成功后该会话的自动续跑被关闭、已排的倒计时被取消。
- I3：下一次用户 prompt 恢复自动续跑（标志复位）。
- I4：桌面端 cancel 必须携带 runId；cancel 失败必须对用户可见。

### 1.5 R400 落地（已实施）

**daemon（`aethercode-protocol/.../AetherCodeMethods.java`）**

| 改动 | 位置 |
|---|---|
| `cancel` 的 `runId` 由必填改为可选；回落链 = 显式 `runId` → 显式 `sessionId` → engine 当前会话 → `sessionRunLock` | `cancel(Object params)` |
| 新增 `currentRunIdFor(String sessionId)`（读 `sessionRunLock`，与 `inFlight` 始终一致） | 私有 |
| cancel 顺带停自动续跑：`setContinuationStopped(sid,true)` + `cancelPendingContinuationCountdown(sid)`；**只在显式 runId 属于该会话（或未给 runId）时**才停，避免停错会话 | `cancel` 内 |
| 返回体改用 `LinkedHashMap`（`Map.of` 不容 null），新增 `runId` / `sessionId` / `continuationStopped` | `cancel` 内 |
| 新增测试缝 `continuationDispatcher()`（package-private） | `AetherCodeMethods` |

**桌面端（`aethercode-desktop/src/`）**

| 改动 | 位置 |
|---|---|
| `query()` 返回类型由过时的 `{ok, messageId}` 改为 daemon 实际返回的 `{runId, accepted}` | `lib/methods.ts` |
| `cancel(opts?: {runId?, sessionId?})` → `this.call('cancel', opts ?? {})` | `lib/methods.ts` |
| 新增 `setContinuationStopped(stopped, sessionId?)` | `lib/methods.ts` |
| 新增测试缝 `AetherCodeRpc.__test_dispatchNotification(method, params)` —— 派发给与 WS `ws-notify` **同一批** handler | `lib/methods.ts` |
| store 新增 `activeRunId`（接口 + 初始值） | `store/index.ts` |
| `sendMessage` 捕获 `r.runId` 存进 `activeRunId`（原来直接丢弃） | `store/index.ts` |
| `cancelQuery` / `cancelTask` 带 `{runId, sessionId}`；失败**不再静默**，落一条 `isError` 的 `[停止失败] …` system message | `store/index.ts` |
| 新增 `rpc.on('todo_continuation_countdown')`：粘性 toast「即将自动续跑」+「停止自动续跑」按钮 ⇒ `setContinuationStopped(true)`；`run_start` 时清该 toast | `store/index.ts` |
| **`'cancelled'` 从本地 `failedStopKinds` 里移除** | `store/index.ts` |

**为什么必须移除 `'cancelled'`**：daemon 自己的分类法就说了「cancelled —— 用户要求的，不是失败」，
`StreamEvent.isErrorOutcomeOf` 明确排除它，daemon 因此在 wire 上放 `isError: false`。
但桌面端的 `matchedKind` **在 daemon 已经表态时仍被查询**，本地列表会盖过 daemon 的判断。
这在 cancel 从未生效时是**看不见的**（没有任何 run 会以 `cancelled` 结束）；
一旦取消真的生效，**每一次用户主动停止**都会渲染成「● Failed (cancelled)」+ ↻ 重试按钮。
所以「让取消生效」和「取消不算失败」必须同时落地，否则修一个就制造另一个。

**不变式 → 测试**

| 不变式 | 测试 |
|---|---|
| I1 | `AetherCodeMethodsCancelR400Test.cancelWithNoParamsDoesNotThrowInvalidParams` / `.cancelWithNoParamsStopsTheSessionsCurrentRun` |
| I1'（诚实报告） | `.cancelWithAnUnknownRunIdIsHonest` |
| I2 | `.cancelDisarmsTheAutoContinue` |
| I3 | `.theNextUserPromptReArmsTheAutoContinue` |
| I4 | `cancelR400.test.ts`（桌面端 9 例，走**真实** store handler + 真实 `invoke` 边界） |
| **I5**（实机发现的第二层） | `QueryEngineCancelR400Test.interruptWhileParkedInHasNextEndsTheRunAsCancelled` + `.anUninterruptedRunStillEndsCleanly` |

#### 实机验证发现的第二层根因：stopReason 报错了

第一层修复（daemon 回落 + 桌面端带 runId）让 cancel **到达了**引擎 —— 实机打正在运行的
daemon 确认：无参 cancel 现在返回
`{runId, sessionId, continuationStopped:true, cancelled:true, forceMs:5000}`，
且**流确实停了**（cancel 之后 0 个新 delta）。

但终端帧的 stopReason 是 **`end_turn`**，不是 `cancelled`。这不是噪音，它有两个后果：

1. 桌面端把用户主动停止渲染成「✓Done 完成」而不是「⏹ 已停止」；
2. `StreamEvent.isDeterministicHardStopOf` **不含** `end_turn` ⇒ boulder hook 的硬停闸门
   不命中 ⇒ 「取消后引擎才结束」这一路仍可能重排 2 秒倒计时。

**根因**（`QueryEngine.java:2492` 附近）：引擎在 `currentLlmIt.hasNext()` 上 park 等下一个
SSE chunk。cancel 的 `t.interrupt()` 到达时，**reactive pipeline 遇中断是「完成」而不是
「抛异常」** ⇒ `hasNext()` 返回 `false` ⇒ 引擎把**被截断的流**读成了「模型正常说完」。

R721 的 `Thread.interrupted()` 检查点（在 `tryAdvance` 顶部）抓不到这个形状 —— 它只在
引擎**推进迭代**时执行，而此时引擎正阻塞在 `hasNext()` 里。

**修法**：在「iterator 耗尽」这条路上加一个分支 —— 耗尽**且**中断标志置位 ⇒ 以裸 kind
`cancelled` 结束并发终端帧。未被中断的线程标志是清的，所以正常路径完全不受影响
（反向用例 `anUninterruptedRunStillEndsCleanly` 钉住这一点）。

**为什么这一层不能靠 daemon 侧的 disarm 兜住**：daemon 的 `setContinuationStopped` 是**持久
标志**（到下一次用户 prompt 为止），它确实能挡住续跑。但 stopReason 本身就是错的语义
—— 一个「用户主动停止」的 run 不该报「正常结束」。**语义错误会一路传染到渲染、指标、
续跑闸门、审计日志**，所以要在产生它的地方修。

**变异验证**：把引擎改回修复前形态（`git checkout` 回 HEAD）⇒
`expected: <cancelled> but was: <end_turn>` —— 与实机观测到的现象**逐字一致**；
反向用例保持绿。

---

## 2. 意图识别 → profile 路由（问题 2）

### 2.1 为什么不只加一句 prompt 指令

「往 system prompt 里塞一句『这是问题，别写代码』」是**软的**：模型可以不听。用户的抱怨是
「生成了一堆莫名其妙的代码」，而**写文件是不可逆的**。opencode 的做法更硬 —— 每个 agent
带自己的**权限集**：

> opencode 内置两个 primary agent：**Build**（全工具）与 **Plan**（file edits / bash 默认
> `ask`）；三个 subagent：**General**、**Explore**（只读，答代码库问题）、**Scout**
> （只读，专做**外部文档与依赖调研**）。权限写在 agent 定义里，不是写在提示词里。

### 2.2 AetherCode 已经有的零件

**零件 A —— profile 形态已存在**：`org.aethercode.core.agent.SubagentRole.RolePreset`
（`SubagentRole.java:84`）= `record(name, description, systemPrompt, allowedTools, deniedTools)`。

**关键：限制是结构性强制，不是提示。** `SubagentRole.filterTools(parentTools, role)`
（`SubagentRole.java:256`）在**模型看到工具表之前**就把 denied 工具从池子里删掉：

```java
if (role.deniedTools() != null && role.deniedTools().contains(t.name())) continue;
if (role.allowedTools() != null && !role.allowedTools().contains(t.name())) continue;
```

⇒ **被禁的工具，模型连调都调不到。** 这是「别生成代码」唯一可靠的实现方式。

**零件 B —— 主循环的工具池是每轮传入的**：`QueryEngine.java:2451-2452`

```java
currentLlmStream = effectiveChatClient.stream(
        cleanedTranscript, turnSystem, appState.toolPool());
```

⇒ 主 agent 的工具池**可以逐轮裁剪**，不需要改引擎架构。

**零件 C —— per-turn 指令槽已跑通**：`buildSystemForTurn`（`QueryEngine.java:1754`）的第 ⑤ 块
（R737 语言指令，`:1781-1785`）是**每轮从 transcript 反向重算、落在 prompt 尾部**的指令位。
意图指令的形态与它完全一致 —— 这套机制已经被验证过了。

**零件 D —— 主 agent 的 system prompt 注入位已存在**：`defaultAgentBlock`
（`QueryEngine.java:1743`，R379）—— `agent/setDefault` RPC 走这条路，语义就是
「换一段行为准则」。

### 2.3 缺的是什么

| 缺口 | 证据 |
|---|---|
| 主 agent **没有 profile 概念** | `SubagentRole` 只服务于 `spawn_agent`；主循环永远是全工具 |
| **没有意图分类器** | 全仓无 |
| **没有「按分类收窄工具」的通道** | 主循环的 `appState.toolPool()` 原样传入 |
| **UI 看不到、也改不了意图** | 无 wire 字段、无徽标、无覆盖入口 |
| **prompt 到达后没有任何决策点** | `onUserPrompt` 是 `Consumer<String>`（void），不能阻断、不能改写 |

### 2.4 设计

#### 2.4.1 数据模型（新增 `core/intent/` + `core/profile/`）

```
IntentKind { CODE_CHANGE, DIAGNOSE, RESEARCH, EXPLAIN, REVIEW, OPS, UNKNOWN }
Intent    { kind, reason, profileName, confidence }
Profile   { name, label, description, systemPrompt, allowedTools, deniedTools }
```

`Profile` 的形状**故意与 `SubagentRole.RolePreset` 一致**（systemPrompt + allowedTools +
deniedTools），但**另立 registry**：

> 主 profile 语义 =「这一轮我是什么姿态」；子代理 role 语义 =「我派出去的人是谁」。
> 两者形态相同但生命周期、作用域、注入位置都不同，合并成一个类会制造第二个
> 「同一真相两份拷贝」。

#### 2.4.2 分类器（确定性）

`core/intent/IntentClassifier` —— 纯规则表，**零模型调用、零额外延迟、可复现**。
判定顺序固定，第一条命中即返回（顺序本身是要被 pin 住的不变量）：

1. 显式外部资料请求（`web search` / `查资料` / `官方文档` / `github` …） ⇒ **RESEARCH**
2. 失败症状（`报错` / `不生效` / `崩溃` / `排查` …） ⇒ **DIAGNOSE**
3. 评审请求 ⇒ **REVIEW**
4. 构建 / 运行 / 部署 ⇒ **OPS**
5. 问句 **且** 有本地锚点（路径、本仓库、这个类…） ⇒ **EXPLAIN**
6. 问句 **且** 无本地锚点 ⇒ **RESEARCH**（概念问题，答案不在这个工作树里）
7. 含明确实现动词（`实现` / `新增` / `重构` / `implement` …） ⇒ **CODE_CHANGE**
8. 其它 ⇒ **UNKNOWN**

**硬不变式（必须 pin 住）**：

> **带问号的 prompt，除非含明确实现动词，否则绝不判成 `CODE_CHANGE`。**

理由：把「问题」误判成「代码任务」会让 agent 写出一堆没人要的文件，而用户很难撤销；
反过来把「代码任务」误判成「问题」，代价只是一轮浪费。

`UNKNOWN` 也**照样注入一段护栏**（「是问题就回答、别创建文件；是要代码就写」），
因为默认行为恰恰是用户抱怨的那个。

#### 2.4.3 profile 表（初稿）

| profile | 绑定意图 | system prompt 要点 | 工具 |
|---|---|---|---|
| `builder` | CODE_CHANGE | 实现它；先读后写；别做环境调研 | 全量 |
| `debugger` | DIAGNOSE | 先定位根因，给 file:line 证据；没让改就别改 | 全量 |
| `researcher` | RESEARCH | 先 `web_search` 找、`web_fetch` 读，**再**汇总作答并给来源 | **禁** `file_write` / `file_edit` / `file_create` |
| `analyst` | EXPLAIN | 读本仓库回答，引用 file:line | **禁** `file_write` / `file_edit` |
| `reviewer` | REVIEW | 只读 + 报告，**不要重写** | **禁** `file_write` / `file_edit` |
| `operator` | OPS | 跑真命令、报真输出 | 全量 |

`researcher` 的允许集：`web_search` / `web_fetch` / `file_read` / `grep` / `glob` / `bash`(只读)。

#### 2.4.4 路由（三层叠加）

```
用户 prompt
  │
  ├─① 分类：IntentClassifier.classify(prompt)  →  Intent  (query 开头，0 延迟)
  │        └─ 被用户覆盖时优先用 override
  │
  ├─② 选 profile：intent → Profile
  │
  ├─③ 注入 prompt：buildSystemForTurn 的 ⑤ 之后追加 profile.systemPrompt
  │        （每轮重算、落在尾部 —— 与 R737 语言指令同一槽位）
  │
  ├─④ 收窄工具：stream(..., profileFilter(appState.toolPool(), profile))
  │        （模型看不到被禁的工具 ⇒ 调不到）
  │
  └─⑤ 可见可改：wire 上带 intent + profile ⇒ 桌面端徽标 + 一键覆盖
```

注入顺序：意图指令放在 **R737 语言指令之前** ⇒ 语言指令仍然是 prompt 的最后一块
（R737 的 pin「语言指令必须在 plan 之后」依然成立），意图指令落在倒数第二块。
> 反过来（意图在最后）会让语言锁失去 recency 优势，而 R737 是实测过的。

#### 2.4.5 覆盖

- 新 RPC `setProfile`：`{sessionId?, profile}` ⇒ 写入 `AppState.profileOverride`（per-session）。
- 覆盖**持续生效**直到用户改回「自动」，与 `PermissionMode` 的语义一致。
- 桌面端：header 里一个 chip，显示「意图：概念搜索 · 档案：researcher」，
  点击可切换（含「自动」）。分类错了 ⇒ 用户一键改。

#### 2.4.6 与 workflow 的关系：**意图不自动选 workflow**

理由：workflow 是**多步、有副作用**的链路（`core/workflow/WorkflowExecutor` 的步骤类型有
`shell` / `skill` / `agent` / `gate` / `pipeline` …），自动触发风险远高于换一个 profile。

| | 谁来选 | 为什么 |
|---|---|---|
| 主 profile | **意图自动选**（用户可覆盖） | 只是收窄工具 + 加提示，副作用可控 |
| workflow | **用户显式选**（现状不变） | 多步副作用 |

这与 opencode 一致：**primary agent 由用户切换；subagent 由模型按 description 自动调用。**

若将来要让意图参与 workflow，建议的形态是**建议而非自动**（"这个 prompt 看起来像
调研，要不要用 XX workflow？"），留给后续轮次。

### 2.5 现有 hook 契约的欠账（问题里提到的 hook）

调研发现三个必须先说清楚的事实：

1. **死枚举**：`Hook.Kind` 的 7 个值里，引擎**只触发 2 个**（`PRE_TOOL_USE` /
   `POST_TOOL_USE`）+ 1 个旁路（`SESSION_IDLE`）。`USER_PROMPT_SUBMIT` / `STOP` /
   `PRE_MODEL_QUERY` / `POST_STREAM_END` **从未被触发**。
2. **假承诺**：`SettingsHooks`（`aethercode-hooks/.../SettingsHooks.java:62`）能���
   `settings.json` 的 `hooks` 块加载 `UserPromptSubmit` / `Stop` 规则并注册进 registry ——
   但**没有任何代码路径会调用它们**。用户写了不生效。
3. **签名不够**：`onUserPrompt` 是 `Consumer<String>`（void）—— 只能旁路观察，
   不能阻断、不能改写 prompt、不能注入 context。

对照 claude code 的 hook 契约（已查官方文档）：

- 三档节奏：**每 session**（SessionStart / SessionEnd）、**每 turn**
  （UserPromptSubmit / Stop / StopFailure）、**每 tool call**（PreToolUse / PostToolUse）。
- `UserPromptSubmit` 拿到 `prompt` 字段，可以（a）**注入 `additionalContext`**——
  作为 system reminder 插进上下文、不进对话历史；（b）**拒绝这个 prompt**（exit 2）。
- 超时语义明确：command/http/mcp hook 超时 ⇒ 取消并**丢弃其输出**（fail-open，
  prompt 仍会送到模型）；SDK callback 超时 ⇒ **阻断**（fail-closed，因为它是策略闸门）。

**设计建议（后续轮次，不在 P0）**：把 `USER_PROMPT_SUBMIT` 这条死枚举补齐，
把 `onUserPrompt` 从 `Consumer<String>` 升级成能返回决策的形式
（`allow` / `block(reason)` / `inject(context)`），语义对齐 claude code。
**意图识别本身就是这条 hook 的第一个消费者** —— 先有 hook 契约，意图路由才有正名。

---

## 3. checkpoint 与状态持久化（问题 1 的一半）

### 3.1 现状：三族并存，只有一族在生产路径上

| 族 | 位置 | 语义 | 生产接线？ |
|---|---|---|---|
| **A. Code checkpoint** | `core/transcript/CodeCheckpointStore`（R388n）+ `sdk/AetherCodeEngine.saveCodeCheckpoint` | **每个用户 turn 边界**，对「本会话用 `file_write`/`file_edit` 改过的文件」做**内容快照**（before 态） | ✅ `setOnTurnBoundary`（`AetherCodeEngine.java:990-1000`） |
| **B. Task transcript checkpoint** | `tasks/Checkpoint` + `tasks/CheckpointScheduler`（R20-J） | 把 engine transcript 序列化成 JSONL，供崩溃/重启后 resume | ❌ **只有测试引用，生产零引用** |
| **C. Supervisor child state** | `tasks/supervisor/ResumePlanner` + `StateFileStore` / `StateCheckpointCodec` | supervisor 重启后按状态 respawn 子进程 | ⚠️ 部分（`children.state` 列运行中**从不写** ⇒ RESPAWN 只能带着 prompt+cwd 重来） |
| （死代码） | `core/transcript/CheckpointStore`（R9，只存消息） | — | ❌ 零引用 |
| （死代码） | `aethercode-deepagents/.../Checkpointer`（LangGraph 兼容） | — | 仅 ACP 用 |

### 3.2 族 A 的完整能力（已对齐 claude code）

daemon 协议层三个 RPC（`AetherCodeMethods.java:1861-1864`）：

| RPC | 能力 |
|---|---|
| `checkpoints/list` | 每个点的文件列表 + R388s 预览（`changedFiles` / `changedLines` / `previewComplete`）+ `evicted` 计数 |
| `checkpoints/restore` | `scope` ∈ `code` / `conversation` / `both` —— **三个 scope 是同一个 RPC 的参数**，不是三个操作 |
| `checkpoints/summarize` | `mode` = `from` / `up_to`（claude code 的 "Summarize from here" / "Summarize up to here"） |

外加 `rewind`（R379，按 user-message ordinal 回滚对话，内存 + 磁盘双截断）。

**规格对齐 claude code 的 6 动作 rewind 菜单**（TUI 的 `CheckpointMenu.tsx` 已实现：
Restore code and conversation / Restore conversation / Restore code /
Summarize from here / Summarize up to here / Never mind）。

### 3.3 缺口

| # | 缺口 | 证据 |
|---|---|---|
| G1 | **checkpoint 不落盘** ⇒ daemon 重启后全部消失，连 transcript 位置锚点都没了 | `CodeCheckpointStore.java:197`（全文件无写盘调用）；`AetherCodeEngine.java:2824-2825`（engine 实例字段） |
| G2 | **不能在 run 中途打 checkpoint** ⇒ 只有 turn 边界一个打点 | `QueryEngine.java:1993-2008`（唯一触发点）；`saveCodeCheckpoint(` 全仓仅 `AetherCodeEngine.java:995` 一处调用 |
| G3 | **没有「从 checkpoint 恢复未完成的 run 并让它继续跑」的入口** —— 现有的是回退（rewind），没有 forward-resume | RPC 层面只有 list / restore / summarize 三条 |
| G4 | **运行态不落盘** ⇒ `todoList`、pending tool call、in-flight 计划全是内存态 | `AppState.java:75`（`volatile List`，无持久化路径） |
| G5 | **桌面端没有 checkpoint 菜单** ⇒ 用户用的桌面端根本访问不到 | `aethercode-desktop/` 全目录 grep `checkpoints/*` **零命中**；对比 `aethercode-tui/src/tui.tsx:2250/4821/4856` |
| G6 | **checkpoint 与 transcript 的对齐是"事后推导"** ⇒ 快照时刻的 transcript 内容没单独存档，靠 `transcriptIndex` 指回当前 transcript；中间若发生 compaction 改写 transcript，索引可能不再指向同一段内容 | `AetherCodeEngine.java:2864-2878`（只记 index，不记内容） |

### 3.4 关于「停掉一个任务，任务停在某个 checkpoint」的诚实说明

**claude code 也没有这个语义。** 它的 checkpoint 在 **prompt 边界**：中途 Esc 打断时，
代码停在半途，最近的 checkpoint 是「这一轮开始前」。恢复 = rewind 到该点 + 发新 prompt。

所以用户描述的语义（停掉 ⇒ 落在某个 checkpoint ⇒ 继续 = 新 prompt + checkpoint 向下执行）
**比 claude code 更进一步**，需要自己定义。三个候选：

| 语义 | 描述 | 代价 |
|---|---|---|
| **A（claude code 式）** | checkpoint 只在 turn 边界；中途 cancel ⇒ 代码半途，最近 checkpoint 是本轮开始前 | 零新增成本（现状即此） |
| **B（每工具调用后打点）** | 粒度最细，能停在任意工具边界 | 快照数爆炸 —— 现在每个 checkpoint 已全量拷贝被改过的文件（`CodeCheckpointStore.java:312-354`），再加粒度就是 O(n²) 内存 |
| **C（取消时打点）** | cancel 时对当前工作区打一个「停在这里」checkpoint | 需保存 cancel 时刻的**半成品**状态（部分文件已改、部分没改）—— 这正是「停在这里」想要的语义 |

**建议 A + C**：A 是已有的、对齐 claude code 的；C 正好满足「停掉一个任务时任务停在某个
checkpoint」的诉求，实现成本低（复用现有 save 机制，只是多一个触发点）。

### 3.5 三步走

| 步 | 内容 | 规模 | 前置 |
|---|---|---|---|
| **C1（P1）** | **桌面端接 checkpoint 菜单** —— daemon RPC 已在，纯前端工作 | 中（一个 React 面板 + store） | 无 |
| **C2（P1）** | **checkpoint 落盘** ⇒ 跨 daemon 重启还能 rewind | 中（store 加持久化 + 保留策略 + 清理） | C1 |
| **C3（P2）** | **运行态落盘**（todoList / in-flight 计划）⇒ 真正的长周期任务状态持久化 | 大 | C2 |

C2 的设计要点：

- 落盘位置 `<sessionsDir>/<sessionId>/checkpoints/`：
  `cp-{ordinal}-{millis}.json`（元数据：id / transcriptIndex / summary / files 列表）
  + `cp-{ordinal}-{millis}/`（被改文件的 before 内容）。
- 保留策略：`MAX_CHECKPOINTS = 64` 同步到磁盘；删会话时一并删。
- ⚠️ **跨重启后不能再从 transcript 反推 `touchedFiles`**（transcript 已变）⇒ 必须用落盘的
  files 列表。**这正是 G6 的解法**：落盘时把「快照内容 + 当时的 transcript 位置 + files
  列表」作为一个整体存下来。

---

## 4. 实施顺序建议

| 轮 | 内容 | 理由 |
|---|---|---|
| **R400** | **取消真正生效**（daemon 回落 + 桌面端传 runId + 失败可见 + 倒计时预警） | 独立可验证、改动面小、单独就能解掉一半的致命问题。且它是后续一切的地基 —— 没有可靠的 stop，「停在 checkpoint」无从谈起 |
| **R401** | **意图 → profile 路由**（分类器 + profile registry + per-turn 注入 + 工具池裁剪 + wire + 桌面端徽标 + 覆盖） | 用户两个致命问题里的第二个 |
| **R402** | **桌面端 checkpoint 菜单**（纯前端，daemon 已就绪） | 让已有能力可用 |
| **R403** | **checkpoint 落盘**（跨重启 rewind）+ 取消时打点 | 补 G1 / G2 |
| **R404（可选）** | **hook 契约升级**：`USER_PROMPT_SUBMIT` 补齐 + `onUserPrompt` 改成可决策 | 顺带修掉 `settings.json` hooks 写了不生效的假承诺 |

---

## 5. 需要拍板的问题（已拍板）

1. **概念问题的默认口径** ⇒ **RESEARCH**（去查资料）。
   用户的原话是「这是个问题，可以通过 web search 或者 web fetch 查找资料，汇总之后给我答案」，
   所以默认口径按 RESEARCH。
2. **profile 是否默认收窄工具** ⇒ **收窄**（`prune`）：researcher 模式的工具表里
   **直接没有** `file_write` / `file_edit`，而不是「有但 deny」。
   理由：`filterTools` 在模型看到工具表**之前**就做结构性裁剪，模型根本调不到被裁掉的工具；
   纯 prompt 指令太软，而「生成一堆莫名其妙的代码」的代价正是这个方案要防的。
3. **checkpoint 的范围** ⇒ **能力在 daemon、入口在 UI**。
   桌面端**不自己实现** checkpoint，只调 daemon 已有的三个 RPC 并补一个菜单面板；
   daemon 侧另有两个真缺口要补：G1（不落盘）、G2（只有 turn 边界打点，无中途打点）。
   实施顺序：C1（桌面端菜单，纯前端）→ C2（落盘）→ C3（运行态持久化）。
   打点语义建议 **A（claude code 式 turn 边界）+ C（取消时打点「停在这里」）**。

---

## 6. 附录：证据索引

**取消**
- `aethercode-desktop/src/lib/methods.ts:739`（cancel 无参数）、`:738`（query 类型过时）、`:652`（call）
- `aethercode-desktop/src/store/index.ts:5594`（catch 吞掉）、`:5473`（丢弃 query 响应）、
  `:5587-5599`（cancelQuery）、`:7694-7707`（cancelTask 同一 bug）、`:2986`（run_start 置 isStreaming）
- `aethercode/aethercode-protocol/.../AetherCodeMethods.java:5320-5434`（cancel 四层硬取消）、
  `:9193-9197`（stringOrThrow）、`:566`（sessionRunLock）、`:3983-3990`（新 prompt 复位 + 取消倒计时）、
  `:1139-1141`（cancelPendingContinuationCountdown）、`:4426-4429`（query 返回 runId）
- `aethercode/aethercode-core/.../engine/QueryEngine.java:2270-2290`（interrupt → cancelled）
- `aethercode/aethercode-hooks/.../TodoContinuationHook.java:237/272/284/393/464/504`
- `aethercode/aethercode-protocol/.../EngineContinuationDispatcher.java:110/155/340-356`
- `aethercode/aethercode-core/.../stream/StreamEvent.java:190-198`（isDeterministicHardStopOf）
- TUI 对照：`aethercode-tui/src/state.ts:446`、`aethercode-tui/src/tui.tsx:1547-1560/4065-4077`、
  `aethercode-tui/src/runControl.ts:100/130-135`

**R400 新增/改动的文件（行号对应 R400 之后的树）**

| 文件 | 作用 |
|---|---|
| `aethercode/aethercode-protocol/.../AetherCodeMethods.java` | `cancel` 回落链 + 停自动续跑 + `currentRunIdFor` + `continuationDispatcher()` 测试缝 |
| `aethercode/aethercode-core/.../engine/QueryEngine.java` | iterator 耗尽 + 中断 ⇒ 以 `cancelled` 结束（类 javadoc 的「R400 — a cancelled run must SAY SO」段是完整分析） |
| `aethercode/aethercode-protocol/src/test/.../AetherCodeMethodsCancelR400Test.java` | daemon 侧 5 例 |
| `aethercode/aethercode-core/src/test/.../engine/QueryEngineCancelR400Test.java` | 引擎侧 2 例（含反向不变式） |
| `aethercode-desktop/src/store/__tests__/cancelR400.test.ts` | 桌面端 9 例 |
| `aethercode-desktop/src/lib/methods.ts` | `cancel(opts)` / `setContinuationStopped` / `query` 返回类型 / `__test_dispatchNotification` |
| `aethercode-desktop/src/store/index.ts` | `activeRunId` / `cancelQuery` / `cancelTask` / 倒计时 toast / `cancelled` 移出失败表 |

**checkpoint**
- `aethercode/aethercode-core/.../transcript/CodeCheckpointStore.java:197/228/239/279-295/312-354/580-599`
- `aethercode/aethercode-sdk/.../AetherCodeEngine.java:990-1000/2824-2825/2862-2879/2937-2942/2964-3004`
- `aethercode/aethercode-protocol/.../AetherCodeMethods.java:1861-1864/8881-8968/9012-9066/9085-9115`
- `aethercode/aethercode-hooks/.../TodoContinuationHook.java:587-629`（continuation prompt）
- `aethercode/aethercode-tasks/.../Checkpoint.java:63`、`CheckpointScheduler.java:50`（未接线）
- `aethercode/aethercode-tasks/.../supervisor/ResumePlanner.java:69-75`、`SupervisorStore.java:244-248/572-584`
- `aethercode/aethercode-sdd/.../SddPhaseState.java:275-299`（真落盘的阶段状态，可参考）
- TUI：`aethercode-tui/src/components/CheckpointMenu.tsx`、`src/checkpointPreview.ts`、`src/listWindow.ts`
- 桌面端：**零命中**（`aethercode-desktop/src` 全目录 grep `checkpoints/*`）

**意图 / profile / hook**
- `aethercode/aethercode-core/.../agent/SubagentRole.java:84/113-149/250-272`（RolePreset + filterTools）
- `aethercode/aethercode-core/.../engine/QueryEngine.java:1754-1787`（buildSystemForTurn）、
  `:1781-1785`（R737 语言指令槽）、`:1822-1870`（每轮从 transcript 反向检测）、
  `:2451-2452`（每轮传 toolPool）、`:232`（setPlanModeSuffix，**无生产调用方**）、
  `:129/238/2013`（onUserPrompt，Consumer<void>）、`:151/258/2003`（onTurnBoundary）
- `aethercode/aethercode-hooks/.../Hook.java:46-92`（Kind 枚举）、`:104-220`（Outcome）、
  `:228-314`（HookContext）
- `aethercode/aethercode-hooks/.../SettingsHooks.java:62/89-90`（**注册了但没人调**）
- `aethercode/aethercode-core/.../permission/PermissionMode.java:30-73`
- `aethercode/aethercode-sdk/.../AetherCodeEngine.java:2040`（setDefaultAgentBlock）、
  `:4417/745-748/929-937`（hook 注册入口）
- workflow：`aethercode/aethercode-core/.../core/workflow/WorkflowExecutor.java`（daemon 跑的这套）、
  `aethercode/aethercode-workflows/.../Workflow.java:52`（未接 daemon 的那套）、
  `aethercode-desktop/src/store/index.ts:5443-5453`（唯一的「同一入口两条链路」分叉）

**外部参考**
- Claude Code checkpointing：https://code.claude.com/docs/en/checkpointing
  —— 「automatically captures the state of your code before each prompt you send that starts a turn」、
  「keeps file snapshots for the 100 most recent checkpoints」、「saves checkpoints with the conversation」、
  六个 rewind 动作、以及四条限制（bash 改动不追踪 / 子代理编辑不还原 / 外部改动不追踪 /
  **turn 中途发的消息不产生 checkpoint**）。
- Claude Code hooks：https://code.claude.com/docs/en/hooks
  —— 三档节奏、`UserPromptSubmit` 的 `prompt` 输入与 `additionalContext` 注入、
  「UserPromptSubmit can't replace the prompt; it only injects additionalContext alongside it」、
  超时的 fail-open / fail-closed 两种语义。
- opencode agents：https://opencode.ai/docs/agents
  —— agent = (prompt, model, permission)；primary（Build / Plan）与 subagent
  （General / Explore / Scout）；Scout 专做外部文档与依赖调研；
  「Subagents are invoked by the primary agent based on their descriptions」。
