# R406 — checkpoint 的文件清单：说清它覆盖什么

## 1. 目标

在 checkpoint 的 **wire 元数据**里标注：文件清单**只覆盖写工具**写下的文件，
**用 shell 写下的文件不在其中**。

这是 R403 / R402 一直记着的那条「已知限制」的正面处理：
以前是「只记为已知限制」（文档里写一句，界面不吭声），
这一轮把限制**放到 wire 上**，让任何一个渲染器都能读到它并说给用户听。

## 2. 事实核查（先量，再改）

### 2.1 文件清单是怎么来的

`CodeCheckpointStore.touchedFiles(transcript, cwd)` 扫**整份 transcript** 的
`tool_use` 块，只认一张表里的工具：

```java
// aethercode-core/.../transcript/CodeCheckpointStore.java
private static final Map<String, String> WRITE_TOOLS = Map.of(
        "file_write", "file_path",
        "file_edit", "file_path");
```

⇒ **清单 = 这两个工具在 transcript 里点名过的文件**。没有别的来源。

### 2.2 于是覆盖范围是这么回事

| 写盘通道 | 在清单里？ | 为什么 |
|----------|-----------|--------|
| `file_write` | ✅ | 表里有 |
| `file_edit` | ✅ | 表里有 |
| **`notebook_edit`** | ❌ **（这是个 bug）** | 它**真的写盘**（`NotebookEditTool` 里 `Files.writeString(p, …)`），路径在 `notebook_path` —— 和另外两个**完全同形**，本该能覆盖，但表里没有 |
| `bash` | ❌ **（这是边界）** | shell 写盘（`echo x > f`、`sed -i`）却**不点名任何文件** ⇒ 没有路径可快照 ⇒ **补不上** |

### 2.3 而且那条 gate 自己就在说假话

`CodeCheckpointStoreR388nTest.writeToolsAreTheOnesThatChangeFiles` 断言这张表
**就是**「会改文件的工具」：

```java
assertEquals(Map.of("file_write", "file_path", "file_edit", "file_path"),
        CodeCheckpointStore.writeTools());
```

而 `notebook_edit` **会改文件**，却不在表里。
⇒ **一条写着「这些就是会改文件的工具」的断言，本身漏了一个会改文件的工具。**

（这不是台架坏了，是台架**不够狠**：它钉住了自己知道的那份清单，
而「会改文件的工具」这个事实**在系统里已经有第二份声明** ——
`ProfileRegistry.WRITE_TOOLS`，它早就把 `notebook_edit` 列进去了。）

## 3. 设计决策

1. **先让「覆盖写工具」这句话成真，再去标注它。**
   否则标注就是假话：wire 说「只覆盖写工具」，
   而用户用 `notebook_edit` 写的文件根本不在清单里。
   ⇒ `notebook_edit` / `notebook_path` 进表（**一行**）。
   ⇒ 这**不是**补 bash 写盘追踪（那是启发式），而是补一个**和另外两个同形**的
   结构化工具调用。
2. **wire 上的覆盖契约必须从实现派生。**
   `coveredTools` 直接取 `CodeCheckpointStore.writeTools()` 的键集 ——
   和文件清单**同一张表**。
   ⇒ 手写一份「我们覆盖哪些工具」就是**第二份清单**，而第二份清单必然漂移（R380）。
   ⇒ `shellCovered` 同样派生：`writeTools().containsKey("bash")` ⇒ 今天为 false。
   ⇒ 那句给用户看的话也是**生成**的（按覆盖集拼出来），所以它不可能描述一个
   代码没有的覆盖范围。
3. **标注放在 `checkpoints/list` 与 `checkpoints/restore` 两处。**
   - list：用户挑点之前就要知道这份清单的边界；
   - restore：`restored: [a.txt]` 读起来像「树回到了那个点」，
     而 shell 写的文件**从来没进过快照**，回退之后它仍然是改过的。
4. **对话回退不带这个字段。**
   和「文件结果」同一组规则：**代码没在范围内，文件结果就整个不出现**。

### 3.1 wire 长什么样

```jsonc
{
  "ok": true,
  "checkpoints": [ /* … */ ],
  "evicted": 0,
  "filesCoverage": {
    "coveredTools": ["file_edit", "file_write", "notebook_edit"],
    "shellCovered": false,
    "note": "the file list covers the files written through file_edit, file_write, notebook_edit; writes made through the shell are not listed"
  }
}
```

### 3.2 桌面端把它显示出来

`CheckpointMenu` 在「已丢弃 N 个点」那条下面加一行：

> 文件清单只覆盖写工具（file_edit、file_write、notebook_edit）写下的文件；
> 用 shell 写下的文件不在其中，回退之后仍然是改过的。

**事实来自 wire**（`coveredTools` / `shellCovered`），**措辞是渲染器的**。
⇒ 渲染器**不保留自己的一份工具清单**（那会是第二份清单）。
⇒ 老 daemon 不发这个字段时，菜单**什么都不显示** ——
既不崩，也**不替 daemon 编一个覆盖范围**。

## 4. 改动

| 文件 | 改了什么 |
|------|---------|
| `aethercode-core/.../transcript/CodeCheckpointStore.java` | `WRITE_TOOLS` 补 `notebook_edit` → `notebook_path`；javadoc 写清「这张表**就是**覆盖契约」，以及 shell 为什么补不上 |
| `aethercode-protocol/.../methods/AetherCodeMethods.java` | 新增 `filesCoverage()`（从 `writeTools()` 派生）；`checkpoints/list` 与 `checkpoints/restore` 的响应带上它 |
| `aethercode-desktop/src/lib/methods.ts` | 新增 `CheckpointFilesCoverage` 类型；list / restore 的响应类型带上 `filesCoverage?` |
| `aethercode-desktop/src/components/CheckpointMenu.tsx` | 渲染覆盖说明那一行（事实来自 wire） |
| `aethercode-desktop/src/rpc/MockRpcServer.ts` | mock **镜像 daemon 的契约**：默认发当前 daemon 的覆盖；seed 可以给 `null` 模拟老 daemon |

## 5. 测试

| 测试类 | 例数 | 钉住什么 |
|--------|------|---------|
| `CodeCheckpointStoreR406Test`（core，新） | 6 | **交叉校验**（覆盖集 == `ProfileRegistry.WRITE_TOOLS`）；shell 不在表内；notebook 进清单；checkpoint 真的快照了 notebook；shell 仍然进不来 |
| `AetherCodeMethodsFilesCoverageR406Test`（protocol，新） | 6 | wire 带覆盖契约；**覆盖集就是实现那张表**；没有点时也在；restore 也带；对话回退不带；shell 是唯一被点名的缺口 |
| `CodeCheckpointStoreR388nTest`（core，改） | — | 那条 gate 改成「形状 pin」，并指向 R406 的交叉校验 |
| `CheckpointMenuR406.test.tsx`（桌面，新） | 4 | 菜单显示覆盖说明；不谎称 shell 被覆盖；**老 daemon 不崩也不编造**；措辞随 wire 变 |

## 6. 变异

**9 个突变（7 Java + 2 桌面 TS），全部被抓住**：

| 突变 | 改了什么 | 证人（预期被谁抓住） |
|------|---------|-------------|
| M01 | 把 `notebook_edit` 从表里拿掉 | `everyWriteToolIsCoveredByTheCheckpoint` |
| M02 | wire 自己留一份工具清单 | `theCoverageOnTheWireIsTheImplementationsOwn` |
| M03 | wire 谎称 shell 被覆盖（`shellCovered = true`） | `theListCarriesTheCoverage` |
| M04 | list 不再带覆盖契约 | `theListCarriesTheCoverage` |
| M05 | restore 不再带覆盖契约 | `theRestoreCarriesTheCoverageToo` |
| M08 | 覆盖清单里加上 `bash` | `theShellIsTheOneNamedGap` |
| M09 | 对话回退也带覆盖契约 | `aConversationOnlyRestoreCarriesNoFileCoverage` |
| M06 | 渲染器把工具清单写死 | 桌面「the coverage comes off the wire…」 |
| M07 | 渲染器把覆盖契约丢掉 | 桌面「states that the list only covers the write tools」 |

基线 ×3 全绿（core 25 + protocol 6 = 31 例）、终检 ×3 全绿、
还原证明逐字节一致（源 vs 副本）、桌面文件哈希前后一致。

### 6.1 ★ 台架自己坏过三处 —— 坏的是量具，不是产品 ★

第一轮台架报 **3 条「没抓住」**。逐条查下来，**产品的测试全是对的**：

| 症状 | 真相 |
|------|------|
| M03「没抓住」 | **期望挂错了测试**：M03 改的是 `shellCovered` 标志，而 `theShellIsTheOneNamedGap` 断言的是**工具集合** ⇒ 突变其实被另外两条抓住了（`theListCarriesTheCoverage` / `theRestoreCarriesTheCoverageToo`）⇒ **是我的期望表错了，不是测试弱** |
| M06 / M07「没抓住」 | **比较的是带装饰的名字**：vitest 打印的用例名带 ANSI 前缀（`ESC[22m` + `★`），台架拿**干净串**跟它做全等比较 ⇒ 期望的名字**明明在失败集合里**，却被判成「没抓住」 |
| M06 / M07 的失败集合是乱码 | 台架用 PowerShell 的**默认 ANSI 编码**读 UTF-8 的 JSON ⇒ `★`(E2 98 85) 被 gb2312 解成乱码 ⇒ 期望名也对不上 ⇒ 读 JSON 必须显式 `-Encoding UTF8` |

⇒ 修法：比较**规范化后的名字**（剥 ANSI、剥用例自己的 `★` 标记）；读 JSON 显式 UTF-8；
再给台架加一条**盲测保护**（`ran <= 0` ⇒ 「量具没跑到东西」，不许判）。

### 6.2 ★ 更要命的推论：那条测试当时**没有任何突变以它为证人** ★

M03 的期望挂错，**表面**是台架的小失误；**底下**是一个真问题：

`theShellIsTheOneNamedGap` 写的时候是为了钉「shell 是唯一被点名的缺口」，
但第一版变异表里**没有一条突变去打「覆盖清单里含 bash」这个形状**
⇒ 那条测试**当时没有被任何突变验证过** ⇒ 没人知道它到底在防什么。

⇒ 补了 **M08**（覆盖清单里加上 `bash`）与 **M09**（对话回退也带覆盖契约）
⇒ 现在 **7 条测试条条有专属证人**。

### 6.3 ★ `.ps1` 必须是纯 ASCII ★（R405 的教训，本轮又差点踩）

本轮我自己在台架注释里打了一个 `★`（**6 个非 ASCII 字节**）——
台架的**自检**当场拒跑（`exit 3`），没等到出事。

（若它落在**行尾**，GBK 会把 `85` 当前导字节、与下一个字节配对；
下一个字节是 `0x0A`（换行）时**换行被吞**，下一行整行变成注释 ⇒
「6 个锚点全落空」那种形态。R405 整整一轮就栽在这上面。）
⇒ R406 的台架：**纯 ASCII** + **跑前自检非 ASCII 字节数** + **锚点先对源文件预检**
+ **摘要边跑边写**。

## 7. 实机验证

**决定性实验**：让模型**同时**用两种方式写盘 —— `file_write` 写一个，
`bash` 写一个 —— 然后核对**标注**与**实际清单**是否一致。

**24 / 24 全绿**（端口 22995，一次性 cwd 与 sessions 目录）。

### 7.1 标注与清单必须一致

| 断言 | 结果 |
|------|------|
| `file_write` 写的文件在磁盘上 | ✅ |
| shell 写的文件在磁盘上 | ✅ |
| wire 带 `filesCoverage` | ✅ |
| 覆盖集非空 | ✅ |
| `shellCovered` 是 false | ✅ |
| 覆盖说明里点了 shell 的名 | ✅ |
| 有点位带文件 | ✅ |
| **`file_write` 写的文件在清单里** | ✅ |
| **shell 写的文件不在清单里** | ✅ |
| 工作目录**恰好**那两个文件 | ✅ |

### 7.2 回退的行为必须和标注对得上

光是「清单里有/没有」还不够 —— 还要看**回退到底动了什么**。
所以在**快照之后**把两个文件都改掉，再回退那个点：

| 断言 | 结果 |
|------|------|
| restore 的响应也带覆盖契约 | ✅ |
| restore 的 `shellCovered` 仍是 false | ✅ |
| restore 报告 file_write 那个文件为 restored | ✅ |
| **file_write 的文件内容被还原成快照里的内容** | ✅ |
| **shell 写的文件原样留着（没被还原）** | ✅ |

⇒ **标注说的和清单做的是同一件事，回退做的也和标注是同一件事** ——
这才是「诚实标注」的验收标准。

### 7.3 实机脚本自己修掉的两个量具问题

1. **★ 我对 `created` 的理解是错的 ★**
   我一度断言「回退会删掉会话新建的文件」。
   读实现后发现：`FileState.created` 的含义是
   **「快照那一刻这个文件不存在」**，**不是**「会话创建的」。
   turn-2 边界的快照是在 turn 1 写完之后拍的 ⇒ 文件已存在 ⇒
   `created=false` ⇒ 还原是**写回内容**，不是删除。
   ⇒ 断言改对之后反而**更强**了：从「文件在不在」升级成
   「内容有没有被回滚」（§7.2）。
2. **★ 同一个提示词，模型有时只说不做 ★**
   第一轮实验模型动了手，第二轮它只回了文本、一次工具都没调。
   ⇒ 「模型这次没写」**什么都不能证明**（R405 的同一条）。
   ⇒ 实机脚本改成**重试**（提示词一字不改、有上限），
   并且把**事件类型 + 助手文本**打出来 ——
   否则「没写盘」会被误读成「产品不支持」。
   ⇒ 第三次跑第 1 次就写成了 ⇒ 前一轮是模型的一次性没配合。


## 8. 已知限制

1. **shell 写的文件仍然回退不了。**
   这是**结构性**的：shell 不点名文件，补追踪就是启发式
   （猜 `>`、`sed -i` 的参数），会引入误报。
   本轮按拍板走「诚实标注」，不补追踪。
2. **`notebook_edit` 现在进了清单** ⇒ 它的改动也会被快照与回退。
   这是修正（之前是漏的），但它**改变了回退的范围**：
   一个改过 notebook 的会话，回退时 notebook 也会被还原。
3. **二进制的 notebook** 仍按「不可还原」处理（和所有二进制文件一样）。

## 9. 教训

- **Lesson 1523 —— 诚实的标注，前提是被标注的那句话是真的。**
  「文件清单只覆盖写工具」这句话，在 `notebook_edit` 缺席时**是假的**。
  ⇒ 先补上那个**同形**的缺口（不是启发式），再标注剩下的那个
  **结构性**边界（shell）。
  ⇒ 顺带：`CodeCheckpointStoreR388nTest` 那条 gate 钉的是
  **自己知道的清单**，而不是事实 ⇒ 一条钉住自己知识的断言，
  可以长期为假而不自知（R388n 的那条就是这么过了很久）。
  ⇒ 真正的钉法是**交叉校验**：`writeTools().keySet()` 必须等于
  `ProfileRegistry.WRITE_TOOLS` —— 后者是**另一处**对「哪些工具会写盘」的声明，
  它不可能和实现一起说谎。

- **Lesson 1524 —— 「覆盖范围」这种契约，该从实现派生，不该手写。**
  wire 上的 `coveredTools` 就是 `writeTools()` 的键集；
  那句给用户看的说明文字也是**生成**的。
  ⇒ 手写一份就是第二份清单，而第二份清单必然漂移（R380）。
  ⇒ 同一条：让**说明文字**也生成，它才不可能描述一个代码没有的覆盖范围。

- **Lesson 1525 —— 一个渲染器不许替服务端编造它没说过的覆盖范围；
  没人读的标注等于假的成功。**
  老 daemon 不发这个字段时，菜单**什么都不显示** ——
  既不崩，也**不编**。
  ⇒ 反过来说：daemon 发了而客户端不渲染，等于没发。

- **Lesson 1526 —— 「突变被抓到了」不等于「你写的那条测试抓到了它」；
  每条测试都要有一个专属的突变证人。**
  M03 的期望挂在了一条**测的是别的东西**的测试上 ⇒ 突变被抓住了，
  但那条测试**当时没有任何突变以它为证人** ⇒ 它的存在理由没被验证过。
  ⇒ 判据不是「红集合非空」，而是「**每条测试都有一条突变指着它**」。

- **Lesson 1527 —— 量具比的是事实，不是事实的包装。**
  装饰（ANSI 转义、用例自己的 `★` 标记、读取时的编码）必须先剥掉再比，
  否则一次**假阴性**长得和「测试没 pin 住」一模一样。
  ⇒ 同一个坑的两种形态：期望名带了装饰 ⇒ 全等失败；
  读取端按 ANSI 解 UTF-8 ⇒ 期望名本身就变了。
  ⇒ 顺带：**读出来的数要是乱的，先怀疑读的那一步，别急着怀疑被测物。**

- **Lesson 1528 —— 断言失败时，先分清「被测物错了」和「我的模型错了」；
  判据是去读实现，不是再跑一遍。**
  实机里我断言「回退会删掉会话新建的文件」，红了。
  读实现才发现：`FileState.created` 的含义是
  **「快照那一刻这个文件不存在」**，不是「会话创建的」
  ⇒ turn-2 边界的快照是在写完之后拍的 ⇒ `created=false`
  ⇒ 还原是**写回内容**，不是删除。
  ⇒ 改对之后断言反而**更强**了：从「文件在不在」升级成
  「内容有没有被回滚」。
  ⇒ 一条断言失败的第一嫌疑是「我以为的语义」，不是「代码坏了」。

- **Lesson 1529 —— 实机脚本必须能区分「模型没动手」和「模型没有工具」。**
  同一个提示词、同一个 daemon：第一轮模型动了手，第二轮它只回了文本、
  一次工具都没调 ⇒ **「模型这次没写」什么都不能证明**（R405 的同一条）。
  ⇒ 实机脚本要**重试**（提示词**一字不改**，且有上限），
  ⇒ 并且要把**事件类型 + 助手文本**打出来 ——
  否则「没写盘」会被读成「产品不支持」，而那是一个**产品缺陷**的形状。


