# R411 — The capability axis, and the domain axis

## The ask

R408 measured the gaps across seven task shapes and ranked them. P0-1
(the prompt's tool table), P0-2 (the prompt's cwd) and P0-4 (the refusal's
wording) became R409 and R410. What is left of the P0/P1 list is the one
that no amount of prompt work can fix:

> **P1-6 — 「要不要动工作树」从领域里拆出来，做成独立判据.**
> Evidence: S4「写首诗保存到 poem.txt」没落盘.
> Judge: 落盘类请求拿得到写工具（不论产物是代码、诗还是 SQL）.

S4 is the turn R408 measured and it ended like this:

```
prompt: 写一首关于秋天的打油诗，保存到 poem.txt
⇒ UNCERTAIN → navigator (read-only) → file_write PRUNED
⇒ 63 tool calls, every write channel refused
⇒ the poem was in the answer; the FILE was not
```

The interesting part is not that the turn failed. It is **why the
classifier could not have got it right**.

## The defect

The classifier had one table and it was answering two questions at once:

| question | answered by |
|---|---|
| *what KIND of work is this?* | the verb tables (`CODE_VERBS`, `DIAGNOSE_MARKERS`, …) |
| *may this turn change the working tree?* | …the same tables, by accident |

「写一首关于秋天的打油诗，保存到 poem.txt」has no verb in the domain
tables — 「写一首」 is not 「写一个」/「写个」/「写一段」, and 「保存到」 is
not in any of them — so the domain said UNCERTAIN, and the write channel
went with it.

But look at what the turn actually asks for:

- the PRODUCT is a poem — and a poem is **not code**;
- the REQUIREMENT is a file on disk — and that is **not about the domain**.

⇒ **A domain table cannot answer 「may I write?」, and a
write-capability table cannot answer 「what kind of work is this?」.**
Using one table for both questions is what made S4 impossible: there was
no row in it that could say "a poem, to a file".

The R408 table also shows the same confusion from the other side —
「这条 SQL 查询很慢，帮我看看怎么优化」was judged RESEARCH (go to the web),
because 「这条」 was not a local anchor. The user pointed at a row in
*their* database and the agent was told to search the internet for it.

## The fix — two axes, asked separately

### 1. the capability axis

`IntentClassifier.Intent` now carries a second verdict:

```java
public record Intent(Kind kind, String reason, boolean requestsWorkspaceChange)
```

`kind` is the **domain** axis ("how should the agent approach this?").
`requestsWorkspaceChange()` is the **capability** axis ("may this turn
touch the disk?"). They are computed from the same prompt but they
answer different questions, and only the second one decides whether the
write channel is handed over.

The capability is true in exactly three situations:

1. **the domain already means "change"** — `CODE_CHANGE` and `OPS` are
   requests to change something by definition;
2. **the request names where its result goes** — 「保存到 poem.txt」,
   `save to notes.md`. This is S4. The marker table (`PERSIST_MARKERS`)
   only carries forms with a DIRECTION (`到` / `成` / `为` / `进` / `盘` /
   `file`) or the English preposition, because 「保存」 alone is also the
   word in 「怎么保存」 and 「保存当前进度」;
3. **a request wearing a question mark** — a question-shaped prompt that
   carries a request hint (「帮我」), points at a LOCAL artefact
   (「这条 SQL」), and asks for a change (「优化」). All three, or none of
   them.

★ **The safety rule is in rule 3, and it is the asymmetry R401 insisted
on**: a misfire on the write path costs the user their working tree, a
misfire on the read path costs one turn. So a question reaches the write
channel only when it is wearing a request's clothes AND pointing at
something local. 「帮我优化这个算法要注意什么？」— a request hint, but no
local artefact — stays read-only.

### 2. the demonstrative anchors

「这条」/「刚才那个」/「这个」alone are **not** local anchors: 「这个方案」
is a concept question, and it belongs on the web. A demonstrative
pointed at a **concrete artefact** is:

```java
DEMONSTRATIVES   = 这条, 这份, 刚才那个, 上面那个, 这个, 那个, …
ANCHORED_ARTIFACTS = 语句, 查询, 代码, 记录, 日志, 报错, 错误, 异常, 工具, 脚本, 配置, 字段, …
```

★ The pairing is the signal, and the artefact half is checked with a
**standalone-word** test: 「nosql」 and 「mysql」 contain 「sql」, and those
are concept questions, not rows in this tree.

### 3. the escalation, and the posture that must not lie

`ProfileRegistry.forIntent(Intent)` resolves the DOMAIN profile first and
then asks the capability axis whether the write channel may be handed
over:

```java
Profile base = forIntent(intent.kind());
if (intent.requestsWorkspaceChange() && !grantsWrite(base)) return PRODUCER;
return base;
```

`PRODUCER` is a new posture: write-capable and **domain-neutral** — its
directive says "produce exactly what was asked, and put it where the
user said", and explicitly forbids the model from turning the request
into a different kind of work (a poem stays a poem, a SQL fix stays a SQL
fix).

★ **Why a new profile and not "the read-only one plus a line"**: the
read-only postures say, in their own words, 「Do NOT create or modify
files」. Handing such a turn a `file_write` produces a prompt that
contradicts the tool table — the exact defect R401 was written to
remove. The escalated turn gets a posture that says what it is.

★ **Why the write-capable domains are untouched**: `CODE_CHANGE` and
`OPS` already grant the channel, and their postures are worth keeping —
「read the surrounding code before you write」 is better advice than
「produce exactly what was asked」. The escalation only ever fires for a
read-only domain.

★ **Why the user's override still wins**: the override is a decision, and
the capability axis is a guess. R401's contract is unchanged.

## Where the truth lives

| fact | lives in |
|---|---|
| the domain verdict | `IntentClassifier.classifyInternal` (unchanged) |
| the capability verdict | `IntentClassifier.requestsWorkspaceChange` |
| the destination / change / request-hint tables | `IntentClassifier` (one list each) |
| the posture wording | `ProfileRegistry.PRODUCER` |
| whether a profile keeps the write channel | `ProfileRegistry.grantsWrite` — asked of the **deny list**, not of a list of "write-capable profiles" |
| which profile a turn gets | `ProfileRegistry.forIntent(Intent)` — the SDK calls THIS one |

★ `grantsWrite` asks the deny list because the tool pool is not a closed
set (R410's Lesson 1551): a new profile would otherwise have to be
added to a list of write-capable profiles, and a list nobody updates
silently loses a capability.

## The gates

### The tests

29 new cases in 4 classes:

| class | cases | what it pins |
|---|---|---|
| `IntentClassifierR411Test` (core) | 14 | the capability axis, the demonstrative anchors, and the REGRESSION net over R408's fourteen measured prompts |
| `ProfileRegistryR411Test` (core) | 8 | the escalation, the posture that must not lie, `grantsWrite`, and R410's "a write posture keeps the agent tools" contract |
| `QueryEngineIntentR411Test` (core) | 4 | the behaviour: the write tool reaches the MODEL and the EXECUTOR, or it does not |
| `AetherCodeEngineCapabilityR411Test` (sdk) | 3 | the PRODUCTION path — the line that resolves the profile from the whole verdict |

★ Every rule that grants the write channel has a mirror rule that
withholds it, and both are pinned. A classifier that only ever says
"yes" passes a one-sided test.

★ The R401/R404/R405 engine tests were changed to resolve the posture
through `forIntent(intent)` — the production path. Resolving only the
kind would have kept them green while the product went back to pruning
the write channel off a turn that asked for a file.

★ One R401 pin was relaxed on purpose: the per-profile directive marker
was the literal `"# Task posture (R401"`, and the new profile's
directive is marked `(R411, …)` — so the pin went red on a change that
was CORRECT. The invariant is "every profile carries a per-turn task
posture", and the round number inside the marker is a spelling detail
(R384's Lesson 1233).

★ And the wire's profile list was pinned TWICE, in two places, as a
literal count. The protocol test asserted `hasSize(7)` and the seven
names — a second copy of the registry's vocabulary in another module, and
the second copy is the one that drifts. Both protocol pins now compare
against `ProfileRegistry.names()`: the wire's contract is "it carries
EVERY profile the registry has, in the registry's order", and the
vocabulary itself is pinned once, in `ProfileRegistryR401Test`. The pair
pins the vocabulary once and the completeness once.

★ The full package caught the second copy: the wire test went red on the
first build, with "Expected size: 7 but was: 8" — and the profile it
named (`producer`) was in the response exactly as intended.

### The live measurement

The daemon was rebuilt from the full reactor (**667 classes / 6361 tests /
0 failures / 0 errors / 5 skipped**), the jar was synced to all four
places with one sha256, and the R408 live bench was re-run against it
(**18 passed / 0 failed**):

| scenario | before (R408) | after (R411) |
|---|---|---|
| **S3** a poem, no destination | no tool, no file ✔ | **unchanged**: no tool, no file ✔ — the mirror holds |
| **S4** a poem that must land on disk | ★ 63 tool calls, **no file** ✘ | ★ **`poem.txt` EXISTS on disk** ✔ — `tools=[bash, file_write, file_read]`, and the model's own reasoning says it followed the posture: "Read the target before you change it, when the target already exists" |
| **S7** a slow SQL | went to the web (RESEARCH) ✘ | ★ **it read the LOCAL sql, it did NOT go to the web, and it CHANGED it** ✔ — `OrderDao.java` and `schema.sql` were rewritten in place (the "能改" half of the acceptance criterion) |

★ And the agent registry was compared before and after the whole run
(15 files, sha256 per file): **UNCHANGED** ✔ — the R408 damage (the
model rewriting the user's `end-user` agent) does not come back through
the escalated write posture.

★ The S7 turn is LOUD: 77 tool calls and 73 690 characters, because the
escalated posture carries the whole tool set (the shell included) and the
model used it. The behaviour is right and the acceptance criterion is
met, but the turn is expensive — see "What is still open".

### The mutation witnesses

10 mutations, each with its own witness:

| mutation | witness |
|---|---|
| the destination is ignored | `aPoemSavedToAFileAsksForAChange` |
| the escalation never fires | `aTurnThatAsksForAChangeGetsTheWriteChannel` |
| the demonstrative anchor is dropped | `aLocalQuestionWithARequestHintAsksForAChange` |
| a question needs no local artefact | `aQuestionWithARequestHintButNoLocalArtefactDoesNot` |
| a question needs no request hint | `theSameQuestionWithoutTheRequestHintDoesNot` |
| a question about saving grants the write channel | `aQuestionAboutSavingIsNotARequestToSave` |
| the write posture says read-only | `theEscalatedPostureDoesNotSayReadOnly` |
| the domain no longer implies a change | `aCodeChangeAlwaysAsksForAChange` |
| the write posture denies the write tools | `aTurnThatAsksForAChangeGetsTheWriteChannel` |
| the engine resolves only the domain | `theWriteToolReachesTheModelWhenTheTurnAsksForAFile` |

## What is still open

- **P1-7 — 「查一下/搜一下」 into `RESEARCH_MARKERS`** was **NOT** done
  here, and it is deliberately not done yet. `RESEARCH_MARKERS` is
  checked FIRST, before the local anchor — so a bare 「查一下」 would
  send 「查一下这个文件」 to the web. The marker needs the direction
  「查资料」 that R401 already uses; the loose form is a trap.
- **The agent registry in a WRITE posture.** `PRODUCER` keeps the agent
  tools, because R410's contract scopes the deny set to the READ-ONLY
  profiles — 「a turn asked to change something may still set an agent
  up」. That is deliberate and pinned
  (`aWritePostureKeepsTheAgentTools`), but it means the live S4 turn
  runs with the user's real agent registry reachable. The live run
  compares the registry before and after, because the R408 measurement
  showed the model reaching for it.
- **R412 — the verdict on the wire.** The user still cannot see which
  posture a turn got, and the capability axis is not on the wire either.
- **R413 — the researcher's two contradictory instructions.**
- **★ The escalated posture is LOUD ★** `PRODUCER` carries the whole
  tool set — the shell included — because a "write me a file" turn may
  also need to run something (a test, a build). The live S7 turn spent
  77 tool calls and 73 690 characters on a two-file SQL fix. A narrower
  producer (the write tools, no shell) would be cheaper and would also
  be safer, at the price of a turn that needs a command. That is a
  posture decision, not a bug — but it is a decision nobody has made
  yet.
- The classifier is still a rule table, and a rule table is a table of
  guesses. The capability axis made the guesses orthogonal, not fewer.

## Lessons

- **Lesson 1555 — 一个表只能回答一个问题。** 「这是哪一类工作」和
  「能不能动工作树」是两个问题，用一张表回答，等于让「写首诗」和
  「落盘」互相绑架。
- **Lesson 1556 — 产物是什么，与需求要落在哪里，是两件事。** 诗不是代码，
  但「保存到 poem.txt」是需求。领域轴看不见需求，能力轴看不见产物。
- **Lesson 1557 — 疑问句要拿到写通道，得同时穿三件衣服：请求的语气
  （帮我）、本地的指向（这条 SQL）、要改的动作（优化）。** 少一件就
  退回只读——因为写错的代价是用户的工作树，读错的代价只是一轮。
- **Lesson 1558 — 指示代词本身不是本地锚点，指向具体产物才是。**
  「这个方案」是概念，「这条 SQL」是这棵树里的东西。成对判断，
  单看任一半都会误判。
- **Lesson 1559 — 升级后的姿态不能撒谎。** 不能给一个写着「不要创建
  文件」的只读姿态塞进 `file_write`——那正是 R401 要消灭的
  「提示词说一套、结构说另一套」。所以它是新档案，不是加一行。
- **Lesson 1560 — 「能不能写」要问 deny 列表，不要问「哪些档案能写」。**
  后者是一张没人更新的表，而没人更新的表会悄悄丢掉一个能力。
