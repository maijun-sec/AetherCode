# R388 —— 一个名字只能有一个处理者,一份描述只能有一份

> 打包 0.2.74 时,esbuild 在每次构建里都喊了一句话:
>
> ```
> ▲ [WARNING] This case clause will never be evaluated because it
>   duplicates an earlier case clause [duplicate-case]
>   src/commands.ts:1017:9:  case "budget": {
>   The earlier case clause is here:
>   src/commands.ts:852:9:   case "budget": {
> ```
>
> 顺着这句话查下去,发现两件事:
>
> 1. `/budget` 在**同一个 switch** 里有两个处理者,第二个是**死代码** ——
>    daemon 的 `setPhaseBudget` 因此**完全不可达**,而**目录描述的正是那个死分支**;
>    活着的那条干的是另一件事(本地成本条)。
> 2. 命令面板有**自己的一份手写描述表**,只覆盖 23 条命令 ——
>    目录里 **88 条**命令中,面板上 **65 条**的描述是**空的**。
>
> R380 号称"命令面只有一份列表",把 `/help` 和 Tab 补全都改成了从目录派生 ——
> **唯独漏了面板这一份消费者**。R378 的 Lesson 1197:
> 同一份真相的第二份副本会漂移,而且**漂移得悄无声息**。

---

## 0. 这一轮做了什么

| # | 缺陷 | 现状 | 修法 |
|---|------|------|------|
| 1.1 | `/budget` 有两个 `case` | 第二个是死代码 | 阶段上限那条改名 `phase-budget` |
| 1.2 | daemon 的 `setPhaseBudget` 不可达 | 目录却在宣传它 | 有了自己的名字,真的能调到 |
| 1.3 | 死分支里第二处谎言 | 省略第三参="上限保持不变",实际是**无限** | 第三个参数**必填** |
| 2.1 | 面板自己的手写描述表 | 88 条里 **65 条**描述为空 | 描述从目录**派生** |
| 2.2 | 面板和目录对同一命令说法不同 | `/budget`:面板"set cost budget" / 目录"phase 的上限" | 只有一份,不可能不同 |
| 3.1 | `/budget` 的目录描述 | 描述的是死分支 | 改成它真正做的事 |

---

## 1. 缺陷:一个名字,两个处理者

### 1.1 esbuild 早就说了

```
src/commands.ts:1017:9   case "budget": {        ← 永远不会执行
The earlier case clause is here:
src/commands.ts:852:9    case "budget": {        ← 真正执行的那个
```

**重复的 `case` 标签不是编译错误,也不是测试失败**:第二个分支只是永远匹配不上。
esbuild 会在构建时喊一嗓子 —— 而那行输出躺在**没人看的构建日志**里。

R380 的门禁查了"router 的每个 `case` 都在目录里"和"目录的每条都能路由",
两条都**过了**:重复的标签**两条都满足**。所以那个洞是**逻辑上**的,不是抄写错误。

### 1.2 后果:目录在宣传一个不可达的能力

| | `/budget` 活着的分支 | `/budget` 死掉的分支 |
|---|---|---|
| 干什么 | 设 `state.costBudget` —— StatusBar 成本条的**本地**阈值 | 调 daemon 的 `setPhaseBudget` —— 给某个阶段设工具调用/成本上限 |
| 目录怎么写的 | — | "set a phase's tool-call / cost cap" |
| 用户敲 `/budget implement 80` | → `usage: /budget <usd> (e.g. /budget 1.50, /budget off)` | 永远到不了 |

`setPhaseBudget` 在 TUI 里**只有那一个调用点**,而它就在死分支里 ——
daemon 明明注册了这个能力(`AetherCodeMethods.java:1548`、
`HttpJsonRpcServer.java:859`),TUI 却够不着。

### 1.3 死分支里还藏着第二处谎言

旧注释说:省略第三个参数 = "the USD cap stays"(上限保持不变)。

**不是。** daemon 的约定是 **0 = 无限**
(`PhaseTracker.Bucket.remainingCostUsd()`:`cap <= 0` → `POSITIVE_INFINITY`),
而 `setBudget` 是**覆盖**不是合并。所以省略它会**悄悄把成本上限拆掉**。

而 daemon 的 `setPhaseBudget` **要求三个字段齐全**(少 `maxCostUsd` 就回
"missing required field"),也**没有**"保持不变"的编码方式。

修法:第三个参数**必填**。**必填的参数是诚实的,一个含义是"无限"的默认值是陷阱。**

---

## 2. 缺陷:面板的第二份描述表

`CommandPalette.tsx` 有一份**手写**的 `describeCommand` switch,覆盖 **23** 条命令。

| | 数量 |
|---|---|
| 目录里的命令 | **88** |
| 面板有描述的 | **23** |
| 面板描述为**空**的 | **65**(74%) |

面板是**发现**界面——用户打开它正是因为记不住命令名。
于是**用户最需要帮助的那 65 行,一个字都不说**。

而这 23 条里的说法还会和目录打架:`/budget` 在面板里是 "set cost budget",
在目录里是 "set a phase's tool-call / cost cap"。

R380 把目录变成了 `/help` 和 Tab 补全的唯一来源,**面板是漏掉的那个消费者**。

修法:描述改成从目录**派生**(`commands.ts` 的 `describeCommand`),
手写表**删掉**。R388 的门禁从**两个方向**查:
每条命令都有非空描述,而且面板显示的**就是**目录里的那句。

---

## 3. 修法

### 3.1 命令层(`src/commands.ts`)

| 改动 | 位置 |
|------|------|
| 死分支改名 `case "phase-budget"`,并**必填**第三个参数 | `routeSlash` |
| 目录:`/budget` 描述改成它真正做的事(本地成本条,Ink only) | 目录 |
| 目录:新增 `phase-budget`(`<phase> <maxToolCalls> <maxCostUsd>`) | 目录 |
| 新增 `describeCommand(name)` —— 从目录**派生** | 目录模块 |

```ts
export function describeCommand(name: string): string {
  const entry = SLASH_COMMANDS_DETAILED.find((c) => c.name === name);
  return entry ? entry.description : "";
}
```

### 3.2 面板(`src/components/CommandPalette.tsx`)

手写的 48 行 `describeCommand` **删除**,改 import 目录那一份。

---

## 4. 门禁与变异电池

### 4.1 `scripts/test/r388-command-truth.test.mjs` —— 15 条

| § | 断言 |
|---|------|
| 1 | router 的 `case` 标签**唯一**(反向不变量;再加一个同名分支就红) |
| 2 | 目录里**每条**命令都有非空描述;不存在的命令返回空 |
| 3 | 面板的描述**就是**目录的;面板里**没有**第二份表 |
| 4 | `/budget` → 本地成本条;`/phase-budget` → 三个字段齐全;缺参数拒绝;负数拒绝 |
| 5 | daemon 两条传输上都注册/路由了 `setPhaseBudget` |

### 4.2 `scripts/test/r388-mutate.mjs` —— 14 个变异,**全部被抓**

| 组 | 变异 |
|----|------|
| 一个名字一个处理者 | 重复的 `/budget` 分支回来;无关命令的标签被重复 |
| 一份描述 | 派生函数返回空;派生函数自己编措辞;面板长出第二张表;面板不再从目录取 |
| 阶段上限诚实 | 丢掉 daemon 必填的成本字段;成本参数又变可选(悄悄拆上限);负数放行;`/budget` 不再设成本条 |
| 目录 | `/budget` 的描述退回成阶段上限;目录丢掉 `phase-budget` |
| daemon | stdio 不再注册;HTTP 不再路由 |

> **电池第一次跑也报了一个 HOLE,这次是 Lesson 1269 的又一次:**
> needle 打的是 `maxCost` 的负数检查,而门禁只传了**负的调用次数**
> (`/phase-budget implement -1 0.2`)——**代码变了,行为没变**。
> 补上门禁里"负的成本上限"那一条之后,才真的被抓。
> 结论一样:**改了代码不等于改了行为**。

---

## 5. Lessons

- **Lesson 1273 —— 一个名字只能有一个处理者:重复的 `case` 标签不是错误,是死代码。**
  它不是编译错误,也不是测试失败;esbuild 会在构建日志里喊一嗓子,
  而没人看构建日志。R380 的门禁查了"每个 case 都在目录里"和"每条都能路由",
  两条**都过**——重复的标签两条都满足。**要查的是"唯一",不是"在不在"。**

- **Lesson 1274 —— 目录描述的可能是一个没人能调到的分支。**
  `/budget` 的目录写着"阶段的工具调用/成本上限",而真正能调到的是那个
  **死分支**。目录和路由必须**指向同一个处理者**。

- **Lesson 1275 —— "保持不变"如果没有编码方式,就不要写成"保持不变"。**
  daemon 的 `setPhaseBudget` 要求三个字段且**没有**"不变"的编码,
  而 0 的含义是**无限**。省略参数 = 悄悄拆掉上限。
  **必填的参数是诚实的;一个含义是"无限"的默认值是陷阱。**

- **Lesson 1276 —— 修好一个消费者,别忘了问"还有几个消费者"。**
  R380 把目录变成了 `/help` 和 Tab 补全的唯一来源,
  **唯独漏了命令面板**——而面板正是**发现**界面。
  同一份真相的第二份副本会漂移,而且漂移得悄无声息。

- **Lesson 1277 —— 描述为空,比没有这一行更糟。**
  88 条命令里 65 条在面板上**一个字都不说**。
  面板是用户"记不住命令名"时才打开的界面。

---

## 6. 文件

| 文件 | 动作 |
|------|------|
| `aethercode-tui/src/commands.ts` | 死分支改名 + 必填参数;目录两条;新增派生的 `describeCommand` |
| `aethercode-tui/src/components/CommandPalette.tsx` | 删掉手写表,改用目录 |
| `aethercode-tui/scripts/test/r388-command-truth.test.mjs` | **新增** — 门禁 15 条 |
| `aethercode-tui/scripts/test/r388-mutate.mjs` | **新增** — 变异电池 14 例 |
| `AGENTS.md` | Lessons 索引 + 陷阱清单 |
