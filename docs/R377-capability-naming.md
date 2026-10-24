# R377 — 一次「拒绝」藏住了一个能用的功能

> 轮次：R377 · 日期：2026-10-24 · 分支：`main`
> 上一轮：[R376 — 让「提交前先 format」从习惯变成契约](R376-tui-format.md)

---

## 1. 缺陷：诚实的措辞，撒谎的结论

TUI 的「能力表」(`src/rpcCapabilities.ts`) 里有四条拒绝项。其中一条是
`effort/set` —— 一个**从来不存在的 RPC 方法名**。

TUI 拒绝用户的理由，写在 Ink 面板的源码注释里：

```ts
// NOTE: the daemon exposes no effort RPC (verified against
// `AetherCodeMethods`' method registry — there is neither a read
// nor a write path for it)
```

**这个核查是真的。** `AetherCodeMethods` 的方法注册表里确实没有 "effort"。
**这个结论是错的。** 注册表里没有你找的那个词，它有的是**同义词**：

| 位置 | 叫法 |
|------|------|
| 用户 | `effort`（`/effort high`） |
| 引擎 | `variant`（`Variant` —— 一个 "per-model effort / quality preset"） |
| CLI | `--effort`（`Main.java:122`，R747 记录了但没映射到 provider 参数） |

daemon 真正提供的方法是 **`switchVariant`**，从 R285 起就在**两个传输**上：

- stdio：`AetherCodeMethods.java:1849` — `dispatcher.register("switchVariant", this::switchVariant);`
- HTTP：`HttpJsonRpcServer.java` — `case "switchVariant" -> methods.switchVariant(params);`

而 **desktop 的 Quality 下拉框一直在用它**。

于是 TUI 对用户说"这个功能不存在"，而 desktop 同时在用同一个功能。
用户按 `/effort high`，得到一句"此 daemon 不支持"。

> **Lesson 1193 — 一次拒绝，是对**另一个系统**的词汇表的断言，而这种断言无法自证。**
> 注册表里没有你要找的词，它有的是同义词。所以"我查过了，它不在"必须写成
> "我查过了，daemon 自己管它叫什么" —— 否则一个**命名差异**就会被发布成
> **一个缺失的功能**。
>
> 一个拒绝比没有拒绝更糟：它把"其实只差一个词"说成"不可能"。

---

## 2. 决策一：`/effort` 变成真的功能

`effort/set` 这一条从拒绝表里**删掉**（不是加个理由，是删掉——因为它根本
不是缺失能力）。取而代之：

- `src/rpcCapabilities.ts` 新增 **`export const EFFORT_METHOD = "switchVariant"`**，
  注释里写清"用户说 effort、引擎说 variant"这段距离。
- `src/commands.ts` 的 `case "effort"` 返回 `{ rpcMethod: EFFORT_METHOD, rpcParams: { variant: effort } }`。
- `src/effort.ts`（新）：两个渲染器**共用**的词汇表 —— `EFFORT_LEVELS`、
  `EFFORT_EFFECT`、`variantLine()`。
- `src/tui.tsx`（Ink）与 `src/line.ts`（cmd.exe 行模式）都真的调用它。

### 2.1 报**daemon 解析出来的**，不报**你请求的**

`Variant.byName` 对不认识的���名**静默回退**到 bundled default：

```java
case "xhigh" -> XHIGH;
default -> null; // unknown — caller walks fallback chain
```

所以报 "你请求的 high" 可能是在撒谎。新的 toast 报的是 daemon 的
`activeVariant`（`name` / `description` / `temperature` / `maxTokens` /
`reasoningBudget` / `extendedThinking`），例如：

```
reasoning effort → high — 1.0 / 48k
reasoning effort → xhigh — 1.0 / 64k + think
```

### 2.2 一个动作，一份实现

`variantLine()` 只存在于 `src/effort.ts`。门禁**钉住**这一点：两个渲染器
里都不得出现自己的 `function variantLine(`。

---

## 3. 决策二：剩下的三条拒绝，理由必须落在 daemon 自己的标识符上

`agent/setDefault`、`rewind`、`session/tokens` 是**真的**能力缺口。但它们
的**理由文本**当时是错的：

| 方法 | 当时的理由 | 问题 |
|------|-----------|------|
| `session/tokens` | "no per-session token RPC; **getState** carries the aggregate counters" | `getState` **根本没有**任何 token 计数器。聚合量在 `CostTracker` 里，而且是**按模型**记的 |
| `agent/setDefault` | "no 'set the active agent' RPC" | 内部简写，用户读不懂；也没说 daemon 到底有什么 |
| `rewind` | "no rewind handler; transcript rollback is unimplemented daemon-side" | 同上 |

新的理由**逐条引用 daemon 自己的标识符**，而 R377 门禁会检查**每一个被引用
的标识符确实存在于 daemon 的代码里**（**剥掉注释**再查 —— 靠一句 Javadoc
撑着的断言，是关于散文的断言，不是关于行为的断言）：

| 方法 | 现在的理由（引用 daemon 的标识符） |
|------|-----------------------------------|
| `agent/setDefault` | `AgentRegistry` 暴露 `list` / `create` / `update` / `delete`，`listAgents` 不发布任何 default 标志 |
| `rewind` | `AppState` 只通过 `appendMessage` 追加，`transcript` 是只读的 |
| `session/tokens` | `CostTracker` 把用量汇总进 `totalInput` / `totalOutput` / `byModel`，`listSessions` 的行不带 token 计数 |

> **理由要能被检查。** 一句"daemon 没有 effort 概念"没法检查；
> 一句"`CostTracker` 按模型记账"可以 —— 读者能去 `CostTracker.java` 里看。

---

## 4. 决策三：拒绝必须守住**每一条**会调它的路径

R377 调查时发现两个**没走拒绝表**的调用点：

- `src/tui.tsx` 的 **Ctrl-Z** 直接调 `rewind` —— 于是同一个能力，斜杠命令
  用**人话**回答，同一个按键却甩一个裸的 `-32601` 出去。
- `src/rpc/queries.ts:232` 在死代码里写着 `session/tokens`。

Ctrl-Z 现在先问 `unsupportedReason("rewind")`，再决定要不要发请求。门禁
钉的是**顺序**：拒绝检查必须在请求之前。

---

## 5. 附带发现：电池对着一扇**本来就红**的门禁在跑

修完上面这些，跑 R377 的变异电池 —— 15 个用例全绿，0 no-op。

**太漂亮了。** 因为门禁自己有一条断言是红的：

```ts
const body = methodsSrc.slice(methodsSrc.indexOf(`public Object ${EFFORT_METHOD}(Object params)`));
const end = body.indexOf("\n  }");   // ← 永远匹配不到
```

daemon 的收尾大括号缩进**四个空格**，不是两个。一个靠猜的字符窗口。

而这件事的后果不是"一条测试红"，是：

> **一个已经红的门禁，在任何变异下都还是红的。** 所以每个用例都报"抓到了"，
> 15 个用例全绿 —— **测的东西是零**。

红门禁是看得见的症状；看不见的是**对红门禁耸了肩的电池**。

### 5.1 修法：电池有前置条件

新增 `scripts/test/mutation-baseline.mjs`：任何变异发生**之前**先跑一次门禁，
不是绿的就**拒绝开跑**。13 个电池全部接上。

### 5.2 前置条件一加上，三个**真洞**立刻现形

| 洞 | 现象 | 根因 | 修法 |
|----|------|------|------|
| 门禁的 daemon 参数检查扫的是**整份文件** | 把 `switchProvider` 的 `variant` 改名，门禁照样绿 | `AetherCodeMethods` 里**不止一个**方法读 `variant` 参数 | 检查**收进 handler 的方法体**，用大括号配对提取 |
| 门禁的词汇检查问的是"文件里**有没有** `"xhigh"` 这个字符串" | 把目录项改名，门禁照样绿 | `effort.ts` 的**文档注释里**就有 `"xhigh"` | 改比**编译后的 `EFFORT_LEVELS`** 与 daemon 的 `Variant.BUILTIN`（从 Java **推导**出来，不是手抄） |
| 三个变异改在了**错误的出现位置** | 变异确实改了文件，但改的不是门禁看的那一处 | `String.replace` 改的是**第一个**出现 | 针脚**收窄**到目标（handler 体内 / `EFFORT_LEVELS` 里） |

> **Lesson 1195 — 一个变异改在错误的出现位置，是一个伪装成通过的 no-op。**
> 文件确实变了、报告确实说"抓到了"，而被测的那一处**根本没被碰过**。
> 和 R376 的 no-op 是同一个病：**"什么都没测"必须和"测过了"在输出里长得不一样。**
>
> **推论："某个字符串出现在这个文件里"不是对一个值的检查。** 文档注释、
> 注释掉的代码、另一个方法里的同名字符串 —— 都能满足它。要检查**值**，
> 就去读**值**。

### 5.3 顺带修掉的：4 根因为改写理由而失效的针脚

R377 重写了三条拒绝的理由文案，而 r366 / r369 / r375 三个电池的针脚是**写死
在文案上**的 —— 它们当场变成 no-op。

新增 `scripts/test/caps-table.mjs`：**按 KEY 定位表项**的共享编辑器
（`findEntry` / `readEntries` / `deleteEntry` / `setReason` / `replaceInReason`
/ `insertEntry`），三个电池都用它。**三根针脚，一份实现** —— 和 R370 把
Java 剥注释器抽成 `java-strip.mjs` 是同一个理由。

这个编辑器**自己也有自测**（`selfTest()`），并由 R377 门禁执行：一个只有变异
电池在用的工具，正是最容易藏着洞的那种代码。

### 5.4 格式化之后又抓到两个

R376 的规矩是"**格式化之后再跑一遍**电池"。R377 照做了，抓到两个：

| 洞 | 症状 | 根因 | 修法 |
|----|------|------|------|
| r377 电池**没有 `process.exit`** | 打印了 "1 MUTATION(S) SURVIVED"，**退出码还是 0** | 这个电池是本轮新写的，忘了写最后一行 | 补上 `process.exit(holes === 0 ? 0 : 1)`（另外 12 个电池本来就有） |
| "阉割门禁"的那根针脚 post-format 变 no-op | 门禁被 prettier 压成**一行**，而针脚期待 `",\n  );` | **阉废针脚和它阉废的断言，必须一样抗排版** | 改成 `[^;]*?`（跨不过语句边界）+ 尾逗号可选 |

> **推论:一个只会打印、不会失败的电池，不能报告失败。**
> 判据不是"它打印了洞"，而是"**它退出码非零**"。

### 5.5 一条操作纪律：别在电池运行时看工作树

变异电池**原地改产品源码**。R377 有一轮我在电池跑着的时候读了 `git diff`，
diff 里赫然出现 r364 的 `cwd_changed` 处理器被删 —— 那是 r364 电池的一个
**变异中间态**，不是真回归。

**电池和测试套件不能并发，电池和"看代码"也不能并发。**

---

## 6. 证据

| 检查 | 结果 |
|------|------|
| `scripts/test/r377-capabilities.test.mjs` | **12/12** |
| `scripts/test/r377-mutate.mjs` | **15/15 全部抓住，0 no-op，0 hole**，退出码非零 |
| 13 个变异电池（**格式化之后**跑） | **13/13 全绿，0 no-op，0 survived** |
| `npm run format:check` | exit 0，`All matched files use Prettier code style!` |
| `tsc --noEmit` | exit 0 |
| 全量 `npm test` | **1357 tests / 1314 pass / 43 fail** |
| 与 R376 基线（1345 / 1302 / 43）逐条比对失败**名字** | **0 条新增失败，0 条修复** —— 43 条既存失败集合完全相同 |

> 最后一行是本轮的核心断言：**新加了 12 条门禁，失败集一个字节没变。**
> 失败集合是**按名字**比对的，不是按计数 —— 计数相同但名字换一批，是另一种
> 回归。

"格式化之后再跑电池，电池依然全绿"是 R376 定下的证明方式：它证明的是
**针脚已经与排版无关**，而不只是"这一次的针脚碰巧还能用"。

---

## 7. 留下的规矩

1. **一次拒绝必须引用 daemon 自己的标识符**，而且门禁要检查那些标识符真的
   存在于 daemon 的**代码**里（注释不算）。
2. **拒绝要守住每一条路径**，不只斜杠分发器。Ctrl-Z 这种"直接调"的地方
   也要问表。
3. **报 daemon 解析出来的结果**，不要报你请求的值 —— 静默回退的 API 会让你
   说谎。
4. **一个动作，一份实现** —— `variantLine()` 只存在一处。
5. **变异电池有前置条件**：门禁在变异之前必须是绿的。
   （`scripts/test/mutation-baseline.mjs`）
6. **变异要改在被检查的那一处**。"文件里出现了这个字符串"不等于"这个值是它"。
7. **表按 KEY 定位，不按文案定位** —— 文案会被改写，键不会。
8. **阉废针脚要和它阉废的断言一样抗排版。**
9. **电池必须会失败** —— 打印"洞"不算，退出码非零才算。
10. **别在电池运行时看工作树** —— 你看到的是变异中间态。

## 8. 后续（不在本轮范围）

- `agent/setDefault` / `rewind` / `session/tokens` 仍然是**真的** daemon 缺口，
  补它们需要新的 daemon RPC。
- `aethercode-cli` 的 `--effort` 依然是 "recorded but not yet mapped to a
  provider parameter (R747)" —— 独立的缺口。
- `TaskPanel.tsx` / `src/rpc/queries.ts` / `src/rpc.ts` 里残留的 `task/*`、
  `session/tokens` 死引用。
- daemon 的 `todo_continuation_countdown` 通知仍被 TUI 丢弃。
- 其余四个包还没有 prettier-clean，根级 `format` 脚本暂时收不窄。
