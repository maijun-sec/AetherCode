# R398 — daemon 重启后，用户被丢回了 `default` 会话

> 2026-11-24。用户报：**daemon 重启之后，桌面端回到了 `default` 会话**，不是他刚才那个。
> 更糟的是：`default` **恰好是磁盘上一个真实会话的 id**（用户的老 RAG 对话，
> 「检索增强项目问题梳理与能力规划」，1846 条消息），而那一条**在回收站里**。

---

## 0. 结论先说

| | 之前 | 现在 |
| --- | --- | --- |
| daemon 重启后引擎落在哪个会话 | 字面量 `"default"`（**且它是一个已回收站的真会话**） | **用户最后所在的那个会话** |
| 谁记的这件事 | 没有任何人记 | `SessionStore.markActive`，写在会话库里 |
| 首次启动（还没有指针） | 仍然落在 `"default"` | 落在**最近使用的存活会话**上 |
| 指针指向已回收站的会话 | —— | **拒绝恢复**，并把指针清掉 |
| 桌面端改动 | —— | **零改动**（daemon 是会话的归属方） |
| 新增回归测试 | —— | 3 个测试类 / 23 例 |

---

## 1. 根因：一条四节的链，每一节都有出处

### 1.1 daemon 启动时，引擎的会话 id 是字面量 `"default"`

`aethercode-cli/src/main/java/org/aethercode/cli/Main.java:830`

```java
return buildEngineForSession("default");
```

注释里写得很清楚：这是「the daemon-internal session」，**桌面端 / TUI 永远不会引用它**。
桌面端会自己 `createEngine`。

### 1.2 `listSessions.current` / `getState.sessionId` 报的就是引擎的会话 id

- `aethercode-protocol/.../methods/AetherCodeMethods.java:6137`
  → `Map.of("sessions", out, "current", engine.appState().sessionId(), …)`
- 同文件 `:2799` → `r.put("sessionId", reported.appState().sessionId());`

### 1.3 桌面端**照单全收**

`aethercode-desktop/src/store/index.ts:4792`

```ts
currentSessionId: state?.sessionId ?? null,
```

然后 `hydrateTranscript(state.sessionId)` 去拉这个会话的消息
（`index.ts:5740-5747`）：它先 `rpc.getTranscript()`，再校验
`t.sessionId === sessionId`，**不一致就整个跳过**。

⇒ 所以 daemon 说 `"default"`，桌面端就渲染 `"default"` 那条**已回收站**的
1846 条消息。**用户看到的不是「空会话」，是别人的（自己的）旧对话。**

（桌面端这个行为是**对的** —— daemon 之前给的值是错的。⇒ 桌面端零改动。）

### 1.4 而 `"default"` 是磁盘上一个真实的 session id

实机 `listSessions` 的回收站里就有一条：

```
  1846 msg | default | 检索增强项目问题梳理与能力规划
```

⇒ 这不是「落在一个无害的默认值上」，是**「落进回收站」**。
下一条消息会写进回收站。

---

## 2. 修法

### 2.1 为什么不能用 `last_used_at`

索引里已经有 `last_used_at`，看起来正好能用。**但它记的是「最后一次被写入」，
不是「最后一次被打开」。**

用户可以点开一个**旧会话**只是读一读 —— 那个会话不是最近被写的，但**它就是用户
正在看的那个**。按 `last_used_at` 恢复会恢复到错的会话。

⇒ 所以这件事必须**单独记**，而且必须记在「会话 id 变了」的那一刻。

### 2.2 指针落在会话库里：`active-session.txt`

`aethercode-core/.../transcript/SessionStore.java`

| 方法 | 作用 |
| --- | --- |
| `markActive(id)` | 原子写（tmp + rename；`ATOMIC_MOVE` 不支持时降级） |
| `activeSessionId()` | 读；**缺失 / 空白 / 损坏一律等价于「不知道」**，绝不抛 |
| `clearActive()` | 忘掉指针 |
| `sessionState(id)` | 这个会话**还活着吗** ⇒ `ALIVE` / `TRASHED` / `GONE` / `UNKNOWN` |

指针放在**会话库目录内**（`<sessionsDir>/active-session.txt`），跟会话同生命周期。
不用 per-user 配置文件：会话库是会话的归属方，指针就该跟着它。

### 2.3 谁来写这个指针：**引擎的两个「会话 id 变了」的地方**

`aethercode-sdk/.../sdk/AetherCodeEngine.java`

- `loadSession(id)` —— 用户在左栏点了一个已有会话
- `createSession(cwd)` —— 用户点了「＋ 新建会话」

⚠️ **第一版只改了 `loadSession`，漏了 `createSession`** ——
因为 `createSession` **不走** `loadSession`（它自己铸 id、自己换 transcript）。
⇒ 「＋ 新建会话」这条更常见的路没有被记录，daemon 重启后用户会回到**上一个**会话。
⇒ 现在两条路都调 `recordActiveSession(id)`，并且
**`AetherCodeEngineR398Test.createSessionRecordsTheActiveSession` 就是专门钉这一条的**
（见 §4 的 M2 变异）。

（顺带核实：桌面端的三条路径 —— 左栏切换 / 「＋ 新建」/ 首次发送 ——
最终**都**会打到 daemon 的 `loadSession` RPC（`AetherCodeMethods:6745` → `engine.loadSession(id)`），
所以指针一定会被写到。）

### 2.4 谁来用这个指针：daemon 启动时选会话

`aethercode-cli/.../cli/DaemonRunner.java` → `restoreActiveSession(engine, pathHint)`

**三个候选，按优先级：**

1. **记录的指针** —— 用户当时在的那个会话（存活才恢复）。
2. **启动会话**（`"default"`），**只要它不在回收站**。
   这是 R398 之前的行为，对**全新安装**是对的（一个会话都没有）。
3. **最近使用的存活会话** —— 兜底。

两个调用点：
- **stdio** 线：`ensureSessionStore(engine, sessionManager, "stdio")` 之后
- **HTTP** 线：`ensureSessionStore(engine, sessionManager, "http")` 之后
  （HTTP+WS 才是桌面端连的那个；TUI 也走它）

**为什么放在 daemon 侧而不是桌面侧**：daemon 是会话的归属方，TUI 也走它；
而且桌面端**重连时也会重跑 `initialize()`** ⇒ **daemon 侧修一处，同时覆盖
「桌面端重启」和「daemon 崩了重连」两种场景**。桌面端**零改动**。

**为什么需要第 3 条兜底**：升级后的**第一次**启动还没有指针 ⇒ 只有 1 和 2 可选；
而本机恰好就是「启动会话在回收站」这个形态 ⇒ 没有 3，用户第一次重启**仍然**
会掉进回收站，看起来像「没修好」。有了 3，第一次启动就落在
「最近使用的存活会话」上 —— 实机就是用户正在用的那一条。

### 2.5 判「还活着吗」必须用**一次行查询**，不能拿两个列表去凑

第一版是这么写的：

```java
for (SessionInfo info : store.list())      { if (id.equals(info.id())) alive = true; }
for (SessionInfo t : store.listTrashed())  { if (id.equals(t.id())) alive = false; }
```

**这是错的**，而且错得很隐蔽：`list()` 在 SQLite 读不出来时会**降级成目录扫描**，
而**软删除不删 JSONL 文件** ⇒ 目录扫描**看得见回收站里的会话**；
同时 `listTrashed()` 在同样的降级下**返回空列表** ⇒ 过滤失效
⇒ **正好把最该拒绝的那种会话恢复了**。

⇒ 现在是**一次行查询**（`meta.get(id)` 看 `trashed_at`），并且**分四种状态**，
因为四种状态对应四种不同的动作：

| 状态 | 含义 | 指针指它 ⇒ | 启动会话是它 ⇒ |
| --- | --- | --- | --- |
| `ALIVE` | 有行、没进回收站 | **恢复** | 保持 |
| `TRASHED` | 有行、在回收站 | **不恢复 + 清指针** | **换到最近使用的存活会话** |
| `GONE` | 根本没有行（已删 / 从没进索引） | **不恢复 + 清指针** | 保持 |
| `UNKNOWN` | 索引读不出来 | **不恢复**，但**不清指针**（下次再看） | 保持 |

`UNKNOWN` 单列出来，是因为「索引暂时读不出来」和「这个会话确实没了」
**不能同一种处理**：前者不该把指针清掉（清了就再也回不去了）。

### 2.6 指针死了就**忘掉**，不是反复重试

指针指向一个已被回收站 / 已删除的会话 ⇒ 清掉指针，daemon 留在启动会话上
（也就是本轮改动之前的行为）。留着死指针会让**每一次**启动都重试一次失败。

### 2.7 恢复永远不能把 daemon 带崩

`restoreActiveSession` 里每一步都是 best-effort：
指针读不出来、索引读不出来、加载失败 —— 任何一步失败都只是「留在启动会话」，
**和本轮改动之前一模一样**，绝不让 daemon 起不来。
指针的**缺失 / 空白 / 损坏**一律等价于「不知道」，**绝不抛**。

---

## 3. 测试：3 个测试类 / 23 例

| 测试类 | 模块 | 例数 | 钉的是什么 |
| --- | --- | --- | --- |
| `SessionStoreR398Test` | core | 9 | 指针本身：往返、覆盖、空白 id 不擦除、清空、**缺失/空白/损坏都读作「不知道」且不抛**、新 Store 指向同一目录能读回 |
| `AetherCodeEngineR398Test` | sdk | 6 | 引擎**记录**它：loadSession 记录、**createSession 记录**、最后一次切换赢、**指针不是「最近被写的」那个**、指针跨引擎存活、无 store 时不炸 |
| `DaemonRunnerR398Test` | cli | 8 | daemon **选**它：按指针恢复、**不恢复回收站里的且清指针**、指针指向幽灵会话时忘掉、没指针时不动、**启动会话在回收站 ⇒ 换到存活会话**、启动会话存活 ⇒ 不动、无 store 时不炸、**损坏的指针不把 daemon 带崩** |

三个类的分工对应三层：`存`（core）→ `记`（sdk）→ `用`（cli）。

---

## 4. 变异验证

> ★ 突变**永远**打在**副本**上（工作树的字节是**输入**，不是**输出**）。
> 本轮把 `aethercode/` 整棵树（去掉 `target/`）复制到仓库外的
> `D:\work\tmp\r398-mutant`，**只在副本上改**，在副本里跑 mvn。
> ⇒ 「变异残留伪装成设计决定」在结构上就不可能发生。
> 副本的 `target/` 保留 ⇒ 每个变异只重编一个模块，不是整条 reactor。

**基线**（副本里，强制重编）：`SessionStoreR398Test` 9 / `AetherCodeEngineR398Test` 6 /
`DaemonRunnerR398Test` 8 = **23 例，0 红**。

| 变异 | 改了什么 | 设计红集合 | 实测 | |
| --- | --- | --- | --- | --- |
| **M1** | `markActive` 不写盘 | 11 | 11 | ✔ 逐条相符 |
| **M2** | `createSession` 不记录 | 1 | 1 | ✔ 逐条相符 |
| **M3** | 恢复时不做存活校验 | 2 | 2 | ✔ 逐条相符 |
| **M4** | `sessionState` 忽略 `trashed_at` | 2 | 2 | ✔ 逐条相符 |
| **M5** | 去掉「启动会话在回收站 ⇒ 换一个」的兜底 | 1 | 1 | ✔ 逐条相符 |

**M2 与 M5 是本轮最关键的两条**：M2 证明「＋ 新建会话」这条路**真的被钉住了**
（红集合只有它一条 ⇒ 别的用例没有被牵连）；M5 证明 §2.4 的第 3 条兜底
**真的被执行到了**（没有它，升级后的第一次启动仍然会掉进回收站）。

**M4 与 M3 一起证明 §2.5 的论点**：把存活校验换成两次列表过滤（第一版的写法）
在正常路径下与现在**没有区别** —— 区别只在降级路径上，而 M4 直接把它掀开。

### 4.1 变异台本身踩到的三个坑（**都差点让结论看起来是对的**）

这三个坑的共同点：**它们产出的红集合「看起来非常合理」**，不是「0 条」，
也不是「报错」—— 所以不会触发任何直觉。

1. **reactor 在第一个红模块处停下** ⇒ 跨模块的红集合**天生残缺**：
   core 一红，sdk / cli **根本没跑**，而解析出来的是「core 有 5 条红」——
   对一个 core 变异来说完全说得通。⇒ 要跨模块测红集合，必须
   `-Dmaven.test.failure.ignore=true`（让 surefire 记录失败但不中断 reactor）。
2. **Windows 的 `CopyFile` 保留源文件时间戳** ⇒ 同步副本时副本里的 `.java`
   带着**工作树的旧 mtime** ⇒ maven 增量编译看到「.java 比 `.class` 旧」
   ⇒ **不重编** ⇒ **上一轮变异的 `.class` 活了下来** ⇒ 这一轮的**基线**
   跑出上一轮的红集合（11 条，条条对得上设计！）。
   ⇒ 修法不是「小心一点地同步」，而是**让残留无处可藏**：
   每轮跑之前删掉被测模块的 `target/classes`。
3. **surefire 的 XML 报告是上一轮的残留** ⇒ 「这一轮没跑到这个模块」
   会读到旧报告。⇒ 每轮跑之前删掉 `target/surefire-reports`。
   （另：属性匹配 `<testcase[^>]*name="` 里的 `[^>]*` 贪婪 ⇒ 抓到的是
   `classname="…"` 的那个 `name=` ⇒ 失败用例的名字变成类名；
   要写 `<testcase\s+name="`。）

⇒ 这三条合起来就是 Lesson 1489–1492。

---

## 5. 实机验证

部署：`mvn -o -DskipTests package` ⇒ `aethercode-cli-…-shaded.jar`
**56,948,784 bytes**，sha256 `60396CA27F572C9D77E55C7FDE4CCB97DFCF0C0BEBBF53B3D0079CA3407253F6`，
同步到 `aethercode-desktop/src-tauri/resources/aethercode.jar` 与
`dist/aethercode-green-r388/resources/aethercode.jar`，重启 daemon（22888）。

本机的「活会话 / 回收站」实况（`listSessions`）：

```
活:  ecce07c1  511 msg  新增排序方向与字节数组支持
     6cd6ff77  638 msg  多类型数组排序与测试
回收站: default 1846 msg 检索增强项目问题梳理与能力规划   ← ★ 就是它
        + 5 条别的
```

### 5.1 三步断言（全部通过）

| # | 做法 | 期望 | 实测 |
| --- | --- | --- | --- |
| **A** | **删掉指针**后重启 daemon（第一次升级后还没有指针） | 启动会话 `default` 在回收站 ⇒ **不能**停在它上面 | ✔ daemon 日志：`the startup session default is in the trash — started on ecce07c1-… instead`；`getState.sessionId = ecce07c1`，transcript **511** 条 |
| **B** | `loadSession(6cd6ff77)` | 指针被写下来 | ✔ `active-session.txt` = `6cd6ff77-…`；`listSessions.current = 6cd6ff77` |
| **C** | 再重启 daemon | **回到** B 里切过去的那个会话 | ✔ daemon 日志：`restored the active session 6cd6ff77-…`；`getState.sessionId = 6cd6ff77`，transcript **638** 条 |

### 5.2 一次被污染的测量（**记下来，因为它的形态很典型**）

A 的第一轮**失败**了：daemon 日志说「restored the active session ecce07c1」，
而不是「兜底」那一条 —— 也就是说**它找到了指针**，可我明明刚把指针挪走。

原因：**顺序**。我先把指针挪走，再去杀 daemon；而**旧 daemon 还活着的那两秒里，
它自己又把指针写回去了**（它是新代码，`loadSession` 会记录指针）。

⇒ 修法不是「再等久一点」，而是**把顺序反过来**：**先杀 daemon，再动指针，再启动**。
⇒ 这跟 Lesson 1490 是同一个家族：**量具（这里是指针）被另一个进程改写**，
所以「我刚才看过的状态」不一定是「它现在的状态」。

### 5.3 完整回归

`mvn -o -pl aethercode-core,aethercode-sdk,aethercode-cli -am test`
（16 个模块：core → runtime → memory → MCP → hooks → compact → prompts → SDK →
SDD → protocol → models → orchestration → A2A → deepagents → bridge → CLI）

**588 个测试类 / 5,459 例，0 failures，0 errors，4 skipped**，13 分 52 秒，BUILD SUCCESS。

### 5.4 桌面端

**零改动**（本轮没有动 `aethercode-desktop/**` 任何一行）。桌面端重连后会
重跑 `initialize()` ⇒ 读 daemon 的 `state.sessionId` ⇒ 拿到的是**已经恢复好的**
那个会话 ⇒ `hydrateTranscript` 拉到的也是同一个会话的消息
（`hydrateTranscript` 会校验 `t.sessionId === sessionId`，不一致就整段跳过 ——
所以 daemon 必须在**第一个 `getState` 之前**就恢复好，这正是恢复放在 daemon
启动路径里的原因）。

---

## 6. 边界与「没做什么」

- **桌面端零改动。** 它只是**照单全收** daemon 的 `current` —— 那是**对的**
  行为，daemon 之前给的值是错的。
- **没有动 TUI。** TUI 也走 daemon ⇒ 自动受益，但本轮没测 TUI。
- **回收站里的会话不会被恢复。** 用户把正在用的会话丢进回收站后重启 daemon，
  会话会停在启动会话上（指针被清掉）。这是**有意的**：恢复进回收站更糟。
- **「最近使用的存活会话」这个兜底用 `last_used_at` 排序。** 它是「最近被
  **写**的」，不是「最近被**打开**的」—— 但兜底只在**没有指针**时用，
  而指针记的才是「打开」。第一次启动没有指针，用一个合理的近似好过掉进回收站。
- **本轮没有做「会话被删/被回收后把指针清掉」的即时清理**（不是必需的：
  恢复路径会自愈）。

---

## Lessons

**Lesson 1482 — 「恢复到最后打开的」和「恢复到最后写入的」是两件事。**
索引里现成的 `last_used_at` 看着就能用，但它记的是**写**。
「点开一个旧会话读一读」这个动作**不写**它 ⇒ 按它恢复会恢复到错的会话。
⇒ **凡是「上次在哪儿」这类事实，先问一句：现成那个字段记的是不是同一件事。**

**Lesson 1483 — 一对「降级路径不同步」的列表方法，合起来过滤不出正确答案。**
`list()` 在 SQLite 读不出来时降级成**目录扫描**（看得见软删除的 JSONL），
`listTrashed()` 在同样情况下**返回空** ⇒ 两者合起来过滤，**回收站里的会话会被判成活的**。
⇒ **要判「这个会话还活着吗」，就去问索引本身**（一次行查询看 `trashed_at`），
不要用两个降级行为不一致的列表去凑。

**Lesson 1484 — 「会话 id 变了的地方」不一定只有一个。**
第一版只在 `loadSession` 里记指针，漏了 `createSession` ——
因为后者**不走**前者，它自己铸 id、自己换 transcript。
⇒ 找「唯一的地方」时，要按**语义**（会话 id 变了）去找，不是按**调用链**去找。
⇒ 而且「＋ 新建会话」比「点开已有会话」更常见 ⇒ 漏掉它的后果更常见。

**Lesson 1485 — 「索引读不出来」和「这个东西确实没了」不能同一种处理。**
`UNKNOWN` 单列成一个状态：指针指着它 ⇒ **不恢复，但不清指针**（下次再看）；
启动会话是它 ⇒ **保持不动**。把它并进「没了」⇒ 一次暂时的数据库故障
就把指针永久清掉了。

**Lesson 1486 — 指针要有「死了怎么办」的分支，而且要**忘掉**而不是重试。**
指针可能指向已被回收站 / 已删除的会话。留着它 ⇒ **每一次**启动都重试一次失败。
⇒ 死指针 ⇒ 清掉。

**Lesson 1487 — 修好之后，**第一次**运行必须也是对的，否则用户会说「没修好」。**
指针机制要等到用户切过一次会话之后才有内容；升级后的第一次启动**没有指针**
⇒ 旧行为照旧 ⇒ 而旧行为恰好就是「掉进回收站」。
⇒ 所以还得有第 3 条兜底（启动会话在回收站 ⇒ 换到最近使用的存活会话），
并且**专门用一个变异（M5）钉住它**。

**Lesson 1488 — 突变打在副本上，工作树就结构上不可能留下残骸。**
本轮把整棵源码树复制到仓库外再改 ⇒ 「变异残留伪装成设计决定」
（它躲得过红集合推导、躲得过 MISSING 形态的端到端失败、躲得过大部分门禁）
这件事**在结构上**就不可能发生。⇒ 复制成本低的时候，**永远选副本**。

**Lesson 1489 — 跨模块测「红集合」时，红集合天生是残缺的，除非 reactor 跑到底。**
surefire 失败 ⇒ 模块失败 ⇒ **reactor 停在第一个红模块** ⇒ 后面的模块
**根本没跑** ⇒ 解析出来的是「core 有 5 条红」—— 对一个 core 变异来说
完全说得通，**没有任何东西会报警**。⇒ 跨模块测红集合必须
`-Dmaven.test.failure.ignore=true`（记录失败但不中断 reactor）。

**Lesson 1490 — 同步副本时，时间戳会被保留 ⇒ 增量编译让上一轮的变异 class 活下来。**
**Windows 的 `CopyFile` 保留源文件时间戳**（Node 的 `copyFileSync` 走的就是它）
⇒ 副本里的 `.java` 带着工作树的旧 mtime ⇒ maven 增量编译看到
「.java 比 `.class` 旧」⇒ **不重编** ⇒ 上一轮变异的 `.class` 活了下来
⇒ 这一轮的**基线**跑出上一轮的红集合（11 条，条条对得上设计！）。
⇒ **修法不是「小心一点地同步」，而是「让残留无处可藏」**：
每轮跑之前删掉被测模块的 `target/classes`。
（跨平台的通用做法：同步之后**显式把 mtime 拨到当前**，或直接删产物。）

**Lesson 1491 — 量具的输出也要清：surefire 的 XML 报告是上一轮的残留。**
「这一轮没跑到这个模块」会读到**上一轮**的报告 ⇒ 红集合看起来完全正常。
⇒ 每轮跑之前删掉 `target/surefire-reports`；并加一条守卫：
**三个测试类的报告必须都在**，少一个就判「量具没读到东西」而不是
「变异没生效」。

**Lesson 1492 — 量具的输出要归一化再比，否则你会去改「设计」而不是改「量具」。**
两个具体的坑：① `<testcase[^>]*name="…"` 里的 `[^>]*` **贪婪** ⇒ 抓到的是
`classname="…"` 的那个 `name=` ⇒ 失败用例的名字全变成类名（要写
`<testcase\s+name="`）；② surefire 的 `name` 是「方法名 + 参数列表」
（`foo(Path)`）⇒ 两边都要去掉 `(…)` 再比。
⇒ **先怀疑量具，再怀疑设计** —— 否则会去改一条本来正确的设计来迁就量具。

**Lesson 1493 — 被另一个进程改写的「状态」：你刚才看过的状态，不一定是它现在的状态。**
实机验证 A 的第一轮失败了：daemon 日志说「**restored** the active session」，
而不是兜底那一条 —— 它**找到了指针**，可我明明刚把指针挪走。
原因：**顺序** —— 我先挪指针、后杀 daemon，而**旧 daemon 还活着的那两秒里
它自己又把指针写回去了**（它是新代码，`loadSession` 会记录指针）。
⇒ 修法不是「再等久一点」，而是**把顺序反过来**：**先杀进程，再动被测状态，
再启动**。⇒ 凡是「某个活着的进程会写这个文件」的实验，
**被测文件必须在那个进程死掉之后才能动**。
Doc: `docs/R398-daemon-restart-session-restore.md`.
