# R364 — /cwd-pick 真的能切换了；以及门禁自己差点是坏的

> 日期：2026-10-22
> 范围：`aethercode-tui`（Ink + line 双 renderer），**不改 Java**
> 起点：R362 面板审计把 `/cwd-pick` 标成 `unavailable`，理由是"后端没有 setCwd RPC"

---

## 1. 那个结论是错的

R362 的审计在 `panelIntents.ts` 里写下：

```ts
ink: 'unavailable',
line: 'unavailable',
gapNote: 'R362 / P0.1 target. CwdSwitcher.tsx (176 lines) exists, unmounted.',
```

**两条都是假的。** 核实过程（全部 file:line，改动之前）：

| 断言 | 事实 |
|---|---|
| "line mode unmounted" | `line.ts:2962` `case "__CWD_PICK__": await panelCwdPick(ctx)` —— **早就接好了** |
| "后端没有 setCwd RPC" | 有，而且不叫 `setCwd` |

### 真正的根因

`setCwd` 是 **desktop 的 Tauri IPC 命令名**，不是 JSON-RPC 方法：

- `aethercode-desktop/src/store/index.ts:2317` 声明 `setCwd: (path: string) => Promise<void>`
- `index.ts:7494` 调用 `rpc.setCwd(path, sid)` —— 走 Tauri IPC，Rust supervisor 接管

TUI 直连 Java daemon，说的是 JSON-RPC。它把这个名字抄了过来：

- `tui.tsx` CwdSwitcher → `client.request("setCwd", …)`
- `tui.tsx` ThreadSelector 跨 cwd 切换 → `client.request("setCwd", …)`

daemon 没有这个 handler，所以**每一次 `/cwd-pick` 都死在 `-32601 method not found`**。

JSON-RPC 里的真名是 **`switchProject`**，而且早就在正确的名字上跑着：

- `AetherCodeMethods.java:1701` `dispatcher.register("switchProject", this::switchProject)`
- `HttpJsonRpcServer.java:837` `case "switchProject" -> methods.switchProject(params)`
- `AetherCodeMethods.java:2351-2409` 真实实现：`engine.setCwd` + 落 SQLite + invalidate project memory + 发 `cwd_changed`
- `/cwd <path>`（`commands.ts:468`）和 line mode 的 `panelCwdPick`（`line.ts:2398`）**一直是对的**

所以这是一个**客户端命名 bug**，不是后端能力缺失。

> Lesson：跨进程边界时，方法名是有命名空间的。Tauri IPC 的 `setCwd` 和
> JSON-RPC 的 `switchProject` 是**同名概念在两套传输里的不同名字**。抄名字
> 不抄传输，编译期永远不报错，运行期永远 -32601。

---

## 2. 修完命名之后，还有三个"UI 在成功后撒谎"

单独改名会让 `/cwd-pick` **成功**——然后用户切完目录，面板还写着自己��在老目录。那比一直失败更坏。

| # | 缺陷 | 位置 |
|---|---|---|
| 2 | TUI 没有 `cwd_changed` 的 case，daemon 一直在发这个通知 | `tui.tsx` notification handler |
| 3 | `cwd` 是启动参数（不可变 prop），`State` 里根本没有 cwd 字段 | `state.ts` / `App({cwd})` |
| 4 | line mode 同病：`displayState.cwd` 只在初始化设一次 | `line.ts` |

外加一个更根本的：**`getState` 不返回 cwd**，所以 TUI 从头到尾只知道自己是从哪启动的。
daemon 的真实 cwd 在 `listProjects` 里（`AetherCodeMethods.listProjects` 返回
`{cwd, name, active:true}`，而 `panelCwdPick` 早就依赖这个结构）。

改法：

```ts
// state.ts —— 新增（action 刻意不叫 setCwd，避免同样的混淆）
cwd: string;
type SetCurrentCwd = { type: "setCurrentCwd"; cwd: string }

// tui.tsx —— 唯一的派生点
const currentCwd = state.cwd || cwd;

// tui.tsx / line.ts —— 启动时和 daemon 对账
client.request("listProjects") → setCurrentCwd / displayState.cwd

// tui.tsx / line.ts —— 通知
case "cwd_changed" / method === "cwd_changed" → setCurrentCwd
```

`currentCwd` 收口了 **10 个**读启动参数的地方：Header pill、Welcome、Sidebar、
SDD banner、`/export` 默认路径、export frontmatter，以及**四个 `sdd.*` RPC 载荷**。
最后四个是真 bug —— 切完目录后，SDD 会被告知去一个会话已经离开的目录里建工程。

---

## 3. 门禁差点是坏的（这一轮真正的收获）

### 3.1 我自己的注释剥离器在吃代码

R364 的门禁有一条断言一直红：

```
assert.match(stripComments(line), /probe\(ctx,\s*"switchProject"/)   ✖
```

根因不是代码，是**测试工具**。所有 source-pin 门禁共用的剥离器是这个正则实现：

```js
src.replace(/\/\*[\s\S]*?\*\//g, blank).replace(/\/\/[^\n]*/g, "")
```

而 `line.ts:1961` 有一句字符串：

```ts
reason: "there is no update or delete RPC on memory/* — only append, get and list.",
```

里面的 **`memory/* `** 被当成注释开头，于是往后吞掉 **916 行、约 24 KB 真代码**
（1961-2877）。落在那个窗口里的断言，全部在读空白。

改成一个真正的 scanner（跟踪字符串 / 模板字面量 / `${}` 嵌套），
放在 `scripts/test/strip-comments.mjs`，四个门禁共用。

**影响评估**（改之前确认过，不是猜的）：

- `tui.tsx` / `state.ts` / `commands.ts` 都不含这个坑，剥离结果正确
- 只有 `line.ts` 中招；而 R363 的全部 `line.ts` 断言都在 3181+ 行，**在窗口之外**，
  并且变异测试全部抓到 —— 所以 R363 的门禁是有效的
- 但它是个地雷：下一个落在 1961-2877 的断言会静默失效

### 3.2 我自己写的"防呆检查"是装饰品

新的 scanner 我加了一个 `assertNotOverStripped` 守卫。它写成：

```js
const ratio = (original.length - stripped.length) / original.length;
```

**永远是 0。** 因为剥离器**按设计保留长度**（这样位置断言才有效），
长度差恒为零。守卫从来没触发过，而且抓不到它本该抓的东西。

改成逐字符比对（数非换行字符里有多少被置空）之后，它立刻抓到了真问题：
`lineInput.ts` 51.5% 被置空。查下来是虚惊（那个文件确实注释密度高，全仓最高是
`rpc.ts` 的 64.1%），阈值从 45% 调到 85%——真实最大值之上、灾难性失步之下。

### 3.3 变异测试又一次抓到了我自己

`r364-mutate.mjs` 12 个变异，第一轮**5 个没被抓住**：

| 变异 | 为什么漏 |
|---|---|
| 把 `listProjects` 改名 | 断言用 `listProjects` 子串匹配，`"listProjectsDISABLED"` **包含**它 |
| 删掉 Ink 的 `cwd_changed` handler | 变异正则没匹配上真实缩进，是个 no-op |
| 删掉 line mode 的启动对账 | 变异改错了 `listProjects` 调用点（`panelCwdPick` vs 启动那段） |
| 让空 cwd 覆盖真 cwd | 断言是**空转的**：`INITIAL.cwd === ""`，所以 `"" === ""` 天然成立 |
| 同上（第二个） | — |

最后一条最典型：**测试名说的是"空值不许覆盖"，实际断言的是一个恒真式**。

修法分别是无词边界的匹配、锚在真实缩进上、锚在正确的调用点、以及**先把 cwd 设成真值再试空值**。

### 3.4 顺手修掉一个我自己造成的 CRLF 事故

用 `[IO.File]::WriteAllLines` 做三行替换 —— 它按 `Environment.NewLine` 拼接，
在 Windows 上就是 CRLF，于是**整个 `tui.tsx` 被从 LF 转成了 CRLF**，
违反本项目 LF 约定，也正是上面那条变异正则 `\n        }` 匹配不上的原因。

已恢复 LF 并对 `src/` + `scripts/` 全量校验（其余 CRLF 文件是历史遗留，未被我触碰）。

> Lesson：PowerShell 的 `[IO.File]::WriteAll*` 和 `Get-Content|Set-Content`
> 是同一类陷阱的两面——编码和行尾都得显式指定。改动 TS 源码优先用 Edit 工具。

---

## 4. 门禁

`r364-cwd-rpc.test.mjs` —— **12 例**，`r364-mutate.mjs` —— **12 个变异全捕获**。

最要紧的一条是负向断言：

```js
// 剥离注释后，源码里不允许再出现 request("setCwd")
const calls = tuiCode.match(/request(?:<[^>]*>)?\(\s*"setCwd"/g) || [];
assert.equal(calls.length, 0, …);
```

必须**先剥离注释**才能成立——因为 R364 的注释里到处在引用 `setCwd` 来解释这个 bug，
不剥离的话断言会被自己的文档满足。

另外两条防"顺手重构"：

- `panelIntents` 里 cwd 面板必须声明 `ink: 'mounted'` / `line: 'rendered'`
- `/cwd` 与 `panelCwdPick` 必须继续用 `switchProject`（它们本来就是对的，
  要防的是有人为了"和坏的那处保持一致"把它们改回去）

---

## 5. 基线

| | tests | pass | fail | failing files |
|---|---|---|---|---|
| HEAD baseline | 1081 | 1030 | 51 | 16 |
| R362 | 1119 | 1071 | 48 | 14 |
| R363 | 1142 | 1095 | 47 | 14 |
| **R364** | **1154** | **1107** | **47** | **14** |

47 个失败全部落在同一批既有文件里，与 R363 完全一致，无一属于 R362/R363/R364。
`npx tsc -p tsconfig.json --noEmit` exit 0，`T-7-10` 保持绿。

顺带修了一个 R363 遗留的 source-pin：`r33-pills.test.mjs` 钉的是
`<Header state={state} cwd={cwd} />` —— 钉的正是"启动参数"这个 bug 本身，
改成钉 `currentCwd`。

---

## 6. 仍然明确没做假成功的部分

- **`agent/setActive`、完整 effort 域、MCP RPC、memory update/delete** 后端确实不存在，
  面板给出明确的不可用提示，不做假成功。
- R362 的 12 类面板 + 队列 strip 仍欠一次**实机视觉验收**（Ink 真实交互 / 80 列宽度）。

> 讽刺的是 `/cwd-pick` 的诚实降级被标成 `unavailable`，而真正缺后端的
> `memory-edit`（无 update/delete RPC）也是 `unavailable` —— 两种完全不同的
> 情况在同一个字段里长得一模一样。R364 把前者纠正了；**下一步应该给
> `PanelIntent` 区分 "backend missing" 和 "not wired"**，否则这个字段会继续
> 骗人。这条留作 R365 的入口。