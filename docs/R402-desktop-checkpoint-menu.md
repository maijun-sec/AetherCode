# R402 — 桌面端的回退菜单，以及一句说反了的 javadoc

## 1. 目标

让**桌面端**能用上 daemon 早就有的 checkpoint 能力。

## 2. 现状（事实，附证据）

daemon 侧从 R388n 起就有了完整的 checkpoint 面：

| 能力 | 何时有的 |
|------|---------|
| `checkpoints/list` / `checkpoints/restore` / `checkpoints/summarize` | R388n |
| 取消点（`kind="cancel"`） | R403 |
| 点落盘、跨 daemon 重启存活 | R403 |

TUI 从 R388n 起就有回退菜单（`aethercode-tui/src/components/CheckpointMenu.tsx`，6 个动作）。

**桌面端一个字都没有**：`checkpoints/` 与 `rewind` 在
`aethercode-desktop/src/` 里出现 **0 次**（实测 grep）。
最接近的同类界面是 R284 的 `SnapshotModal`（列出一批对象 + 弹窗详情）。

⇒ 也就是说：**R388n–R403 造出来的能力，对用桌面端的人是不可见的。**

## 3. 设计决策

1. **动作集与 TUI 对齐**（5 个动作 + 关闭）：
   恢复代码和对话 / 恢复对话 / 恢复代码 / 从这里总结 / 总结到这里。
   两个包不能共享代码（各自打包），所以这份动作表**是**第二份副本 ——
   **真正把两边钉在一起的是 daemon 的 RPC**，动作 id 也用同一套字符串。
2. **「恢复代码和对话」不是第三套实现**，而是两个简单动作的
   **flags 组合**（`touchesCode` / `touchesConversation`）。
   如果它有自己的实现，修「恢复代码」就修不到它，两边会漂。
3. **渲染器只显示，不推导**：
   - `kind`（turn / cancel）是 **daemon 的裁决**，渲染器不从 summary 散文里猜；
   - 「恢复会改动什么」的 preview 是 **daemon 算的**（只有 daemon 手里有快照）；
   - **被丢弃的点数**也是 daemon 的 —— 菜单从「point 40」开始而不说一声，
     看起来就像 daemon 把历史弄丢了。
4. **「不知道」不是 0**：preview 里 `known: false`（二进制、读不了）
   显示「不知道」，不显示「0 行」—— 一个用户会照着做的数字，错不得。
5. **顺序由渲染器决定**：daemon 按插入顺序（最老在前）返回，菜单**倒序**显示
   （用户想回退时，要的通常是最近那个点），行上保留 daemon 的轮次序号便于对照。
6. **恢复的结果要被说出来**：restored / deleted / skipped / failed 四类分开报。
   会话**新建**的文件在恢复时是**删除**，不是「写回空内容」。
7. **失败时菜单不关**：菜单是错误被渲染出来的地方；失败就关 ⇒ 错误
   在用户读到之前就被卸载了，而对话**并没有**被总结 —— 一个「看起来成功」
   的失败是最坏的答案。（本轮在桌面端改掉的一个真缺陷。）
8. **两个错误字段都要读**：daemon 用 `reason` 表示「操作被拒绝」，
   用 `error` 表示「会话没找到」（§6.5 实测）。只读一个 ⇒ 另一半失败
   变成通用兜底文案。

## 4. 本轮的一个发现：daemon 的 javadoc 说的是**反的**

写桌面端菜单时我按 daemon 的 javadoc 去理解两个总结动作的区别，
读到的是：

> "Summarize UP TO here" is the same operation with the **summarised side flipped**；
> `keepAfter` false = "summarize up to here" (**keep the head**)。

**而实现从来不是这样。** `summarizeAtTranscriptIndex` 里：

```java
int split = keepAfter ? boundaryIndex : boundaryIndex + 1;
// head = transcript[0, split)   ← 被总结的永远是 head
// tail = transcript[split, end) ← 保留下来的永远是 tail
```

两个模式**都总结前半段、都保留后半段**，唯一的差别是**边界那条消息**归谁：

| 模式 | 切点 | 边界消息 |
|------|------|---------|
| `from` | `boundaryIndex` | **保留**（留在 tail 里） |
| `up_to` | `boundaryIndex + 1` | **被折进总结** |

三份副本里有两份说对、一份说反：

| 副本 | 说的是 |
|------|--------|
| daemon 的**行内注释**（同一个方法里） | ✅「head 被总结、tail 被保留」 |
| TUI 的菜单注释 | ✅ 与上面一致，还写明「`from` 保留边界消息、`up_to` 折进去」 |
| daemon 的 **javadoc** | ❌「summarised side flipped」「up_to = keep the head」 |

⇒ **同一个文件里的两份注释已经互相矛盾了**，而 javadoc 恰好是读者最信的那一份。
本轮做三件事：

1. 把 daemon 的三处 javadoc 改成与实现一致（`AetherCodeEngine.java`）。
2. 新增 `AetherCodeMethodsSummarizeModeR402Test`（6 例，**行为测试**，不是源码 pin）：
   用一个会记录输入的假 compactor，驱动真实的 `checkpoints/summarize` RPC，
   然后断言**留下来的 transcript** 与**总结器实际收到的消息**。
   这样「谁被总结、谁被保留」从此由测试说了算，而不是由注释说了算。
3. 桌面端的按钮文案按 TUI 的精确措辞对齐。

> 这一节的教训不是「注释会错」，而是：**渲染器绝不能照着 javadoc 写。**
> 写客户端时读服务端的**代码**，不是它的文档；否则你会把一份漂移的副本
> 复制成第二份。

## 5. 改动

| 文件 | 改了什么 |
|------|---------|
| `aethercode-desktop/src/lib/methods.ts` | `AetherCodeRpc` 加 `checkpointsList` / `checkpointsRestore` / `checkpointsSummarize` 三个方法 + `CheckpointPoint` 类型 |
| `aethercode-desktop/src/components/CheckpointMenu.tsx` | **新建**：菜单组件 + 动作表 |
| `aethercode-desktop/src/components/CheckpointMenu.css` | **新建**：样式 |
| `aethercode-desktop/src/components/commandCommands.ts` | 新增 `/rewind` 命令（派发事件，保持 commands → App 单向） |
| `aethercode-desktop/src/App.tsx` | 七处接线：state、事件监听、props 接口、两个 `MainLayout` 调用点、modal 挂载、import |
| `aethercode-desktop/src/rpc/MockRpcServer.ts` | Mock 支持 `checkpoints/*`（seed + 三个 handler） |
| `aethercode-desktop/src/components/__tests__/CheckpointMenuR402.test.tsx` | **新建**：16 例 |
| `aethercode/aethercode-sdk/.../AetherCodeEngine.java` | 修三处 javadoc + 一处行内注释（§4） |
| `aethercode/aethercode-protocol/src/test/.../AetherCodeMethodsSummarizeModeR402Test.java` | **新建**：6 例（§4） |
| `docs/R402-desktop-checkpoint-menu.md` | 本文件 |
| `AGENTS.md` | 新增 R402 小节（Lesson 1508–1513） |

## 6. 测试

### 6.1 桌面端

`npx vitest run src/components/__tests__/CheckpointMenuR402.test.tsx` ⇒ **16/16 绿**，
`npm run typecheck` 干净，**全量 143 个文件 / 1522 例全绿**（本轮 +3）。

几条承重的断言：

- **daemon 的 kind 原样显示** —— 取消点的 summary 被**故意写成**
  「a turn like any other」，badge 仍须显示「停在这里」
  ⇒ 渲染器没有从散文推导类型。
- **preview 未知项显示「不知道」**，且**不含**「0 行」。
- **每个动作调对的 RPC、带对的 scope**（code / conversation / both）。
- **组合动作的 flags 就是两个简单动作的 flags**。
- **恢复结果分开报**：turn 点的文件是「恢复」，取消点的文件是「删除」。
- **两个总结动作发对的 mode**（`from` / `up_to`），且带对的 checkpointId。
- **总结失败时菜单不关、错误可见**（§3 第 7 条）。
- **两个错误字段都读**（§3 第 8 条）。
- **`/rewind` 命令存在且派发打开事件**。

### 6.2 daemon

`AetherCodeMethodsSummarizeModeR402Test` ⇒ **6/6 绿**：
`from` 保留边界消息 / `up_to` 折进边界消息 / **两个模式都不保留 head** /
两个模式只差一条消息 / 序号路径行为一致。

### 6.3 变异验证

在**副本**（`D:\work\tmp\r402-mutant`）上打 6 个突变：

| 突变 | 含义 | 被谁抓住 |
|------|------|---------|
| M01 | 边界翻转（`from`↔`up_to`） | `summarizeFromHereKeepsThePointsOwnMessage` |
| M02 | `up_to` 退化成 `from` | `summarizeUpToHereFoldsThePointsOwnMessageIntoTheSummary` |
| M03 | `from` 退化成 `up_to` | `summarizeFromHereKeepsThePointsOwnMessage` |
| M04 | **保留 head 而不是 tail**（旧 javadoc 声称的那个行为） | `summarizeUpToHereDoesNotKeepTheHead` |
| M05 | 总结器收到 tail 而不是 head | `summarizeUpToHereFoldsThePointsOwnMessageIntoTheSummary` |
| M06 | wire 上 mode→keepAfter 的映射反过来 | `theTwoModesDifferByExactlyTheBoundaryMessage` |

**6 抓住 / 0 漏网**；基线 ×3 全绿、终检 ×3 全绿；两个文件**还原逐字节一致**。

> 变异台本身在这一轮坏过三次，三次都伪装成「突变没被抓住」：
> ① 脚本有一处**单引号没闭合** ⇒ PowerShell 解析失败、脚本从未执行，
>    而总结文件是**上一轮**留下的 ⇒ 读数是陈的；
> ② 失败名带 `(Path)` 参数后缀（测试方法有 `@TempDir` 形参）⇒
>    拿到了正确的失败集合却比错了名字；
> ③ 「文件没变」的分支**没有还原** ⇒ 下一个突变的锚点被前一个突变吃掉了。
> 修法：脚本**先自检语法**、比较前剥掉参数列表、每个突变**从源码树重新还原**。

### 6.4 全量构建

`mvn package`（整个 reactor，**带全部测试**）⇒ **BUILD SUCCESS，9 分 23 秒**；
surefire XML 统计：**651 个测试类 / 6264 例 / 0 失败 / 0 错误 / 4 跳过**
（比 R404 的 650/6258 正好 +1 类 +6 例 = 本轮新增的那个测试类）。
jar SHA256 `88861D639070D53545D61F8C83C2F12C7FAF3BC0EDAC00605C8B6A5711694F64`
（56 980 976 字节）已同步到 3 处；桌面端重启后 **42 秒** daemon healthz 200。

### 6.5 实机：daemon 的真点 vs 桌面端的类型

桌面端菜单的单测跑在 **MockRpcServer** 上 —— 那是 daemon 回答的**手写副本**。
手写副本在真 daemon 被问过之前都只是猜测，所以实机脚本
（`D:\work\tmp\r402-live.mjs`）**起一个一次性 daemon**（一次性 sessions 目录 +
一次性 cwd + 禁 bash + `builder` 档案 + 客户端应答每一次权限询问），
跑两轮让模型用 `file_write` 真写一个文件，然后逐字段核对 daemon 的回答
与桌面端 `CheckpointPoint` 类型的每一个声明。

**52/52 全绿。**

实测过程中量具先红了两次，两次都是量具自己的问题：

1. **第一次：0 个点。** 用户机器上的 6 个会话**一个 `checkpoints/` 目录都没有**
   ⇒ 形状检查**什么都没证明**。⇒ 必须造一个**真的**点。
2. **第二次：点有了，但 `files` 是空数组。** 第 1 轮的边界点是在该轮用户消息
   追加**之前**取的 ⇒ 它装的是「这一轮开跑前」的文件状态 ⇒ 空的。
   ⇒ 嵌套 `files[]` / `preview[]` 的检查**遍历了空数组、全部空洞通过**。
   ⇒ 加跑第 2 轮，它的边界点才真正**装着那个文件**（R403 的那条教训又一次生效）。

实机还量出**两件事**，都是真发现：

- **daemon 的错误字段有两个名字**：`reason`（操作被拒绝）与 `error`（会话没找到）。
  菜单原先只读 `reason` ⇒ 「会话不存在」时用户只看到通用兜底。
  ⇒ 已改成 `reason ?? error ?? 兜底`，并补了断言。
- **取消点与轮次点是同一个 record**，只有 `kind` 不同 ⇒ 形状检查对两者通用。

## 7. 已知限制

1. **点只覆盖写工具**（`file_write` / `file_edit`）—— 模型走 shell 改的文件
   不在快照里（R388n 起的既有缺口，R403 §9 记着）。
2. **没有键盘导航**：菜单用鼠标点。TUI 那套方向键在桌面端没有对应物。
3. **总结动作没有把 daemon 的数字显示出来**：daemon 的 `summarized` 是
   「摘要消息的条数」（通常 1），不是「被总结的对话条数」；把 1 显示成
   「总结了 1 条」是错的。本轮选择**不显示**，只让对话本身变短作为反馈。
4. **总结成功后菜单直接关闭**，不给数字回执（同上）。
5. **菜单不显示「这个点之后的对话」**：daemon 的点只记了边界，没记尾部长度。

## 8. 产物

`D:\work\tmp\r402-package.log`、`r402-deploy.log`、`r402-mutate-summary.txt`、
`r402-live.log`、`r402-mutate.ps1`、`r402-live.mjs`。
