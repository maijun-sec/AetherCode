# R369 — 让广告的命令诚实回答

日期：2026-10-23
范围：`aethercode-tui/src/rpcCapabilities.ts`（新）、`commands.ts`、`line.ts`、三个测试文件

## 一句话

`/help` 广告着 5 个命令,而每一个在 TUI 连的那个 daemon 上都是
`-32601`。用户敲了 `/help` 告诉他存在的命令,拿回一个裸的 JSON-RPC 错误码。

## 缺陷本体

| 命令 | 指向的方法 | daemon 是否注册 |
|---|---|---|
| `/stop`、`/kill` | `task/kill` | ❌ |
| `/pause` | `task/pause` | ❌ |
| `/continue`、`/resume` | `task/resume` | ❌ |
| `/rewind <n>` | `rewind` | ❌ |
| `/tokens`、`/token` | `session/tokens` | ❌ |
| `/agent-pick-set-default` | `agent/setDefault` | ❌ |
| `/effort <level>` | `effort/set` | ❌ |

七个都在 `SLASH_COMMANDS_DETAILED` 里(它同时喂 Tab 补全**和** `/help` 分组),
所以全部被 `/help` 广告。**R366 把它们记进了白名单,让门禁变绿 —— 但只解决了门禁,
没解决用户。** 白名单让缺口"可数且可见",可用户看见的是一个会失败的按钮。

### 为什么 Ink 侧看起来没事

`/agent-pick` 和 `/effort-pick` 的 Ink 面板**已经诚实**:警告横幅 + 明说
"this daemon has no effort RPC — the level below is recorded locally only"。
但 **line mode 是直接 `probe()` 的**,绕过了任何提示,直接拿到错误码。
两个 renderer 对同一件事给出两种诚实度,本身就是缺陷。

## 改了什么

### 1. 能力表从测试文件搬到 `src/`

关键在于**这张表原先只存在于 `r366-rpc-names.test.mjs` 里**。
它是 R366 为了"让门禁保持绿色同时让缺口可数"而建的 allowlist。

结果是:UI 需要同样的知识,但**没有地方可读**。要么复制一份,
要么继续沉默。搬到 `src/rpcCapabilities.ts` 之后:

- `handleSlash` 在派发前查它
- R366 门禁**编译并 import 它**,不再自己声明一份
- 两个理由的消费者终于读到同一张表

> **Lesson：门禁里的 allowlist 不是文档,是产品需求。
> 一旦 UI 也要用它,它就必须搬到 `src/`。**

### 2. 拦截装在 `handleSlash` 出口,不是 5 个 case 里

```ts
export function handleSlash(input: string): SlashResult | null {
  const result = routeSlash(input);
  const why = unsupportedReason(result?.rpcMethod);
  if (!result || !why || !result.rpcMethod) return result;
  return { local: unsupportedMessage(result.rpcMethod, why) };
}
```

**为什么不是逐个 case 改**:R364 和 R366 都是一个一个调用点修出来的。
装在分派器上意味着**下个月新增的命令只要指向缺失方法,自动就是诚实的**。

和 R367 的教训同一个形状:**改模型,不改调用点。**

`routeSlash`(未过滤的内层函数)被导出,因为拒绝会丢掉 `rpcParams`,
而那是唯一还在钉 `/stop <id>` 参数形状的东西 —— 悄悄丢掉它比多导出一个函数更糟。

### 3. line mode 的两处直连也查表

`/agent-pick` 和 `/effort-pick` 在用户选完之后直接 `probe()`,
不经过 `handleSlash`。两处都加了 `unsupportedReason()` 前置检查。

## 实际效果

```
/stop          -> "task/kill is not available on this daemon — the daemon has no
                   task registry, so there is no running task to kill."
/pause         -> "task/pause is not available on this daemon — the daemon has no
                   task registry, so there is no running task to pause."
/tokens        -> "session/tokens is not available on this daemon — no per-session
                   token RPC; getState carries the aggregate counters (see /metrics)."
/model gpt     -> rpcMethod=setModel          (未受影响)
/todos         -> "__TODO_BOARD__"           (未受影响)
```

**不是静默跳过**:命令仍然被广告、仍然执行,只是用一句人话回答,而不是错误码。
隐藏它会丢失可发现性;发那个请求则要花一个往返去知道我们已经知道的事。

## 门禁抓到的东西

`r369-honest-commands.test.mjs`(5 用例)+ `r369-mutate.mjs`(11 例,全捕获)。

写完第一次运行就抓到一处真缺陷:`task/pause` 和 `task/resume` 的理由写的是
**"same as task/kill"** —— 那是内部简写,用户看到的是
`task/pause is not available on this daemon — same as task/kill.`,毫无意义。
已改成各自能独立成立的句子。**门禁要求每条理由 > 25 字符,就是为了挡住这种。**

变异电池里有一条专门测**过度拦截**:如果过滤器把 `/model`、`/todos`、`/memory`
一起吞掉,门禁必须变红。只证明"坏的东西修好了"的门禁,会在过滤器弄坏其他一切时
照样放行。

### 同一个坑的第五种外形

我的两个变异被自己的 harness 判为 NO-OP:

1. **"把下限从 7 改成 0"** —— 存活了,而且**应该存活**。它只是关掉了守卫,
   没破坏任何东西,其他断言照常成立。下限要挡的是**抽取失效**。
2. **`for...of matchAll(...)` 循环天生看不见自己匹配失败** —— 正则一漂移,
   循环体一次都不执行,没有任何断言触发。**同一个错误(未检查的抽取)的第五次出现。**

修法是给两次抽取**各自**加计数下限。

## 顺带修掉的

`aethercode-tui/src/_t6_17_helpers.ts`、`_t7_06_mock.ts`、`_t7_12_mock.ts`
这三个 **测试 shim 被跟踪进了版本控制**。它们是测试运行时生成的一行
`export { X } from "..."`,src 里没有任何东西引用它们。
`.gitignore` 里已有 `src/**/_*.ts`,但 gitignore 对已跟踪文件无效 ——
所以**每次 `npm test` 都会弄脏工作树**。已 `git rm --cached`(文件保留在磁盘)。

## 验证

| 项 | 结果 |
|---|---|
| `tsc --noEmit` | 0 |
| 变异电池 × 6 | r362 **4** / r363 10 / r364 12 / r365 9 / r366 **25** / r369 **11** —— 全捕获 |
| R362–R369 聚焦门禁 | 111/111 |
| 全量 npm | 1184 / 1137 pass / **47 fail** —— 与基线一致的 14 个既有文件 |

`phase-6-t6-17.test.mjs` 和 `t457-e2e-smoke.test.mjs` 里原本有 3 处断言
**钉的是坏行为**(`assert.equal(r.rpcMethod, "task/resume")`)。已改为:
既断言拒绝发生、又用 `routeSlash` 精确钉住映射 —— **比原来更强,不是更弱。**

## 剩下没做的

- 那 7 个方法**依然没有实现**。R369 让它们诚实,不是让它们工作。
  真要工作需要 daemon 侧的新 RPC,那是另一个量级的工作。
- `/context` 的产品决策(加命令挂 `ContextMeter` vs 删占位 intent)仍未决。
- 视觉验收(真 Ink、80 列)仍未做。