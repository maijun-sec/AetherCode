# R362 — 面板意图契约与真实的 prompt 队列

日期：2026-10-22（补写于 2026-10-23）
范围：`aethercode-tui/src/panelIntents.ts`、`commands.ts`、`tui.tsx`、`line.ts`、`state.ts`、`lineInput.ts`

> 这份文档是补写的。R362 有代码、有门禁，但没有写文档 ——
> 而 R363–R367 每轮都有。数字全部在补写时从代码里重新量过，
> 不是凭记忆。

## 一句话

`commands.ts` 的 slash 命令返回一个内部 sentinel（`local: "__MEMORY_PANEL__:project"`），
两个 renderer **都不认领**；用户敲 `/memory`，屏幕上打印出字面量
`__MEMORY_PANEL__:project`。而同时，另一条路径在**丢掉用户的输入**：
运行中敲下的 prompt 只弹一个 toast 说"已排队"，然后 `return`。

## 一、面板契约

`panelIntents.ts` 成为 sentinel 与 renderer 之间的唯一契约：

- **12 个 intent，17 个 sentinel**（补写时实测）
- Ink 侧 9 个 `mounted`，line 侧 11 个 `rendered`
- 3 个带 blocker，其中 `context-meter` 是 `no-command`
  （命令本身不存在），`workflow-picker` / `update-available` 是
  Ink `not-wired` 而 line 已 `rendered`

统一的面板外壳补上了 pending / error / empty 三态和宽度自适应，
sentinel 兜底改成可读句子而不是裸 token。
`/help` 改为从注册表**推导**（`SLASH_COMMANDS_DETAILED` +
`SLASH_COMMAND_GROUPS`），顺带消掉了 `SLASH_HELP`（88）与
`SLASH_COMMANDS`（76）之间的漂移，以及用户可见文本里的开发笔记。

## 二、prompt 队列

`state.ts:240` `pendingPrompts: string[]`，三个 action：
`enqueuePrompt` / `dequeuePrompt` / `clearPendingPrompts`。

`enqueuePrompt` 的 reducer（`state.ts:1443`）做四件事：
`trim()` 后丢弃空白、与队尾相同的相邻条目去重（防止连按两下 Enter 排两次）、
`.slice(-20)` 封顶（队列每一项都要渲染，无界队列会一直长到进程死）、
写入 `history`（保留 200 条）。

`tui.tsx` 侧：2207 行取队首位置、2346 行提示
`sending queued prompt (N still queued)`、2518 行 Esc → `clearPendingPrompts`
并提示 `dropped N queued prompts`。实时发送与排队发送统一走 `sendPrompt(text)`。

## 三、为什么一个 toast 是不够的

改之前，运行中敲 prompt 得到的是：

```
model is still working — prompt queued
```

然后 `return`。**文本被扔掉了。** `state.ts` 里没有任何队列结构，
只有一个 `submitting: boolean`。

> **一个断言了代码没有实现的状态，比没有这个 toast 更糟 ——
> 用户会以为自己的输入是安全的。**
> 任何 "queued / will resume / in progress" 的字符串，背后必须有真实的
> 队列结构；否则文案必须改成描述真实发生的事（比如 "按 Ctrl+C 中断"）。

## 四、R368 — 补写这一节时发现的两个洞

补写文档时顺手审计了 R362 留下的门禁，发现它有两个洞。
两者都是同一个形状：**门禁对世界的建模比代码窄，落在缝里的东西全绿。**

### 洞 1：`INLINE_OK` 是从不被检查的白名单

R362 用一个正则豁免"内联渲染"的 sentinel 家族：

```js
const INLINE_OK = /__(EXIT|CLEAR|HISTORY|DEMO|THEME|LAYOUT|BUDGET|
  BOOKMARK|SNIPPET|EXPORT|THEMES_PICK|TUTORIAL|BANK|SDD|MEMORY_AUDIT|PICK_MODE)/;
```

它在门禁里**只被当作过滤器使用，从不与 renderer 对账**。
变异证明后果：把 `tui.tsx` 里 `__HISTORY__` 的 handler 干掉，
**22 个断言全绿**。任何命中任一家族的 sentinel 都被永久开脱，
不管有没有 renderer 认领过它。

**修法**：家族列表改成真正的数组（唯一 owner，可被测试遍历），
并加一条断言：每个被 `INLINE_OK` 豁免的 sentinel，
**必须至少被一个 renderer 以真实路由形式认领**
（`case "__X__":` / `=== "__X__"` / `=== '__X__'` / `startsWith("__X__:")`）。
再加一条：没有任何 emitted sentinel 匹配的家族条目直接判红 ——
给一个没人能发出的 sentinel 留白名单条目，就是**纯预留的抑制**。

### 洞 2：参数化 sentinel 整个逃出了检查

修好洞 1 后，重新测量发现 `emittedSentinels()` 用两个正则：

```js
/local:\s*[`'"](__[A-Z_0-9]+__)[`'"]/g   // 要求 __ 后紧跟结束引号
/local:\s*`(__[A-Z_0-9]+__)/g           // 只认反引号
```

于是**所有带参数后缀的 sentinel 一个都抓不到**：

| 之前 | 之后 |
|---|---|
| emitted 35 个 | emitted **44 个** |
| 命中 INLINE_OK 20 个 | **29 个** |
| 无用家族 3 个（LAYOUT / BUDGET / EXPORT） | **0 个** |

消失的 9 个是 `__BUDGET__:0`、`__LAYOUT__:name`、`__SNIPPET_SAVE__:path`、
`__BOOKMARK__:n`、`__MCP_LOGIN__:name`、`__MCP_RECONNECT__:target`、
`__BANK_RECALL__:kind:n`、`__THEME__:name`、`__MEMORY_PANEL__:project` ——
**全是用户敲一条命令就能触发的**。

> **Lesson 1120 第四次出现：白名单 / 正则 / 捕获模式，必须自己被测。
> "门禁建模的世界比代码窄"是同一个错误的三种外形。**

### 一个我没抓住的变异，和它说明的事

第一版 `claimedBy()` 写成 `src.includes(sentinel)`。我的变异是
把分支改成 `if (false && slash?.local === "__HISTORY__")` ——
**门禁依然全绿**。这不是门禁的错：**任何基于文本的门禁都无法发现
一个死分支**，那是 source pin 的结构性上限。

值得断言的失败不是"分支变死"，而是 **token 消失** —— 那才是真正会
ship 的失败：重构时删掉 handler，sentinel 就开始把自己打印给用户。
变异脚本因此改成"两个 renderer 同时移除 token"，门禁变红并点名
`__HISTORY__`。

## 五、门禁

`r362-panel-intents.test.mjs` 12 个用例、
`r362-help-derived.test.mjs` 11 个、
`r362-prompt-queue.test.mjs` 16 个 —— 合计 **38**。

`r362-mutate.mjs`（**R362 原本没有变异电池，这正是洞能活下来的原因**）：

| 变异 | 结果 |
|---|---|
| 两个 renderer 同时删除 `__HISTORY__` | 红 ✓ |
| 把 `emittedSentinels()` 改回两个窄正则 | 红 ✓ |
| 往 `INLINE_FAMILIES` 加一个不存在的家族 | 红 ✓ |
| 删掉"无用家族"断言 | 红 ✓ |

另外顺手修的两处：
- `StatusBar.tsx` 的 `⏩ 5` 徽章补回 "skip" 字样（R350 压成单行时丢的，
  用户扫一眼状态条无从判断那是计数器）
- `tsconfig.json` 排除 `src/**/_*.ts(x)`，`.gitignore` 覆盖各测试
  留下的 `tmp-*` 目录形状（否则一次 `npm test` 会留下约 80 个
  untracked 目录把真实改动埋掉）