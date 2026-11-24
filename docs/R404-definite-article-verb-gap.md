# R404 — 定冠词也是实现动词

## 1. 目标

修掉 R401 留下的一个**功能回归**：用户用最普通的英文措辞要求写文件，
agent 却**没有写工具可用**。

现象（实机实测）：同一句话，只差一个冠词：

| 提示 | R404 之前 | R404 之后 |
|------|-----------|-----------|
| `Create a file named X` | ✅ 写工具到模型手里，文件落盘 | ✅ |
| `Create the file named X` | ❌ `UNCERTAIN` ⇒ 只读档案 ⇒ `file_write` 被裁掉 | ✅ |

模型在失败那一次的实测表现：连续 6 次 `unknown tool: file_write`，
然后绕道 `echo hello > file` 把文件写了出来。

## 2. 现状（事实，附证据）

### 2.1 分类器的两张动词表

`IntentClassifier` 有两张表，**服务的分支不同**：

| 表 | 何时被读 | 作用 |
|----|---------|------|
| `CODE_VERBS` | **仅第 6 步：非疑问句** | 非疑问句的显式实现动词 |
| `STRICT_CODE_VERBS` | 第 5 步：疑问句 | 疑问句要「动词 + 宾语」复合词才能升格 |

`STRICT_CODE_VERBS` **本来就有** `create the` / `add the` / `write the`；
`CODE_VERBS` **只有不定冠词**形式（`create a` / `create an` / `write a` …）。
⇒ 所以缺口只在**非疑问句**这一条路径上，而且是可以外科式修的：
`CODE_VERBS` 在疑问句分支里**根本不会被读到**（`classifyInternal` 第 5 步先 return）。

### 2.2 为什么这些动词要带冠词

英语里这些动词和名词同形：`the create method` / `the write path` /
`the build tool` / `the add operation`。裸 `create` 会全部命中，
所以它们被写成「动词 + 限定词」（`create a` / `create an`）。

**`the` 和 `a` / `an` 是同一类限定词，消歧能力完全一样**，
而且限定词必须跟在动词后面 —— 所以 `the create method` 里**不含** `create the`。

### 2.3 实测（R404 之前，jshell 跑真分类器）

```
UNCERTAIN   Create the file r404-probe.txt
UNCERTAIN   Add the file probe.txt
UNCERTAIN   Write the file probe.txt
UNCERTAIN   Build the file probe.txt
UNCERTAIN   Generate the file probe.txt
UNCERTAIN   Update the README
UNCERTAIN   Update the pom.xml with the new dependency
--- 对照组 ---
CODE_CHANGE create a file r404-probe.txt
CODE_CHANGE write a file probe.txt
CODE_CHANGE add a test for the parser
```

## 3. 设计决策

1. **只补「限定词形式」，不补裸动词。**
   裸 `update` 会把 `give me a status update on the migration`（名词短语，
   实测过）读成指令 —— 而那正是 R401 要防的失败模式
   （「生成一堆没人要的文件」）。`update the README` 没有这个歧义。
2. **只动 `CODE_VERBS`。**
   疑问句路径不读这张表 ⇒ 改动的爆炸半径**只在非疑问句**，
   而且由一个专门的 pin 钉住（§5.2）。
3. **补的不是「更多词」，是「一个缺失的语法位」。**
   补完之后，动词的两种限定词形式齐了：
   `create a` / `create an` / `create the`。

### 3.1 补了哪几个

`create the` / `add the` / `write the` / `build the` / `generate the` / `update the`。

`implement the` / `refactor the` / `rewrite the` / `delete the` / `remove the`
**不用补** —— 它们的裸动词已经在表里（`implement` / `refactor` / `rewrite` /
`delete` / `remove`），早就命中了。

## 4. 改动

| 文件 | 改了什么 |
|------|---------|
| `aethercode-core/.../intent/IntentClassifier.java` | `CODE_VERBS` 补 6 个限定词形式；注释写清「为什么冠词是动词的一部分」「为什么裸 `update` 不在表里」 |
| `aethercode-core/src/test/.../intent/IntentClassifierR404Test.java` | **新建**：判定表 + 名词碰撞对照组 + 疑问句路径 pin |
| `aethercode-core/src/test/.../engine/QueryEngineIntentR404Test.java` | **新建**：用户可见的后果（写工具到不到模型手里） |

## 5. 测试与变异

### 5.1 两个测试类，11 例

- `IntentClassifierR404Test`（8 例）：修复本身、不定冠词对照组、
  **名词碰撞对照组**（6 条名词短语必须仍是 `UNCERTAIN`）、
  疑问句路径 pin、R401 的不变量仍在。
- `QueryEngineIntentR404Test`（3 例）：**用户可见的后果** ——
  修复后 `file_write` 出现在**模型看到的工具表**里，而且执行器真的跑了它；
  反方向：名词短语仍然是只读，写工具既不出现也跑不了。

### 5.2 三个结构性的 pin

- `theNounCollisionsStayUncertain` —— **防补过头**。把裸 `update` 加进去，
  这条就红。
- `theQuestionPathDoesNotReadTheNonQuestionVerbList` ——
  `should I update the file?` 的唯一实现信号是 `update the`（只存在于
  `CODE_VERBS`），所以这条问题**必须不是** `CODE_CHANGE`。
  哪天疑问句分支开始读 `CODE_VERBS`，这条就红。
- `theDeterminerIsTheOnlyVariable` —— `create a` 与 `create the` 必须落在
  同一档，而 `the create method` 必须落在另一档。

### 5.3 变异验证（副本上，6 个突变）

每个突变打在**不同的**一条断言上，否则一条过宽的断言能让所有突变都看起来
「被抓住」。

| 突变 | 期望被哪条抓住 | 结果 |
|------|---------------|------|
| M01 删掉 `create the` | `theDefiniteArticlePromotesAnImplementationRequest` | ✅ |
| M02 加裸 `update`（补过头） | `theNounCollisionsStayUncertain` | ✅ |
| M03 疑问句分支也读 `CODE_VERBS` | `theQuestionPathDoesNotReadTheNonQuestionVerbList` | ✅ |
| M04 reason 里丢掉命中的动词 | `theVerdictNamesTheVerbThatProducedIt` | ✅ |
| M05 `UNCERTAIN` 映射到 `BUILDER` | `aNounPhraseIsStillReadOnly` | ✅ |
| M06 删掉 `create a` | `theIndefiniteFormsStillPromote` | ✅ |

基线 ×3 全绿、终检 ×3 全绿、还原后两个被改文件与原始树**逐字节一致**。

## 6. 实机验证

脚本 `D:\work\tmp\r404-live.mjs`（一次性 cwd + 一次性 sessions dir）。
**决定性实验**：三臂，分类器是唯一变量，档案**不钉**（脚本从不调 `setProfile`），
`bash` 被显式 deny（R401 的教训：不禁 shell，模型会绕道）。

**22/22 全绿**，日志 `D:\work\tmp\r404-live.log`。

| 臂 | 提示 | daemon 判定 | 档案 | 结果 |
|----|------|------------|------|------|
| A（对照） | `Create a file named "r404-a.txt" …` | `code_change` | `builder` | 写入成功，走 `file_write` |
| B（修复） | `Create the file named "r404-b.txt" …` | `code_change` | `builder` | **写入成功，走 `file_write`，无 shell 调用** |
| C（反方向） | `What is the relationship between a checkpoint and a hook …` | `research` | `researcher` | 没有写工具被调用 |

三条结构性断言：

- **三臂落在三种不同的姿态上**（`builder` / `builder` / `researcher`）⇒
  **没有任何东西被钉住** ⇒ B 臂的 `builder` 是**分类器的裁决**，不是脚本的常量。
- **B 臂的工具调用只有 `file_write`** ⇒ shell 没有被绕过
  （shell 已被显式 deny，所以绕道也写不出去）。
- **cwd 里恰好两个文件**（`r404-a.txt` / `r404-b.txt`）⇒ 内容级泄漏检查通过：
  模型没有顺手多写任何东西。

### 6.1 打包

全 reactor `mvn -o package`（含全量测试）：**BUILD SUCCESS，
650 测试类 / 6258 例 / 0 失败 / 0 错误 / 4 skipped**，11:57 min。
（比 R403 的 648 类 / 6247 例多出 **2 类 11 例** = 本轮的两个新测试类。）

jar 同步三处，SHA256 `624DC2DCF57AE83C6DBA8506E4F4357472D1928450137136C32D6F271C0CD8DD`，
桌面端重启后 daemon healthz 200。

## 7. 已知限制

1. **动词表的长尾没解决。** `Update pom.xml`（无冠词）、`Modify X`、
   `Change X`、`Edit X`、`Introduce X` 仍然判 `UNCERTAIN`。
   原因：英语里几乎每个动词都有名词义（`the change` / `the edit` / `the port`），
   词表法补不完。**补法**：与「动词 + 路径样 token」组合判定
   （路径本身就是消歧信号）—— 那是改判定表结构，**本轮明确不做**。
2. **`explain what create the file means` 判成 `CODE_CHANGE`** ——
   这是 R401 就有的假阳性（`means` 不在 `CONCEPT_BLOCKERS` 里），
   本轮没碰。
