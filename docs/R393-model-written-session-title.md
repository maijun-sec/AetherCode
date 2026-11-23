# R393 — 让模型给 session 取标题

> 2026-11-23. 用户的原话：
>
> > 先做个事情，然后再提交哈，我看现在取的标题，好像是我完整的prompt内容，
> > 能不能概括一下，比如让模型给你取个 5-15个字的标题来使用？
>
> 事实：session 的标题**就是**用户的第一条 prompt 原文。
> `listSessions` 的解析顺序是
> `firstNonBlank(用户改名, first_prompt, transcript 扫描)`，
> 而 `first_prompt` 存的是原文（最多 2000 字符）。
> 所以左栏那一行显示的是「在当前项目目录下，创建一个 java maven 项目，支持 至少 5 种排序实现……」。

---

## 0. 结论先说

标题现在由**模型**写，5-15 个字，落库后持久化。

- 用户自己的改名**永远赢**（模型的标题只写进空槽）。
- 模型调用在**后台线程**，列表不等它。
- 标题落库时 daemon 推 `session_title` 通知，渲染层就地改那一行，不用刷新。
- **默认关闭**，由 daemon 显式开启（原因见 §5）。

---

## 1. 为什么标题不能是 prompt 原文

R390 已经把标题做成了「持久化事实」（用户改名 > first_prompt > transcript 扫描），
解决的是「改名被覆盖」和「压缩后标题变成 `[PostCompact re-injection]…`」。

但 R390 选的那个第一兜底是 `first_prompt` —— **prompt 原文**。
它能当兜底，是因为它至少是**这个会话**的语义；
但它不是**标题**：40 个汉字塞进左栏一行，既读不出重点，也占满整行。

用户要的是「概括」。所以缺的不是「更可靠的原文」，而是**另一个来源**：模型写的短标题。

> 代码库里其实早就预告过这件事 ——
> `aethercode-desktop/src/components/SessionList.tsx` 里有一段注释：
>
> > the LLM-generated "10-char summary" is a future round (R223+ candidate)
>
> R393 就是那一轮。本轮把那段注释更新成了「已经实现」。

---

## 2. 形状

```
listSessions（读路径）
   │
   ├─ 这个 session 有标题吗？  ──有──→ 用它（用户的改名 / 上次写的模型标题）
   │        │没有
   │        ↓
   │   有 first_prompt 吗？   ──没有──→ 结束
   │        │有
   │        ↓
   └─ 交给协调器（后台线程，10 分钟冷却）
          │
          ├─ 调模型 → 摘要（5-15 字）
          ├─ 写进「空的」title 槽位（SQL 里的 WHERE title IS NULL）
          │      └─ 写不进去 ⇒ 用户已经改过名 ⇒ 丢弃模型的标题
          └─ 通知渲染层：session_title { sessionId, title, atMs }
```

### 2.1 为什么触发点选在 `listSessions`

它是**唯一**同时知道两件事的地方：「这个 session 没有标题」和「它的第一条 prompt 是什么」。

放在读路径上还有一条现成的先例：R390 的 `backfillFirstPrompt` 已经在
`listSessions` 里回写 store 了（`SessionStore` 的 javadoc 里写着
「★ WHY THE STORE IS ALLOWED TO WRITE ON A READ PATH ★」）。
本轮是同一形状：**把一个可以推导的事实物化一次，之后它就是持久化的**。

好处是**一条路径覆盖新老 session**：新 session 第一轮就会被命名，
R390 之前的老 session 也会在被列出时补上。

### 2.2 为什么必须异步

模型要几秒。`listSessions` 不能等 —— 它是左栏刷新的来源，
等模型会把整个列表卡住。

所以：**先返回旧标题**（长 prompt），模型答完再推通知。

---

## 3. 长度规则不是「一个数」

用户说的是「5-15 个字」。这是**中文形状**的规则：
15 个汉字放得进左栏一行，但 15 个拉丁字符只有三个词（"Sort arrays"）。

所以规则按**形状**分：

| 标题形状 | 上限 | 为什么 |
|---------|------|--------|
| CJK 主导 | 15 个字符 | 用户的「5 到 15 个字」 |
| 拉丁主导 | 60 个字符 | ≈ 8-10 个英文词 |

「CJK 主导」的判定照 R737 的做法（数 CJK 码点 vs 拉丁字母），
但阈值要改：R737 用「CJK ≥ 12」，那是对**整段 prompt** 说的；
一个 6 个字的中文标题有 0 个拉丁字母，用「≥ 12」会被判成英文。
这里是「CJK > 0 且 CJK × 2 ≥ 拉丁」。

下限统一是 **5 个字符** —— 2 个字的标题在列表里是噪音，
宁可退回原来的长 prompt。

---

## 4. 三个文件

### 4.1 `SessionTitleSummarizer`（core，纯逻辑）

```java
public static String buildPrompt(String firstPrompt)   // 给模型看的话
public static String sanitize(String raw)              // 模型答案 → 标题（不合格返回 null）
public String summarize(String firstPrompt)            // 一次性模型调用
public boolean applyTitle(String sessionId, String t)  // 只写空槽
```

`sanitize` 要处理模型的一堆装饰：引号（`"` `“”` `「」` `『』` `《》` `【】`）、
markdown（`**` `__` `` ` `` `*` `_` `~`）、列表符号、结尾标点（`。.` `!` `?` `,` `、` `;` `:`）、
以及**只取第一行**（模型有时会在第二行加解释）。

> ★ 模型会答非中文 ★ 指令本身是英文的，而 MiniMax-M3 这类模型**跟随 prompt 的语言** ——
> 所以不显式写「中文请求 ⇒ 中文标题」的话，中文请求会得到英文标题。
> 这和 R737 修的是**同一个失败模式**（R737 修的是回答，R393 修的是标题）。

> ★ 推理前言（实机发现，见 §4.4）★ MiniMax-M3 是**推理模型**，它的
> `<think>…</think>` 规划笔记和答案在**同一条文本流**里。

### 4.2 `SessionTitleCoordinator`（core，决定何时花钱）

```java
public void maybeSummarize(String sessionId, String firstPrompt, boolean hasTitle)
```

守卫（`listSessions` 每次刷新都会调它，所以守卫就是功能本身）：

| 守卫 | 为什么 |
|------|--------|
| 已经有标题 ⇒ 不问 | 用户的改名不能被覆盖 |
| 没有 prompt ⇒ 不问 | 没东西可概括 |
| 10 分钟冷却 ⇒ 不重复问 | provider 挂掉时不会每次刷新都打一次 |
| 后台线程 ⇒ 列表不等 | 见 §2.2 |

线程是 **daemon** 线程 —— 非 daemon 的池会拖住 JVM 不退出。

### 4.3 `SessionStore.setTitleIfAbsent`（core，写库）

和 `setFirstPromptIfEmpty` 完全同形（那是为了 `first_prompt` 写的）：

```sql
UPDATE sessions SET title = ? WHERE id = ? AND (title IS NULL OR TRIM(title) = '')
```

★ 为什么「空槽」检查必须在 SQL 里 ★
Java 侧「先读后写」有竞态：模型调用在飞的这几秒里，用户可能刚改名。
先读后写会把用户的名字覆盖掉。放在 SQL 里，判定与写入是**原子的**。

### 4.4 ★ 实机发现：标题写成了模型的「推理前言」 ★

第一次部署之后，库里真实出现了这样的标题：

```
"<think>We need title only Chinese 5-15 chars, summarize"
```

**根因**：MiniMax-M3 是**推理模型**，它的规划笔记用 `<think>…</think>` 包着，
而传输层把这段笔记和答案放在**同一条文本流**里。所以 `TextDelta` 拼出来的
「答案」开头是模型自己的思考过程。

**为什么单测没抓到**：单测里的假客户端喂的是干净的答案。
**真实模型的答案长什么样，只有真的问一次才知道** —— 这一条是实机才暴露的。

**修法**（`SessionTitleSummarizer.stripReasoning`）：

```java
s = raw.replaceAll("(?is)<think>.*?</think>", " ");  // 完整的推理块
int open = indexOfIgnoreCase(s, "<think>");
if (open >= 0) s = s.substring(0, open);            // 没闭合 ⇒ 后面全是推理
s = s.replaceAll("(?i)</think>", " ");                // 孤立的闭合标签
```

★ 顺序是关键 ★ **先剥推理块，再取第一行**。
反过来（先取第一行再剥）会得到一个**空的第一行** → 标题退回整条 prompt，
也就是 R393 本来要消灭的那个东西。

★ 这个约定仓库里已经有了 ★ `aethercode-evals` 的 `BenchmarkLlmAgent`
早就用 `(?is)<think>.*?</think>` 剥同一个 provider 的同一个形状 ——
**已知形状要复用既有做法，不要另发明一套。**

### 4.5 ★ 拒绝「把 prompt 原样抄回来」的标题 ★

模型有时不听指令，直接把**请求本身**当答案。
那种标题一旦写进空槽，空槽守卫就会**永远**留着它 —— 那等于把 R393 要消灭的东西
又装回去了。所以多一道判断：**标题是 prompt 的字面前缀 ⇒ 拒绝**（槽位保持空）。

---

## 5. 为什么这个功能默认关闭

★ 这是本轮踩到的最值得记的一处 ★

触发点在 `listSessions` 上，而**默认构造的 engine 自带一个
`SpringAiChatClient`**（`AetherCodeEngine` 的 builder 在没有指定 provider 时
也会 `new SpringAiChatClient(b.model, opts)`）。

于是「默认开启」意味着：**每一个列出 session 的单元测试，都会拿测试里的
prompt 文本去打真实的模型接口**。

- 在**没有** API key 的机器上：那是一次注定失败的外部请求（慢、吵）；
- 在**有** API key 的机器上：它会**成功**，然后把测试正在断言的那个标题改掉 ——
  测试变成「取决于外部服务会不会成功」。

两条都不可接受。所以：

```java
private volatile boolean sessionTitleSummariesEnabled = false;
public void setSessionTitleSummariesEnabled(boolean enabled) { ... }
```

**daemon 显式打开**（`DaemonRunner` 的 stdio 与 http 两条路），
**测试要显式打开**。

> ★ 教训 ★ **「默认开启」是一个会外溢到别人测试里的决定。**
> 一个在读路径上触发**外部调用**的功能，必须是**显式 opt-in** ——
> 否则下一个写测试的人会替我们付账，而且他不知道为什么。

---

## 6. 渲染层

`aethercode-desktop/src/store/index.ts`：

```ts
rpc.on('session_title', (params: any) => {
  const p = params as { sessionId?: string; title?: string; atMs?: number };
  if (!p || typeof p.sessionId !== 'string' || typeof p.title !== 'string') return;
  if (!p.title.trim()) return;
  set((s) => ({
    sessions: s.sessions.map((x) => (x.id === p.sessionId ? { ...x, title: p.title } : x)),
    // 回收站是**另一份数组** —— 只改 sessions 的话，Trash 页还是长 prompt
    trashedSessions: s.trashedSessions.map((x) => (x.id === p.sessionId ? { ...x, title: p.title } : x)),
  }));
});
```

左栏行渲染（`components/SessionList.tsx`）只显示 `sessionLabel(s)`，
而 `sessionLabel` 的第一分支就是 `s.title` ⇒ **短标题直接显示，
没有第二行 preview**。

---

## 7. 测试

| 层 | 文件 | 例数 | 覆盖 |
|----|------|------|------|
| core | `SessionTitleSummarizerR393Test` | 25 | prompt 带语言规则 / 各种装饰的清洗 / 长度规则（CJK 15、拉丁 60）/ **剥推理前言（3 种形状）** / **拒绝抄 prompt** / 模型失败返回 null / **写入守卫：改名不被覆盖** |
| core | `SessionTitleCoordinatorR393Test` | 6 | 已有标题不问 / 无 prompt 不问 / **冷却期内不问第二次** / 落库 + 通知 / 无 client 不花钱 / 改名存活 |
| protocol | `AetherCodeMethodsR393SessionTitleTest` | 4 | **列表会触发摘要并落库 + 发通知** / 改名不被替换 / **默认关闭（不发外部请求）** / 列表不等模型 |
| desktop | `sessionTitleR393.test.ts` | 4 | 通知只改那一行 / 回收站那一份数组也被改 / 畸形通知不改任何东西 / 未知 id 不崩 |

### 7.1 两处变异验证

**（a）桌面端的通知处理器失效**（`if (true) return;`）：

```
4 tests | 2 failed | 2 passed
```

红的是那两条「处理器确实改了标题」；另 2 条是畸形输入的护栏，**理应不红**。

**（b）★ 写守卫失效 ★**（把
`WHERE id = ? AND (title IS NULL OR TRIM(title) = '')` 改成 `OR 1 = 1`）：

```
SessionTitleSummarizerR393Test   19 tests | 1 failed  ← theUsersRenameIsNeverOverwritten
SessionTitleCoordinatorR393Test    6 tests | 1 failed  ← theUsersRenameSurvivesTheModel
AetherCodeMethodsR393SessionTitleTest 4 tests | 0 failed
```

★ 注意第三行 ★ 协议层那条**没有变红** —— 因为改名保护有**两道独立的门**：

1. **触发门**：`listSessions` 只在「标题为空」时才问模型 ⇒ 改过名的 session 根本不会进模型；
2. **写入门**：SQL 的 `WHERE title IS NULL` ⇒ 万一模型问了，写也写不进去。

变异打掉的是第 2 道，所以只有**直接测第 2 道**的两条 core 用例变红。
这正是分层的价值：**任何一道单独失效，用户的名字都还在。**
（如果只测协议层，这条变异会「全绿」—— 那就是一个恒真断言。）

### 7.2 一处测试基础设施的坑
协议层第一版有 1 例报：

```
java.io.IOException: Failed to delete temp directory … sessions.db
  另一个程序正在使用此文件，进程无法访问。
```

**不是断言失败，是 JUnit 删临时目录时文件被锁着** ——
我的后台写任务还活着（它活得比请求久，这正是设计）。
修法：测试结束前等写入落地。

> 这个错误值得记，因为它**长得像一个失败**（ERROR + 栈），实际是**清理失败**。
> 读 surefire 报告的原文（`Tests run: 4, Failures: 0, Errors: 1`），
> 而不是只看「BUILD FAILURE」。

### 7.3 ★ 修好推理前言之后的第二次变异验证 ★

把 `stripReasoning(raw)` 换成 `raw`（等于不剥）：

```
SessionTitleSummarizerR393Test   25 tests | 5 failed
```

红的 5 条：

```
theReasoningPreambleIsStripped
aMultiLineReasoningBlockIsStripped
anUnterminatedReasoningBlockYieldsNoTitle
aStrayClosingTagIsDropped
aReasoningPrefixedStreamYieldsACleanTitle
```

**理应不红**的那条是 `aTitleThatCopiesThePromptIsRejected` ——
它测的是另一道门（抄 prompt 的拒绝），与推理块无关，
所以它保持绿是**对的**，不是漏网。

---

## 8. 教训

**Lesson 1447 — 一个在读路径上触发外部调用的功能，必须是显式 opt-in。**
默认构造的 engine 自带 chat client ⇒ 「默认开启」会让每个列 session 的单测
都拿测试文本去打真实接口：有 key 时它会**改掉被断言的值**，没 key 时它会
**把 prompt 发出去**。见 §5。

**Lesson 1448 — 「空槽」判定必须在 SQL 里，不能在 Java 里。**
先读后写有竞态：模型调用在飞的几秒里用户可能刚改名。
Java 侧的判定与写入之间有窗口，SQL 的 `WHERE title IS NULL` 没有。

**Lesson 1449 — 派生事实可以在读路径上物化，但要有「物化过」的标记。**
本轮复用了 R390 的形状（`backfillFirstPrompt`）：写进去之后它就是持久化事实。
但**必须有标记**，否则每次刷新都会重新算一次 —— 这里的标记是
「`title` 槽位非空」加上协调器的冷却表。

**Lesson 1450 — 「5-15 个字」是中文形状的规则，不是长度规则。**
15 个拉丁字符只有三个词。规则要按**形状**分（CJK 15 / 拉丁 60），
判定阈值也要跟着改（R737 的「CJK ≥ 12」是对整段 prompt 说的，
对 6 个字的中文标题会判错）。

**Lesson 1451 — 模型会答非中文，而指令是英文的。**
和 R737 同一个失败模式：模型跟随 prompt 的语言。
要它用中文答，就**显式写**「中文请求 ⇒ 中文标题」。

**Lesson 1452 — 后台任务活得比请求久，测试要考虑它。**
模型调用在飞的时候它锁着 SQLite 文件；JUnit 删临时目录会失败，
而那个失败长得像一个断言失败。

**Lesson 1453 — 单测里的假客户端不会告诉你真实模型的答案长什么样。**
假客户端喂的是干净答案，所以推理模型的前言（`<think>…</think>`）在单测里
永远不出现；它是在**第一次实机部署**里以「标题变成了
`<think>We need title only Chinese 5-15 chars, summarize`」的形式暴露的。
⇒ **凡是「把模型输出直接当数据用」的功能，单元测试绿了不算数** ——
要有一处**真的问一次模型**的验证（哪怕只是部署后的第一次）。
⇒ 而已知形状要**复用仓库既有做法**：`aethercode-evals` 的 `BenchmarkLlmAgent`
早就剥同一个 provider 的同一个 `<think>` 块了。

**Lesson 1454 — 清洗的顺序会决定清洗是否还有意义。**
「先取第一行，再剥推理块」会得到一个**空的第一行**，
于是标题退回整条 prompt —— 也就是这个功能本来要消灭的那个东西。
**清洗要按「污染源在文本流的哪一层」来定顺序**：推理块在**流**里，
所以要**先在流上剥**，再在行上取。

**Lesson 1455 — 一个派生值一旦落进「只写空槽」的槽位，就再也出不来了。**
模型偶尔会把请求原文当答案返回；那种标题写进 `title` 之后，
空槽守卫会永远保护它 —— 于是「R393 消灭长 prompt」的效果被**永久**抵消。
⇒ 派生值的守卫要**在写之前**就拒掉明显不合格的形态
（本轮：标题是 prompt 的字面前缀 ⇒ 拒绝），而不是指望写进去之后还能修。
