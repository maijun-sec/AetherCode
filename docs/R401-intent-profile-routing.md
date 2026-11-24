# R401 — 意图 → 档案路由：让「概念问题」不再长成一堆无关源码

> 2026-11-24 · 承接 `docs/R400-cancel-intent-checkpoint-design.md` §2（意图识别）与 §6（证据索引）

## 1. 症状

用户提了一个概念问题：

> AI Agent 中 checkpoint 和 hook 的关系，及如何记录代码是 human 生成的，还是 ai 生成的

引擎的回答是**生成了一批无关的源码文件**。

根因不是模型不听话，而是**它没有可听的话**：system prompt 描述的是「这个 agent 是什么」（一个写代码的助手），从来不描述「**这一轮**是干什么的」。一个被告知「你是编程助手」的 agent 拿到一句含糊的话，一定会去写代码——而一句概念问题和一个功能需求，在它眼里长得一模一样。

代价是不对称的：

| 误判方向 | 后果 | 可逆性 |
|---|---|---|
| 代码任务 → 当成问答 | 浪费一轮 | 可逆 |
| **问答 → 当成代码任务** | **用户的工作树里长出没人要的文件** | **几乎不可逆** |

⇒ 所以默认姿态必须是安全的那一端。

## 2. 上游对位：两家都没有现成的东西可抄

| | 意图分类 | 最接近的机制 |
|---|---|---|
| **claude code** | **无** | permission modes（7 种）+ subagent 的 `tools` / `disallowedTools` 允许列表 |
| **opencode V2** | **无** | built-in agent 4 个（Build / Plan / General / Explore）+ permissions = `action × resource × effect(allow\|ask\|deny)` 有序规则，最后匹配者胜 |
| **AetherCode（本轮之前）** | **无** | 子代理 role 3 个（`explore` / `general-purpose` / `coder`），是 **agent 维度**，不是**轮次维度** |

两家上游都是「**先选一个 agent / 模式，再在里面工作**」，而不是「**先看这一轮像什么，再决定用什么姿态**」。

⇒ **R401 走的是第三条路（自创）**：轮次级的意图判定 + 由此收窄工具池。

## 3. 方案

```
用户 prompt
   │
   ├─ IntentClassifier.classify(prompt)          ← 确定性规则表，零模型调用
   │      → Intent(kind, reason)
   │
   ├─ ProfileRegistry.resolve(kind, override)     ← override 优先（用户手动指定）
   │      → Profile(name, systemPrompt, deniedTools)
   │
   ├─ ProfileRegistry.prune(工具池, profile)       ← 模型可见池 + 执行器解析池，同一个池
   │
   └─ QueryEngine.buildSystemForTurn(memory, plan, posture)
          posture = profile.systemPrompt()          ← 插在 plan 之后、R737 语言锁之前
```

三个决定：

1. **确定性，不是模型调用。** LLM 分类器每轮多一次往返，而且**同一个 prompt 两次判定可能不同**。分类必须是可复现的——可复现的判定才能在 UI 上显示、才能被用户覆盖。
2. **收窄工具池，不只是加提示词。** 提示词是软的，模型可以不听；**被裁掉的工具根本不在模型看到的工具表里**，所以它调不出来。这是 R400 已拍板的「确定性逻辑走确定性路径」。
3. **默认只读。** `UNCERTAIN` → `navigator`（只读），不是「识别不出来就全量放行」。误判最多浪费一轮（下一轮重新分类就恢复），收益是写工具不可逆。

## 4. 意图分类表（7 类）

`IntentClassifier.Kind`（`aethercode-core/.../intent/IntentClassifier.java`）：

| token | 中文 label | 含义 |
|---|---|---|
| `code_change` | 代码生成 | 写 / 改代码 |
| `diagnose` | 问题定位 | 查清「为什么不工作」 |
| `research` | 概念搜索 | 找**外部**材料再作答 |
| `review` | 代码审查 | 读代码 / diff 并报告，不重写 |
| `ops` | 构建运维 | 构建 / 运行 / 部署 / 验证 |
| `answer` | 仓库答疑 | 用**本仓库**回答问题 |
| `uncertain` | 未识别 | 没有可用信号 ⇒ 只读 |

### 判定顺序（先命中先赢）

1. `RESEARCH_MARKERS` —— 用户点名了外部来源（"web search"、"官方文档"、"github"…）⇒ `RESEARCH`
2. `DIAGNOSE_MARKERS` —— 故障症状（"报错"、"不生效"、"崩溃"…）⇒ `DIAGNOSE`
   *（放在问句规则之前：「为什么报错」既是问句也是诊断，诊断更具体。）*
3. `REVIEW_MARKERS` ⇒ `REVIEW`
4. `OPS_MARKERS` ⇒ `OPS`
5. **问句分支**
   - 5a 带**概念阻断词**（`CONCEPT_BLOCKERS`：原理 / 机制 / 关系 / 是什么 / what is…）⇒ 要的是**解释** ⇒ 有本地锚点则 `ANSWER`，否则 `RESEARCH`
   - 5b 有本地锚点（`LOCAL_ANCHORS`：这个项目 / 这个文件 / 本仓库… 或路径样 token）⇒ `ANSWER`；但若带**严格动词**（`STRICT_CODE_VERBS`：实现一个 / write a…）⇒ `CODE_CHANGE`
   - 5c 无本地锚点 ⇒ 严格动词则 `CODE_CHANGE`，否则 `RESEARCH`
6. 非问句 + **宽松动词**（`CODE_VERBS`：实现 / 编写 / 重构 / implement…）⇒ `CODE_CHANGE`
7. 都没命中 ⇒ `UNCERTAIN`

### 两道防线（这是本设计的核心）

宽松动词表里有「**实现**」——而「**实现原理**」里也有「实现」。两道防线防住它：

- **概念阻断词**：出现「原理 / 机制 / 关系 / 是什么…」⇒ 这一轮要的是解释，**无论还带什么动词都不提升为代码任务**；
- **严格动词**：问句**只有**「动词 + 宾语」复合（"实现一个"、"write a"）才允许被提升为代码任务，光秃秃一个动词不行。

⇒ 不变量：**问句形状的 prompt，除非带明确的实现动词，否则永远不会被分类成 `CODE_CHANGE`。**

## 5. 档案与工具池（7 个）

`ProfileRegistry`（`aethercode-core/.../profile/ProfileRegistry.java`）：

| name | label | 写工具 | `bash` | `spawn_agent` |
|---|---|---|---|---|
| `builder` | 写代码 | ✅ | ✅ | ✅ |
| `debugger` | 定位问题 | ✅ | ✅ | ✅ |
| `operator` | 构建运维 | ✅ | ✅ | ✅ |
| `researcher` | 查资料 | ❌ | ❌ | ❌ |
| `analyst` | 读代码答疑 | ❌ | ❌ | ✅ |
| `reviewer` | 代码审查 | ❌ | ❌ | ✅ |
| `navigator` | 未识别（只读） | ❌ | ❌ | ✅ |

**唯一规则**：> **没有被要求「改东西」的一轮，拿不到写工具——也拿不到 shell。**

`file_write` / `file_edit` / `notebook_edit` 只在 `CODE_CHANGE` / `DIAGNOSE` / `OPS` 下活着。**凡是禁写工具的档案，一律连 `bash` 一起禁**——理由见 §5.1，那是实机测出来的。

> `debugger` 保留写工具是有意的：用户说「这个不生效」时，他要的多半是**修**而不是「告诉我哪儿错了」；而「没让改就别改」这句写在档案的 prompt 里。

### 5.1 shell 就是写入通道（实机发现）

初版设计里，`analyst` / `reviewer` / `navigator` 禁写工具但**保留 `bash`**（理由：只读排查需要 `git log`、构建输出）。

**实机把这个洞测出来了**（R401 live verification，决定性实验）：

| | Turn C（override=`analyst`） | Turn E（override=`auto` ⇒ `builder`） |
|---|---|---|
| intent | `code_change` | `code_change` |
| profile | `analyst` | `builder` |
| `file_write` 调用 | **6 次，6 次被拒** `unknown tool: file_write` | 1 次，**成功** |
| `bash` 调用 | 3 次 | 11 次 |
| **文件是否生成** | **是（绕过 `file_write`，经 `bash` 写出）** | 是 |

同一个 prompt、同一个会话，**唯一变量是档案** ⇒ 档案确实决定写能力 ✓。但 Turn C 的文件**照样被创建了**——模型在 `file_write` 被拒 6 次之后，改用 `echo hello > r401-probe.txt` 写了出来。

⇒ **禁掉写工具 ≠ 禁掉写入。shell 是一条独立的写入通道。**
⇒ 只读档案若留着 shell，「只读」就退化成**提示词**而不是保证——而 R401 的全部论点就是「结构性的，不是提示词」。
⇒ 修法：**凡是禁写工具的档案，一律禁 `bash`**（`ProfileRegistry.READ_ONLY_DENY`）。只读工具（`file_read` / `grep` / `glob`）保留——它们改不了任何东西。
⇒ 代价：只读档案不能跑 `git log` 了。**接受**——只读排查的主力是 `file_read` / `grep` / `glob`，而一个「结构上只读」的承诺值这个价。
⇒ 钉死：`ProfileRegistryR401Test.aProfileWithoutTheWriteToolsHasNoShellEither`（遍历所有档案的结构性断言）+ `ProfileRegistryToolNamesR401Test.theShellToolIsRealAndIsDeniedByEveryReadOnlyProfile`。

> **Lesson 1257 — 「禁掉 X」不蕴含「X 做不到」：每多一条能达成 X 的通路，就要多禁一条。** 写工具被禁了，shell 还在。
> **Lesson 1258 — 单元测试测「工具被裁了」，测不到「工具被绕过」。** 只有实机能测到绕过，因为绕过是**模型的选择**，不是代码的路径。


### 工具名必须是真名

档案里的 deny 名是 `org.aethercode.tools.StandardTools#all()` 里的**真名**（`file_write` / `file_edit` / `notebook_edit` / `bash` / `spawn_agent` …）。**写错一个名字就等于没裁**——`ProfileRegistryToolNamesR401Test`（在 `aethercode-tools` 模块）把每个名字对着标准工具集钉死。

### 收窄必须同时作用于两个池

| 池 | 用途 | 裁剪点 |
|---|---|---|
| `visibleTools` | **模型看到**的工具表 | `ProfileRegistry.prune` |
| `explicitTools` | **执行器解析**工具调用时的查找池 | 同一个 `ProfileRegistry.prune` |

只裁一个是不够的：模型看不见 ≠ 调不出来。执行器的查找池不裁，模型仍能凭记忆写出 `file_write` 并被执行。

### 子代理继承父级收窄

子代理路径**不分类**（任务是 agent 写的，不是用户写的），但**继承父级 `appState.activeProfile` 的收窄** ⇒ 只读的父级没法通过 spawn 洗出一份写入。

> `activeProfile` 不在轮末清除：惰性 Stream 下「轮末」不可观测，陈旧值只会**更严**，下一轮会被覆盖。

## 6. Wire 契约

### `run_start` 新增 4 个字段

```json
{ "type": "run_start", "runId": "...", "model": "...",
  "intent": "answer", "intentLabel": "仓库答疑",
  "profile": "analyst", "profileLabel": "读代码答疑" }
```

**判定权在 daemon，渲染器只显示**（R385 规则的延伸）。桌面端拿不到判定时保留旧值，不清空。

### 新 RPC `setProfile`

- 请求：`{ "sessionId": "…", "profile": "analyst" }`；`"auto"` / `""` / 缺省 = 交回分类器
- 响应：`{ ok, sessionId, profile, profiles }`
- **未知名字被拒绝**（不静默回落）：打错字回落成 auto，看起来就像「覆盖没生效」，用户会被留在一个被误分类的轮次里且无从判断原因
- `profiles` 字段携带 daemon 自己的档案词表（`ProfileRegistry.describeAll()`）⇒ **渲染器不留第二份名字表**
- 双传输注册：`registerAll()`（stdio）+ `HttpJsonRpcServer` 的手写 switch（HTTP）
- 语义与 `setPermissionMode` 一致：**持续生效直到用户切回 auto**，按会话隔离

### `getState` 新增

`profileOverride` + `profiles`。

## 7. 桌面端

`Header` 上一个徽标（`data-testid="header-intent-chip"`）+ 一个下拉选择器：**自动 / daemon 提供的档案列表**。失败不吞，落一条 `[档案切换失败]` 的 isError 消息。

## 8. 验证

| 层 | 文件 | 例数 |
|---|---|---|
| 分类器 | `IntentClassifierR401Test` | 16 |
| 档案 / 工具池 | `ProfileRegistryR401Test` | 11 |
| 引擎注入 | `QueryEngineIntentR401Test`（用**真实** `StreamingToolExecutor`） | 6 |
| RPC / wire | `AetherCodeMethodsProfileR401Test` | 9 |
| 工具名真实性 | `ProfileRegistryToolNamesR401Test` | 5 |
| 桌面 store | `intentR401.test.ts` | 7 |
| 桌面 Header | `HeaderIntentR401.test.tsx` | 4 |

**变异验证**：9 个变异全部按设计变红，**0 存活、0 还原不干净**（SHA256 逐字节校验）。红集合覆盖：概念阻断词失效、宽松动词越权、姿态指令位置跑偏、模型看到未裁剪的工具表、被裁工具仍可执行、HTTP switch 漏注册、override 不生效、未知 intent 回落不安全。

**全 reactor**：31/31 模块 SUCCESS，644 个测试类 / 6,209 例 / 0 失败 0 错误。
**桌面端**：142 个测试文件 / 1,506 例全绿 + `tsc -b --noEmit` clean。

### 8.1 实机验证（18 项检查）

跑在**真的 daemon** 上（`D:\work\tmp\r401-live.mjs`），会话的 cwd 是一个**一次性目录**——万一模型还是写了文件，落点在临时目录而不是用户的工作树。

| 检查 | 结果 |
|---|---|
| daemon 判定了意图，`run_start` 带上 `intent` / `intentLabel` | ✅ `intent=answer` `intentLabel=仓库答疑` |
| 概念问题**不是**代码任务 | ✅ |
| 选了档案，`run_start` 带上 `profile` / `profileLabel` | ✅ `profile=analyst` `profileLabel=读代码答疑` |
| 概念问题那一轮**没调用任何写工具**、**工作树没多文件** | ✅ |
| `setProfile` 拒绝未知名字 | ✅ `ok=false error=unknown profile: not-a-profile` |
| 拒绝响应带 daemon 自己的档案词表（7 个） | ✅ |
| `setProfile` 接受合法名字 | ✅ |
| **override 压过分类器**（prompt 本身是 `code_change`） | ✅ `intent=code_change` 但 `profile=analyst` |
| 切回 `auto` 后分类器重新掌权 | ✅ `profile=builder intent=code_change` |
| 只读档案下**文件没被创建** | ✅（**修掉 shell 漏洞之后**才成立，见 §5.1） |
| `auto` 下同一 prompt **写工具又可用了、文件被创建** | ✅ `file_write` 成功，`r401-probe.txt` 落盘 |
| 每轮裁剪**没有改坏会话池**（35 → 35） | ✅ |
| 探针内容**没有漏进别的会话的转录**（内容级检查，不是数条数） | ✅ |
| 会话数 5 → 5 | ✅ |

⇒ **决定性实验**：同一个 prompt、同一个会话，**唯一变量是档案** ⇒ 档案决定写能力。

**修复后**再跑一遍，同样两个轮次：

| | Turn C（override=`analyst`） | Turn E（override=`auto` ⇒ `builder`） |
|---|---|---|
| intent / profile | `code_change` / `analyst` | `code_change` / `builder` |
| 写工具 / shell 调用 | `file_write`×4 + `file_edit`×1 + `bash`×1 —— **6 次全部被拒** | `file_write`×2 —— **全部成功** |
| **文件是否生成** | **否** | **是**（`r401-probe.txt` 落盘） |

⇒ 只读档案下**写工具和 shell 都调不动**，文件不存在；同一 prompt 交给分类器，写工具立刻可用、文件落盘。

另一条独立的实机证据（`researcher` 档案那一轮，用户原始那个 prompt）：模型调了 7 次 `bash`，**7 次全部被拒** `unknown tool: bash`；`file_write` / `file_edit` **一次都没被调用**；工作树零新增文件。
⇒ **执行器确实按裁剪后的池子解析工具**——模型（凭记忆或幻觉）写出的工具名会被当场拒绝。



## 9. 本轮附带修掉的一处回归（重要）

R401 往 `QueryEngine` 里加了约 600 字符，**打红了 R735a 的源码 pin**：

```
QueryEngineR735Test.tryAdvance_hasOuterTryCatchRuntimeException  FAILED
```

先分清是**实现坏了**还是**量具坏了**——量出来的是：`catch (Throwable t)` 在偏移 18,899，`runtime_exception` 在 **60,110**，而 pin 的窗口是 **60,000**。**差 110 字符。**

⇒ 实现完好，是 pin 的量具不够宽。但顺着这条线查下去发现了**两个更糟的问题**：

### (1) 这个 pin 一直是空转的

旧 pin 搜的是**裸词** `runtime_exception`。而该词在文件里的**第一次**出现是 catch 内部的一句**注释**（"stopReason is prefixed `runtime_exception` so …"），真正的**赋值**在 2.5 kB 之外、**窗口之外**。

⇒ 它声称在验证「catch 把异常翻译成 RunEnd」，实际验的是**散文里提了一句**。

**证据**（本轮变异脚本 `D:\work\tmp\r401-pin-mutate.mjs`）：

| 变异 | 期望 | 新 pin | **旧 pin** |
|---|---|---|---|
| P1 外层 catch 改成 `catch (Exception t)` | 红 | 🔴 红 | **绿** |
| P2 stopReason 字面量改成 `"boom: "` | 红 | 🔴 红 | **绿** |
| P3 catch 不再 emit 终端 RunEnd | 红 | 🔴 红 | **绿** |
| P4 只改注释（阴性对照） | 绿 | 🟢 绿 | 绿 |

三个真实缺陷下旧 pin **全部说绿**。

### (2) 修法：pin 不变量，不 pin 距离

新 pin 用**结构锚点**代替字符窗口：

1. 找 `stopReason = "runtime_exception: "` 的**赋值**（代码，不是散文）
2. 它必须落在 `tryAdvance` 的方法体内（括号匹配）
3. 最近的**外层** `catch (Throwable t)` 块必须**包住**它
4. 那个 catch 必须为同一个 stop reason emit 终端 `StreamEvent.RunEnd`

括号匹配器跳过注释、字符串、字符字面量与文本块 ⇒ 方法可以随意变长，pin 不会再被无关改动打断。

匹配器本身**带自测**（`matchBrace_ignoresBracesInCommentsAndLiterals`）：一个没有自测的扫描器，它的 bug 会被「恰好撞上它的那个门禁」发现——那不叫计划。

> **Lesson 1253** — 一个 pin 报告「保护的东西没了」，而它就在那儿，是量具坏了不是代码坏了。
> **Lesson 1254** — **断言被散文满足 = 这个断言是空转的。** 搜裸词永远搜得到注释；搜「赋值形状」才搜得到代码。
> **Lesson 1255** — 字符窗口是**排版**的代理，不是**契约**的代理。方法会长；契约不会。
> **Lesson 1256** — 阴性对照是必需的：一个「所有变异都红」的 pin 也可能只是「所有东西都红」。

## 10. 不在本轮范围

- **R403** — checkpoint 落盘（跨重启 rewind）+ 取消时打点
- **R402** — 桌面端 checkpoint 菜单（daemon RPC 已就绪，纯前端）
- 意图**不自动选 workflow**（沿用 R400 拍板）

## 11. 证据索引

| 事实 | 出处 |
|---|---|
| 分类器实现 | `aethercode/aethercode-core/src/main/java/org/aethercode/core/intent/IntentClassifier.java` |
| 档案与裁剪 | `aethercode/aethercode-core/src/main/java/org/aethercode/core/profile/ProfileRegistry.java` |
| 轮次态（值传递） | `aethercode/aethercode-core/src/main/java/org/aethercode/core/intent/RunContext.java` |
| 姿态注入点 | `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`（`buildSystemForTurn`） |
| 双池裁剪 | `.../engine/StreamingToolExecutor.java`（`run(calls, appState, toolPool)`） |
| 主路径 / 子代理路径 | `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` |
| wire / RPC | `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` |
| HTTP 注册 | `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java` |
| 桌面 store | `aethercode-desktop/src/store/index.ts` |
| 桌面 Header | `aethercode-desktop/src/components/Header.tsx` |
| 上游对位（claude code） | `https://code.claude.com/docs/en/sub-agents.md`、`https://code.claude.com/docs/en/permission-modes` |
| 上游对位（opencode V2） | `https://opencode.ai/docs/agents.md`（V2 built-ins：Build / Plan / General / Explore；**无 scout**） |
