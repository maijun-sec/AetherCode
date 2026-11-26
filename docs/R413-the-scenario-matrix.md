# R413 — the scenario matrix, and the one defect it found

> **The ask**: 「我之前提过，各种场景都进行测试，包括单纯的问题，设计，开发，
> 问题定位，写设计稿等，之前已有，确认是否都可以正确执行」
>
> **The answer**: 12 个场景在**真 daemon**上跑完，**判定 12/12 全对**；
> 但矩阵抓到一个**判定与工具池自相矛盾**的真缺陷（只定位的问题定位场景，
> 往用户工作树里写了 4 个没人要的文件），本轮修掉了。另有 3 处**未闭项**
> 记在 §8。

---

## 1. 场景清单的权威来源（不自造）

用户说的「之前已有」不是凭记忆，是三份已经在仓库里的东西：

| 来源 | 内容 |
|---|---|
| `docs/R408-capability-measurement.md` §4 | 实机 8 场景（S1 概念问题 / S2 仓库内问题 / S3 纯创作 / S4 创作+落盘 / S6 接口超时 / S7 慢 SQL / S8 写特性） |
| `docs/R411-the-capability-axis.md` | 能力轴（要不要动工作树）与「域轴」分开；`PRODUCER` 档案 |
| `D:\work\tmp\r408-live2.mjs` | S1–S8 的**可执行**定义（prompt / 夹具 / 期望） |

⇒ 本轮把这份清单**升级成 12 场景的矩阵**：既有 6 个原场景 + 用户点名的
5 类场景（单纯的问题 / 设计 / 开发 / 问题定位 / 写设计稿）+ 镜像对照，
并**把它固化进仓库**（`scripts/bench-scenarios.mjs`）——矩阵不该只活在
某一轮的临时目录里。

---

## 2. 矩阵

期望判定是**设计契约**（单元测试钉着的那版），不是快照。

| id | 场景 | prompt | 期望判定 | 期望产物 | 护栏 |
|---|---|---|---|---|---|
| Q1 | 单纯的问题（外部） | 用一句话解释什么是幂等性？ | research / researcher | 文字答案 | 不写盘、不用写通道 |
| Q2 | 仓库内的问题 | 这个仓库的 checkpoint 机制是怎么设计的 | answer / analyst | 引用 file:line | 不写盘 |
| C1 | 纯创作（无落点） | 写一首关于秋天的打油诗 | uncertain / navigator | 文字答案 | 零工具、零文件 |
| C2 | 创作 + 落盘 | 写一首关于秋天的打油诗，保存到 poem.txt | uncertain / producer | **poem.txt 落盘** | 不多写别的文件 |
| D1 | 写设计稿（无落点） | 帮我写一份订单状态机的设计稿 | uncertain / navigator | 文字设计稿 | 零文件 |
| D2 | 写设计稿 + 落盘 | 帮我写一份订单状态机的设计稿，保存到 design.md | uncertain / producer | **design.md 落盘** | 不多写别的文件 |
| D3 | 设计（无落点） | 帮我设计一个订单状态机 | uncertain / navigator | 文字设计 | 零文件 |
| D4 | 设计 + 落盘 | 帮我设计一个订单状态机，保存到 design.md | uncertain / producer | **design.md 落盘** | 不多写别的文件 |
| V1 | 开发 | 给 LruCache 加一个容量上限的字段 | code_change / builder | **LruCache.java 真改** | — |
| G1 | 问题定位（只诊断） | 订单接口偶发超时，帮我定位一下原因 | diagnose / debugger | file:line 证据 | ★ **一个字都不许改** |
| G2 | 问题定位 + 能改 | 这条 SQL 查询很慢，帮我看看怎么优化 | answer / producer | **SQL 真改** | 不上网 |
| O1 | 构建运维 | 跑一下单元测试 | ops / operator | 真命令 + 真输出 | — |

**判定怎么读**：`intent` 是**领域轴**（这是哪一类工作），`profile` 是**姿态**
（这一轮能拿什么工具）。R411 起两者是**两条轴**：`uncertain / producer`
不是矛盾——领域没认出「创作」，但能力轴认出「要落盘」，于是给可写姿态。

---

## 3. 台架（可复跑）

```powershell
# 全量 12 场景（每个场景独立 daemon + 真 HTTP RPC + 真 WS 事件流）
node scripts/bench-scenarios.mjs

# 只跑几个
$env:SCENARIOS_ONLY='G1,G2'; node scripts/bench-scenarios.mjs

# 换一份 jar 对照
$env:SCENARIOS_JAR='D:/work/tmp/old.jar'; node scripts/bench-scenarios.mjs
```

每场景断言三件事：

1. **判定**：`run_start` 事件与轮询 `getState` 都带判定，且**等于设计值**，
   两处**必须一致**（R412 的一套词汇两个载体）。
2. **产物**：文件落盘 / 代码真改 / 读了本地文件 / 引用了 file:line /
   没上网。
3. **护栏**：只读姿态**一个文件都不许多出来**，且**用户 agent 注册表全程
   逐字节不变**（R408 踩过的坑：只读轮次改写了用户的 `end-user` agent）。

台架本身的三条纪律：

- **每个场景一个 daemon**（R408 的教训）：daemon 的 boot cwd 与会话 cwd
  不一致时，模型会察觉矛盾，然后满树盲扫（实测 124 次工具调用）——
  那样量到的是「矛盾」，不是「场景」。
- **`--sessions-dir` 在沙箱外**：放在沙箱里，模型 `glob` 会看到一堆会话
  转录，然后得出「这个仓库是空壳」——**量具污染被测对象**。
- **期望写死在台架里**：期望是设计契约，不是跑出来的读数；否则台架只是
  把分类器对着自己照镜子。

---

## 4. 实机结果（修复前，R412 的 jar）

**115 passed / 5 failed**。5 个失败**全是同一项**：轮次在 300s 内没跑完
（D2 / D4 / G1 / G2 / O1）——**判定与产物全部正确**。

| id | 判定 | 工具 | 产物 | 多余文件 | 结论 |
|---|---|---|---|---|---|
| Q1 | research/researcher ✔ | （无） | 文字 297 | 0 | ✔ |
| Q2 | answer/analyst ✔ | grep/glob/file_read/web_fetch | 文字 24 707 | 0 | ✔（但**上网了**，见 §8.5） |
| C1 | uncertain/navigator ✔ | （无） | 文字 2 619 | 0 | ✔ |
| C2 | uncertain/producer ✔ | bash/file_write/file_read | **poem.txt** | 0 | ✔ |
| D1 | uncertain/navigator ✔ | grep/glob | 文字 34 188 | 0 | ✔ |
| D2 | uncertain/producer ✔ | glob/bash/file_read/todo_write/file_write/grep/file_edit | **design.md** | ★ **+3**（`_check.py`/`_check.out`/`verify_doc.py`） | 产物对，**树被弄脏**；且 300s 没跑完（91 824 字符） |
| D3 | uncertain/navigator ✔ | glob/file_read/grep/todo_write | 文字 21 830 | 0 | ✔ |
| D4 | uncertain/producer ✔ | bash/glob/grep/todo_write/file_write/file_read/file_edit | **design.md** | 0 | 产物对；300s 没跑完（55 206 字符） |
| V1 | code_change/builder ✔ | grep/bash/file_read/glob/todo_write/file_write | **LruCache.java 真改** | 0 | ✔ |
| G1 | diagnose/debugger ✔ | bash/glob/file_read/todo_write/file_write/file_edit | file:line 证据 | ★ **+4**（`repro/Repro.java`、`repro/Repro2.java`、`out/Repro.class`、`out/Repro2.class`） | ★★ **本轮的核心缺陷** |
| G2 | answer/producer ✔ | glob/bash/file_read/todo_write/file_write/file_edit | **SQL 真改** | ★ **+13**（sqlite、python 脚本、`_compile_out/**.class`、`_harness_tmp/**`…） | 产物对，**树被严重弄脏**；300s 没跑完（79 398 字符） |
| O1 | ops/operator ✔ | bash/glob/file_read | 文字 10 233 | 0 | ✔ 跑了真命令；300s 没跑完 |

**agent 注册表**：15 个文件，跑前跑后**逐字节未变** ✔（R408 的事故没有回来）。

---

## 4.5 修复后的复测（新 jar，真 daemon）

用**仓库里的台架**（`scripts/bench-scenarios.mjs`，已修：sessions 目录在沙箱外、
超时 600s、多余文件成为硬断言、注册表护栏自带）复跑关键场景：

| id | 判定 | 工具 | 产物 | 多余文件 | 结论 |
|---|---|---|---|---|---|
| C2 | uncertain/producer ✔ | bash/file_write/file_read | **poem.txt** | 0 | ✔ 无回归 |
| D2 | uncertain/producer ✔ | glob/bash/file_write/grep/file_read | **design.md** | **0**（此前 +3） | ★ 干净了；文本 91 824 → **8 397** 字符 |
| V1 | code_change/builder ✔ | grep/bash/file_read/glob/todo_write/file_write | **LruCache.java 真改** | 0 | ✔ 无回归 |
| G1 | diagnose/**investigator** ✔ | todo_write/grep/glob/file_read/web_search/web_fetch | file:line 证据 | **0**（此前 +4） | ★★ **修好了**：工具池里没有 bash、没有 file_write，工作树**零新增零修改** |
| G2 | answer/producer ✔ | glob/bash/file_read/todo_write/file_write | **schema.sql + OrderDao.java 真改** | **0**（此前 +13） | ★ 干净了，且文件真改了；文本 54 933 字符 |

复测共 **23 passed / 0 failed**（G1 + G2 两个场景），**agent 注册表 15 个文件
逐字节未变** ✔。

★ **G2 第一次复跑是红的**（轮次 600s 没跑完，24 万字符还在跟 Windows 的
`find` 语法较劲，文件没来得及改）；第二次跑就绿了。⇒ **同一场景两次结果
不同，是模型的方差，不是契约的方差** —— 但它同时说明 §8.6 那件事是真的：
**写场景的轮次长度没有上界。**

★ **复测里台架自己红了一次**：G1 的期望仍写着旧档案名 `debugger`。
⇒ **契约改了，台架必须跟着改**（台架就是契约的副本，改契约就要改它）——
这是台架该有的行为，不是台架的缺陷。

---

## 5. ★ 矩阵抓到的缺陷：判定说只读，工具池却能写

G1 的 prompt 是「订单接口偶发超时，**帮我定位一下原因**」——用户只要**原因**。

- 判定里的能力轴：**`requestsWorkspaceChange = false`**（这一轮没有要求改工作树）；
- 姿态：`debugger` —— R401 故意**留着写工具**的（「这个不生效」多半是要修）；
- 于是模型有 `file_write` / `file_edit` / `bash` ⇒ 它写了
  `repro/Repro.java` + `out/Repro.class` **四个没人要的文件**。

⇒ **同一个判定里，两句话互相矛盾**：判定说「这一轮不要求改」，工具池说
「你可以改」。而**代价落在用户的工作树上**。

★ **根因不是「模型不听话」，是产品自相矛盾**：R411 把能力轴接到了**升级**
那一半（只读域 + 要改 ⇒ 升 `producer`），却**没接到收紧**那一半
（可写域 + 不要改 ⇒ 收掉写通道）。R401 的原话是「只读姿态必须是结构性的，
不是提示词」——「没让改就别改」当时只是 debugger 姿态里**一句提示词**。

---

## 6. 修法

### 6.1 能力轴**双向**生效（core）

```java
if (intent.requestsWorkspaceChange()) {
    return grantsWrite(base) ? base : PRODUCER;   // 要改 ⇒ 给写通道
}
return readOnlyTwin(base);                        // 不要改 ⇒ 收掉写通道
```

`readOnlyTwin(DEBUGGER)` ⇒ 新档案 **`investigator`（定位问题（只读））**：
自己的名字、自己的标签、自己的姿态，工具池按 `READ_ONLY_DENY` 收掉
写工具 + shell + agent 注册表写入。

★ **为什么是「双胞胎档案」而不是「同名字不同工具池」**：同名字的话，用户在
UI 上看到的还是「定位问题」，**看不出这一轮不能写**；而且两个同名不同池的
档案就是「同一份真相的两份拷贝」，会漂（R378/R380 的老教训）。

### 6.2 「诊断但要修」的能力规则（core）

收紧之前必须先补上另一半，否则「这个不生效，**帮我修一下**」会因为
`不生效` 是失败症状（⇒ DIAGNOSE）而丢掉写通道 —— **请求被分类器自己
判成了它做不到的事**。

新增 `DIAGNOSE_ACTION_VERBS`：`修复 / 修一下 / 修好 / 改好 / 解决 / 搞定 /
复现 / 重现 / 跑一下 / 试一下 / 验证一下 / fix / repair / patch`……
**规则仍受问句约束**（「这个不生效怎么修」是问句 ⇒ 不给写通道，R401 的
不变量不动）。

### 6.3 producer 姿态：产物是**唯一**的文件

姿态原来就写着「do not add files」，但那是埋在第 4 条的一句话。现在它
写在**第一条**，并且点名禁止那套习惯动作（验证脚本、临时文件、旁边的
README）：

> **Write ONLY the deliverable the user named.** … To check your own work,
> READ what you wrote — do not write a program to check a program.

★ 这是**提示词级**的修法，不是结构性的 —— 结构性的修法是「写作用域」
（§8.1）。本轮选提示词级是因为它便宜且不改变能力轴的语义；**结构性的
那条留给下一轮**。

---

## 7. 见证（每条断言一个专属变异证人）

8 个变异，全部打在**副本**上（`D:\work\tmp\r413-mutant`），还原后
**逐字节证明**：

| 变异 | 专属证人 | 结果 |
|---|---|---|
| 收紧那一半永不触发 | `aDiagnosisThatOnlyLocatesGetsNoWriteChannel` | 抓住 |
| 双胞胎换回可写姿态 | `aDiagnosisThatOnlyLocatesGetsNoWriteChannel` | 抓住 |
| 「诊断要修」规则永不触发 | `aDiagnosisThatAsksForTheFixClaimsAChange` | 抓住 |
| 「诊断要修」规则不限定诊断 | `theActionRuleIsScopedToDiagnoses` | 抓住 |
| 问句也能买到写通道 | `aQuestionAboutTheFixDoesNotClaimAChange` | ★ **第一版没抓住**（见证无效，见下） |
| producer 丢掉「只有产物」那条 | `theProducerPostureNamesOnlyTheDeliverable` | 抓住 |
| producer 丢掉「别写程序验程序」那条 | `theProducerPostureNamesOnlyTheDeliverable` | 抓住 |
| 只读诊断留着 shell | `aDiagnosisThatOnlyLocatesGetsNoWriteChannel` | 抓住 |

★ **台架抓到的是我自己的见证无效**：M45（去掉问句约束）第一版**没被抓住**，
因为那个场景（「这个不生效怎么修」）里**根本没有动作动词**——`修` 单独不在
表里 ⇒ 被变异的代码路径压根没被执行到 ⇒ 断言恒真。
⇒ 换成「怎么修复这个不生效的问题？」（**同时**带失败症状与动作动词）后，
两种实现第一次给出不同答案 ⇒ 抓住。
⇒ **Lesson 1581 — 见证只有在「两种实现给出不同答案」时才是见证；
断言恒真不是「护栏够强」，是「场景问不到那条代码」。**
（R412 的 Lesson 1568 在同一件事上又教了一遍。）

---

## 8. 还没闭的（带证据，不带猜测）

1. **★ 写场景会往工作树里扔垃圾（D2 +3 / G1 +4 / G2 +13）** ——
   三个写场景**三个都扔**，所以这不是模型方差，是**写通道没有范围限制**：
   姿态只有一句「别加没人要的文件」，而写工具 + shell 是**开着的**。
   结构性的修法是**写作用域**：从 prompt 里抽出目的地，只允许写那一个路径
   （且要连 bash 一起收，否则 `echo > f` 绕过）。
   ⇒ **R414 的头号条目。**
2. **能力轴仍然不在 wire 上**（R411 的 still-open）。桌面端因此看不出
   「这一轮会不会动工作树」——只能靠档案名猜。
3. **领域轴没有「创作 / 设计」这一行**（R408 §3.1(2) 早就记下，R411 只补
   了能力轴那一半）。于是「写设计稿」在桌面端显示**意图：未识别 ·
   判定依据：no intent signal** —— 执行对了，**显示是错的**。
4. **TUI 侧根本没有判定展示面**（`aethercode-tui/src` 里搜不到
   `intentLabel` / `profile` / `setProfile`）。「为什么这么判」只有桌面端
   能回答。
5. **Q2 上了网**（`web_fetch`）——仓库内的问题，模型还是去网上找了。
   `analyst` 姿态没有「先读本地」的指令（那是 `researcher` 的）。
6. **★ 写场景的轮次可能无限长 ★**（R413 实测）。修复前的 12 场景里
   **5 个**轮次在 300s 内没跑完（D2/D4/G1/G2/O1，文本 55k–92k 字符）；
   修复后的 G2 更是跑到 **242 035 字符**仍未结束——模型在一个只有两三个
   文件的沙箱里反复试 BusyBox 的 `find` 语法（`--` 被当成选项）。
   ⇒ R411 早就记过「升级后的姿态很吵」，本轮量到了具体数字。
   ⇒ **轮次没有硬上限**，而写场景带着 shell，于是「一句帮我优化 SQL」
   可能变成一场跟 shell 的拉锯战。⇒ R414 要给轮次一个**可预期的停止
   条件**（不是静默截断，而是「已改的部分 + 还剩什么」）。
7. **★ 台架的期望是契约的副本，契约改了它就得改 ★**（R413）。复测时
   G1 的期望还写着旧档案名 `debugger`，台架当场红 ⇒ 这是**台架该有的
   行为**：它提醒你「契约变了，别忘了改矩阵」。

---

## 9. 教训

- **Lesson 1573 — 一个只活在某一轮临时目录里的场景矩阵，等于没有矩阵。**
  ⇒ 台架和**期望**一起进仓库（`scripts/bench-scenarios.mjs`），下一轮
  改坏了它就会红。
- **Lesson 1574 — 量具会污染被测对象。** `--sessions-dir` 放在沙箱里，
  模型 `glob` 到一堆会话转录，于是说「这个仓库是空壳」；一轮的读数因此
  失真。⇒ **沙箱里只放夹具，别的都放外面。**
- **Lesson 1575 — 判定和工具池必须说同一句话。** 「判定说这一轮不改，
  工具池说你能改」= 产品自相矛盾，而且代价落在用户的工作树上。
  ⇒ **能力轴要双向接线**：要改 ⇒ 给写通道；不要改 ⇒ 收掉写通道。
  （R411 只接了前一半。）
- **Lesson 1576 — 收紧能力之前，先补上另一半。** 「这个不生效，帮我修一下」
  会被失败症状规则判成 DIAGNOSE；只收紧不补 ⇒ 用户要修的那一轮反而
  拿不到写通道 ⇒ **请求被分类器判成了它做不到的事**。
- **Lesson 1577 — 「执行对了」不等于「交付干净了」。** 三个写场景产物全部
  到位，同时全部留下了没人要的文件（1 / 4 / 13 个）。⇒ 判定对了、产物对了，
  还要问「**工作树干净吗**」。
- **Lesson 1578 — 同一件事要有两个方向的不变量。** 「不要求改 ⇒ 不给写通道」
  的一半，不测就没人知道它断了；补上「要求改 ⇒ 必须给写通道」这一半，
  才不会把修复做成新的回归。
- **Lesson 1581 — 见证只有在「两种实现给出不同答案」时才是见证。**
  断言恒真不是「护栏够强」，是「**场景问不到那条代码**」：本轮的
  M45 变异（去掉问句约束）第一版没被抓住，因为那个场景里根本没有
  动作动词 ⇒ 被变异的代码路径压根没执行。
  ⇒ 写完断言要问一句：**「这个变异会让这两行代码产生不同的答案吗？」**
- **Lesson 1582 — 契约改了，连带它的副本一起改。** 台架里的期望是契约的
  副本；契约一变，副本就该红 —— 那正是它在提醒你。
