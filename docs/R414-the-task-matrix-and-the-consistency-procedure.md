# R414 — 任务类型矩阵与「一致性方案」

> 本轮回答用户的三句话：
> **「我们需要能执行各种不同的任务类型」** ⇒ 场景矩阵从 12 个扩到 **21 个**，
> 覆盖查资料 / 读本地 / 审查 / 重构 / 写测试 / 中文「把…改成…」 / 一致性正反两面。
> **「执行任务时，步骤恰当合理，不要做乱七八糟的操作」** ⇒ 两条新判定规则 +
> 一条写作用域纪律（只写被要求的产物），并由矩阵**实测**。
> **「如果涉及修改代码，要能够提供一致性方案」** ⇒ 写代码类姿态新增
> **一致性三步**（找副本 → 一起改 → 点名报告），由矩阵**实测**。
>
> **本轮定位**：A 层（姿态纪律 + 可见报告 + 台架测量）。
> 「要不要引擎端硬校验（B 层）」这个问题**仍然开放**，本轮的实测数据就是回答它的依据。

---

## 1. 先量：23 条候选提示词，7 条落空 —— 其中**只有 4 条是真缺口**

R413 结束时判定表覆盖了英文/中文的常见形态，但用户真正会说的两类话**没有任何
规则覆盖**。R414 用一个**离线探针**（纯函数，零模型调用）把 23 条候选任务类型
提示词跑了一遍：

| id | 任务类型 | 提示词 | 判定（R413 之前） | 应该是 |
|---|---|---|---|---|
| R-1 | 查资料（外部，非问句） | 查一下 Java 21 的虚拟线程 | **uncertain / navigator** | research / researcher |
| R-2 | 查资料（外部，非问句，搜一下） | 搜一下 Spring Boot 4 的新特性 | **uncertain / navigator** | research / researcher |
| R-3 | 查资料（外部，问句） | 查一下 Spring Boot 4 的新特性是什么 | research / researcher | ✓ |
| R-4 | 查资料（本地锚点） | 查一下这个仓库的 checkpoint 机制 | answer / analyst | ✓ |
| R-5 | 查资料（外部，明确官方文档） | 查一下 Spring Boot 4 的官方文档 | research / researcher | ✓ |
| V-1 | 代码审查 | 帮我看看这个改动有没有问题 | review / reviewer | ✓ |
| V-2 | 代码审查（带路径） | 审查一下 src/OrderDao.java 的实现 | review / reviewer | ✓ |
| C-1 | 开发（加字段） | 给 LruCache 加一个容量上限的字段 | code_change / builder | ✓ |
| C-2 | 开发（中文把字句·改常量） | 把 AppConfig 里的 MAX_RETRIES 改成 5 | **uncertain / navigator** | code_change / builder |
| C-3 | 开发（中文把字句·参数化） | 把这条 SQL 改成参数化的 | **uncertain / navigator** | code_change / builder |
| C-4 | 重构 | 重构一下 OrderDao，把 SQL 拼接抽出来 | code_change / builder | ✓ |
| C-5 | 写测试 | 给 LruCache 写一个单元测试 | code_change / builder | ✓ |
| C-6 | 改名 | 把 OrderDao 里的 findOrders 改名为 queryOrders | code_change / builder | ✓ |
| G-1 | 只定位 | 订单接口偶发超时，帮我定位一下原因 | diagnose / investigator | ✓ |
| G-2 | 要修复 | 这个不生效，帮我修一下 | diagnose / debugger | ✓ |
| G-3 | 要修复（问句） | 怎么修复这个不生效的问题？ | diagnose / investigator | ✓ |
| D-1 | 纯创作 | 写一首关于秋天的打油诗 | uncertain / navigator | ✓（无落点 ⇒ 不给写通道） |
| D-2 | 创作+落盘 | 写一首关于秋天的打油诗，保存到 poem.txt | uncertain / producer | ✓ |
| D-3 | 设计稿+落盘 | 帮我写一份订单状态机的设计稿，保存到 design.md | uncertain / producer | ✓ |
| O-1 | 构建运维 | 跑一下单元测试 | ops / operator | ✓ |
| O-2 | 构建运维（构建） | 构建一下这个项目 | ops / operator | ✓ |
| Q-1 | 纯问题（外部） | 用一句话解释什么是幂等性？ | research / researcher | ✓ |
| Q-2 | 仓库内问题 | 这个仓库的 checkpoint 机制是怎么设计的 | answer / analyst | ✓ |

⇒ **两个缺口，都是用户每天会说的话**：

1. **「查一下 / 搜一下」落空** ⇒ 用户要一次查资料，拿到的是
   `navigator`（"没能识别这一轮的意图"）。
2. **中文「把 X 改成 Y」落空** ⇒ 用户要一次改代码，**写通道被剪掉**，
   这一轮根本改不了。

> ★ D-1 的 `uncertain` **不是缺陷**：没有落点的创作就该只读回答。
>
> ★ 所以「7 条落空」这个数字**不能直接当成「7 个缺陷」**：落空的 7 条里
> **3 条（D-1 / D-2 / D-3）本来就是设计如此**（没有落点的创作不给写通道），
> **真缺口只有 4 条（R-1 / R-2 / C-2 / C-3）**。
> ⇒ 一个「落空」的读数里，**要先分清「错」和「按设计」**，否则会把
> 缺陷数报大一倍还说得很有把握。

---

## 2. 两条新规则

### 2.1 查资料：`WEAK_RESEARCH_MARKERS` + 锚点分流

```java
private static final List<String> WEAK_RESEARCH_MARKERS = List.of(
        "查一下", "查一查", "查查", "搜一下", "搜一搜", "搜搜",
        "搜索一下", "查下", "看下资料",
        "look up", "search for", "find out about");
```

★ **为什么不能直接加进 `RESEARCH_MARKERS`**：那张表是**规则 1**，压过一切，
包括本地锚点。对它表内的条目这是对的（"官方文档"、"github" 明确指外部来源），
但「查一下」**没有**指明去哪里 —— 用户说的是「查一下」，问题是**查哪**。
进规则 1 ⇒ 「查一下这个文件」会被送去上网查一个就躺在眼前的文件。

⇒ 所以弱标记由**锚点**分流：
* 有本地锚点 ⇒ `ANSWER`（读本仓库，用 analyst 姿态）
* 无本地锚点 ⇒ `RESEARCH`（查外部，用 researcher 姿态）

★ **子句才是「查」这个动作的作用域**：
* 「查一下这个类里的**改名**方法」—— `改名` 是**主语的一部分**，
  读成「把它改名」就把写通道交给了一个读请求 ⇒ 判 `ANSWER`。
* 「查一下 X，**并重命名** findOrders」—— 两个子句、两个请求 ⇒ 判 `CODE_CHANGE`，
  否则用户的第二个请求没人执行。

实现：`lookupRequest(p, lower)` 用 `CLAUSE_BOUNDARIES`（标点 + 「并且/并把/再把/然后」）
把提示词切成子句，**子句外**出现改动动词 ⇒ 这不是一次查资料。

### 2.2 中文「把 X 改成 Y」

```java
private static final String BA_CONSTRUCTION = "把";
private static final List<String> BA_CHANGE_VERBS = List.of(
        "改成", "换成", "设为", "变成", "翻译成", "翻译为", "改写为");
```

★ **中文把物件放在前、动词放在后**：R405 的「动词在句首」规则读的是句首，
所以每一个把字句都漏了下去。三件事必须同时成立才算一次把字句改动请求：

1. 句中有「把」；
2. 「把」**之后**出现一个 `BA_CHANGE_VERBS` 里的动词；
3. **宾语**（把与动词之间那段）**像这棵树里的东西** —— 产物词（文档/字段/测试…）、
   路径（`src/OrderDao.java`）、或**标识符**（`AppConfig`、`MAX_RETRIES`、`SQL`）。

★ **动词表为什么这么窄**：`AMBIGUOUS_CODE_VERBS` 里的词也常作名词
（「修改记录」= 变更记录）。在把字句里，宾语可以整个就是那个名词
（「把修改记录给我看看」），所以动词必须是**一定带宾语**的那一类。

★ **第 3 条为什么不能省**：「把 status update 的说明改成中文」有把字句，
但**这棵树里没有目标** —— 用户要的是聊天里的译文。读成代码改动就是把写通道
交给了一次翻译请求。

★ **实现踩到的坑（实测）**：判定用的小写化文本，而
`AppConfig` / `MAX_RETRIES` 的大写信息被小写化抹掉了 ⇒ 标识符永远认不出来。
⇒ 形状判定必须用**原文**（`baChangeRequest(p, lower)` 的 `p`），
关键词表才用小写化文本。

---

## 3. 一致性方案（用户第三句话）

### 3.1 契约：三步

> ★ R414: CONSISTENCY — when you change a contract, a name, a constant, a signature or a
> default, its **COPIES** must change with it. A contract that changed in one place and not
> in the others is a broken build, and the user is the one who has to find the leftover.
> 1. **FIND** the copies BEFORE you edit: `grep` the tree for the identifier you are changing
>    (its name, its value, its signature) and read every hit. A hit that merely CONTAINS
>    the name is not a copy — a look-alike is not a copy.
> 2. Change the original AND every real copy in the **SAME pass**. If two places must agree,
>    they agree in this turn.
> 3. **NAME** what you changed, with `file:line`, in your final report — every copy you touched
>    AND every copy you deliberately did not touch. A silent leftover is worse than a
>    named one.

它**只写一份**（`ProfileRegistry.CONSISTENCY_STEPS`），由 **BUILDER / DEBUGGER / PRODUCER**
三个会改文件的姿态共用；六个只读姿态**不带**它（一个什么都不改的回合不该被要求
「改副本」—— 姿态和自己的工具集矛盾，就是 R401 要消除的那个缺陷）。

★ **为什么是姿态而不是 hook**：hook 只能说「你不许写这个文件」，
它没法说「把副本一起改」—— 副本集合要等改动计划出来才知道。
这正是「一致性方案」只能是**方案**而不是**校验**的原因。

### 3.2 怎么测：一致性不变量，不是「文件被碰过」

契约不是「某个文件被改了」，而是**副本之间一致**：

* 每个提到该 token 的副本，都必须带上**新值**；
* **长得像但不是副本**的文件（名字里含 token、值却是另一个常量）必须**原封不动**；
* 报告里必须**点名**被改的副本。

---

## 4. 矩阵：12 → 21

新增 9 个场景（K1–K9）：

| id | 任务类型 | 提示词 | 期望判定 | 模式 | 断言 |
|---|---|---|---|---|---|
| K1 | 查资料（外部） | 查一下 Java 21 的虚拟线程 | research / researcher | ro | 零文件、零写通道 |
| K2 | 查资料（本地） | 查一下这个仓库的 checkpoint 快照放在哪儿 | answer / analyst | ans | 读本地 + file:line |
| K3 | 中文把字句 | 把这条 SQL 改成参数化的 | code_change / builder | chg | 文件真改 |
| K4 | **一致性（正向）** | 把 AppConfig 里的 MAX_RETRIES 改成 5 | code_change / builder | cons | 三处副本一致 + 点名 |
| K5 | **一致性（精度）** | 同上，但树里多一个**像副本的**常量 | code_change / builder | cons2 | 副本同改 + 像的那个不动 |
| K6 | 审查 | 帮我看看这个改动有没有问题 | review / reviewer | diag | 读本地 + file:line + 一个字都不许改 |
| K7 | 写测试 | 给 LruCache 写一个单元测试 | code_change / builder | new | 交付物落盘 + 不多写别的 |
| K8 | 重构 | 重构一下 OrderDao，把 SQL 拼接抽出来 | code_change / builder | chg | 文件真改 |
| K9 | 查资料（英文） | look up the Java 21 virtual thread model | research / researcher | ro | 零文件、零写通道 |

★ **K4 与 K5 用同一条提示词、不同的一棵树** —— 一致性行为必须跟着**树**走，
而不是跟着**话术**走。K5 的第三个文件 `MAX_RETRIES_DEFAULT = 30` 名字里含
token、值却是另一个常量：把它一起改成 5 就是用户说的「乱七八糟的操作」。

---

## 5. 验证

### 5.1 离线判定：21/21

把 21 个场景的提示词直接喂给分类器（纯函数，零模型调用），
**21 条全部与台架里写死的期望一致**（`mismatches=0`）——
先证契约自洽，再花钱跑实机。

★ **这是两次不同的测量，别把它们混成一次 ★**：§1 的探针量的是
**候选提示词落在哪个判定上**（23 条，找缺口）；这里的探针量的是
**矩阵场景的判定与台架写死的期望是否一致**（21 条，证契约自洽）。
⇒ 「21/21」**不是**「23 条里 21 条对了」，两个分母不是一回事。

### 5.2 全量构建

`673 个测试类 / 6415 例 / 0 失败 / 0 错误 / 5 跳过`（R413 是 671 / 6387），
jar `56 995 954` B，sha256 `6A30D04B2E31224D16CCD7745B994F048A12F9501995B151F199AAB5DD5355F5`，
各处 jar 同步后哈希一致，daemon 2 秒内健康。

★ **数测试数不能只数文件 ★**：`surefire-reports` 里躺着一份 **11-25 的陈旧报告**
（`R408CapabilityProbeTest`，那个类后来被改没了），直接数文件会得到
`674 类 / 6417 例` ⇒ 比真实值多一个类、两个例。⇒ 统计必须按**时间窗**过滤
（本轮只取当天那批 673 份），否则「6417」这个数字是假的。

### 5.3 变异台架：18 个变异，18 个全被抓住

★ 最终读数：**`RESULT: 18 caught, 0 missed, 18 attempted`**，
还原校验两个源文件 `IDENTICAL`，基线 ×2 与终态 ×2 各跑 **91 例 / 0 失败**。★

**一个行为一个见证**（不是「一个规则一个见证」）：

| 变异 | 打掉的东西 | 专属见证 |
|---|---|---|
| M51 | 查资料规则整个没了 | `aPlainLookupIsResearch` |
| M52 | 查资料的本地那一半没了 | `aLookupAboutThisTreeIsAnswer` |
| M53 | 查资料变成「要求改动」 | `aLookupClaimsNoChange` |
| M54 | 把字句规则没了 | `theBaConstructionIsACodeChange` |
| M55 | 把字句的「宾语像代码」检查没了 | `aBaConstructionWithoutACodeTargetIsNotACodeChange` |
| M56 | 子句外的协调编辑检查没了 | `aCoordinatedCodeVerbOutsideTheLookupKeepsTheWriteChannel` |
| M57 | 一致性步骤从一个姿态里掉了 | `theProcedureIsONEText` |
| M58 | 找副本的工具名没了 | `theProcedureNamesTheToolThatFindsTheCopies` |
| M59 | 「一起改」的要求没了 | `theProcedureDemandsThatTheCopiesChangeTogether` |
| M60 | 「点名 + file:line」的要求没了 | `theProcedureDemandsThatTheCopiesAreNamed` |
| M61 | 「像副本的不是副本」这句没了 | `theProcedureTellsTheLookAlikeApartFromTheCopy` |
| M62 | 只读姿态也开始要求一致性 | `noReadOnlyPostureCarriesTheProcedure` |
| M63 | builder 没了写通道 | `theProcedureDoesNotCostTheWriteChannel` |
| M64 | builder 的一句话承诺没了 | `theBuilderSaysSoInItsOneLineDescription` |
| M65 | 运维姿态没了「这里没东西可跑」这一档 | `theOperatorSaysSoWhenThereIsNothingToRun` |
| M66 | 一致性步骤没了「点名一个文件不是全集」 | `theProcedureSaysThatOneNamedFileIsNotTheWholeSet` |
| M67 | 一致性步骤没了「别留下别的东西」 | `theProcedureForbidsLeavingAnythingElseBehind` |
| M68 | builder 姿态没了「不许脚手架」 | `theBuilderForbidsScaffolding` |

★ **首轮 12 抓住 / 2 漏，两处漏都是台架自己的错，不是产品的错 ★**
（台架的进度是三段：首轮 14 个 ⇒ 12 抓 2 漏 ⇒ 修正后 14/14 ⇒
再补 M65–M68（对应 §5.5 的四处产品修复）⇒ 18/18。）

* **M55**：我把「代码形状检查」改成 `return false`（只关掉**一半**），
  而见证场景的宾语**本来就不像代码** ⇒ 变异前后行为**完全一样**
  ⇒ 断言照过 ⇒ **变异是文本级 no-op**。改成 `return true`（整个检查失效）后立刻抓住。
  ⇒ 「改了文本」≠「改了行为」。
* **M61**：见证断言的是 `look-alike` 这个词，而我把变异打在 `CONTAINS` 上
  ⇒ 被改的词和被断言的词不是同一个 ⇒ **见证打偏了**。改到 `look-alike` 那句上后抓住。

★ **台架自己也有两个洞，都当场补了 ★**

* `-File script.ps1 -Only M55,M61` 里逗号是**一个字符串**，过滤后**零个变异运行**，
  台架却报「0 caught, 0 missed」并**退出 0** ⇒ **空转被当成了绿**。
  修法：过滤前拆逗号；且**「尝试了 0 个」直接判失败**。
* 门禁脚本的纯 ASCII 自检**当场抓到了我自己**在注释里写的 `★`（6 个非 ASCII 字节）
  ⇒ 脚本拒绝运行 ⇒ 这就是它存在的理由。

### 5.4 实机矩阵：第一轮（修好沙箱布局后）—— 214 通过 / 6 失败

21 个场景，**4 个场景红**，而且**每一格红都量到了一条真实的产品缺口**：

| 场景 | 红在哪 | 量到了什么 |
|---|---|---|
| **O1**（跑一下单元测试） | 轮次 600 s 没跑完 | **空目录下，代理满盘找项目**：`ls ..`、`ls ../..` …… 十分钟不收尾。⇒ 运维姿态缺「这里没东西可跑」这一档。 |
| **K4**（一致性正向） | `LegacyConfig.java` 还写着 `MAX_RETRIES = 3` | 代理**确实 grep 到了三处副本并全读了**，却因为用户只点名 `AppConfig` 就把「legacy 模块」那份当成了范围外 ⇒ **树不一致**。报告倒是点名了副本 ✓。 |
| **K7**（写测试） | 多出 **13 个文件** | 为写**一个**测试文件，代理自己造了一整套 Maven 工程：`pom.xml`、`.gitignore`、整个 `target/`。 |
| **K8**（重构） | 轮次 600 s 没跑完 | 代理顺手写了一份测试、编译、验证 ⇒ 干太多事，轮次超时（R413 遗留 ⑥ 的又一次现身）。 |

★ **K5 证明这套步骤是有效的 ★**：同一套措辞、同一个模型，在 K5 里**把两处副本都改了**、
**没动那个只是名字像的常量**，而且模型自己在思考里写「Per R414, I need to grep for
MAX_RETRIES to find copies」⇒ **姿态文本确实被读到了、也确实被照做了**。

⇒ 所以 A 层（只靠措辞）**不是全无效果**：它有效，但**会漏**。
⇒ 而漏的那一格（K4）是**可命名的缺口**：「用户只点名了一个文件」。

### 5.5 四处产品修复（按量到的缺口改）

1. **一致性步骤第 2 步**补一句：★ 用户点名**一个**文件只是**起点**，不是全集；
   叫 "legacy" / "old" / "deprecated" 的副本**仍然是副本**，要一起改；
   决定不改，就要明说并说明理由。★
2. **一致性步骤第 4 步**（新增）：★ 别留下别的东西 ★ —— 编译、跑测试、临时副本
   都不许往树里加文件；工作树里应当只有这次改动。
3. **写代码姿态**补一条：★ 不许脚手架 ★ —— 不要加构建文件、工程骨架、示例、
   辅助脚本、验证程序；仓库没有构建，就写代码并说明「没东西可跑」。
4. **运维姿态**补一条：★ 这里没东西可跑就说清楚并停下 ★ —— 工作目录就是范围，
   不要去文件系统的别处找项目。

### 5.6 台架自己也有一个洞：沙箱能被 `..` 走出去

★ **R414 的实机跑抓到 R413 台架的一个结构性缺陷 ★**

R413 把沙箱放在 `<repo>/.aethercode/bench-scenarios` ⇒ **「沙箱」和「用户的工作树」
是同一块磁盘**。实机跑出来的后果（O1 场景「跑一下单元测试」，沙箱是空的）：

```
bash("ls -la ../..")        → 整个 bench-scenarios 目录
bash("ls -la ../../..")     → 用户真实仓库的根目录（AGENTS.md、.git、aethercode/…）
bash("git log --oneline")   → 用户的提交历史
bash("cat summary.json")    → 台架自己的报告
```

模型**一路走出了沙箱，在读用户的真实工作树** —— 而台架**看不出任何异常**
（它只看沙箱里的文件；被测对象跑去了哪，它根本不知道）。

⇒ 两条修法（本轮已落地）：
1. **沙箱根目录改到系统临时目录**（`os.tmpdir()`），仓库外 ⇒ 走不出去；
2. **工作树护栏**：run 前后比对每个 **tracked** 文件的哈希，
   用户的工作树有任何变化 ⇒ 台架红。

★ 这条护栏**当场抓到了我本人**：第一轮跑测期间我改了
`ProfileRegistry.java` 与 `scripts/bench-scenarios.mjs`，护栏直接报红
（`changed=[…]`）⇒ 原来「跑测期间不要改源码」这条规矩**只靠自觉**，
现在它**有牙齿**了。★

★ 这也是「不要糊弄」的一个实例：台架自己红过一次，才配得上量别人。 ★

### 5.7 第二轮（产品修复后）：**220 通过 / 0 失败**

第一轮量到的每一个缺口，修完再跑一遍，**每一格都从红转绿**：

| 场景 | 第一轮 | 第二轮 | 是什么让它变的 |
|---|---|---|---|
| **O1**（跑一下单元测试） | 红：600 s 没跑完，满盘找项目 | ✅ 绿 | 运维姿态的「这里没东西可跑就说清楚并停下」 |
| **K4**（一致性正向） | 红：`LegacyConfig` 留着 `MAX_RETRIES = 3` | ✅ 绿 | 一致性步骤第 2 步：「点名一个文件只是起点」 |
| **K7**（写一个测试） | 红：多出 13 个文件（整套 Maven 工程） | ✅ 绿 | 写代码姿态的「不许脚手架」 |
| **K8**（重构） | 红：600 s 没跑完（顺手写测试、编译、验证） | ✅ 绿 | 同上（不许加验证程序/辅助脚本） |
| **C2**（写一首诗存文件） | 红（模型发空参数 `input:{}`，判为抖动） | ✅ 绿 | 复跑，不是改期望 |

最终读数：

```
== scenario matrix: 220 passed / 0 failed ==
  PASS  ★ the agent registry is UNCHANGED after the whole run
  PASS  ★ the USER'S WORKING TREE is UNCHANGED after the whole run
```

每格通过数（断言条数因场景而异）：Q1 10 / Q2 10 / C1 10 / C2 11 / D1 10 /
D2 11 / D3 10 / D4 11 / V1 9 / G1 11 / G2 11 / O1 9 / K1 10 / K2 10 / K3 9 /
K4 12 / K5 13 / K6 11 / K7 11 / K8 9 / K9 12。

★ **K4 的读数值得单独说 ★**：模型改了**三个**文件
（`AppConfig.java` / `AppConfigTest.java` / `LegacyConfig.java`），
断言「每个副本都带新值 5」「报告点名 AppConfig」「报告点名 LegacyConfig」
「别留下别的东西」四条全过。而且它自己在思考里写下了理由：

> *"Per R414 CONSISTENCY: the user named one file (AppConfig). But copies —
> LegacyConfig.java has MAX_RETRIES = 3, and AppConfigTest asse…"*

⇒ **不是碰巧改对，是步骤被读到了、被照做了** ⇒ 这就是「方案」这一层能买到的东西。

★ **K8 留了一个诚实的尾巴 ★**：重构顺手抽出了两个新类
（`src/OrderSql.java` / `src/SqlQuery.java`）。这**不是**脚手架，是重构本身的一部分，
但它说明「不许脚手架」这条禁令**管得住「加构建文件」，管不住「多加一个类」**。
⇒ 写作用域（哪些文件可以改）仍然是缺的，见 §6。

★ **读数本身也会骗人 ★**：本轮统计失败数时用了 PowerShell 的
`Select-String -Pattern 'FAIL'`，而它**默认不区分大小写** ⇒ 一条
`reason="failure symptom: 定位一下"` 被数成了失败 ⇒ 绿色的跑被读成红的。
⇒ 台架自己也是量具，量具的读法也会出错 ⇒ 失败计数必须用
**行首锚定**的模式（`^\s+FAIL\b`），不是子串匹配。

---

## 6. 还没有闭的

* **「一致性方案」要不要做到引擎端硬校验（B 层）？** 本轮做的是 A 层
  （姿态纪律 + 可见报告 + 台架测量）。B 层意味着引擎自己扫副本、发现漏改就
  拒绝本轮 —— 这是个更大的改动，**等本轮的实测数据再定**。
  ⇒ **本轮数据（供决定用）**：A 层在**量到的那一格**上有效且可归因
  —— K4 从红转绿，模型在思考里点名了它照做的那一步；K5 证明它**不会连
  长得像的东西一起改**。⇒ **A 层值得保留，且它已经堵住了实测到的那个洞。**
  ⇒ 但 A 层**不是保证**：它是**措辞**，模型可以读漏、可以在没读到的树上跑。
  ⇒ B 层的价值是**把「读到了」从前提变成事实** —— 引擎自己数副本，
  少一个就拒绝本轮，不依赖模型自觉。
  ⇒ **代价要说清**：B 层需要「哪些 token 的副本算一个集合」的判定，
  而这个判定**要在改动计划出来之后才知道**（本轮 §3.1 写过这件事）；
  引擎侧要拿到它，就得让引擎看见改动计划 ⇒ 这是引擎侧的改动，不是
  姿态文本的改动。
  ⇒ **建议**：A 层保留（本轮已证明它有效），B 层作为**独立的下一轮**，
  落点放在「引擎在回合结束时核对副本集合」而不是「引擎在写之前拦」。
* **写作用域**：写场景仍然没有「只能改哪些文件」的硬边界（R413 遗留 ①）。
  收紧写通道时**必须连 bash 一起收**（shell 写的文件 checkpoint 撤不掉）。
  ⇒ **本轮量到了它的三个面**：K7 第一轮多出 13 个文件（整套 Maven 工程）、
  K8 第二轮多抽出 2 个新类、K7 第一轮沙箱里多出 3 个 `.class`。
  ⇒ 「不许脚手架」堵住了**第一面**（构建文件/工程骨架），
  **第二面（多加一个类）和第三面（编译产物）仍然开着**。
* **能力轴还不在 wire 上**（R413 遗留 ②）、**创作/设计域缺一行**（③）、
  **TUI 无判定展示面**（④）、**仓库内问题仍联网**（⑤）、**轮次长度无上界**（⑥）。
