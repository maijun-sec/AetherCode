# R405 — 缺的不是词，是一个「位置」

## 1. 目标

修掉与 R404 **同类**的第二个静默功能失效：用户用最普通的祈使句要求改文件，
agent 却**没有写工具可用**。

R404 补的是「定冠词形式」（`create the`），R405 补的是**语法位**：
`Update pom.xml` 这类**不带任何限定词**的祈使句。

现象（实机实测，同一句话，两种姿态）：

| 提示 | R405 之前 | R405 之后 |
|------|-----------|-----------|
| `Update pom.xml` | ❌ `UNCERTAIN` ⇒ 只读档案 ⇒ `file_write` 被裁掉 | ✅ |
| `Modify Foo.java to handle nulls` | ❌ `UNCERTAIN` ⇒ 同上 | ✅ |
| `Add tests for the parser` | ❌ `UNCERTAIN` ⇒ 同上 | ✅ |
| `修改 Foo.java` | ❌ `UNCERTAIN` ⇒ 同上 | ✅ |
| `the implementation of LRU` | ❌ **假阳性** `CODE_CHANGE` ⇒ 概念问题拿到了写工具 | ✅ `UNCERTAIN` |

模型在失败那一次的实测表现（R404 记录的同一形态）：连续 6 次
`unknown tool: file_write`，然后绕道 `echo hello > file` 把文件写了出来。

## 2. 现状（事实，附证据）

### 2.1 缺口：祈使句里的动词是「裸」的

R404 之后，`CODE_VERBS` 里这些动词只有**限定词形式**：

```
create a / create an / create the
add a / add an / add the
write a / write an / write the
build a / build an / build the
generate a / generate an / generate the
update the
```

于是「动词 + 路径」这种**最常见的祈使形态**整片漏掉：

```
Update pom.xml
Modify Foo.java
Edit main.py
Introduce a Parser.java
Bump the version in package.json
Add ParserTest.java
Build Parser.java
```

实测（R405 之前，jshell 打真分类器，40 条提示）：

```
UNCERTAIN   Update pom.xml
UNCERTAIN   Update pom.xml to use the new dependency
UNCERTAIN   Modify Foo.java to handle nulls
UNCERTAIN   Edit main.py
UNCERTAIN   Introduce a Parser.java
UNCERTAIN   Bump the version in package.json
UNCERTAIN   Add tests for the parser
UNCERTAIN   Add ParserTest.java
UNCERTAIN   Build Parser.java
UNCERTAIN   修改 Foo.java
```

### 2.2 假阳性：`implement` 是唯一在表里的裸动词

`CODE_VERBS` 用 `String.contains` 读，**子串匹配**。
`implement` 又是唯一以裸形式在表里的实现动词 ⇒
`the implementation of LRU` 命中 `implement` ⇒ 判 `CODE_CHANGE`。

实测：

```
CODE_CHANGE the implementation of LRU     <-- 假阳性
```

一个**关于 LRU 的概念问题**，拿到了写工具。

### 2.3 为什么「再补几个词」这条路是走不通的

R404 的教训是「英语里几乎每个动词都有名词义」；R405 把这条教训推到底：

- `update` → a status update / the update
- `change` → a change / the change log
- `edit` → in edit mode / an edit
- `write` → the write path / a write
- `add` → an add operation
- `build` → the build tool
- `create` → the create method
- `implement` → the implementation of LRU
- `modify` / `introduce` / `bump` / `adjust` / `replace` / `generate` —— 同理

**给每个动词补齐全部限定词形式是不可完成的**：名词可以跟在任何动词后面。
`the update README` 也是英语。

⇒ 缺的不是词表里的条目，是**一个能区分「动词」与「名词」的判据**。

### 2.4 那个判据是「位置」

实测把每一组正反例摊开看，**唯一的差别不是词，是词坐在哪儿**：

| 祈使句（请求） | 名词短语（不是请求） |
|----------------|---------------------|
| `Update pom.xml` | `give me a status update on the migration` |
| `Modify Foo.java` | `the write path of the engine` |
| `Add tests for the parser` | `the created file` |
| `Introduce a Parser.java` | `the generated code` |

⇒ **动词在句首（祈使位置）⇒ 是请求；动词坐在名词短语里 ⇒ 不是请求。**

## 3. 设计决策

1. **新增一张「歧义动词表」，而不是往 `CODE_VERBS` 里加词。**
   `CODE_VERBS` 是 `String.contains` 读的裸表，往里加裸动词会引入新的假阳性；
   `AMBIGUOUS_CODE_VERBS` 只在**独立成词**时才算命中。
2. **命中条件 = 独立成词 AND（祈使位置 OR 路径样宾语）。**
   - 祈使位置覆盖「对象不是文件名」的请求（`Add tests for the parser`）；
   - 路径样宾语覆盖「动词不在句首但点名了文件」（`Update pom.xml`）。
3. **疑问句路径只用祈使位置，不用路径锚定。**
   `can you update pom.xml?` 是**穿着问号的请求** ⇒ `CODE_CHANGE`；
   而 `what changed in pom.xml?` 是**关于文件的问题** ⇒ 仍然是 `ANSWER`。
   ⇒ 「路径样宾语」这一半**不给疑问句用**，这是这一轮最关键的一条边界。
4. **请求前缀表里刻意不含疑问词。**
   `should i` / `what` / `why` / `how` / `did` 属于「信息式问句」，
   放进前缀表会把 R404 留下的 pin（`should I update the file?` 不是代码工作）弄红。
5. **中文按自己的词边界规则。**
   中文没有词边界：`修改记录`（变更记录）以 `修改` 开头。
   ⇒ 中文动词**后面不接汉字**才算独立成词：
   `修改记录` 不是指令，`修改 Foo.java` 是指令。

## 4. 改动

| 文件 | 改了什么 |
|------|---------|
| `aethercode-core/.../intent/IntentClassifier.java` | 新增 `AMBIGUOUS_CODE_VERBS` / `REQUEST_PREFIXES` 两张表；新增 `isLatinWordChar` / `isCjk` / `isStandaloneAt` / `standaloneAmbiguousVerb` / `imperativeAmbiguousVerb` 五个辅助；决策表新增第 5b/5c 的祈使分支与第 6b 步；`CODE_VERBS` 去掉裸 `implement`、改列 `implement a/an/the` |
| `aethercode-core/src/test/.../intent/IntentClassifierR405Test.java` | **新建** 8 例：祈使句批量转正、请求前缀保持祈使、名词短语仍然不动、`implement` 假阳性、分词形式（`the updated pom.xml`）、中文复合词、请求式问句 vs 信息式问句、R401/R404 不变量 |
| `aethercode-core/src/test/.../engine/QueryEngineIntentR405Test.java` | **新建** 3 例：写工具到不到模型手里 + 执行器真的跑了 |

### 4.1 决策表的落点

```
5b. 疑问句 + 本地锚点
      ├─ 概念问题            → ANSWER / RESEARCH
      ├─ STRICT 复合词       → CODE_CHANGE          （R404 留下的）
      ├─ 祈使位置的歧义动词  → CODE_CHANGE          （R405 新增）
      └─ 否则                → ANSWER
5c. 疑问句 + 无本地锚点
      ├─ STRICT 复合词       → CODE_CHANGE
      ├─ 祈使位置的歧义动词  → CODE_CHANGE          （R405 新增）
      └─ 否则                → RESEARCH
6b. 非疑问句
      ├─ CODE_VERBS 命中     → CODE_CHANGE          （R404 留下的）
      ├─ 独立成词的歧义动词
      │    AND（祈使位置 OR 路径样宾语）→ CODE_CHANGE  （R405 新增）
      └─ 否则                → UNCERTAIN
```

### 4.2 为什么路径样宾语这一半不给疑问句

这是本轮最容易写错的一处。

- `Update pom.xml`（非疑问句）⇒ 动词不在句首，但**点名了一个文件** ⇒ 指令。
- `what changed in pom.xml?`（疑问句）⇒ 同样点名了一个文件，但这是**在问**。

所以 6b 步用「祈使位置 OR 路径样宾语」，5b/5c 步**只用**祈使位置。
两处都用「祈使位置」，是因为「句首先出现这个动词」在两种句式下
都是「用户在开口要人干活」。

## 5. 测试与变异

### 5.1 测试

| 测试类 | 例数 | 钉住什么 |
|--------|------|---------|
| `IntentClassifierR405Test` | 9 | 判定表：正例、反例、假阳性、分词形式、中文边界、问句两侧、R401/R404 不变量 |
| `QueryEngineIntentR405Test` | 3 | 用户可见的后果：**写工具到不到模型手里**，以及执行器真的跑了 |

连同 R401 / R404 的回归对照：**12 新 + 32 回归 = 44 例全绿**
（变异台基线读到的 `ran=12` 就是这两个新类的例数）。

### 5.2 变异

6 个突变，**每一个都打在不同的地方、预期命中不同的测试**：

| 突变 | 改了什么 | 预期被谁抓住 |
|------|---------|-------------|
| M01 | 整条 6b 规则关掉 | `aBareVerbInImperativePositionIsCodeWork` |
| M02 | 触发条件降级成恒真 | `theNounPhrasesStayUncertain` |
| M03 | 整词判定（whole-word）失效 | `theParticipleIsNotAVerb` |
| M04 | 请求前缀不再剥离 | `aRequestPrefixKeepsTheImperativeReading` |
| M05 | 疑问句标志被丢掉 | `aRequestQuestionIsCodeWorkButAnInformationQuestionIsNot` |
| M06 | 中文词边界判定失效 | `aChineseCompoundIsNotAnInstruction` |

**结果：6/6 全部被抓住**，基线 ×3 全绿、终检 ×3 全绿、还原后与源文件逐字节一致。

## 6. 实机验证

三臂，同一个 daemon，同一个会话，一次性 cwd：

| 臂 | 提示 | 期望 |
|----|------|------|
| A | `Update r405-probe-a.txt so that it contains exactly this content: …` | `code_change` / `builder` / 文件落盘 |
| B | `Create the file r405-probe-b.txt with exactly this content: …`（R404 对照） | `code_change` / `builder` / 文件落盘 |
| C | `give me a status update on the migration` | 不是 `code_change` / 只读档案 / 无写入 |

**A 与 C 携带同一个词（`update`），只有位置不同** ⇒ 这是这一轮的**决定性实验**：
它证明起作用的是位置，不是词表。

shell 在三臂里都被显式拒绝（R401 的教训：只裁写工具、不裁 shell，
模型会绕道 `echo hello > file`），所以**唯一**的写通道是 `file_write`。

实测（**27 条断言全绿**）：

```
PASS  ★ arm A: the daemon's verdict is CODE_CHANGE
PASS  ★ arm A: the profile carries the write tools      (builder)
PASS  ★ arm A: the file was written
PASS  ★ arm A: the write went through file_write
PASS  ★ arm A: every shell attempt was REFUSED (isError)
PASS  ★ arm B: the daemon's verdict is CODE_CHANGE
PASS  ★ arm B: the profile carries the write tools      (builder)
PASS  ★ arm B: the file was written
PASS  ★ arm B: the write went through file_write
PASS  ★ arm B: every shell attempt was REFUSED (isError)
PASS  ★ arm C: the daemon's verdict is NOT code work
PASS  ★ arm C: the profile is the read-only one
PASS  ★ arm C: no FILE-writing tool was called
PASS  ★ arm C: every shell attempt was REFUSED (isError)
PASS  ★ the working directory holds EXACTLY the expected files
PASS  ★ the three arms landed on DIFFERENT postures (nothing was pinned)
PASS  ★ arms A and C carry the SAME word and differ only in POSITION
```

### 6.1 两轮跑出来的**测具**缺陷（不是产品缺陷）

第一轮实机是 **24 通过 / 2 失败**，两条失败都在**脚本的断言**上；
修完再跑，又冒出两条同类的：

1. **「arm A: no shell call slipped in」** —— 实测模型**确实调了两次 `bash`**
   （写完文件想看一眼）。
   但两次都被策略拒绝（`isError=true`），磁盘上**零泄漏**
   （内容级检查通过：工作目录里恰好是那几个文件，内容各自正确）。
   ⇒ **断言钉错了东西**：它钉的是**模型的行为**（会不会去调 shell），
   而要钉的是**这个调用的结果**（有没有生效）。
   ⇒ 这正是 R403 已经写下的那条：「断言『模型没调 X 工具』是错的断言，
   该断言的是『结果』」。**已改成断言「每一次 shell 尝试的结果都是错误」。**
2. **「arm C: the turn finished」** —— 两次超时（180 秒、420 秒）。
   C 臂的提示在仓库里没有本地答案，只读档案会把整个仓库走一遍
   （**已知的 `researcher` 过度收集代价**，R401 遗留，不是本轮引入的缺陷）——
   实测 7 分钟、transcript 涨到 253k 字符。
   ⇒ 超时会让这一轮变红，而原因与本轮要测的东西无关
   ⇒ **已给工作区预置一份 `MIGRATION.md`**，让 C 臂有本地答案。
   **提示词一个字没改** ⇒ 被测的仍然是「词的位置」，不是环境。
3. **「arm C: no write tool was called」** —— 断言用 `/write|edit/` 匹配工具名，
   而 **`todo_write` 也含 "write"**（它写的是待办清单，不是文件）。
   ⇒ **已改成点名真实写文件的工具**（`file_write` / `file_edit` / `notebook_edit`）。
4. **一个实测事实（不是缺陷）**：C 臂的切片里出现过一次 **`bash`** ——
   **只读档案里根本没有 shell**（R401 的 pin：`aProfileWithoutTheWriteToolsHasNoShellEither`），
   模型却仍然去调它。这和 R401 实机里「模型在档案没有 `file_write` 时连调 6 次」
   是同一形态：**模型会调用它没有的工具**，而拒绝它的是策略，不是模型的自觉。
   ⇒ 实机脚本现在对 C 臂也断言「每一次 shell 尝试的结果都是错误」。

⇒ **Lesson 1521 —— 「被拒绝的工具」不等于「模型不会去调它」；
限制是否成立，要由它的**结果**来判，不由模型的选择来判。**
⇒ **Lesson 1522 —— 一个断言里的**正则**也会咬人：
`/write|edit/` 匹配的是 `todo_write`，而待办清单不是文件。
断言要**点名**它关心的那几件东西。**

## 7. 已知限制

1. **名词短语 + 路径样宾语仍然是假阳性面。**
   `the update in pom.xml` 会被判 `CODE_CHANGE`。
   要修就得做真正的句法分析，超出本轮范围；记录在案。
2. **`the prefixes of the config` 之类的既有假阳性仍在。**
   实测判定是 `CODE_CHANGE`，reason 是 `implementation verb: fix` ——
   因为 `pre**fix**es` 里含有 `fix`，而 `fix` 是 `CODE_VERBS` 里**既有的裸条目**。
   本轮没有动 `CODE_VERBS` 的既有条目（改动面小 ⇒ 回归面小），
   所以这条老问题原样留着。
   ⇒ 同一个根因（`String.contains` 子串匹配）也在提醒：
   **裸动词进这张表要付「假阳性」的代价**（Lesson 1518）。
3. **请求前缀表是白名单。** 没列进去的礼貌用语（`pls` 之外的 `kindly`、
   `could you please` 的组合等）不会被剥掉 ⇒ 那样的提示仍可能落到
   UNCERTAIN。**只影响读，不影响写**（路径样宾语那一半还在）。

## 8. 教训

- **Lesson 1516 —— 补词补不完的时候，要找「位置」而不是「词」。**
  英语里几乎每个实现动词都有名词义；名词可以跟在任何动词后面，
  所以「给动词补齐全部限定词形式」是不可完成的。
  能完成的是：**一个能区分动词与名词的判据**，而位置（祈使位）就是那个判据。
- **Lesson 1517 —— 同一个判据在不同句式下可以有不同的一半。**
  祈使位置在疑问句与非疑问句里都成立；路径样宾语只在非疑问句里成立。
  把两半无差别地共用 ⇒ `what changed in pom.xml?` 会被读成请求。
- **Lesson 1518 —— 子串匹配的动词表，必须配一个整词判定。**
  `implement` 命中 `implementation` 是一个假阳性；
  R401/R404 的动词表都是 `String.contains` 读的 ⇒ 任何裸动词进表都要付这个代价。
- **Lesson 1519 —— 中文没有词边界，它的「整词」规则必须自己定。**
  「动词后面不接汉字」这个规则同时保住了 `修改记录` 与 `修改 Foo.java`。
- **Lesson 1520 —— 台架自己坏掉的形态，和「测试没 pin 住」长得一模一样。**
  本轮的变异台架整整一轮报「0 caught / 6 missed」，真相是
  **`.ps1` 文件里的一行注释以 `★` 结尾，把下一行吃进了注释** ⇒
  `$CLS` 从未被赋值 ⇒ 6 个突变的锚点全部落空。
  ⇒ 判据：**「全部漏网」的第一嫌疑是量具坏了，不是被测物坏了**；
  而且量具必须**先证明自己读到了东西**（锚点预检 + 还原证明），
  并在跑之前**自检语法与编码**。

（Lesson 1521 / 1522 见 §6.1：限制由**结果**判定；断言里的正则也会咬人。）
