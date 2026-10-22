# R366 — 把「跨传输抄方法名」变成一条可执行的规则

> 日期：2026-10-22
> 范围：`aethercode-tui`（**不改 Java**），新增仓库级 RPC 契约门禁
> 起点：R365 末尾「下一步」里我列的 `workflow-picker`

---

## 1. 起点是一次失败

我本来打算给 Ink 补 `workflow-picker` 的面板。动手前查后端，发现
`workflow/list` 这个名字**在 TUI 连的那个 daemon 上不存在**。

于是没有继续接线，而是回头审了全部 RPC 调用点。

---

## 2. 结果：13 个方法名，3 个是真 bug，10 个是真缺口

扫描口径：**TUI 发的名字** vs **TUI 实际连的 daemon 注册的名字**。
先确认 TUI 连的是谁 —— `ac-tui.ts` 启动 `aethercode.jar --daemon`，
即 `DaemonRunner` → `AetherCodeMethods.registerAll`（+ `MemoryMethods`），
**不是** `SupervisorProcess`。

### 2.1 三个真 bug（本轮修掉）

| 名字 | 真相 | 症状 |
|---|---|---|
| `model/set` | **DTO 有**（`protocol/rpc/model/ModelSetParams`，Phase 1.2 / T-1-19），**handler 从没注册过** | `/model <name>` 全程 -32601 |
| `workflow/run` | 注册在 `SupervisorRpcServer`（aethercode-tasks），那是独立 task CLI，TUI 从不启动 | `/workflow <name>` -32601 |
| `workflow/list` | 同上 | `/workflow` 面板永远失败 |

`model/set` 最阴险：`commands.ts` 里那段注释写

> We keep the legacy `setModel` method as a secondary alias so older
> daemons still respond. The Phase 6.3 form wins when the daemon supports it.

**两句都是假的。** `setModel` 不是 alias，它是唯一的 handler；
`model/set` 从来没被注册过。DTO 写好了、注释断言了、没人去读注册点。

**并且参数名也要改**：`setModel` 读 `stringOrThrow(p, "model")`，
死的 `model/*` DTO 用的是 `name`。只改方法名仍然会 -32602。

### 2.2 十个真缺口（`BACKEND_MISSING`，逐条附理由）

`agent/setDefault`、`effort/set`、`grants/revoke`、`permission/list`、
`permission/revoke`、`rewind`、`session/tokens`、`task/kill`、`task/pause`、
`task/resume`。

后三个属于 `SupervisorRpcServer`（同样是那个 TUI 不启动的独立 CLI）。
其余在注册名里根本找不到对应概念。

> 这十条现在**有名字、有理由、被计数**。之前它们散落在源码里，
> 每一个都在用户敲下去之后才 -32601。

---

## 3. 顺带挖到的第四个 bug：成功但空白的面板

`line.ts` 的 `asRows()` 有个「容错」信封列表：

```ts
["items", "entries", "rows", "results", "sessions", "agents",
 "servers", "todos", "data"]
```

而 daemon 实际返回的是：

| RPC | 返回 | `asRows` 认得？ |
|---|---|---|
| `listSessions` | `{sessions:[…]}` | ✅ |
| `listAgents` | `{ok, count, agents:[…]}` | ✅ |
| `listProjects` | `{projects:[…], current}` | ❌ |
| `listWorkflows` | `{workflows:[…], count, dir}` | ❌ |

所以 `/cwd-pick` 和 `/workflow` 面板**渲染成功、没有任何报错、列表是空的**。
一个列不出东西的 picker，和一个没东西可列的 picker，用户分辨不出来。

修法：把 `projects` 和 `workflows` 加进列表。一行修好两个面板。

> 这是本项目最糟的失败形状：**成功、静默、无信息**。
> R365 的 Lesson 1111（修好 RPC 后先问 UI 显示什么）在这里又应验了一次 ——
> 只是这次连「成功」都是假的。

---

## 4. 门禁

`scripts/test/r366-rpc-names.test.mjs` —— **9 例**，
`r366-mutate.mjs` —— **10 个变异全捕获**。

核心断言：**TUI 发的每个方法名都必须在 daemon 的注册表里**，
否则要么改调用点，要么进 `BACKEND_MISSING` 并写清理由。

门禁自身也被测（三条针对 harness 的变异）：

| 变异 | 为什么必须有 |
|---|---|
| 删掉一条 allowlist | 证明 allowlist 不是「万能橡皮章」—— 删了它会以未知方法冒出来 |
| 清空一条 allowlist 理由 | 理由必须真的有内容 |
| **让抽取正则失效** | 如果主断言是靠「匹配不到任何东西」而绿，那它毫无意义 |

> **Lesson：带 allowlist 的门禁，allowlist 本身必须在测。**
> 否则它就只是把「不知道」写成了「已知」。

变异里有两个专门针对「半修」：

- 改对方法名、**参数键写错** → 抓（`setModel` 读 `model` 不读 `name`）
- 把 `workflows` / `projects` 从 `asRows` 列表里删掉 → 抓

---

## 5. 连带修正的测试

`phase-6-t6-17.test.mjs` 的用例标题是
`/model <name> → model/set + /workflow [name] → picker or workflow/run`
—— 它把两个**不存在的方法名**当成契约在钉。

这种 source-pin 和 R362 那批一样：钉住的是 bug 本身。
已改成钉真名，并写明为什么变。

---

## 6. 基线

| | tests | pass | fail | failing files |
|---|---|---|---|---|
| HEAD baseline | 1081 | 1030 | 51 | 16 |
| R364 | 1154 | 1107 | 47 | 14 |
| R365 | 1164 | 1117 | 47 | 14 |
| **R366** | **1173** | **1126** | **47** | **14** |

47 个失败仍是同一批既有文件。`tsc` exit 0，`T-7-10` 绿，
R362-R366 全部 92 个门禁用例绿，**四套变异 harness 全部 ALL MUTATIONS CAUGHT**，
改动文件全部 LF。

---

## 7. 同一类缺陷的三次复现

| round | 名字 | 来源 |
|---|---|---|
| R364 | `setCwd` | desktop 的 Tauri IPC 命令 |
| R366 | `model/set` | 只有 DTO，handler 没接 |
| R366 | `workflow/list`、`workflow/run` | 另一个 server（`SupervisorRpcServer`）的注册名 |

三次都是：编译期无感、运行期统一 `-32601`、**只有去读注册点才能发现**。

所以 R366 的产出不是这三个修复，而是那条把「读注册点」变成
**每次 `npm test` 都读**的门禁。

---

## 8. 下一轮入口

1. **`BACKEND_MISSING` 的十条**。现在有名字有理由，可以逐条决策：
   - `task/pause|resume|kill` —— daemon 侧要不要补？还是 UI 该走 `SupervisorRpcServer`？
   - `permission/list|revoke` + `grants/revoke` —— `/consents` 面板现在**只能看不能撤**，
     而面板文案写的是 "Review and revoke stored tool grants"。**文案承诺了后端没有的能力**，
     这属于要改文案还是补后端的产品决定。
   - `effort/set` —— `/effort-pick` 面板能选，选了没反应。
   - `agent/setDefault` —— `/agent-pick` 同上。

   后面这三条和 R365 的 `not-wired` 一起，构成「面板能打开但按钮是死的」这一类。

2. **`/context` 命令要不要加**（R365 留下的产品决定，仍未决）。

3. **实机视觉验收**（R362 起一直欠着）。