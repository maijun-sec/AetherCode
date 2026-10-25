# R381 — 可达性是测量出来的、散文也是一张表、以及一个看不见代码的扫描器

> 轮次：R381 · 日期：2026-10-25 · 分支：`main`
> 上一轮：[R380 — 命令面只有一份名单](R380-one-command-list.md)

---

## 0. 这一轮做了什么

R379 把十个面板的按键提示收进了注册表（`src/componentKeys.ts`），但留了三个尾巴：

1. **范围是手写的。** R379 自己的备注说"还有 9 个可达组件手写按键表"，然后列了 9 个。
   这份名单是**错的**。
2. **注册表不支持"散文"。** 输入框的提示是一整句话
   （`type a prompt — Enter to send, shift+Enter for newline, / for commands, …`），
   不是一串键值对。
3. **门禁的扫描器看不见代码。** `scripts/test/ts-scan.mjs` 把 JSX 文本里的撇号
   当成字符串起始，一路吞到下一个撇号为止。

R381 把这三件事都做掉了，并且在做的过程中**挖出三个真的 bug**——不是"提示不精确"，
是**用户按了键什么都没发生**、**用户敲了命令被告知"命令不存在"**。

---

## 1. 三个真问题

### 1.1 扫描器吞掉了 47 行真实代码

`src/tui.tsx:4017` 有一句文案：

```tsx
token count is the daemon's estimate (transcript characters ÷ 4), not a tokenizer
```

`ts-scan.mjs` 的旧规则是：遇到 `'` 就算字符串开始，一直读到**下一个** `'`。
`daemon's` 的撇号于是吞掉了 4017–4064 共 47 行——**其中包括
`<GrantsManager grants={grants} onRevoke={onRevoke} />` 这个面板的渲染**。

后果不是"某个断言变红"，而是**所有断言都变绿**：
门禁读到的后半段是空白，而它以为那是代码。

```
$ node scripts/test/probe-swallowed-literals.mjs
src/tui.tsx
   "'" lines 4017..4064  "s estimate (transcript characters ÷ 4), not a tokenizer
2 file(s), 320 multi-line literal(s)
```

**修法**（`scripts/test/ts-scan.mjs:122`）：单引号/双引号字面量在 TypeScript 里
**不可能跨行**，所以没有同行搭档的引号不是字面量，是**散文**。遇到它就当普通字符，
**并且不抹掉它周围的文本**——因为那正是 JSX 文本，门禁要读的按键提示就在里面。

这条规则现在和 `strip-comments.mjs`（另一个扫描器）**完全一致**。
此前两个扫描器各有一套规则，而不一致的那个正是没人测的那个。

> **Lesson 1212 —— 一个没有自测的扫描器，它的 bug 只能靠某个恰好踩到的门禁来发现，
> 那不叫计划。**
> `scripts/test/ts-scan.mjs` 此前没有自己的测试；R381 补了 9 条（`ts-scan.test.mjs`）。

### 1.2 `SubagentSpawnCard` 承诺了一个它永远做不到的动作

卡片头部写着 `(Enter expand)`，但：

- 它的 `useInput` **只绑定了 `x`**；
- 它的 `open` 状态**从来没有被 `setOpen` 改过**——`setOpen` 声明了，一次都没调用。

也就是说：**这张卡永远展不开，任务描述永远看不到**。
装饰性的承诺比不承诺更糟（Lesson 1201）。

**修法**：卡片直接显示正文。终端没有鼠标，桌面版靠点击展开，终端里没有这个交互，
所以"折叠/展开"这个fiction 整个删掉——`Enter` 归提示框（发消息），
抢它会让用户**想发消息却顺手展开了卡片**。

### 1.3 欢迎页教了两个不存在的命令

| 文案 | 说的命令 | 实际 |
| --- | --- | --- |
| `subagents can run in the background — press Ctrl-S to see live jobs, or /spawn <intent> to launch one inline.` | `/spawn` | **不存在**（87 个命令里没有） |
| `you're in PLAN mode — … type a prompt or /plan to proceed.` | `/plan` | **不存在**（真名是 `/lastplan`，而它并不能"proceed"） |

而且第二条对 PLAN 模式的**描述也是错的**：它描述的是 Claude Code 的 plan mode，
而 AetherCode 的 `PermissionMode.PLAN` 是**权限模式**——
daemon 自己的措辞是"built-in safe tools run, but mutations prompt"
（`aethercode-core/.../permission/PermissionMode.java:36`）。

**修法**：
- `Ctrl-S` 这个标签改为**从 app 级注册表取**（`src/keybindings.ts`），
  不再硬编码；`/spawn` 那半句删掉（用户没有这个操作）；
- PLAN 提示改为描述**这个 daemon 真实的 PLAN 模式**，并指向真实命令 `/mode DEFAULT`。

> **Lesson 1213 —— 提示也是承诺。**
> 一个"按 Ctrl-S 就能看到"的提示，和一个"敲 `/spawn` 就能启动"的提示，
> 都是在向用户收债。R381 的门禁现在会检查欢迎页提示里点名的命令**真的存在**。

### 1.4 版本号有四份拷贝，两份互相矛盾

| 位置 | 值 |
| --- | --- |
| `aethercode-tui/src/version.ts`（**唯一真相**） | `0.2.1` |
| `aethercode-tui/package.json` | `0.2.1`（门禁钉住与上一行一致） |
| `aethercode-cli/.../TuiCommand.java` | `aethercode-tui 0.2.1` |
| `aethercode-tui/src/components/Welcome.tsx` | **`v0.2.71`** ← 硬编码 |

也就是说：**欢迎页显示 v0.2.71，而 `--version` 和 `/update` 面板显示 0.2.1**——
同一个应用里两个版本号。

**修法**：`Welcome.tsx` 不再持有副本，直接渲染 `TUI_VERSION`；
出包版本统一为 **0.2.72**（`version.ts` + `package.json` + `package-lock.json`
+ `TuiCommand.java` 一起改，r374 门禁继续钉住它们一致）。

> **Lesson 1216 —— 两份拷贝就是两份真相，抄错的那份没人会发现，
> 因为两边的测试都在检查自己那一份。**

---

## 2. 改了什么

### 2.1 可达性：从"声明"变成"测量"

新模块 `scripts/test/reachability.mjs`（`analyzeReachability()`，`reachability.mjs:144`）
从两个入口（`tui.tsx` / `line.ts`）走 import 图，并且**把每个 JSX 标签解析回它真正的来源**：

```js
const target = map.get(tag) ?? (own.has(tag) ? f : null);   // reachability.mjs:192
```

这一步不能省：`Welcome.tsx` 渲染了一个 `<Pill>`，但它**自己定义**了 `Pill`——
所以 `components/Pill.tsx` 是死的。只按标签名匹配会把死文件判成可达。

实测（R381，2026-10-25）：

| 类别 | 数量 |
| --- | --- |
| `.tsx` 组件（有导出） | 56 |
| import 图可达 | 41 |
| **真正被渲染（用户能打开）** | **39** |
| import 了但从未渲染（死 UI） | 2（`NotificationSettings` / `StaleWarning`） |
| 从未被 import | 16 |

R379 名单里的 9 个，实际只有 **7** 个可达——另外两个
（`NotificationSettings`、`NotificationDetail`）**用户根本打不开**。

### 2.2 注册表：散文也是一张表

`src/componentKeys.ts` 加了三样东西：

| 能力 | 说明 |
| --- | --- |
| `prose` | 整句的提示（`proseFor(component, id)`），每个键都写明**谁绑定它** |
| `via: "global"` | 自己没有 handler 的组件（命令面板、搜索栏、子代理面板、欢迎页）展示的 **app 级键**，对着 `src/keybindings.ts` 校验 |
| 词表统一 | 组件级原来叫 `enter`，app 级叫 `return` —— 统一成 **`return`**，否则"global 的键"没法直接对着 app 注册表查 |

> **Lesson 1214 —— "可达"是一个测量值，不是一句声明。**
> 散文地断言可达性，和手写一张按键表一样会漂。

### 2.3 改造了 12 个组件

| 组件 | 改前 | 改后 |
| --- | --- | --- |
| `ThemePicker` | `↑/↓ (j/k): navigate Enter: select Esc: close` | 注册表 |
| `LogViewer` | 4 处手写（含重复的头尾两份） | 注册表（`[/]` 只在真有第二个标签时出现） |
| `PresetSelector` | 4 处手写 | 注册表 + 4 条散文 |
| `SessionControl` | `c/p/s to act · … · Enter to confirm`（**藏了 Space**） | 注册表（`Enter/Space: confirm`） |
| `SubagentSpawnCard` | `(Enter expand)` + `(press x to dismiss)` | 注册表（并删掉那个假承诺） |
| `StatusBar` | `(Enter)` | 注册表（`Enter: re-attach`） |
| `InputBox` | 两段散文 | 注册表（散文） |
| `StaleWarning` | `Enter / y to re-attach · n / Esc to cancel · Tab to swap` | 注册表 |
| `CommandPalette` / `SearchBar` / `SubagentPanel` / `Welcome` | 手写 | 注册表（`via: "global"`） |

`SessionControl` 那一格值得单说：它的三个热键是**通过常量**绑定的

```ts
const HOTKEY: Record<ControlAction, string> = { continue: "c", pause: "p", stop: "s" };
useInput((input, key) => { if (input === HOTKEY.continue) { … } });
```

旧的提取器只看 `input === "字面量"`，**这三个键对它是隐形的**——
一张漏掉它们的表照样能通过"提示与处理一致"的检查。
R381 的提取器（`key-tokens.mjs:103` `moduleConstants` / `:141` `resolveLiteral`）
会解析常量。

> **Lesson 1215 —— 一个回答了"你以为的问题"的检查器，比没有检查器更糟。**
> 它和自己是一致的。

### 2.4 门禁

`scripts/test/r381-panel-keys.test.mjs`（13 条）：

1. 没有任何源文件把代码藏在未终止的引号后面；
2. 可达性分析真的走了一遍树（不是空跑）；
3. **每个可达且手写提示的组件都在注册表里**；
4. **注册表里的组件没有手写提示**；
5. **每个调用注册表的组件都有对应的表**（删表 → 提示渲染成空）；
6. **每个注册表里的组件真的渲染了它**（没人读的标记是假成功）；
7. **每张表的键 = 处理器的键**（双向）；
8. 常量绑定的键对检查器可见；
9. 散文点名的键真的被绑定；
10. `global` 的键 app 真的提供；
11. **欢迎页提示点名的命令真的存在**；
12/13. 不可达但仍被门禁关心的组件，名单是**测量值**（钉住）。

R379 门禁里已经被 R381 覆盖的那 4 条**搬走了**（R381 是严格超集），
两处探测器不会各留一份。

---

## 3. 证据

| 事实 | 位置 |
| --- | --- |
| 撇号吞 47 行（含面板渲染） | `src/tui.tsx:4017`（旧扫描器吞到 4064） |
| 扫描器新规则 | `scripts/test/ts-scan.mjs:122` |
| 两个扫描器现在一致 | `scripts/test/ts-scan.test.mjs` 的 "the scanner agrees with strip-comments.mjs" |
| 可达性解析 JSX 来源 | `scripts/test/reachability.mjs:192` |
| 常量解析 | `scripts/test/key-tokens.mjs:103` / `:141` |
| 注册表（22 张表） | `src/componentKeys.ts:188` |
| 散文渲染 | `src/componentKeys.ts:523` |
| global 键 | `src/componentKeys.ts:593` |
| 假承诺 "Enter expand" | `src/components/chat/SubagentSpawnCard.tsx`（已删） |
| `/spawn` 不存在 | `src/components/Welcome.tsx`（已改） |
| `/plan` 不存在 | `src/components/Welcome.tsx`（已改） |
| PLAN 模式的真实语义 | `aethercode-core/.../permission/PermissionMode.java:36` |

---

## 4. 结果

| 项 | 结果 |
| --- | --- |
| R381 门禁 | `scripts/test/r381-panel-keys.test.mjs` → **13/13 绿** |
| R381 电池 | `scripts/test/r381-mutate.mjs` → **13/13 ALL MUTATIONS CAUGHT**，exit 0 |
| R379 门禁 | `scripts/test/r379-capabilities.test.mjs` → **12/12 绿**（4 条搬给 R381） |
| R378 门禁 | `scripts/test/r378-keybindings.test.mjs` → **9/9 绿**（见 §4.1） |
| 全量测试 | `npm test` → **1413 / 1370 通过 / 43 失败** |
| R380 基线 | 1395 / 1352 / 43 → **+18 测试、+18 通过、失败集合逐名相同** |
| 受影响的旧电池 | `r378` / `r379` / `r380` → **全部 ALL MUTATIONS CAUGHT**（这三家的门禁/被变异文件与本轮相交） |
| 残留快照 | 无 |

失败集合与 R380 基线**逐名相同**（43 条，全部是仓库里既有的红灯：docs 目录、
`StatusBar` 的旧断言、R77/R78/R79 的 Java 断言等），零新增、零静默修复。

R168 有一条断言钉的是 InputBox 源码里的**提示文本**；文本搬进注册表后它变红了。
按 Lesson 1210（一条用例在形状变化后变空气，优先**重定向到推导**而不是删除），
它被重定向到新位置：组件必须渲染注册表的那句 + 注册表里那句必须仍提到 shift+Enter。

### 4.1 旧电池的两个洞，都是这一轮踩出来的

**洞一：R379 电池的三个按键表用例。**
R381 把"按键表 vs 处理���"那一节从 R379 门禁搬到了 R381 门禁（后者是严格超集）。
属性搬走了，**变异用例也得跟着搬**——留在原地的话，它们会改 `componentKeys.ts`，
而 R379 门禁已经不看那张表，于是三个用例全部报洞。

**洞二：R378 电池的"欢迎页渲染空网格"。**
这一轮我给欢迎页加了一次 `welcomeBindings()` 调用（取 `Ctrl-S` 的标签），
于是 R378 电池那条**不唯一的针脚** `s.replace("welcomeBindings()", "[] as never[]")`
落在了**我新加的那一处**，网格照旧渲染，电池报洞。

而门禁那一侧也有责任：它的检查是"文件里出现过 `welcomeBindings(`"——
**一次查找也能满足它**。两处都修了：

- 针脚收窄到网格自己的那次调用（`welcomeBindings().map(`）；
- 门禁的检查改成"必须是 **map** 出来的网格"，并且要求注册表里的 `welcome` 列表**非空**
  （映射一个空列表还是空）。

> **Lesson 1217 —— 一条不唯一的针脚会落在"另一处"，然后伪装成"存活"；
> 一条能被"一次查找"满足的检查，等于没有检查。**
> 这不是两个独立的问题，是同一个问题的两面：**你检查的东西，必须比你要防的东西更具体。**

---

## 5. Lessons

- **Lesson 1212 —— 一个没有自测的扫描器，它的 bug 只能靠某个恰好踩到的门禁来发现，
  那不叫计划。** `ts-scan.mjs` 吞了 47 行真实代码（含一整个面板的渲染），
  而所有断言都变绿——门禁读空白时**并不知道自己在读空白**。
- **Lesson 1213 —— 提示也是承诺。** "(Enter expand)" 背后是一个从未被调用的
  `setOpen`；"/spawn" 背后是一个不存在的命令。装饰性的承诺比不承诺更糟。
- **Lesson 1214 —— "可达"是一个测量值，不是一句声明。** R379 说"9 个可达"，
  实际 7 个；多出来的两个用户根本打不开。
- **Lesson 1215 —— 一个回答了"你以为的问题"的检查器，比没有检查器更糟。**
  常量绑定的三个热键对旧提取器是隐形的，所以漏掉它们的表照样"通过"。
- **Lesson 1216 —— 两份拷贝就是两份真相，抄错的那份没人会发现，
  因为两边的测试都在检查自己那一份。** 欢迎页显示 v0.2.71、`--version` 显示 0.2.1。
- **Lesson 1217 —— 一条不唯一的针脚会落在"另一处"，然后伪装成"存活"；
  一条能被"一次查找"满足的检查，等于没有检查。**
  这一轮同时踩中了两面：R378 的针脚落错了地方，R378 的门禁也太松。
- **Lesson 1218 —— 两份扫描器就是两份真相。**
  `ts-scan.mjs` 跨行读引号，`strip-comments.mjs` 读到行尾就停——
  **不一致的那个正是没人测的那个**。现在两者共用同一条规则。

---

## 6. 下一轮的候选

1. **死 UI 清理**：11 个不可达却带按键提示的组件（`ConsentPrompt` / `McpLogin` /
   `McpReconnect` / `NotificationDetail` / `NotificationSettings` / `TaskPanel` /
   `UpdateAvailable` / `UpdateConfirm` / `UpdateProgress` /
   `consent/ConsentModal` / `models/ModelSelector`）——要么接上，要么删掉。
   R381 的门禁已经钉住这份名单，所以它们不会**静默**长大。
2. **app 级注册表缺 `shift+Enter`**：`InputBox` 真的绑定了它，但
   `src/keybindings.ts` 没写，帮助浮层因此不显示这条键。
3. **app 级注册表与组件级注册表合并**：现在是两张表、同一套键。
   R381 只是让它们的**词表**一致（`return`），还没合成一张。
4. **子代理卡片的 `x` 会误触**：卡片是内联在对话流里的，不受
   `state.activePanel` 那道闸门保护，用户在提示框里打 `x` 会把卡片关掉。
5. `aethercode-cli` 的 `--effort` 仍然是"记录了但没映射到 provider 参数"（R747）。
6. 真实 Ink 交互 + 80 列渲染的视觉验收仍未自动化。
