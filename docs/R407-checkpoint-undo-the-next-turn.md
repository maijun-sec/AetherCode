# R407 — 回退到某点，要真的能撤销下一轮

## 1. 目标

R403 给 checkpoint 落盘时，顺手给取消点（`KIND_CANCEL`）写下了这样一句承诺：

> 撤销被取消那一轮的半成品，是**上一个回合边界点**的职责。

R407 让这句承诺成真。

原因是实测发现：**回退到某点，撤不掉紧接其后那一轮做的事**。更糟的是它**不像失败**——回退报告四个空列表，用户以为成功了，文件还躺在磁盘上。

## 2. 事实核查（先量，再改）

### 2.1 探针读数

R407 的探针（量完即删）打出来的原文：

```
PROBE boundary files=0 ; cancel files=1 names=[new.txt]
PROBE restore restored=[] deleted=[] skipped=[] failed=[]
PROBE the created file still on disk? true
```

三行，三件事：

1. **回合边界点的文件清单是空的**（`files=0`）——尽管那一轮确实新建了 `new.txt`；
2. **回退报告四个空列表**——`restored=[] deleted=[] skipped=[] failed=[]`，一次「成功」的空操作；
3. **文件还在磁盘上**——回退什么也没撤掉。

### 2.2 为什么边界点会漏掉这个文件

回合边界点在**回合开始时**取，快照的内容来自「到这一刻为止，transcript 里记过的 tool_use」。

而那一轮新建的文件，是**那一轮**才第一次被写工具碰到的——在边界点拍照的那一刻，它还没被 transcript 记下。于是：

- 边界点的快照里没有它；
- 它的「写之前长什么样」（不存在 / 存在但被覆盖成什么）这个事实**随着写动作一起消失了**；
- 事后无论怎么从 transcript 反推，都补不回来——transcript 只说「写过这个路径」，不说「写之前它是什么」。

### 2.3 取消点为什么也救不了场

取消点的清单同样是从 transcript 派生的。在取消那一刻，写工具的调用**已经在 transcript 里**了，所以取消点确实记着那个半成品文件（`cancel files=1 names=[new.txt]`）。

但取消点**本来就不该**吸收被取消那一轮的文件——它记录的是「跑停在哪里的现场」，而 R403 §3.3 明确把它排除掉了。

⇒ 于是职责出现了**三方空洞**：

| 角色 | 文档说它该做什么 | 实际做了什么 |
|---|---|---|
| 取消点 | 记现场；**不**吸收被取消轮的文件 | 符合 |
| 回合边界点 | 撤销下一轮（含被取消轮）的工作 | **不知道那个文件存在** |
| 谁来告诉边界点那个文件的存在 | —— | **没人** |

R403 说「不是取消点的职责」是对的，但它没说**那是谁的职责**。R407 补上这一句。

## 3. 设计决策

### 3.1 事实只能在第一次写的那一刻抓到

写之前，文件要么不存在，要么存在（带着某段内容）；写之后，这个答案就没了。所以**上报点必须落在写动作之前**：

- 文件当时不存在 ⇒ 记 `created` ⇒ 回退**删掉**它；
- 文件当时存在且是文本 ⇒ 记下**旧内容** ⇒ 回退**写回**它；
- 文件当时是二进制 / 目录 / 读不了 ⇒ 记「无内容」⇒ 回退**跳过并说明**。

### 3.2 为什么由「写工具」上报，而不是让 executor 重新推导路径

`FileWriteTool` / `FileEditTool` / `NotebookEditTool` **已经**把路径解析到了会话工作目录，**已经**做过越界检查。让 executor 从 tool_use 的参数里再推导一遍路径，等于造出**第二份路径解析**——R380 的老教训（一份名单会漂移）。所以工具用它**即将写入的那个路径**上报。

### 3.3 还债的时机：一轮结束时补给「该轮之前那个点」

事实先攒着（`unclaimedPreTouches`），等**下一个点**被取的时候结算：这一轮**第一次**碰过的文件，归给**这一轮之前**的那个回合点。

为什么是「下一个点」而不是「立刻」：立刻归给谁？那一刻还不知道这一轮会在哪儿结束、下一个边界点在哪儿。

为什么只记**第一次**触碰：第一次之后，每个后续触碰都有自己的点管着；第一次触碰的「之前状态」才是那个原始状态。

### 3.4 结算目标 = 最后一个**回合点**（跳过取消点）

结算要挑一个点来接收这些文件。挑错了，取消点就会吸走别轮的文件（这正是 R403 要禁止的）。

- 取消点坐在**它打断的那一轮之后**，记录的是「停在哪里的现场」；
- 所以它**不是**接收方；
- 接收方是**最后一个回合点**——也就是「刚结束的那一轮」之前那个点。

⇒ `lastTurnPoint()` 遍历时跳过 `KIND_CANCEL`。

### 3.5 结算在「取点」的开头做，新点因此永远拿不到别轮的文件

`save()` 先结算、再造新点 ⇒ 新点自己的清单只来自它自己那一刻的 transcript，不会混进上一轮的文件。

### 3.6 取消路径自动覆盖

取消点也是通过 `save()` 落的 ⇒ 取消那一轮的文件会被同一个结算流程补给前一个回合点。**不需要**为取消单独写一条路径。

### 3.7 修订必须落盘

修订后的点要重新 `disk.save`，否则重启后这次修订就没了（重载会读到旧版本）。

### 3.8 语义不变，爆炸半径小

- `created` 的含义仍然是「**快照那一刻**不存在」，没改；
- 「会话点名了某个文件但它此刻不存在（写被拒 / 失败）⇒ 记 created」是既有约定，沿用；
- **没人上报时**（CLI 路径、单测、任何不装 recorder 的宿主）行为与 R407 之前**逐字节一致**——既有测试全部不受影响。

### 3.9 接线也要能被检查

「recorder 存在但从没装进 executor」是一种**安静腐烂**：每个回退都静悄悄地什么都不做，而且没有任何别的测试会发现。所以 executor 暴露 `fileTouchRecorder()`，引擎暴露 `writePathRecorder()`（**executor 实际带着的那一个**，不是字段本身），让接线可被断言。

## 4. 改动

| 文件 | 改了什么 |
|---|---|
| `core/transcript/FileTouchRecorder.java`（新） | 上报接口 + `EXTRAS_KEY` + 静态 `noteBeforeWrite(ctx, path)`（查找 / 无 recorder / 空路径三种情况都在这一个方法里） |
| `core/transcript/CodeCheckpointStore.java` | `implements FileTouchRecorder`；`preTouch`（按路径去重，只记首次）+ `unclaimedPreTouches`；`beforeWrite()`、`captureState()`、`settlePreTouches()`、`lastTurnPoint()`；`save()` 开头结算；修订后落盘 |
| `core/engine/StreamingToolExecutor.java` | `fileTouchRecorder` 字段 + `withFileTouchRecorder()` + `fileTouchRecorder()`；`buildExtras()` 放入 recorder |
| `tools/file/FileWriteTool.java` | 写之前上报（`createDirectories` 之前） |
| `tools/file/FileEditTool.java` | 写之前上报 |
| `tools/edit/NotebookEditTool.java` | 写之前上报 |
| `sdk/AetherCodeEngine.java` | `fileTouchRecorder` 字段（lambda 读**当前** store）+ `fileTouchRecorder()` + `writePathRecorder()`；构造时 `withFileTouchRecorder(this.fileTouchRecorder)` |

**为什么 recorder 读字段而不是捕获 store 实例**：`codeCheckpoints` 是 `private volatile`，引擎绑定会话时会被**替换**。捕获旧实例的 recorder 会把文件报进**上一个会话**的点里。

## 5. 测试

新增 19 例（本机 1 例因路径沙箱被短路而 skip，见 §8）：

| 测试类 | 例数 | 钉住什么 |
|---|---|---|
| `CodeCheckpointStoreR407Test`（core） | 8 | 点学到新建文件 / 回退删掉它 / 被改写的文件还原旧内容 / 只记首次触碰 / 取消点不夺走该轮文件 / **取消点永不吃别轮的文件**（M02 的专属证人）/ 修订后落盘且重载仍在 / 无人上报时不编造 |
| `StreamingToolExecutorR407Test`（core） | 2 | recorder 到达工具 / 无 recorder 时工具照常跑 |
| `FileWriteToolR407Test`（tools） | 5 | 三个写工具都上报 / 被拒的写不上报 / 无 recorder 也能写 |
| `AetherCodeEngineCheckpointR407Test`（sdk） | 4 | 写路径接线 / 回退删除该轮新建文件 / 回退还原被改写文件 / **取消点之前那个点学到被取消轮的文件** |

回归全绿：R388n 19、R406 core 6、R403 7、protocol R406 6 + R402 6。

## 6. 变异

台架：`D:\work\tmp\r407-mutate.ps1`（纯 ASCII；副本树 `D:\work\tmp\r407-mutant`）。**6 个突变，6 个全被抓住**，baseline ×3 与 final ×3 全绿，还原后逐字节一致。

台架**跑了两遍**：第一遍跑完之后我改了 `FileWriteToolR407Test`（见 §6.4），
于是用**最终的树**重跑了一遍 —— 上面这个数字来自**第二遍**。
理由就是 §6.2 写的那条：量具必须读到**当前的**测试，否则它测的是一份旧快照。

| 突变 | 破坏什么 | 专属证人 |
|---|---|---|
| M01 | 触碰记下了但从不认领 | `thePointBeforeTheTurnLearnsTheFileThatTurnCreated` |
| M02 | 结算时不再跳过取消点 | `aCancelPointNeverAbsorbsAnotherTurnsFiles` |
| M03 | 不存在的文件不再记 `created` | `theRestoreOfThatPointDeletesTheFileTheTurnCreated` |
| M04 | 修订不落盘 | `theAmendedPointSurvivesAReload` |
| M05 | 写工具不上报 | `fileWriteReportsThePathItIsAboutToWrite` |
| M06 | 引擎没把 recorder 装进 executor | `theWritePathIsWiredToTheRecorder` |

### 6.1 ★ 台架自己坏过两处 —— 坏的是量具，不是产品 ★

**其一：M02 的证人一开始是「不可观测」的。**
M02 改的是「结算时挑哪个点」。第一版的证人场景里，结算发生时**最新的点永远是一个回合点**——于是「跳过取消点」和「不跳过取消点」给出**同一个答案**，两种实现同解，突变当然抓不住。

⇒ 修法不是改断言，是**改场景让它可观测**：加第三轮，并且让第三轮的起点与上一个取消点之间**没有回合点**。这样结算那一刻「最新的点恰好是一个取消点」，两种实现才第一次给出不同答案。

这正是 R406 的老教训：**一条没有专属突变的测试，它的存在理由从未被验证过**。

**其二：M04 的突变写法编译不过。**
把 `disk.save(amended);` 换成注释，会让 `try` 块变成空块 ⇒ 编译失败 ⇒ 突变**从未被测过**（量具会把「编译失败」报成「没抓住」，其实是没跑）。

⇒ 改成 `if (amended.files().isEmpty()) { disk.save(amended); }`：能编译，而且在真正有修订的场景里**恰好跳过落盘**。

### 6.2 ★ 台架必须同步副本树 ★

副本树是在第一次运行时 `robocopy` 出来的。本轮**改了测试文件**之后，副本树里的那份是**旧的**——台架会去测一份已经不存在的测试。

⇒ 台架现在**每次运行**都同步一次整棵树，并在同步后**逐个比对**四个 R407 测试文件的 SHA-256；不一致直接抛错（「STALE TEST in the mutant tree」），而不是安静地测旧测试。

这是量具的第四类坏法：**读到的是旧版本**。

### 6.3 ★ 台架必须先证明自己读到了东西 ★

baseline 每次都断言 `ran > 0`（读不到东西的「通过」不算通过），失败集合为空时报的是「哪条测试应该抓住却没抓住」而不是「红」。

### 6.4 ★ 一条测试的名字不许和它的代码说的不一样 ★

`aContextWithoutARecorderIsFine`（tools）本来要钉的是「上下文里**没有** recorder
时，工具照常写」。它的代码却**传了一个 recorder 进去** ⇒ 它钉的是另一件事
（虽然那一件也成立）。名字与代码不一致的测试，读者会以为它守住了它没守住的东西。

⇒ 改成真的不传 recorder（`ctxWithoutRecorder(cwd)`）。

⇒ **Lesson 1540 — 断言失败时先查「我的模型错了」；断言**成立**时，
更要查「这条测试的名字说的是不是它真的在做的那件事」——
名字对不上的测试，守的是另一个洞。**

## 7. 实机验证

脚本：`D:\work\tmp\r407-live.mjs`（端口 22994，用刚打出来的 jar 自己起一个 daemon）。**19 项全绿**。

### 7.1 决定性实验：回退真的撤掉了那一轮

```
PASS  write turn 1: the turn finished
PASS  ★ the session created the file (after 1 turn(s))
PASS  ★ the file holds what the session wrote
PASS  the settling turn: the turn finished
PASS  ★ a point learned the file the turn created
PASS  ★ and it recorded it as CREATED (the restore must delete it)
PASS  ★ the last point (taken after the write) says created=false
PASS  ★ and the last point is NOT the one that learned it
PASS  ★ the restore succeeds
PASS  ★ the restore reports the file as deleted
PASS  ★ ★ THE FILE IS GONE — the rewind undid the turn
PASS  the working directory holds nothing the session wrote
```

关键读数：**回退之后文件从磁盘上消失**。R407 之前，同一次回退的报告是
`restored=[] deleted=[] skipped=[] failed=[]`，文件原地不动。

### 7.2 ★ 那条交叉校验是本轮最硬的一行 ★

同一个文件，在**两个不同的点**里给出**两个不同的答案**：

| 点 | 拍摄时刻 | `created` | 来源 |
|---|---|---|---|
| 学到它的那个点 | 那一轮**之前** | `true` | **写前上报**（R407） |
| 最后一个点 | 那一轮**之后** | `false` | transcript 派生的快照（既有） |

为什么这条不能省：最后那个点是从 transcript 推导的，它只可能说「文件现在存在」
⇒ `created=false`。所以 `created=true` **只可能**来自写前那次上报。
没有这一行，「某个点里有 `created=true`」仍有可能来自别的巧合。

### 7.3 ★ 模型不动作时，脚本必须吸收，而且必须可观测 ★

R406 的 Lesson 1529：同一个提示词，模型有时只回文本、一次工具都不调 ⇒
「模型这次没写」**什么都不能证明**。

⇒ 脚本在文件没出现时用**同一个提示词**重试（上限 3 轮），并打印重试次数与
助手的原话；本轮第 1 轮就写成了，没走重试。

⇒ 并且「回退哪个点」是**问出来的**（哪个点学到了这个文件），不是写死下标 ——
重试会让点数变，写死下标会在重试后指向另一个点。

### 7.4 全量打包与部署

```
surefire: 659 classes / 6307 tests / 0 failures / 0 errors / 5 skipped
BUILD SUCCESS (10:47)
jar synced: 56 985 387 bytes
jar sha256 = 34EB9F2D523081A3924E31D058FF817CD3D091E7CDC40167F568D5D67360059C
daemon healthy after 2s
```

（5 skipped 里，1 例是本机 `AETHERCODE_ALLOW_ANY_PATH=1` 短路了路径沙箱的那例。）

### 7.5 ★ 打包脚本的计数读到了**旧版本**的产物 ★

第一次统计报的是「660 类 / 6308 例」——比 R406 的 655 类 / 6288 例多了 5 类 20 例，
而本轮只新增了 **4 个测试类 19 例**。

多出来的那 1 类 1 例是：`TEST-org.aethercode.sdk.R407CancelCreatedFileProbeTest.xml`
—— R407 早先用来测缺陷的那个探针测试，**源码已删**，但它的 surefire 报告还躺在
`target/surefire-reports/` 里，被统计脚本数了进去。

⇒ 计数脚本现在**只数本次构建写出来的报告**（`LastWriteTime >= 构建开始时间`）。
删掉那份陈旧报告后重新统计：**659 类 / 6307 例**（= 655 + 4，6288 + 19），完全对得上。

⇒ 这是量具的第四类坏法（**读到的是旧版本**）的又一次现身，只是这次藏在
「一个数字看起来挺合理」的外衣下。

### 7.6 部署后的桌面 daemon

桌面起自己的 daemon（22888），`healthz` 2 秒返回 200，jar 与构建产物同哈希。

## 8. 已知限制

- **沙箱类测试在本机跳过**：本机全局环境变量 `AETHERCODE_ALLOW_ANY_PATH=1` 会短路路径沙箱（`FileWriteTool.call` 的 `isPathAllowed`），所以「被拒的写不上报」一例按既有约定 skip。本轮 1 例 skipped。
- **写路径之外的文件仍不在清单里**：shell（`bash`）写的文件不在快照覆盖内——那是 R406 已经标注清楚的边界，R407 不改变它。
- **二进制 / 目录**：回退跳过并说明，不写回损坏的字节。

## 9. 教训

- **Lesson 1530 — 一个「撤销」功能最容易的失败方式不是报错，而是报告成功却什么都没做。** 四个空列表比一个异常危险得多：异常会让人去查，空列表会让人以为已经撤销了。
- **Lesson 1531 — 快照的时刻决定了它能回答什么问题。** 「回合开始时拍的快照」永远答不了「这一轮做了什么」——它拍照的时候，那一轮还没开始。
- **Lesson 1532 — 事实只在写的那一刻存在。** 写之前，文件存在与否、内容是什么，是唯一可得的答案；写完就没了。事后从 transcript 反推补不回来，因为 transcript 只说「写过这个路径」，不说「写之前它是什么」。
- **Lesson 1533 — 「不是 X 的职责」必须同时写下「那是谁的职责」。** R403 把取消点排除得很干净，却留下了一个三方空洞：没人被指派，于是没人做。
- **Lesson 1534 — 结算目标不能是「最新的点」，要是「最新的**那一类**点」。** 「最新」这个词在没有分类的情况下是危险的。
- **Lesson 1535 — 工具已经解析过路径；让别人再解析一次＝第二份真相。** 路径解析的副本会漂移（R380）。
- **Lesson 1536 — 台架必须同步副本树，否则它测的是旧测试。** 副本树是**一次性的快照**，而测试会继续变。
- **Lesson 1537 — 一个证人不可观测时，要改场景让它可观测，不要改断言。** 「两种实现同解」的证人等于没有证人。
- **Lesson 1538 — 「编译失败」是台架的失败，不是被测物的失败。** 一个没跑起来的突变不能算「突变存活」——台架必须把它单独报出来，否则「没抓住」会被误读成「测试不够狠」。
- **Lesson 1539 — 数「跑了多少个测试」时，量具读的是文件夹的历史，不是这次构建的产物。** `surefire-reports/` 会留着**源码已删**的测试类的 XML；把它数进去，计数就多出一个**谁都没跑过**的类。本轮它多报了 1 类 1 例——而这看起来只是「比上次多了一点」。⇒ 计数只数**本次构建写出来的**报告。
- **Lesson 1540 — 一条测试的名字必须和它的代码说同一件事。** `aContextWithoutARecorderIsFine` 传了一个 recorder 进去——它守的是另一个洞；名字对不上的测试，读者会以为它守住了它没守住的东西。⇒ 断言**成立**时也要查这件事（断言**失败**时查「我的模型错了」，断言**成立**时查「名字说的是不是它真在做的事」）。
