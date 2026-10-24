# R376 — 让「提交前先 format」从习惯变成契约

> 轮次：R376 · 日期：2026-10-24 · 分支：`main`
> 上一轮：[R375 — 让运行控制面真的能用](R375-run-control.md)

---

## 1. 缺陷：一条写下来了、但没法执行的规则

用户的常驻规则是：

> 每次提交之前，需要将代码format之后才能提交。

这条规则本身没有争议。问题是它**当时无法被执行**：

| 障碍 | 证据 |
|------|------|
| 仓库里有 `prettier` 和一个根级 `format` 脚本，但**没有任何一个包是 prettier-clean 的** | `aethercode-tui/src` 86/100、`aethercode-desktop/src` 230/231、`aethercode-memory/src` 53/64、`aethercode-compact/src` 18/25、`aethercode-themes/src` 13/29 |
| 根级 `format` 脚本会扫 `**/*.{ts,tsx,js,json,md}` —— 跑一次就是 400+ 文件的无关改动 | `package.json` 的 `format` 脚本 |
| TUI **没有** `.prettierrc.json`，所以 prettier 用的是它的默认配置（80 列、无尾逗号） | 格式化前 `aethercode-tui/` 下没有 prettier 配置文件 |
| 没有任何门禁检查"格式对不对" | 改坏格式，测试不会红 |

也就是说：规则存在，工具存在，两者之间**没有接线**。跑一次 `pnpm format` 只会得到
一个吓人的 diff；不跑，规则就只是记忆。

本轮的目标就一句：**让这条规则可以被执行，而不是被记住。**

---

## 2. 决策：作用域收在 TUI，配置按实测风格，门禁进测试套件

### 2.1 作用域：只做 TUI

其它四个包（desktop / memory / compact / themes）要么不干净，要么根本不在
TUI 这一轮的工作面里。把它们一起格式化会产生 400+ 文件的无关 diff，**污染
每一次提交**，而这正是"格式规则难落地"的头号原因。

- TUI 获得自己的 `.prettierrc.json` 和 `format` / `format:check` 脚本。
- 根级新增 `format:tui` / `format:check:tui` 作为单一入口。
- 根级原有的 `format` 脚本**保持原样**（它是一次全仓扫描，不是提交前的动作）。
  等其它包也干净了，再把它收窄或拆开 —— 那是另一件独立的工程。

### 2.2 配置：按实测的房屋风格，不按 prettier 默认值

不猜阈值，先量。格式化前 `aethercode-tui/src`（100 个 `.ts`/`.tsx`，32614 行）：

| 指标 | 格式化前 | 格式化后（printWidth 100） |
|------|---------|---------------------------|
| p50 行宽 | 35 | 31 |
| p90 行宽 | 68 | 67 |
| p99 行宽 | 103 | 95 |
| 最长行 | 496 | 463 |
| > 80 列的行 | 1213 | 1110 |
| **> 100 列的行** | **374** | **75** |

读法：TUI 的房屋风格本来就在 **~68 列**（p90），所以 `printWidth: 100` 不是
在跟现有风格打架（p90 68 → 67，基本不动），它做的是**把尾巴收回来** ——
超过 100 列的行从 374 条降到 75 条，剩下的 75 条是 prettier 拆不开的东西
（长字符串字面量、长 import 路径）。

若取 prettier 默认的 80 列，会强行改写 p90 那一段；若取 120，374 条超长行
几乎原样留下。100 是把两侧都顾到的那个值。

最终配置（`aethercode-tui/.prettierrc.json`）：

```json
{
  "semi": true,
  "singleQuote": false,
  "trailingComma": "all",
  "printWidth": 100,
  "tabWidth": 2,
  "useTabs": false,
  "arrowParens": "always",
  "endOfLine": "lf"
}
```

- `singleQuote: false` / `semi` / `trailingComma` / `arrowParens` —— 与实测一致
  （格式化前 17017 个双引号 vs 1233 个单引号；只有 `panelIntents.ts` 用单引号）。
- `endOfLine: "lf"` —— 与仓库提交形态一致（`core.autocrlf=input`，入库即 LF）。
  副作用：`src/ac-tui.ts` 工作副本里原本是 CRLF，被归一到 LF —— **入库的 blob
  本来就是 LF**，所以这不是一次内容变更，只是一次工作副本的归一。
- `tabWidth: 2` / `useTabs: false` —— TUI 现有缩进是 2 空格。

### 2.3 门禁：进 `npm test`，不是一条要记得的命令

`scripts/test/r376-format.test.mjs` 有三条断言：

1. **每个 TUI 源文件都是 prettier-clean 的** —— 逐个 `prettier.check()`，
   失败时列出**具体文件名**（不是一个计数）。
2. **格式化脚本覆盖的范围 == 门禁检查的范围** —— 门禁从 `package.json` 读
   `format` / `format:check`，断言两个目录都在里面。范围被改动而门禁没跟上时，
   这条会红。**一个枚举器**（Lesson 1168 的同族）。
3. **prettier 配置就是当初实测的那一套** —— 改配置是一个带 diff 的风格决定，
   必须是决定，不是意外。

> **实现坑（已记）**：prettier 的 **API 不会自己读 `.prettierrc.json`** —— 那是
> CLI 做的事。门禁第一次跑的时候用了 `prettier.check(src, { filepath })`，
> 于是拿 prettier 的**默认值**（80 列）去比，把 215 个文件全判成不干净。
> 正确写法是 `prettier.resolveConfig(file)` 再把结果并进 options。
> **Lesson：门禁用 API 时，配置解析是调用方的责任，不是库的责任。**

---

## 3. 格式化把门禁打瞎了 —— 这才是本轮真正的活

`prettier --write` 改写了 **213 个文件**（`src` + `scripts`），+16099 / −7314 行。
然后：

| 破坏 | 数量 | 症状 |
|------|------|------|
| 门禁里的**源码钉**失配 | 13 条新失败（全量 45 → 58） | 断言的是排版，不是语义 |
| 变异电池的**针脚**失配 | **45 个 no-op**（12 个电池全中） | 匹配不到目标 → 变异是空操作 → 用例什么都没测 |
| 门禁自身的一个**既存 bug** | 1 条 | `T-184` 钉的是 `showButton = ratio >= …`，源码是 `showButton = known && ratio >= …` |

### 3.1 no-op 是最危险的一种

变异电池的每个用例都是"把产品源码改成某个坏样子，然后门禁必须变红"。针脚是
一段**字面文本**，prettier 一重排，针脚就匹配不上了 —— 而电池报告的是
**"MUTATION SURVIVED"**（用例通过了），不是"没测到"。

换句话说：**一个 reflow 就能让一个门禁静默失明，而所有仪表盘都是绿的。**
这和 R375 的 Lesson 1187 是同一族：一个工具失败的方式比工具本身更贵。

12 个电池在格式化后的 no-op 分布：

| 电池 | r362 | r363 | r364 | r365 | r366 | r369 | r370 | r371 | r372 | r373 | r374 | r375 |
|------|------|------|------|------|------|------|------|------|------|------|------|------|
| no-op | 1 | 3 | 2 | 7 | 3 | 0 | 4 | 2 | 2 | 6 | 13 | 3 |

### 3.2 修法：钉语义，不钉排版

所有失配的钉和针脚都改成**对排版免疫**的形式：

| 原来（钉排版） | 改成（钉语义） |
|----------------|----------------|
| `'"todos", "data", "projects"'` | `/"todos",\s*"data",\s*"projects"/` |
| `"ink: 'mounted',\n    line: 'rendered',"` | `/ink: ["']mounted["'],\s*line: ["']rendered["'],/` |
| `"assert.ok(valid.has(\"continue\"),"` | `/assert\.ok\(\s*valid\.has\("continue"\),/` |
| `'"case \"install\": return \"Install an update\";"'` | `/case "install":\s*return "Install an update";/` |
| `0\.8` | `0\.8(?:0)?` |
| `>\s*\{` | `>\s*\{` |

规律：**空白可选（`\s*` / `[\s\S]*?`）、引号是字符类（`["']`）、数字写法不挑
（`0\.8(?:0)?`）**。这样以后再跑一次 `prettier --write`，针脚不会失明。

修的过程中还踩到两个**真 bug**，都不是排版问题：

1. **`??` 在正则里是"惰性可选"，不是"两个问号"。**
   `/facts\.daemonVersion \?\? "..."/`（未转义）匹配的是**空串**，
   变异因此从未发生 —— 电池报"ok"，实际什么都没做。
   正确写法是 `\?\?`。
2. **"整条断言替换"会留下半截语法。** 门禁改写用例原先把
   `assert.ok(<条件>, "<消息>")` 整条换成 `assert.ok(true, "checked elsewhere")`，
   但只匹配到条件那一段，剩下的 `"<消息>",\n);` 留在原地 → **语法错误**。
   改成**只替换条件表达式**（`... .test(runBlock)` → `true`），断言结构不动。

### 3.3 一条 anchor 纪律

`panelIntents.ts` 里每一行注册表长得都差不多（`ink` / `line` / `gapNote`）。
原来的针脚 `ink: 'mounted',\n line: 'rendered',` 会**跨行命中**：
`[\s\S]*?` 虽然惰性，但它照样会跳过本行的结尾去后面找下一个匹配。

改法是**锚到这一行自己的身份**上：

```js
/(component: "components\/UpdatePanel\.tsx",[\s\S]*?ink: )"mounted",\n(\s*)line: "rendered",\n(\s*)gapNote:/
```

> 这不是 R376 引入的问题，是 R373 电池里已经写过的教训（"跨行边界"）在
> 换引号之后重演了一次。**Lesson：一条针脚必须能说出"我指的是哪一个"，
> 而不是"我匹配到了什么"。**

---

## 4. 证据

| 检查 | 结果 |
|------|------|
| `npm run format` | exit 0，213 个文件被重写（`src` + `scripts`） |
| `npm run format:check` | exit 0，`All matched files use Prettier code style!` |
| `scripts/test/r376-format.test.mjs` | **3/3** |
| 12 个变异电池（格式化**之后**跑） | **12/12 全绿，0 no-op，0 survived** |
| `tsc --noEmit` | exit 0 |
| 全量 `npm test` | **1345 tests / 1302 pass / 43 fail** |
| 与 R375 基线（1342 / 1297 / 45）逐条比对 | **0 条新增失败，2 条修好**（`T-184` 门禁自身的既存 bug、`R35: line.ts has categorizeLine…`） |

"格式化之后再跑电池，电池依然全绿"这一条是本轮的核心断言：它证明的是
**针脚已经与排版无关**，而不只是"这一次的针脚碰巧还能用"。

---

## 5. 留下的规矩

1. **TUI 的代码提交前必须 format。** 命令：`pnpm format:tui`（根目录）或
   `npm run format`（`aethercode-tui/` 目录内）。
2. **门禁会替你记着。** `npm test` 里有 `r376-format.test.mjs`，格式不对就是红的，
   不需要任何人记得这条规则。
3. **写源码钉的时候，钉语义。** 空白、引号、数字写法都不是语义。
   判据：**这条钉能不能在一次 `prettier --write` 之后还成立。**
4. **写变异针脚的时候，同样钉语义**，并且**必须有 no-op 探测器** ——
   匹配不到目标的用例和通过的用例，在输出里长得一模一样。

---

## 6. 后续（不在本轮范围）

- 其余四个包（desktop / memory / compact / themes）各自加 `.prettierrc.json`
  并清理干净，根级 `format` 脚本再收窄。
- 根级 `format` 脚本目前仍是一次全仓扫描，**不是提交前的动作**。
- TUI 源码里剩下的 75 条 >100 列的行（长字符串、长 import），可以人工拆。
