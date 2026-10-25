# R379 — 三条"真的没有"的能力、一次没人看的倒计时、以及十张手写的按键表

> 轮次：R379 · 日期：2026-10-25 · 分支：`main`
> 上一轮：[R378 — 帮助必须由真相生成，不能手写第二份](R378-help-truth.md)

---

## 0. 本轮做了什么

| # | 候选 | 状态 | 关键证据 |
|---|------|------|----------|
| 1 | `agent/setDefault` / `rewind` / `session/tokens` | daemon 侧**从零实现**，两个传输都注册 | `AetherCodeMethods.java:1674,1676,1814` · `HttpJsonRpcServer.java:931,933,1050` |
| 2 | `todo_continuation_countdown` 倒计时 | daemon **早就推了**，两个渲染器都丢了 —— 本轮接住，并把 "esc" 兑现成真 RPC | `src/continuationCountdown.ts` · `src/tui.tsx:1016` · `src/line.ts:3828` |
| 3 | 面板组件内部的按键表 | 10 个面板的按键表**从注册表生成**，与 handler **双向校验** | `src/componentKeys.ts` · `scripts/test/r379-capabilities.test.mjs` |

外加两件**被这一轮真的咬到**的基础设施缺陷（第 5 节），它们比上面三条更值钱：

- **快照地雷**：上一轮电池留下的快照文件，让这一轮开跑时"恢复"了工作树 —— **把我刚收紧的门禁和 daemon 的 Java 改动一起revert掉了**。
- **打偏的针脚**：一条变异改到了 **JSDoc 注释**里那句引用，而不是模板字面量 —— 文件变了、行为没变。

---

## 1. 三条能力：daemon 侧从零补

R377 之后，TUI 的拒绝表里躺着三条**真**缺口（不是 `effort/set` 那种"名字对不上"）。诚实的做法是把能力做出来。

### 1.1 `agent/setDefault` —— 没人读的标记不算能力

最省事的实现是：往一个文件里写一行"默认 agent 的名字"。但**没有人读它**，那就是假的。

所以这一轮把标记接到了真正的执行路径上：

| 位置 | 做什么 |
|------|--------|
| `AgentRegistry.java:286` | `DEFAULT_AGENT_MARKER = ".default-agent"` —— 放在 `agentsDir` **里面**，所以"扫目录找 agent"的逻辑不会把它误认成一个 agent |
| `AgentRegistry.java:126` | `setDefaultAgent(String)`：**校验 agent 真的存在**（否则 `IllegalArgumentException("agent not found: …")`）；空串/null = 清除 |
| `AgentRegistry.java:118` | `defaultAgent()` 读标记；标记指向的 agent 没了 → 报"没有默认"而不是抛异常 |
| `AetherCodeEngine.java:1984` | `setDefaultAgent` 把 agent 的正文装到**活着的** `QueryEngine` 上 |
| `QueryEngine.java:69,1589,1646` | `defaultAgentBlock` 被追加进 `effectiveSystem()` **和** `buildSystemForTurn()` —— 正好两个 `new StringBuilder(systemPrompt)` 的位置 |

> **Lesson —— 一个没人读的标记是假成功。**
> 门禁钉的是**调用**（`engine.setDefaultAgent(`），不是标识符：标识符可以只存在于一个没人调用的方法里。

### 1.2 `rewind` —— 名字不是行为

一个注册了 `rewind`、返回 `ok:true`、但根本没碰 transcript 的 handler，能通过上面每一条检查 —— 而用户会被告知"对话回到了那条消息"，其实没有。

| 位置 | 做什么 |
|------|--------|
| `AetherCodeEngine.java:2677` | `rewind(int target)`：**`target` 是"第几条用户消息"的 0 基下标**；该条用户消息**保留**，其后全部丢弃 |
| `AppState.java:142` | `truncateTranscript(int keep)` —— 内存里的那份；返回丢弃条数；**不触发** append 监听器 |
| `Transcript.java:76` | `truncateTo(int keep)` —— 磁盘上的 JSONL，用 `TRUNCATE_EXISTING` 重写 |

内存和磁盘**一起**截断：只截一处的话，重启一次就"回来"了。

### 1.3 `session/tokens` —— 全局总量不是这一轮的总量

`CostTracker` 原来只有进程级计数器。`session/tokens` 要回答的是"**这一轮**用了多少"，所以：

| 位置 | 做什么 |
|------|--------|
| `CostTracker.java:45` | `UNKNOWN_SESSION = "__unknown__"` —— 未知 session **落到一个真实的桶里**，而不是被丢掉 |
| `CostTracker.java:121` | `record(sessionId, model, usage)`：同时更新全局计数**和**按 session 的桶 |
| `CostTracker.java:138` | `sessionSummary(sessionId)`：**永不返回 null**；按 model 分项 + 成本 |
| `QueryEngine.java:508` | `costSessionId()` —— 引擎把用量记在**活着的那个 session** 上 |

> **Lesson —— `null`（未知）不能被当成 `0`（实测为零）。**
> 未知 session 落进 `__unknown__` 桶，于是"这个 session 用了 0"和"这个 session 我们不知道"是两回事。

---

## 2. 两个传输都注册了 —— 否则一半的客户端在撒谎

方法名是**传输/服务端特有的**。只注册一边，就会出现"一个 daemon 能用、另一个报 `-32601`"。

| 方法 | stdio 注册 | HTTP 路由 | 标签 |
|------|-----------|-----------|------|
| `session/tokens` | `AetherCodeMethods.java:1674` | `HttpJsonRpcServer.java:931` | READ + SESSION |
| `rewind` | `AetherCodeMethods.java:1676` | `HttpJsonRpcServer.java:933` | WRITE + SESSION |
| `agent/setDefault` | `AetherCodeMethods.java:1814` | `HttpJsonRpcServer.java:1050` | WRITE + AGENT |

R379 的门禁对这三条逐一断言"**stdio 注册了** AND **HTTP 路由了**"，任何一边缺失都变红。

TUI 侧：拒绝表 `BACKEND_MISSING` 现在是**空的** —— 但**机制还在**（`handleSlash` 仍然查这张表）。空表不是"删掉守卫"，是"这个 daemon 现在真的什么都能给了"。

---

## 3. 倒计时：daemon 早就推了，两个渲染器都丢了

`EngineContinuationDispatcher.notifyCountdown`（`EngineContinuationDispatcher.java:333`）一直在推 `todo_continuation_countdown`。而 **TUI 两个渲染器都没有 case** —— 于是 daemon 排好了一次自动续跑，用户**什么提示都看不到**，一轮就自己开始了。

Claude Code 说的是：

```
Continuing automatically at 3:45pm · esc to cancel
```

TUI 现在说同一句话（`src/continuationCountdown.ts` 的 `countdownLine()`，Ink 和 line 两个渲染器共用同一份实现），而且**"esc" 那一半是真的**：

- Ink：`src/tui.tsx` 的 Esc 阶梯现在先调 `pauseAutoContinue(true)`（和 `/pause` 同一个 RPC `setContinuationStopped`），再走原来的"丢弃队列"分支。
- line：`src/line.ts:3828` 处理同一条通知。

> **Lesson —— 一句承诺必须由一个 RPC 兑现。**
> "esc to cancel" 如果只是装饰，它比不承诺更糟：用户按下 Esc，发现什么都没发生。

### 3.1 倒计时通知长什么样

```
{ sessionId, runId, incompleteCount, totalCount, remainingMs, kind: "countdown-start" }
```

`parseCountdown(params, now)` 对**看不懂的载荷返回 null**（不是返回 0）：没有数字 `remainingMs` 就不是一次倒计时。门禁直接调编译后的模块来钉这个行为。

### 3.2 daemon 侧的一个真 bug：同一份真相的两份拷贝

这一轮的电池在 `EngineContinuationDispatcher` 里**抓到**了一个洞：方法名在同一个文件里出现了**两次** —— 常量声明 + 调用点写的是字面量。于是：

- 门禁只看"文件里有没有这个字面量" → 删掉**调用**之后，常量还在 → 门禁**依然绿** → 一个**已经不再倒计时**的 daemon 通过了检查。

修法（和 R377/R378 同源）：**字面量只留一份**，调用点用常量：

```java
methods.notifyCustom(NOTIFY_TODO_CONTINUATION_COUNTDOWN, payload);
```

门禁现在断言：**这个字面量在整个文件里恰好出现 1 次**，并且**常量真的被 `notifyCustom` 发出去**。

---

## 4. 面板的按键表：手写的第二份真相

R378 把 app 级帮助变成生成的。**面板**留着同样的毛病：每个面板自己有一个 `useInput` handler，**另外**手写一段键位提示。两者会漂：

- `GrantsManager` 绑了 j/k，提示里没提；
- `MemoryPanel` 绑了 Tab，提示里没提；
- `CwdSwitcher` 承诺了一个 handler 根本没绑的 Enter。

### 4.1 一份注册表

`src/componentKeys.ts` 是**唯一**的按键表：

```ts
export interface ComponentKey {
  id: string;
  label: string;
  tokens: string[];
  via: "handler" | "input";   // "input" = 面板自己的文本框吃这个键
}
export const COMPONENT_KEYS: ComponentKeyTable[] = [ … 10 张表 … ];
export function hintFor(component: string, ctx?: unknown): string;
```

面板渲染 `hintFor("components/…")`，**不再自己写字**。

### 4.2 双向校验

门禁对每张表做**两个方向**的比对（`r379-capabilities.test.mjs`）：

1. handler 绑的键 ⊆ 表声明的键 —— 提示不会承诺面板做不到的事；
2. 表声明的键 ⊆ handler 绑的键 —— 提示不会藏起面板真有的键。

而且是**从 handler 的源码里抽**（brace 匹配的 `useInput` 块，不是全文件扫描），所以面板文件里别处的字符串不会混进来。

### 4.3 边界（说清楚，不含糊）

这一轮的注册表覆盖的是 **TUI 自己的 panel 注册表（`src/panelIntents.ts`）宣告的那 10 个面板** —— 也就是用户能用 `/` 命令打开的那些。

实测剩下的部分（`node scripts/test/probe-component-keys.mjs`，可复现）：

- `src/components` 下 **31** 个组件带 `useInput` handler；
- 其中 **17** 个的 `<Text>` 里含按键样式的词（探针口径较宽）；
- **可达**组件里仍有 **9** 个手写按键表：`LogViewer`、`ThemePicker`、`NotificationSettings`、`PresetSelector`、`SessionControl`、`StaleWarning`、`SubagentSpawnCard`、`StatusBar`、`InputBox`。

为什么这一轮不一起做：前 6 个是真正的键位表，可以照搬注册表的做法；但 `InputBox`（"type a prompt — Enter to send, shift+Enter for newline, / for commands, @ for files"）、`StatusBar`、`SubagentSpawnCard` 是**散文式提示**，把它们换成生成的表格是**UX 倒退**。要一起做，得先给注册表加上"散文"这一类，那是另一轮的事。

---

## 5. 基础设施：这一轮真正被咬到的两个

### 5.1 快照地雷 —— "跑完了"和"被杀"长得一模一样

`scripts/test/mutation-snapshot.mjs` 的快照机制是这样的：电池**原地改产品源码**；开跑前把原文件写进快照文件；如果上次被杀，下一次开跑时先按快照恢复。

问题在于：**恢复逻辑分不清"上次被杀"和"上次跑完但忘了删快照"** —— 两者都是"快照文件存在"。区别只在于电池作者**记不记得调 `snapshot.clear()`**。

R379 的电池是 14 个里**唯一**没调的那个。于是它的快照活过了那一轮，然后这一轮开跑时：

```
  !! recovered 12 file(s) from an interrupted mutation run
```

—— **把这一轮刚收紧的门禁、还有 daemon 的 Java 改动，一起revert掉了**。而且日志里还写着"recovered"，看着像正常的自愈。

修法不是"记得调"（**靠自觉的清理步骤，迟早会被漏掉**），而是让漏掉这件事**在物理上不可能**：

- 退出时按**内容**判断，而不是按某个标志：每个被快照的文件当前内容都等于记录的原内容 → 树是干净的 → **删掉快照**；
- 任何文件还不一样 → 电池真的死在半路 → 快照**留下**，并且把**变异后的内容**也记进去；
- 于是下一次恢复**只还原仍然是那个变异的文件**；被人改过的文件**不碰**，并明确报告。

判据：**一个清理步骤如果只有作者记得做，它迟早会被漏。做掉它，让漏掉不可能发生。**

### 5.2 打偏的针脚 —— 改到注释里的变异是"伪装成失败的 no-op"

电池里那条用例是"倒计时不再承诺 esc 可取消"，针脚是 `s.replace("· esc to cancel", "· continuing")`。

但 `src/continuationCountdown.ts` 里**第一次**出现这句话的地方，是**JSDoc 里那句引用 Claude Code 的话**；真正拼出那一行的模板字面量在**后面**。于是：

- 文件变了 ✓
- 行为没变 ✗
- 用例报"没抓到"（HOLE）

文件变了、报告说"抓到了"、而被测的那一处根本没被碰过 —— 这是 Lesson 1195 的下一层。

修法（`scripts/test/code-needle.mjs`）：**针脚必须打在代码上**。注释剥离器把注释涂成空格但**保持长度**，所以"在涂掉注释的文本里找到的偏移"就是"在原文里的同一个偏移"；找不到就分两种情况报：**只在注释里出现过**（针脚打偏了）vs **文件里根本没有**（形状变了/拼错了）。

### 5.3 门禁太松 —— 前缀匹配不是匹配

两个渲染器的"处理了倒计时"断言原来是：

```js
code.includes("COUNTDOWN_METHOD")
```

而变异把 `case COUNTDOWN_METHOD:` 改成了 `case COUNTDOWN_METHOD_DISABLED:` —— **前缀照样命中**，门禁纹丝不动。`_` 是词字符，所以 `COUNTDOWN_METHOD` 和 `_DISABLED` 之间**没有词边界**。

现在用词边界正则：`/case\s+COUNTDOWN_METHOD\b\s*:/`（Ink）、`/method\s*===\s*COUNTDOWN_METHOD\b/`（line），另外加一条地板断言：**去掉 import 行之后，常量必须还被用到**（只 import 不用，等于没有 handler）。

---

## 6. 证据与验证

| 项 | 结果 |
|----|------|
| Java 编译 | `aethercode-build-helper.ps1 -Goals 'compile -DskipTests -q'` → BUILD SUCCESS |
| Java 测试（新增） | `R379GapClosersTest` 14 · `AetherCodeMethodsR379Test` 12 · `HttpJsonRpcServerR379Test` 4 → **30 全绿** |
| TUI 类型 | `npx tsc --noEmit -p tsconfig.json` → 0 错 |
| TUI 格式 | `npm run format` + `format:check` → clean |
| R379 门禁 | `scripts/test/r379-capabilities.test.mjs` → **16/16 绿** |
| R379 电池 | `scripts/test/r379-mutate.mjs` → **19/19 ALL MUTATIONS CAUGHT**，exit 0 |
| 全量测试 | `npm test` → **1383 / 1340 通过 / 43 失败** |
| R378 基线 | 1366 / 1323 / 43 → **+17 测试、+17 通过、失败集合逐名相同**（零新增、零静默修复） |
| 全部电池 | **15/15 绿**（格式化之后跑的） |
| 残留快照 | 无 |

> **Lesson —— 数量相同不等于名字相同。**
> 失败数从 43 到 43 不代表"没变化"：可能是换了一个失败顶替了另一个。所以逐名 diff。

---

## 7. 遗留（都是实测出来的，不是"可能还有"）

1. **可达组件里还有 9 个手写按键表**（第 4.3 节有清单）。要做的话，注册表得先支持"散文"这一类。
2. `aethercode-cli` 的 `--effort` 仍然是"记录了但没映射到 provider 参数"（R747），单独一轮。
3. `TaskPanel.tsx` / `src/rpc/queries.ts` / `src/rpc.ts` 里还有 `task/*`（以及 `session/tokens`）的死引用与注释。
4. 根级 `format` 仍然是全仓扫描（不是提交前那一步）；另外四个包还没 prettier-clean。
5. 真实 Ink 交互 + 80 列渲染的视觉验收仍未自动化。

---

## 8. 教训

- **Lesson 1199 —— 同一份真相在同一文件里的两份拷贝，同样会藏住 bug。** daemon 里"方法名"出现两次（常量 + 字面量），于是"删掉发送"不等于"删掉名字"。字面量只能有一份。
- **Lesson 1200 —— 一个没人读的标记是假成功。** `agent/setDefault` 必须把 agent 的正文装进活着的 system prompt，否则它只是一个写了名字的文件。
- **Lesson 1201 —— 一句承诺必须由一个 RPC 兑现。** "esc to cancel" 要真的调用 `setContinuationStopped`，否则它比不承诺更糟。
- **Lesson 1202 —— 前缀匹配不是匹配。** `includes("COUNTDOWN_METHOD")` 匹配 `COUNTDOWN_METHOD_DISABLED`；词边界不是可选的。
- **Lesson 1203 —— 改到注释里的变异是伪装成失败的 no-op。** 文件变了不等于行为变了。针脚必须自己会说话：只在注释里出现 = 打偏了，压根没出现 = 形状变了。
- **Lesson 1204 —— 靠自觉的清理步骤迟早会被漏掉；让漏掉不可能发生。** 快照文件在退出时按内容自证清白：树是干净的就删掉自己。
- **Lesson 1205 —— 数量相同不等于名字相同。** 失败数不变也要逐名 diff。
- **Lesson 1206 —— 一条契约在空表上会变空洞。** r378 那条"帮助要标出不可用的键"在拒绝表清空后变成了空气；重定向到**推导本身**（`bindingUnavailable` 恒为 true），而不是删掉用例。
