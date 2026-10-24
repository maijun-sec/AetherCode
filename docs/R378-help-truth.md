# R378 — 帮助必须由真相生成，不能手写第二份

> 轮次：R378 · 日期：2026-10-24 · 分支：`main`
> 上一轮：[R377 — 一次「拒绝」藏住了一个能用的功能](R377-capability-naming.md)

---

## 1. 缺陷：三份手写的真相

TUI 有**三处**各自手写了一份按键表：

| 位置 | 份数 | 形态 |
|------|------|------|
| `src/components/HelpOverlay.tsx`（Ctrl-? 浮层） | 13 条 | `SESSION_SHORTCUTS` / `DISPLAY_SHORTCUTS` / `EDITING_SHORTCUTS` 三张手写数组 |
| `src/components/Welcome.tsx`（欢迎页格子） | 12 条 | `SHORTCUTS` 手写数组 |
| `src/ac-tui.ts`（`--help` 文本） | 5 条 | 模板字符串里的一段 |

而 app 级 handler 真实绑定的是 **35 条**。

**三份都错了，而且互相矛盾**：

| 条目 | 浮层/欢迎页说 | handler 实际是 |
|------|------------|--------------|
| `Ctrl-T` | 欢迎页：**theme picker** | **重新打开欢迎页**；主题是 `Ctrl-Shift-T` |
| `Ctrl-L` | 浮层：**clear scrollback** | 一个占位分支，记 "(clear is a no-op in this build)" —— 而**真正的 log viewer 开关在它后面，永远到不了** |
| `Esc` | 浮层：**dismiss overlay / close search** | 还要**停掉正在跑的这一轮**（R363 做的那个功能）—— 整个产品最重要的一个键，**帮助里根本没提** |
| `Ctrl-Z` / `Ctrl+1…9` / `Ctrl+S` / `Ctrl+D` / `Ctrl+I` / `Ctrl+P` / `Ctrl+Shift+B` / `Ctrl+Shift+T` | —— | 真实存在，**一处都没写** |

浮层还把 **8 条开发注记**漏进了用户可见文本：

```ts
{ key: "Ctrl-B", desc: "R37: toggle side panel" },
{ key: "Ctrl-O", desc: "对应历史 round: collapse / expand all cards" },
{ key: "Ctrl-E", desc: "对应历史 round: toggle the most recent card" },
{ key: "A / Y / D / N", desc: "对应历史 round: permission response" },
```

**没有任何东西失败。** 三份都是合法的 TypeScript 数组、合法的字符串，碰过它们的每个门禁都是绿的。
错的只是：**它们互相之间、以及它们和 handler 之间对不上** —— 而没有任何门禁在看这件事。

> **Lesson 1197 —— 一份真相的第二份拷贝会漂移，而且漂移得毫无声响。**
> 修法不是"改的时候小心点"，是**根本没有第二份可以改**。

---

## 2. 决策：一份注册表，三个消费者

新增 **`src/keybindings.ts`** —— 唯一的按键注册表。每一条带：

| 字段 | 作用 |
|------|------|
| `keys` | 归一化后的和弦（`ctrl+c` / `ctrl+shift+b` / `ctrl+1-9` / `esc` / `tab` / `up` / 裸字母） |
| `text` | 不是和弦的**输入约定**（`/` 打开斜杠分发器、`@` 提到文件）—— 门禁不去 handler 里找它 |
| `label` / `desc` / `short` | 显示用；`short` 是欢迎页密集格子用的短版 |
| `group` | `session` / `display` / `editing` / `subagent` / `permission` |
| `when` | 只在特定情境下生效时（"当权限提示在屏幕上"） |
| `method` | 这个和弦调用的 RPC（如果有） |
| `boundIn` | 不是 app 级 handler 绑定时，绑定它的文件（输入框绑 Enter） |
| `welcome` | 是否出现在欢迎页的密集格子里 |

三个消费者全部改为**渲染**它：

- `HelpOverlay.tsx` —— 五个分区从注册表来；斜杠命令分区从 `panelIntents` 来
- `Welcome.tsx` —— 密集格子从注册表的 `welcome` 条目来
- `ac-tui.ts --help` —— Ink 快捷键从注册表来

### 2.1 浮层的命令列表：为什么不再手写

浮层原来自己列了 16 条命令。注册表化之后它列的是 **panel intents**（13 条打开 UI 的命令），
其余的交给 `/help`（`/help` 本身是派生的，R362 做的）。

**理由**：86 条命令塞不进一个模态框；而 `/help` 就是干这个的。
浮层底部加了一行「type /help for every command」。

---

## 3. 附带修掉的：Ctrl-L 的死分支

`tui.tsx` 的 app 级 handler 里有**两个** Ctrl-L 分支：

```ts
if (key.ctrl && input === "l") {
  dispatch({ type: "log", message: "(clear is a no-op in this build)" });   // ← 死的占位
  return;
}
...
if (key.ctrl && (input === "l" || input === "L")) {
  dispatch({ type: "setLogViewerOpen", open: !state.logViewerOpen });        // ← 真的开关
  return;
}
```

**第一个赢** —— 所以 log viewer 从来打不开，而用户按 Ctrl-L 只会看到一行
"(clear is a no-op in this build)"。欢迎页却写着 "Ctrl-L — log viewer"。

R378 删掉那个占位分支：**log viewer 变成可达的**，帮助也终于说的是真的。
（`/clear` 斜杠命令那处 "clear is a no-op" 留着 —— 那是**另一个**诚实的缺口，
scrollback 的清空确实没实现。）

---

## 4. 与 R377 的接线：帮助不能说这个 daemon 做不到的事

`Ctrl-Z` 调的是 `rewind`，而 **R377 已经确认这个 daemon 没有 rewind**。

于是注册表里这一条带着 `method: "rewind"`，而"能不能用"**从拒绝表推导**，
不手写：

```ts
export function bindingUnavailable(b: Keybinding): boolean {
  return !!b.method && BACKEND_MISSING.has(b.method);
}
```

浮层于是会写：

```
 Ctrl+Z         rewind to the previous turn — rewind is not available on this daemon
```

**和 R377 同一个原则**：用户看到的能力表和 dispatcher 读的是**同一张表**，
两者不可能互相矛盾。R377 的门禁还会检查：被标记为"不可用"的和弦集合，
必须**恰好**等于其 RPC 落在拒绝表上的那批。

---

## 5. 门禁：双向覆盖

`scripts/test/r378-keybindings.test.mjs`（9 条）：

1. **app 级 handler 找得到，且提取不是空转** —— 提取出来的和弦数有下限（≥25），
   并且点名 `ctrl+c` / `esc` / `tab` / `ctrl+z` / `ctrl+?` 五个"一定在"的，
   防止正则漂移后所有断言变成空转。
2. **handler 绑的每一条都在注册表里**（不漏）。
3. **注册表写的每一条都真的被绑**（不写幽灵）；带 `boundIn` 的去那个文件里查。
4. **没有开发注记漏给用户** —— 注册表的**值**逐条查，三个消费者的**代码**（剥掉注释）查。
5. **没有第二份拷贝** —— 每个消费者必须 import 注册表，且不得再带一张自己的和弦表。
6. **每个分区都要真的被渲染** —— import 了不等于渲染了；把 `bindingsIn("display")`
   改成 `[]` 必须变红。
7. **浮层的命令列表是派生的**。
8. **帮助不承诺这个 daemon 做不到的事**。
9. **门禁不空转**。

提取器本身用的是**大括号配对**（`scripts/test/ts-scan.mjs`），不是猜的字符窗口 ——
`AetherCodeMethods` 的收尾大括号缩进四个空格，R377 在那里已经吃过一次亏。

> **顺带修掉一个扫描器 bug**：`matchBrace` 原本不跳注释，于是 `InputBox.tsx`
> 注释里的一个撇号（"the user's …"）被当成字符串开头，吞掉了后面 2 KB 真实代码。
> 这正是 R366 记录过的那个 Java 陷阱在 TS 里的翻版。现在 `ts-scan.mjs`
> 的括号匹配**同时跳注释和字符串**。

---

## 6. 附带发现：门禁把一段死代码当成了位置锚点

删掉那个 Ctrl-L 死分支之后，**r363 电池的前置校验立刻拦下了它**（exit 2）——
门禁在变异之前就是红的。查下来，r363 的门禁长这样：

```js
// Anchor on the `key.ctrl && input === "l"` branch, which sits below
// the whole permission / decision-card region.
const downstreamIdx = tuiCode.indexOf('if (key.ctrl && input === "l")');
```

**它把那段死分支当成了"下面还有东西"的标记。** 死代码一被清理，这个门禁就瞎了 ——
而**没有人知道它一直靠在那儿**。

锚点已重新瞄准到同一位置的帮助开关（`key.ctrl && input === "?"`）。

> **Lesson 1198 —— 一段死代码被当成位置锚点，就是一颗定时炸弹。**
> 清理死代码的那天，靠在它上面的门禁会静默失明；而且**没有人知道它靠在那儿**，
> 因为门禁的注释写的是"锚定在下面的某个分支上"，看不出那个分支是死的。
>
> **推论：位置锚点必须挑一个"活着、且有理由活下去"的东西。**
> 判据：**这个锚点被删掉的时候，会有人觉得可惜吗？** 不会，就该换一个。

---

## 7. 我自己写错的三根针脚（这一节是本轮最值钱的部分）

第一次跑电池，14 个用例里 3 个"洞"。逐个查下来，**全部是针脚自己的错**，
不是门禁的洞：

| 洞 | 我以为发生了什么 | 实际发生了什么 |
|----|----------------|--------------|
| "app 级 handler 认不出来了" | 改 `dispatch({ type: "exit" })` 就该让门禁失明 | `tui.tsx` 里有**两处** `type: "exit"`，`String.replace` 改的是**第一处**（`/exit` 斜杠命令），不是 handler 里那处 —— 变异改在了**错误的出现位置** |
| "提取正则不匹配了" | 把 `/key\.ctrl/g` 改成 `/key\.ctr/g` 就该失效 | **`/key\.ctr/g` 仍然匹配 `key.ctrl`** —— 正则没有锚定，`"key.ctr"` 是 `"key.ctrl"` 的**前缀**。**变异根本没发生**，用例"通过"了 |
| "dev-note 检查瞎了" | 把正则换成永不匹配的 | 我写的是 `/(?!x)x/ || /原正则/` —— `||` 把**原正则留了下来**。而且这条性质只有**一个**推导，按电池自己的规矩应当**删掉**而不是留着 |

> **Lesson 1195 的又一次复现**：文件确实变了、报告确实说"抓到了"，而被测的那一处
> 根本没被碰过。**这一次连"文件确实变了"都不成立** —— 针脚匹配到了别的东西，
> 或者压根没匹配。
>
> **推论：正则没有锚定的时候，"把它改短一点"不等于"把它改坏"。**
> 判据：**这个变异之后，被测的行为真的变了吗？** —— 变了吗？没有，就不是变异。
>
> **推论：一条只有单一推导的性质，不该有"阉废门禁"用例。**
> 阉废唯一的推导只能证明"它就是唯一的推导"，那是废话，不是证据。

---

## 8. 证据

| 检查 | 结果 |
|------|------|
| `scripts/test/r378-keybindings.test.mjs` | **9/9** |
| `scripts/test/r378-mutate.mjs` | **14/14 全部抓住，0 no-op，0 hole**，退出码非零 |
| 14 个变异电池（**格式化之后**跑） | **14/14 全绿，0 no-op，0 survived** |
| `npm run format:check` | exit 0，`All matched files use Prettier code style!` |
| `tsc --noEmit` | exit 0 |
| 全量 `npm test` | **1366 tests / 1323 pass / 43 fail** |
| 与 R377 基线（1357 / 1314 / 43）逐条比对失败**名字** | **0 条新增失败，0 条修复** —— 43 条既存失败集合完全相同 |

> 最后一行是本轮的核心断言：**新加了 9 条门禁，失败集一个字节没变。**
> 失败集合是**按名字**比对的，不是按计数 —— 计数相同但名字换一批，是另一种回归。
>
> "格式化之后再跑电池，电池依然全绿"是 R376 定下的证明方式：它证明的是
> **针脚已经与排版无关**，而不只是"这一次的针脚碰巧还能用"。

---

## 9. 留下的规矩

1. **一份真相只能有一份拷贝。** 第二份拷贝无法被验证，而且会漂移。
2. **注册表和 handler 双向绑定**：handler 绑的必须写进注册表，注册表写的必须真的被绑。
3. **帮助不承诺这个 daemon 做不到的事** —— 答案来自拒绝表，不是手写。
4. **报 daemon 解析出来的结果** —— 这里是"报 daemon 做不到的结果"。
5. **一个提取必须有大括号配对，不是猜的字符窗口。**
6. **扫描器必须跳注释** —— 撇号陷阱在 TS 里同样存在。
7. **正则没锚定时，"改短"不等于"改坏"。** 判据是行为真的变了吗。
8. **只有单一推导的性质，不写"阉废门禁"用例。**
9. **位置锚点要挑一个"活着、且有理由活下去"的东西。** 死代码当锚点 = 定时炸弹。

---

## 10. 后续（不在本轮范围）

- `/clear` 仍然不真的清 scrollback（它诚实地说了自己是 no-op）。
- 面板级（`MemoryPanel` / `SubagentPanel` / `EffortPicker` …）的**组件内**按键
  仍然各自手写 —— 本轮只覆盖了 app 级 handler 与输入框这两个全局作用域。
  组件内的键是**上下文相关**的，语义上不冲突，但它们也是第三份拷贝的残留。
- daemon 侧的真缺口（`agent/setDefault` / `rewind` / `session/tokens`）仍未补。
- `todo_continuation_countdown` 通知仍被 TUI 丢弃 —— Claude Code 的
  "Continuing automatically at 3:45pm · esc to cancel" 那一套还没做。
