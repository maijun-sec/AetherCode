# R403 — checkpoint 落盘 + 取消打点

> R400 §3 的 **C2（落盘）** + **C（取消时打点）**。
> R400 §3.4 已拍板「A（turn 边界）+ C（取消时打点）」；本轮把两者都落地。
> 不含 C1（桌面端菜单 = R402）、C3（运行态落盘）、G3（forward-resume）。

---

## 1. 目标

1. **checkpoint 跨 daemon 重启存活** —— 现在 checkpoint 是 engine 实例字段
   （`AetherCodeEngine.java:2824-2825`），daemon 一重启全丢，连「当时在对话的哪个位置」
   这个锚点都没了（G1）。
2. **取消时留下一个「停在这里」的点** —— 用户按停止的那一刻，代码停在半途，
   最近的 turn 边界 checkpoint 是「这一轮开始前」。用户要的是「任务停在某个 checkpoint」。
3. **恢复流程可验证** —— 停 → 重启 daemon → 还能看到那个点 → 能预览、能恢复。

---

## 2. 现状（事实，附证据）

### 2.1 族 A（生产路径上唯一一族）

| 事实 | 证据 |
|---|---|
| `CodeCheckpointStore` 全类**无任何写盘调用** | `core/transcript/CodeCheckpointStore.java`（G1） |
| 它是 engine 的**实例字段** | `sdk/AetherCodeEngine.java:2824-2825` |
| 唯一的生产打点 = **turn 边界** | `sdk/AetherCodeEngine.java:990-1000`（`setOnTurnBoundary`） |
| 快照的 files 列表 = **从 transcript 反推** | `CodeCheckpointStore.touchedFiles()`（扫全量 transcript 的 tool_use） |
| 三个 RPC 已在 | `protocol/methods/AetherCodeMethods.java:1884-1887`（list / restore / summarize） |

### 2.2 取消路径

| 事实 | 证据 |
|---|---|
| `cancel` RPC 在 daemon 层，runId 可选 | `AetherCodeMethods.java:5405` |
| 硬取消四层（future → interrupt → 杀子进程 → Thread.stop 兜底） | `AetherCodeMethods.java:5484-5579` |
| 引擎侧 `Thread.interrupted()` ⇒ `RunEnd("cancelled")` | `core/engine/QueryEngine.java:2687-2712` |

### 2.3 会话目录布局（落盘位置的事实依据）

```
<sessionsDir>/                          ← SessionStore.dir()
  sessions.db                           ← SQLite 元数据
  active-session.txt                    ← R398 活动会话指针
  <sessionId>.jsonl                     ← transcript
  <sessionId>.cwd
  <sessionId>/                          ← ★ 每会话目录（已存在）
    permission-mode.json                ← R105 先例：<sessionsDir>/<sessionId>/<file>.json
```

**先例**：`PermissionModePersistence`（`aethercode-config`）已经把
「per-session 状态落盘」做成了 `<sessionsDir>/<sessionId>/<name>.json` 的形状
（`PermissionModePersistence.java:39-40`、`:72`、`:101`）。
R403 沿用这个形状 ⇒ `<sessionsDir>/<sessionId>/checkpoints/`。

---

## 3. 设计决策

### 3.1 落盘格式

目录：`<sessionsDir>/<sessionId>/checkpoints/`

```
cp-3-1734567890123.json          ← 元数据
cp-3-1734567890123/              ← 该点被改文件的 before 内容
  0.txt
  1.txt
state.json                       ← {"evicted": N}
```

元数据 JSON：

```json
{
  "id": "cp-3-1734567890123",
  "kind": "turn",
  "transcriptIndex": 42,
  "userMessageIndex": 3,
  "summary": "改成流式",
  "createdAt": 1734567890123,
  "files": [
    {"path": "D:\\work\\a.txt", "created": false, "restorable": true,  "blob": "0.txt"},
    {"path": "D:\\work\\b.bin","created": false, "restorable": false, "blob": null},
    {"path": "D:\\work\\c.txt", "created": true,  "restorable": true,  "blob": null}
  ]
}
```

**三个标志位缺一不可**（它们编码的是三件不同的事）：

- `created=true` ⇒ 该文件在会话碰它**之前不存在** ⇒ 恢复时**删除**它，
  而不是写一个空文件。
- `restorable=false` ⇒ 二进制 / 不可读 ⇒ 恢复时**跳过**并报告，
  而不是把乱码写回去。
- `blob=null` ⇒ 没有内容可存（`created=true` 或不可恢复）。

★ **这就是 G6 的解法**：落盘时把「快照内容 + 当时的 transcript 位置 + files 列表」
当作**一个整体**存下。跨重启后不再需要从 transcript 反推 `touchedFiles`
（transcript 已经变了，反推出来的集合会是错的）。

### 3.2 保留策略与清理

| 规则 | 值 | 理由 |
|---|---|---|
| 内存上限 | `CodeCheckpointStore.MAX_CHECKPOINTS = 64` | 已有的有界设计（内存是 O(n²)） |
| 磁盘上限 | **同一条** —— 驱逐时同步删磁盘 | 磁盘与内存必须一致，否则重启后「多出」幽灵点 |
| `evicted` 计数 | 落盘到 `state.json` | 重启后计数归零 = **谎报「没丢过」** |
| 删会话 | 一并删 `<sessionsDir>/<sessionId>/checkpoints/` | 删了会话还留着它的代码快照 = 垃圾 |

★ **内存与磁盘必须共用一条驱逐规则**：如果磁盘单独一套上限，
重启后 load 出来的点数与内存里看到的可能不同 ⇒ 用户看到「列表忽然多了一项」。
⇒ 驱逐逻辑放在 `CodeCheckpointStore.evictIfNeeded()` 里，内存与磁盘同时清。

### 3.3 取消打点的语义（C）

R400 §3.4 已拍板「A + C」。本轮把 C 的确切语义写死：

> **取消点 = 「代码停在半途的那个状态」+「对话回到这一轮的开头」。**

| 字段 | 取值 | 为什么 |
|---|---|---|
| `kind` | `"cancel"`（vs turn 点的 `"turn"`） | 渲染器要能区分「这里是正常的一轮」和「这里是被你按停的」 |
| `files` | 会话到目前为止碰过的**全部**文件，内容 = **取消那一刻的磁盘内容** | R400 §3.4 的 C 就是「保存 cancel 时刻的**半成品**状态」 |
| `transcriptIndex` | **被取消那一轮的 user 消息下标** | 这一轮没跑完，它的半截消息不是一段可续写的对话 |
| `userMessageIndex` | 被取消那一轮的 turn 序号 | 同上 |

**「恢复代码」到取消点** = 把文件放回**半成品状态**（取消那一刻的样子）。
**「恢复对话」到取消点** = 丢掉没跑完的那一轮，回到可以重新提问的位置。

★ **这里有一个必须说清楚的取舍**：取消点的快照是**半成品状态**，
所以「立刻恢复代码到取消点」看起来什么也没变 —— 这是**对的**，
它记录的是「任务停在这里」这个现场，而不是「撤销这一轮」。

「撤销这一轮的部分改动」由**上一轮的 turn 边界点**承担，
而它有一个已知缺口：**这一轮新建的文件不在那个点里**
（turn 边界打点时模型还没决定要写哪些文件）。
补这个缺口需要**工具级的前置快照**（每次 `file_write` 执行前存 before 态），
那是 G2 的「每工具调用后打点」，明确不在本轮范围。

★ **不要用「取消点的代码快照」冒充「撤销这一轮」** ——
那会把一个没实现的能力说成实现了。

### 3.4 取消点的文件集合从哪来

`CodeCheckpointStore.touchedFiles()` 扫 transcript 的 tool_use 块。
**在取消那一刻，本轮的 tool_use 块已经在 transcript 里了吗？**

- 引擎在每轮 LLM 输出结束后、**执行工具之前**把 assistant 消息（含 tool_use 块）
  追加进 transcript（`QueryEngine.java:2740-2743`）。
- ⇒ **工具执行期间** transcript 已含本轮的 tool_use ⇒ 取消点能看见本轮写过的文件。
- ⇒ 但**LLM 正在流式输出**时，本轮的 tool_use 还没进 transcript ⇒ 那一刻取消，
  看不见「还没执行」的文件 —— 而它们也确实还没写盘。**不丢东西。**

`appState.transcript()` 是 `CopyOnWriteArrayList`（`AppState.java:81`）⇒
取消线程遍历它不会 CME。

### 3.5 会话切换 ⇒ 重绑定

checkpoint 是**每会话**的（目录里带 sessionId）。会话 id 会变
（`loadSession` / `createSession`）⇒ 引擎必须**重绑定**到新会话的目录：

- 旧的内存点丢弃（属于别的会话），
- 从新会话的目录 **load** 回来。

⇒ `AetherCodeEngine` 的 `codeCheckpoints` 字段从 `final` 变成可重绑，
并提供 `bindCheckpointDisk(sessionId)`。

### 3.6 一次「取消」的完整时序

```
用户按停止
   │
   ├─► cancel RPC（AetherCodeMethods.cancel）
   │      ├─ ★ 先读「这次 run 属于哪个引擎」（runEngines）★
   │      │     —— 必须在中断之前读：中断会让 worker 收尾，
   │      │        收尾会把这次 run 从跟踪表里删掉 ⇒ 读晚了就查不到
   │      ├─ 停自动续跑（R400 已有）
   │      ├─ future.cancel(true) + thread.interrupt() + 杀子进程 + 看门狗
   │      └─ ★ 本轮新增：markCancelCheckpoint()
   │            └─ codeCheckpoints.save(id="cp-cancel-<millis>", kind="cancel",
   │                                    transcriptIndex=被取消轮的 user 消息下标,
   │                                    files=此刻的磁盘内容)
   │               └─ 写盘：<sessionsDir>/<sessionId>/checkpoints/cp-cancel-<millis>.json + 目录
   │
   └─► 引擎侧 Thread.interrupted() ⇒ RunEnd("cancelled")（R400 已有）
```

★ **取消点只在真的取消了的时候才打**（`cancelled == true`）。
对一个早已结束的 run 调 cancel **不产生**取消点 ——
否则用户每按一次停止就多一个垃圾点。

---

## 4. 数据结构变更

### 4.1 `CodeCheckpointStore.Checkpoint` 增加 `kind`

```java
public record Checkpoint(
        String id,
        int transcriptIndex,
        int userMessageIndex,
        String summary,
        String kind,        // ★ 新增："turn" | "cancel"
        List<FileState> files) {

    /** 兼容旧构造：默认是一个 turn 边界点。 */
    public Checkpoint(String id, int transcriptIndex, int userMessageIndex,
                      String summary, List<FileState> files) {
        this(id, transcriptIndex, userMessageIndex, summary, KIND_TURN, files);
    }
}
```

`kind` 是**不变量的一部分**（渲染器要靠它区分两类点），
所以它进 record、进磁盘、进 wire。

### 4.2 新类 `CheckpointDiskStore`

`core/transcript/CheckpointDiskStore.java` —— **只管磁盘格式与 IO**，
不认识语义。`CodeCheckpointStore` 保留唯一的「什么是一个 checkpoint」的定义。

| 方法 | 职责 |
|---|---|
| `save(Checkpoint)` | 写 `<id>.json` + `<id>/` 目录 |
| `loadAll()` | 读回全部点，按 `createdAt` 升序 |
| `delete(id)` | 删一个点（元数据 + 目录） |
| `prune(keepNewest)` | 只留最新 N 个 |
| `readEvicted()` / `writeEvicted(n)` | `state.json` 的计数 |

### 4.3 `CodeCheckpointStore` 的落盘接线

- 新构造 `CodeCheckpointStore(Path diskDir)`；**无参构造保留**（纯内存，测试与 CLI 路径不受影响）。
- `save()` 之后写盘；写盘失败 **只 warn**（一个坏掉的 checkpoint 不能打断用户这一轮）。
- `evictIfNeeded()` 同时删磁盘。
- `loadFromDisk()` 把磁盘上的点读回内存。

---

## 5. 线协议变更

| RPC | 变更 |
|---|---|
| `checkpoints/list` | 每行增加 `kind`（`"turn"` / `"cancel"`） |
| `cancel` | 成功取消时增加 `checkpointId`（取消点的 id）；没有取消点时为 `null` |
| `checkpoints/restore` | 不变（`scope` 语义不变） |

---

## 6. 明确不做

| 不做 | 为什么 |
|---|---|
| C1 桌面端菜单 | R402，纯前端 |
| C3 运行态落盘（todoList / in-flight 计划） | R404 之后的大工程 |
| G3 forward-resume（从点继续跑未完成的 run） | 本轮的「恢复」= 回退（rewind）+ 新 prompt |
| 工具级前置快照（每次写工具执行前存 before） | G2 的另一半，见 §3.3 的取舍说明 |
| 改 `SessionStore.delete` 去删 `<sessionId>/` 目录 | 那是既有行为（R105 的 `permission-mode.json` 也留在那儿），不扩大范围 |

---

## 7. 测试计划

| 测试类 | 覆盖 |
|---|---|
| `CheckpointDiskStoreR403Test`（core，14 例） | 往返、malformed JSON 跳过、blob 缺失、`prune`、`evicted` 计数往返、**按 createdAt 排序**、**路径穿越 id 被跳过** |
| `CodeCheckpointStorePersistenceR403Test`（core，9 例） | 落盘、重载后还在、**重载后还能恢复文件**、驱逐同时删磁盘、`evicted` 计数往返、**clear 后计数仍在**、kind 往返 |
| `AetherCodeEngineCheckpointR403Test`（sdk，7 例） | 新引擎实例能读到旧引擎写的点（= 跨重启）、落盘目录位置、**切会话换历史**、**删会话删点**、取消打点、`transcriptIndex` 指到被取消那一轮 |
| `AetherCodeMethodsCancelCheckpointR403Test`（protocol，5 例） | cancel 打出取消点并回 `checkpointId`、**无活跃 run 时不产生点**、取消点在 wire 上带 kind、**连打 8 次每次都留点**、**归属只读一次且读在中断之前** |

★ **变异必须打在副本上**，且**红集合为空要先证明量具读到了东西**。

### 7.1 ★ 基线暴露出来的一个真竞态 ★

第一版实现里，取消打点是**在中断之后**才去查「这次 run 是哪个引擎的」：

```java
done.cancel(true);
t.interrupt();                            // ← worker 从这里开始收尾
...
var cp = marked.markCancelCheckpoint(...); // ← 这里才查归属
```

worker 收尾时会把这次 run 从 daemon 的跟踪表里删掉 ⇒ **查归属的时机和删除的时机赛跑**。
worker 收得快的时候查不到归属 ⇒ **打点被静默跳过**。

★ **它是怎么暴露的**：变异验证的**基线跑红、单跑绿** ⇒ 那不是测试的问题，
是实现的竞态（本轮之前那一次基线就是红的）。

⇒ 修法：**归属必须在中断之前捕获**（读一次，存成局部变量），
并且由 query 在启动 run 时登记（`runEngines`），取消时读这个**事实**而不是重新推导。

⇒ **为什么行为测试抓不住它**：竞态窗口只有几十微秒，「连打 8 次」的测试
对「在中断之后才查」的变异 5 次尝试全绿 ⇒ 所以另加了一条**顺序 pin**：
「cancel 里对跟踪表的读取**恰好一次**，且**在中断之前**」。
这条 pin 不点变量的名字 ⇒ 改名不会让它变红，而它存在的那个缺陷会让它变红。

### 7.2 变异验证（副本上）

15 个变异，**基线与终检各连跑 3 次**（专门盯 flake）：

| 结果 | 数量 |
|---|---|
| 按设计变红 | 15 |
| 存活 | 0 |
| harness 错误 | 0 |

★ **M15（「在中断之后才查归属」）是被顺序 pin 抓住的，不是被行为测试抓住的** ——
这正是那条 pin 存在的理由：竞态窗口只有几十微秒，「连打 8 次」的测试对它**五次尝试全绿**。
⇒ **Lesson：行为测试抓不住的缺陷，要用一条**结构化 pin**补上**，
而且那条 pin 必须 pin **不变量**（读几次、在什么之前），不是 pin 变量的名字。

★ 变异脚本自己的两个坑：① 判定时拿**全限定名**去比**裸方法名** ⇒ 全部误判成
「RED-BUT-OTHER-TEST」（红是红了，判定写错了）；② 锚点字符串**必须仍然存在于源码里**
—— 改了 `clear()` 的实现之后，旧锚点匹配 0 次 ⇒ harness 报「锚点失效」而不是
「变异没生效」，两者必须区分开。

---

## 8. 实机验证（已执行）

脚本：`D:\work\tmp\r403-live.mjs`（一次性 cwd + 一次性 sessions dir，jar = 本轮构建产物）。
结果：**36/36 全绿**，日志 `D:\work\tmp\r403-live.log`。

| # | 断言 | 实测 |
|---|------|------|
| 1 | 起 daemon，连 WS，建会话 | `sessionId=2026-11-24T14-06-58.059817900Z_c3238448` |
| 2 | turn 点出现 | 3 个点：`cp-0[turn]{}`、`cp-1[turn]{r403-probe.txt}`、`cp-cancel[cancel]{r403-probe.txt}` |
| 3 | 写盘通道唯一 | `tools=["file_write","bash","file_read"]`；bash 被拒（`isError=true`，理由 `R86 TUI override (session)`）；cwd 内容级检查 = **只有 `r403-probe.txt` 一个文件** |
| 4 | 走的是权限机制不是旁路 | 收到 1 条 `permission_request`（`tool=file_write, riskLevel=medium`），客户端应答 allow |
| 5 | 取消点在线上 | `cancel` 响应带 `checkpointId=cp-cancel-1795529224617`，与线上的 `kind="cancel"` 点 id 一致 |
| 6 | 点在磁盘上 | `<sessionsDir>/<sessionId>/checkpoints/` 里 **3 个 `.json`** = 线上 3 个点 |
| 7 | **杀掉 daemon 重启** | 3 个点**全部存活**，id 与 `kind` 逐条一致；`cp-1` 仍列着 `r403-probe.txt` |
| 8 | restore | 改坏文件 → `checkpoints/restore{scope:"code"}` → `restored=["r403-probe.txt"]`，文件内容回到 `r403-probe-content` |
| 9 | 删会话带走点 | `deleteSession` → `checkpoints/` 目录消失 |

### 8.1 实机暴露的两个「量具」错误（都是脚本的错，不是实现的错）

**★ 头less 客户端不答权限询问 ⇒ 不是「被拒绝」，是「整条 run 挂死」 ★**

第一轮实机：模型 6 秒内就调了 `file_write`，但 run 永远不结束、文件也不落盘。
线程 dump 显示 `StreamingToolExecutor.runOneBackpressured` 停在
`policy.check(tool, input, ctx).get()` —— `ProjectPermissionPolicy` 在
DEFAULT 模式下把写工具判成 **ask**，`JsonRpcPermissionPrompter` 发出
`permission_request` 后返回一个**只有客户端调 `permissionResponse` 才会完成**的
future。脚本没答 ⇒ future 永不完成 ⇒ 流式执行器冻结 ⇒ 直到 5 分钟的 prompter
超时才会被拒。

⇒ **无头驱动必须扮演客户端**：WS 收到 `permission_request` 就回
`permissionResponse{decision:"allow"}`。这与桌面端点「允许」按钮走的是同一条路。
（deny 规则在 ask **之前**求值，所以「拒 bash + 自动放行其余」是安全的组合。）

**★ 断言「模型没调 X 工具」是错的断言，该断言的是「结果」 ★**

第三轮实机红了一项：transcript 里出现了 `bash`。但同一轮的 cwd 内容级检查显示
**只多出 `r403-probe.txt` 一个文件**，而 bash 那次调用 `isError=true`、理由是
`R86 TUI override (session)` —— 模型写完文件后想用 bash 验证，被拒，改用
`file_read`。写通道从未被绕过。

⇒ 断言从「transcript 里没有 bash」改成「**每个 bash 调用都被拒**」＋
「**cwd 里只有该有的文件**」。前者量的是工具名，后者量的是结果。

### 8.2 turn 点的次序（设计上的事实，不是 bug）

turn 边界点在**该轮的用户消息追加之前**触发（`QueryEngine` 的 `onTurnBoundary`
在 user 消息 append 之前），所以「第 N 轮的 turn 点」装的是**第 N 轮开跑前**的文件状态：

- 第 1 轮的 turn 点 ⇒ `files=[]`（那一刻还什么都没写）
- 第 2 轮的 turn 点 ⇒ `files=[r403-probe.txt]`
- 取消点 ⇒ `files=[r403-probe.txt]`

⇒ 「第一个 turn 点里有这个文件」是一个**关于设计的假断言**。正确的问法是
「**有没有**一个 turn 点装着这个文件」。

---

## 9. 已知限制（写下来，别假装没有）

1. **取消点的「恢复代码」在取消当下是空操作** —— 它记录的是现场，不是撤销（§3.3）。
2. **撤销这一轮的部分改动有缺口** —— 这一轮新建的文件不在上一轮的 turn 点里（§3.3）。
3. **取消点与被取消那一轮的 turn 边界点 ordinal 相同** —— 两个点、同一个 turn 序号，
   靠 `kind` 和 `id` 区分。
4. **`transcriptIndex` 是「当时的」下标** —— 落盘后如果 transcript 被 compaction 改写，
   这个下标可能不再指向同一段内容（G6 的残余部分；本轮只保证**文件快照 + files 列表**
   是自洽的，不保证对话下标在 compaction 后仍然精确）。
5. **文件清单只认写工具，不认 shell 改写** —— `CodeCheckpointStore.WRITE_TOOLS`
   只有 `file_write` / `file_edit`。模型若走 `bash`（`echo x > f`、`sed -i`）
   写盘，**那个文件不进快照，restore 也不会还原它**，而且 restore 的报告里
   不会提它（静默不还原）。这是 R388n 就有的缺口，本轮未改。三个选项：
   补 bash 写盘追踪（要 bash 工具侧上报实际写入文件）／在 wire 上诚实标注
   「文件清单只覆盖写工具」／只记为已知限制。**待拍板。**
6. **无头驱动必须答权限询问** —— 不答不是「被拒」，是整条 run 挂死（§8.1）。
   与 checkpoint 无关，但是任何脚本化实机验证都会撞上。
