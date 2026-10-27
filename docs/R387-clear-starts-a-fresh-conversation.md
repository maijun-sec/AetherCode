# R387 —— `/clear` 必须是真清空,而且两个渲染器说同一句话

> `/clear` 在**目录**里承诺 "clear the scrollback",
> 在 **cmd.exe 行模式**里真的清了屏幕,
> 在 **Ink 模式**(主渲染器)里只打印了一行:
> `"(clear is a no-op in this build; restart for a fresh scrollback)"`。
>
> 更贵的一条:即使只把 Ink 的**视图**清空,那也是**假清空**——
> TUI 的 `state.turns` 只是 daemon transcript 的**镜像**,
> 下一次切会话,整段历史**原样回来**;而模型那边**一个字都没忘**。
>
> 而这个能力**一直都在**:daemon 两条传输上都注册了 `createSession`。
> 所以这不是"缺功能",是**没接线**——和 R385 的 `/mode` 一模一样的病。

---

## 0. 这一轮做了什么

| # | 缺陷 | 现状 | 修法 |
|---|------|------|------|
| 1.1 | Ink 的 `/clear` 是空操作 | 打印一句诚实的"没做" | 走 `createSession`,真开新会话 |
| 1.2 | 两个渲染器对同一条命令给两个答案 | line 清屏 / Ink 拒绝 | 共用一个模块 `src/clearConversation.ts` |
| 1.3 | 清空是**非原子**的 | 清屏和失败可能各来一半 | daemon 确认之后才替换视图 |
| 2.1 | 只清视图 = 假清空 | 历史下次切会话就回来 | 连 daemon 的 transcript 一起换掉 |
| 2.2 | 换会话后旧会话的错误卡片还在 | 屏幕上是"上个会话"的报错 | `adoptSession` 一并清掉 `lastError` |
| 3.1 | 目录描述只说了视图 | `/help` 少说了一半 | 描述改成 "clear the scrollback and start a new session" |
| 3.2 | TUI README 写 "Ink: redraw" | Ink **从来没有** redraw 过 | 按真相对齐 |
| 3.3 | 用户手册写着 "currently a no-op" | 已经不准了 | 按真相对齐 |

---

## 1. 缺陷:一个命令,两个答案

### 1.1 三个地方说的不是一件事

| 位置 | 说的是什么 | 是不是真的 |
|------|------------|------------|
| `src/commands.ts:84`(目录) | "clear the scrollback" | 半个承诺 |
| `src/line.ts:4392`(行模式) | `\x1b[2J\x1b[H` + 重画 splash | **真的清了屏幕** |
| `src/tui.tsx:2002`(Ink 模式) | `"(clear is a no-op in this build…)"` | **什么都没做** |

Ink 那句确实是**诚实的**——R378 正是因此把它记成"另一个诚实的缺口"并明确推迟
(`docs/R378-help-truth.md:97`、`:238`)。但**诚实且没用**依然是一条坏命令:
用户被告知能力不存在,而能力其实一直都在。

R385 的病根是同一个:line 模式能用的命令,在 Ink 模式给出一个**假失败**。
那次修法是"给 Ink 一个真的界面",不是"让 Ink 也拒绝"。

### 1.2 只有视图被清空,是**假清空**

`state.turns` 不是会话本身,它是 daemon transcript 的**镜像**:
R372 的会话选择器就是调 `getTranscript` 把 daemon 的消息**重放**进它的
(`src/tui.tsx:3882-3900`)。

所以"只清 `state.turns`"会带来三件事同时出错:

1. 模型**还记得**那段对话(daemon 的 transcript 没动);
2. 用户**看不见**那段对话(视图空了)——模型下一句回答会提到用户已经看不到的东西;
3. 下一次 `/sessions` 切换或重启,历史**整段回来**。

**清完又回来,比不清更糟。**

### 1.3 daemon 早就有这个原语

`createSession {cwd}` 的语义(实测,见 §3):

- 铸造一个新的 session id;
- 写一份**空** transcript;
- 把 engine 切到它上面;
- 给了 cwd 的话,还会把新会话**绑到同一个工程**并登记进 session store。

**零 Java 改动**。两条传输上都已经注册:
stdio 是 `AetherCodeMethods.registerAll` 里的
`dispatcher.register("createSession", this::createSession)`(`AetherCodeMethods.java:1685`),
HTTP 是 switch 里的 `case "createSession" -> methods.createSession(params);`
(`HttpJsonRpcServer.java:1019`)。

> 为什么**不加**一个 `clearTranscript` RPC?
> 因为它**更差**:同一个 session id,但对话被**销毁**、找不回来。
> `createSession` 是**非破坏性**的——旧对话还在 session store 里,`/sessions` 还能捡回来。
> 这也正是 Claude Code 的 `/clear` 做的事(开一段新对话,旧的还能 resume)。

---

## 2. 修法:一个模块,两个渲染器,一次原子替换

### 2.1 共享模块 `src/clearConversation.ts`

| 导出 | 作用 |
|------|------|
| `CLEAR_METHOD`(`:93`) | daemon 方法名 `createSession` |
| `clearParams(cwd)`(`:103`) | 带 cwd;空 cwd 就**不送**(空 cwd 会让新会话不绑工程,`/sessions` 里会被归到"未关联") |
| `newSessionIdOf(result)`(`:116`) | 只从 `ok:true` 且真的带 id 的结果里取 id;**绝不凭空造一个** |
| `clearOutcome(ok, ctx)`(`:134`) | 成功说新 session id,失败说原因 |
| `runClear(request, ctx)`(`:166`) | 一次调用:发 RPC、取 id、给结论 |

方法名和**措辞**都只在这一个文件里(门禁 §4 会查)。

### 2.2 两个渲染器都走它

```ts
// src/tui.tsx:2017
void runClear((method, params) => client.request(method, params), {
  cwd: state.cwd,
}).then((r) => {
  if (r.ok && r.sessionId) {
    dispatch({ type: "adoptSession", sessionId: r.sessionId, turns: [] });
  }
  dispatch({ type: "pushToast", kind: r.ok ? "ok" : "err", text: r.message });
});
```

```ts
// src/line.ts:4408
void runClear((method, params) => client.request(method, params), {
  cwd: displayState.cwd,
}).then((r) => {
  if (r.ok && r.sessionId) {
    displayState = { ...displayState, sessionId: r.sessionId };
    turnsBuffer.length = 0;
    process.stdout.write("\x1b[2J\x1b[H");
    process.stdout.write(renderSplash(displayState, noColor) + "\n");
    const splashLines = Math.max(1, renderSplash(displayState, noColor).split("\n").length - 1);
    lineInput.setInputTopRow(splashLines + 1);
  }
  logAbove(colorize(r.message, r.ok ? C.green : C.red, noColor));
  if (!rlClosed) lineInput.redraw();
});
```

### 2.3 清空是**原子**的

顺序是 **daemon 确认 → 才换视图**。

反过来(先清屏,再发 RPC)会得到一个**假清空**:用户面前是空的,
模型还记得一切,而且这次连"清过"都没成功。失败时**什么都不清**,
只把原因说出来(`clear failed: <原因>`)。

### 2.4 换会话时,旧会话的错误卡片也要走

`adoptSession`(`src/state.ts:2573`)本来就清 `todos` / `lastStopReason` /
`lastStopKind` / `viewHistory`,但**没清 `lastError`**——
于是换完会话,屏幕底部还挂着**上一段对话**的红色报错卡片。

这一轮补上(`src/state.ts:2595`)。理由不是"顺手":那张卡片说的是
"上一次运行失败了",而换完会话之后**根本没有那次运行**。

---

## 3. 实测:daemon 侧

`aethercode-cli-0.1.0-SNAPSHOT-shaded.jar`(R384 构建),
`--http-port 22899 --sessions-dir D:\tmp\r387-sessions`:

```
createSession {cwd:"D:\work\workspace\idea\engine\AetherCode"}
  → {"ok":true,
     "sessionId":"2026-10-27T02-03-16.910966800Z_df7a98cc",
     "cwd":"D:\\work\\workspace\\idea\\engine\\AetherCode",
     "worktree":"","messageCount":0,
     "active":"2026-10-27T02-03-16.910966800Z_df7a98cc"}

getState       → sessionId = 2026-10-27T02-03-16.910966800Z_df7a98cc, transcriptSize = 0
getTranscript  → {"ok":true, "sessionId":"2026-10-27T02-03-16.910966800Z_df7a98cc", "messages":[]}
```

`messageCount: 0` + `getTranscript.messages: []` = 新会话的 transcript 确实是空的,
而 `getState.sessionId` 已经切过去 = **模型这边的上下文也换了**。

---

## 4. 门禁与变异电池

### 4.1 `scripts/test/r387-clear-conversation.test.mjs` —— 19 条,全绿

| § | 断言 |
|---|------|
| 1 | 共享契约:方法名、cwd 参数、id 提取、成败文案(**跑模块**,不看源码) |
| 2 | `runClear` 真的发那个方法;daemon 抛异常 / 拒绝 / 返回畸形结果 → 都是**失败**,且**不造 id** |
| 3 | reducer 真的把 scrollback 清空,并丢掉旧会话的错误卡片 |
| 4 | 两个渲染器都**从共享模块**导入并调用它;旧的 no-op 文案从**整棵树**消失;方法名和措辞各自**只有一个文件**写 |
| 5 | daemon **两条传输**上都真的注册/路由了 `createSession`(Lesson 1022) |
| 6 | 目录描述说得出"开新 session"这一半;`/help` 里是同一句 |

§4 的"两个渲染器都从共享模块导入"这条特意**不按函数名 pin**:
门禁解析出渲染器**导入的那个绑定**再检查它被调用了。
按名字 pin 会在**改名**时变红,而改名不是缺陷(R384, Lesson 1233)。

### 4.2 `scripts/test/r387-mutate.mjs` —— 21 个变异,**全部被抓**

| 组 | 变异 | 门禁的哪一条抓住它 |
|----|------|-------------------|
| 契约 | 方法名换成 daemon 没有的 | §1 |
| 契约 | 不再带 cwd | §1 |
| 契约 | 被拒绝的结果也给出 session id | §1 |
| 契约 | 畸形结果也给出 session id | §1 |
| 契约 | 失败文案不说原因 | §1 |
| 契约 | 失败被说成成功 | §1 |
| runner | 换一个方法去问 | §2 |
| runner | 吞掉 daemon 的异常并报成功 | §2 |
| runner | daemon 拒绝时**编一个** session id | §2 |
| reducer | 换会话时保留旧 turns | §3 |
| reducer | 旧会话的错误卡片留下来 | §3 |
| 渲染器 | Ink 不再用共享模块 | §4 |
| 渲染器 | line 不再用共享模块 | §4 |
| 渲染器 | Ink 换了 session 却留着旧 turns | §4 |
| 渲染器 | line 留着旧会话的 turns | §4 |
| 词汇 | 旧的 no-op 文案回来了 | §4(反向不变量) |
| 词汇 | 某个渲染器自己写方法名 | §4(唯一写点) |
| 词汇 | 某个渲染器自己写措辞 | §4(唯一写点) |
| daemon | stdio 不再注册 | §5 |
| daemon | HTTP 不再路由 | §5 |
| 目录 | 描述退回只说视图 | §6 |

> **电池第一次跑出了一个 HOLE,而且 HOLE 是对的。**
> 那个 needle 打在了 `id.trim()` 上,可真正拦下畸形 id 的是**上一行**的
> `typeof id !== "string"`——代码变了,**行为没变**。
> 换掉 needle(改成拆掉 id 的类型收窄)之后才真的被抓。
> **改了代码不等于改了行为**:这次门禁保持绿是**运气**,不是证明。

---

## 5. 文档里的"行为描述"也是断言

三份文档说的都和事实对不上,而且是**三种不同的对不上**:

| 文件 | 原来写的 | 事实 |
|------|----------|------|
| `aethercode-tui/README.md:111` | "clear the scrollback (Ink: redraw; line: `\x1b[2J`)" | Ink **从来没有** redraw 过 |
| `aethercode/docs/user-guide/slash-commands.md:9` | "currently a no-op in Ink TUI; restart for a fresh scrollback" | 现在不是了;而且"restart" 也不管用——重启会**重放** transcript |
| `doc/user-guide/使用说明-Desktop与TUI能力测试清单.md:319` | "清 scrollback" | 还少说了"模型上下文也换了"这一半 |

三份都按真相对齐了。

---

## 6. Lessons

- **Lesson 1266 —— 诚实的"没做"不等于"可以不做"。**
  底层能力已经存在时,一句诚实的拒绝只是把缺陷**重新包装**了一遍:
  用户学到的是"这个功能不存在",而事实是"这个功能没接线"。
  诚实的**降级**是给**真没有**的能力用的。

- **Lesson 1267 —— 视图不是会话:清了镜像,历史会回来。**
  TUI 的 `state.turns` 是 daemon transcript 的**镜像**(靠 `getTranscript` 重放)。
  只清镜像 = 制造一个**发散**的界面:模型还记得,用户看不见,下次切换整段回来。
  **清完又回来,比不清更糟。**

- **Lesson 1268 —— "清空"必须是原子的。**
  先清屏再发 RPC,失败了就得到一个**假清空**:
  屏幕是空的,模型还记得一切,而且这次连"清过"都没成功。
  顺序必须是 **daemon 确认 → 才换视图**;失败就**什么都不清**,只说原因。

- **Lesson 1269 —— 改了代码不等于改了行为。**
  变异 needle 打错了行,`typeof` 守卫先一步返回,行为一字未变,
  门禁**保持绿**。这次是运气,不是证明。
  判据:needle 改的那一行**是不是决定行为的那一行**。

- **Lesson 1270 —— 文档里的"行为描述"也是断言。**
  README 写 "Ink: redraw",而 Ink **从来没有** redraw 过。
  一份文档说了一个**从来没发生过**的行为,和代码说谎是同一件事,
  只是读者更容易相信文档。

- **Lesson 1271 —— 命名一个 daemon 方法是契约,命名本地函数不是。**
  门禁要 pin 的是"**两个渲染器从同一个模块拿决定**",不是"它们都调用
  `runClear` 这个名字"。按名字 pin 会在**改名**时变红,而改名不是缺陷
  (R384, Lesson 1233)。做法:解析出渲染器**导入的绑定**,再检查它被调用。

- **Lesson 1272 —— 能不动 daemon 就不动;新加一个"更直接"的 RPC 反而更差。**
  `createSession` 早就在两条传输上,语义正好,而且**非破坏性**——
  旧对话还在,`/sessions` 能捡回来。
  自己加一个 `clearTranscript` 会更"直接",但它会**销毁**对话。

---

## 7. 文件

| 文件 | 动作 |
|------|------|
| `aethercode-tui/src/clearConversation.ts` | **新增** — 共享契约 |
| `aethercode-tui/src/tui.tsx` | Ink 走真清空 |
| `aethercode-tui/src/line.ts` | 行模式走同一套 |
| `aethercode-tui/src/state.ts` | `adoptSession` 清掉旧会话的错误卡片 |
| `aethercode-tui/src/commands.ts` | 目录描述说全两半 |
| `aethercode-tui/scripts/test/r387-clear-conversation.test.mjs` | **新增** — 门禁 19 条 |
| `aethercode-tui/scripts/test/r387-mutate.mjs` | **新增** — 变异电池 21 例 |
| `aethercode-tui/README.md` | 行为描述改成真的 |
| `aethercode/docs/user-guide/slash-commands.md` | 同上 |
| `doc/user-guide/使用说明-Desktop与TUI能力测试清单.md` | 同上 |
| `AGENTS.md` | Lessons 索引 + 陷阱清单 |
