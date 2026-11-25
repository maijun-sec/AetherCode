# R412 — The verdict on the wire, and in the header

## The ask

R411 closed with a note on what it did **not** do:

> **R412 — the verdict on the wire.** The user still cannot see which
> posture a turn got, and the capability axis is not on the wire either.

It is the same item R408's §6 P1 list carries:

> **把「这一轮判成了什么」放到 wire，桌面端显示** — 用户看不到判定 ⇒
> R401 承诺的「一次点击纠正」没有前提.

★ **A correction needs a PREMISE.** R401 built the one-click override:
the header chip opens a picker, the user picks a posture, the daemon
honours it. But the premise of that promise is that the user can **see
what the agent decided**. Without that, the chip is empty and the
promise is unpayable — the user can only re-prompt, which is not a
correction.

## The defect

The verdict existed. R401 put it on the `run_start` event:

```
run_start { intent, intentLabel, profile, profileLabel }
```

★ **And that is exactly where it could not survive.** An event is a
moment. The verdict lived only while a run was streaming, and was gone
afterwards — which is precisely when a user asks 「why did it do that?」:

| the user asks | the verdict was… |
|---|---|
| *while* the turn streams | visible (the event) |
| after a reload | **gone** |
| after a reconnect | **gone** |
| after a session switch | **gone** (or, worse, **the other session's**) |

And the surface that carried the verdict was the *event stream*, which
the desktop only listens to while it is connected. `getState` — the one
RPC the desktop polls every 15 s — carried the model, the permission
mode, the context window, the tool count, the live run ids … and **not
the verdict**. `engineHealth` did carry `profileOverride` and the
profile list, but **the desktop never calls `engineHealth`** — so the
picker's vocabulary was unreachable after a reload too.

⇒ Three distinct holes, one cause: **the verdict had no home that
outlived the event.**

## The fix — the verdict gets a home, and two carriers that agree

### 1. the daemon publishes it on the session

`AppState` already carried the resolved **profile** (`activeProfile`) —
the posture the turn actually ran under. R412 adds the **intent** next to
it:

```java
private volatile IntentClassifier.Intent activeIntent = null;
```

★ **Why the profile alone was not enough**: the profile name
(`navigator`) is an *implementation* word. The user's question is 「what
did the agent think this turn WAS about」, and that is the intent
(`未识别` / `代码生成`) plus **the reason** (`no intent signal`).
The profile answers "which tools did this turn get"; the intent answers
"why".

`AetherCodeEngine.query` — the one place where the classifier has
spoken and the posture has been chosen — publishes both:

```java
appState.activeProfile(profile);
appState.activeIntent(intent);
```

★ The override does **not** rewrite the intent, and that is pinned:
the override is a *decision about the posture*; the intent is *what the
classifier said*. Rewriting the intent would make the chip claim the
classifier said something it did not say.

### 2. the polled state carries it

`getState` now carries the verdict, the reason, the override and the
vocabulary:

```
intent  intentLabel  intentReason  profile  profileLabel
profileOverride  profiles
```

★ **The key names are the EVENT's.** `intent` / `intentLabel` /
`profile` / `profileLabel` are the names `run_start` already uses, so a
client reads the verdict from either carrier with **one** parser and the
two cannot drift. (`intentReason` is new, and it is on both.)

### 3. the desktop reads it back — and reads it right

`refreshEngineState` patches the header's verdict from the polled state,
with **three** different null-handlings, because the fields do not mean
the same thing:

| field | `null` means | what the renderer does |
|---|---|---|
| `intent` / `profile` | 「no verdict (yet)」 | **keep** what we know — a null must not erase the verdict the `run_start` frame just delivered, or the chip would flicker on every poll |
| `profileOverride` | 「auto」 — a real answer | **apply** it: the lock icon must show the daemon's truth, not this client's memory |
| the key itself absent | 「this daemon does not know」 | **keep** what we know — an older daemon is not an authority on auto |

### 4. the verdict is per-session, and switching clears it

The verdict describes **this session's** last turn. Keeping it across a
session switch would show session A's verdict over session B — and the
chip is the one thing the user trusts to describe the current turn, so a
stale verdict there is worse than no verdict. `switchSession` clears it;
the new session's own verdict arrives with the next poll or its next
`run_start`.

★ **★ AND THE LIVE BENCH FOUND THAT "PER-SESSION" WAS NOT TRUE ★★**

This is the finding of the round, and it came from the live measurement
rather than from a test — which is the point of running one.

The bench created a second session and asked the daemon what it had
decided **there**:

```
FAIL  a fresh session has NO verdict :: intent="uncertain" profile="navigator"
```

The fresh session reported the PREVIOUS session's verdict. And the
reason is structural:

```java
// AetherCodeEngine.loadSession(...)
// re-register the engine under the new id in the SessionManager
syncSessionManagerRegistration();
```

★ **One engine serves MANY sessions.** The daemon registers the same
engine under each new session id, so the `AppState` — and with it the
verdict — is **shared**. `loadSession` swapped the transcript but left
the verdict behind.

⇒ So the desktop's "clear the chip on a session switch" was undone by
the very next 15 s poll: the chip cleared, and then the poll brought
the old verdict straight back. **A renderer-side fix cannot close a
daemon-side leak.**

Two changes close it, and the second one is the subtle one:

1. **`AetherCodeEngine.loadSession` clears the intent.** The verdict
   describes a turn; the newly-loaded session's last turn is one we do
   not know (it was never persisted), so **"no verdict" is the honest
   answer** — and the next turn in that session sets it again.

2. **The wire reports the verdict as a UNIT.** The intent and the
   profile describe the SAME turn, so they are reported together or not
   at all:

   ```java
   boolean hasVerdict = verdict != null;
   r.put("intent",   hasVerdict ? verdict.token() : null);
   r.put("profile",  hasVerdict && resolved != null ? resolved.name() : null);
   ```

   ★ **Why the profile needs the unit rule**: the profile is
   **deliberately NOT cleared** on a session switch. It is not only a
   display fact — the subagent path prunes the CHILD's tool pool with
   it, so a running read-only parent must keep its restriction even if
   the user walks away to another session. Clearing it there would let
   that parent launder writes through a child — exactly the hole R401
   closed.

   ⇒ So the profile CAN outlive the turn it belongs to ⇒ reporting it
   alone would report **another session's posture as this one's** ⇒
   half a verdict, and the half the user would read as "this session
   runs under this posture".

   ⇒ **No intent ⇒ no verdict ⇒ both null.**

★ **What is deliberately NOT fixed here**: the user's profile OVERRIDE
still follows the engine, not the session. That is R401's pre-existing
shape (the override lives on the shared `AppState`), it errs towards
**more** restriction — the safe direction — and the chip does not render
at all without a verdict, so it is invisible until the new session's
first turn. Changing it is an R401 question, not an R412 one.

### 5. the chip answers "why", not only "what"

The chip showed 「意图：代码生成 · 档案：写代码」. That is the verdict
but not its reason. The reason is now in the tooltip:

```
意图：代码生成 · 档案：写代码
判定依据：implementation verb: create the
```

★ The reason is the daemon's own English string — the classifier's
vocabulary, not the renderer's. That is deliberate: the renderer must
not re-derive the verdict (R385's rule), and re-writing the reason
would be re-deriving it. (R410's refusal message has the same
English-in-a-Chinese-UI inconsistency; it is a known, documented
inconsistency, not an accident.)

## Where the truth lives

| fact | lives in |
|---|---|
| the classification | `IntentClassifier.classify` (unchanged) |
| the published verdict | `AppState.activeIntent` — set by `AetherCodeEngine.query` |
| the published posture | `AppState.activeProfile` (unchanged) |
| the verdict on the wire | `AetherCodeMethods.getState` — the RPC the desktop polls |
| the verdict on the event | `AetherCodeMethods.eventToMap` (`run_start`) — the same key names |
| the reason | `Intent.reason()` — carried by both carriers, under `intentReason` |
| the vocabulary | `ProfileRegistry.describeAll()` — sent with the state, never copied by the renderer |

## The gates

### The build

The whole reactor was packaged with its tests:

```
surefire: 669 classes / 6375 tests / 0 failures / 0 errors / 5 skipped
jar: 56 991 581 bytes
sha256: 7C3866CBE0F59BE689EF9309D279BD8FE93CF819A52CA094F79314670D965510
  aethercode-desktop/src-tauri/resources/aethercode.jar      SAME
  dist/aethercode-green-r388/resources/aethercode.jar        SAME
  dist/aethercode-green-r388/tui/aethercode-0.3.0.jar        SAME
  aethercode-desktop/src-tauri/target/release/aethercode.jar SAME
daemon healthy after 0s
```

★ R411's baseline was 667 classes / 6361 tests ⇒ **+2 classes / +14
tests**, which is exactly this round's new cases (10 protocol + 4 sdk).

★ **And the full build caught something the single-module run did not.**
One case was GREEN alone and RED in the reactor:

```
[ERROR] AetherCodeMethodsVerdictR412Test.aSessionSwitchTakesTheVerdictWithIt
        -- Time elapsed: 0.076 s <<< ERROR!
  java.io.IOException: Failed to delete temp directory ...
  Suppressed: java.nio.file.FileSystemException: ...\store\sessions.db:
        另一个程序正在使用此文件，进程无法访问。
```

★ **The logic was fine; the CLEANUP was not.** The SQLite index file was
still held (by the driver, or by a Windows indexer scanning the fresh
file) when JUnit deleted the `@TempDir` ⇒ a locked file ⇒ the test
failed for a reason that had nothing to do with the verdict. The store
now lives in its own temp directory, deleted best-effort with a retry.

### The tests

24 new cases in 4 classes:

| class | cases | what it pins |
|---|---|---|
| `AetherCodeMethodsVerdictR412Test` (protocol) | 10 | the verdict, the reason, the override and the vocabulary on the **polled** state; that the two carriers agree; that a session that has not run has no verdict; that an unknown session still answers; ★ that a session switch takes the verdict with it |
| `AetherCodeEngineVerdictR412Test` (sdk) | 4 | the engine **publishes** the verdict; the published intent is the one the posture was resolved from; the user's override does not rewrite the intent; ★ the verdict does not survive a session switch (but the live restriction does) |
| `verdictR412.test.ts` (desktop store) | 7 | the chip is filled from the poll; a null does not erase; `auto` IS applied; an absent key is not `auto`; switching sessions clears |
| `HeaderIntentR412.test.tsx` (desktop header) | 3 | the tooltip carries the reason; it carries it even behind the lock; and no reason means **no empty reason line** |

★ The desktop's whole suite is green on the change: **146 files / 1536
tests**.

### The live measurement

The bench drives the real daemon over the real HTTP RPC (the path the
desktop uses), runs one turn, and then asks the daemon what it decided —
**after the run ended**.

| what | before (R411 jar) | after (R412 jar) |
|---|---|---|
| `run_start` carries intent + profile | ✔ | ✔ |
| `run_start` carries the reason | ✘ | ✔ |
| **`getState` carries the verdict** | **✘ (absent)** | **✔** |
| **`getState` carries the reason** | **✘ (absent)** | **✔** |
| **the verdict is still there after the run ended** | **✘** | **✔** |
| the two carriers agree | n/a | ✔ |
| the vocabulary travels with the state | ✘ | ✔ |
| the state carries the override field | ✘ | ✔ |
| ★ **a session that has not run has no verdict** | **✘ (it carried the PREVIOUS session's)** | **✔** |

★ The two runs: **BEFORE 6 passed / 16 failed**, **AFTER 22 passed / 0
failed**.

The two runs, measured:

```
BEFORE (the R411 jar):  6 passed / 16 failed
  ....  getState verdict: intent=undefined intentLabel=undefined intentReason=undefined
                            profile=undefined profileLabel=undefined
  FAIL  ★ the event carries the REASON :: intentReason=undefined
  FAIL  the POLLED state carries the intent :: intent=undefined
  FAIL  ★ the verdict is STILL there after the run ended :: after={}

AFTER  (the R412 jar): 21 passed / 1 failed
  ....  run_start: {... "intent":"uncertain","intentLabel":"未识别",
         "profile":"navigator","profileLabel":"未识别（只读）",
         "intentReason":"no intent signal"}
  ....  getState verdict: intent="uncertain" intentLabel="未识别"
         intentReason="no intent signal" profile="navigator"
         profileLabel="未识别（只读）"
  FAIL  a fresh session has NO verdict :: intent="uncertain" profile="navigator"
```

★ **That last line is the finding.** The FIRST "after" run still
carried the previous session's verdict into the fresh one — and that is
what sent the round back to the daemon (see "the verdict is per-session"
above). The table's "after" column is from the run AFTER the leak was
closed:

```
AFTER (the fixed R412 jar): 22 passed / 0 failed
  ....  getState verdict: intent="uncertain" intentLabel="未识别"
         intentReason="no intent signal" profile="navigator"
         profileLabel="未识别（只读）"
  PASS  ★ the verdict is STILL there after the run ended
  PASS  a fresh session has NO verdict
```

★ And note the verdict the bench measured: **「意图：未识别 · 档案：未识别（只读）」**
with the reason **「no intent signal」** — a READ-ONLY posture, decided
by the daemon, on the wire, and readable after the run ended. That is
the round's acceptance criterion, met on the wire.

### ★ The desktop's own chain, against the RUNNING daemon ★

The bench above proves the WIRE. The desktop half needed its own
measurement, because every desktop gate in this round used a stubbed
daemon. So a **temporary live probe** was run — and then deleted (a
test that needs a running daemon is a job, not a test):

```
live query accepted: {"runId":"run-1","accepted":true}
live getState verdict: {"intent":"research","intentLabel":"概念搜索",
  "intentReason":"concept question with no local anchor",
  "profile":"researcher","profileLabel":"查资料",
  "profileOverride":null,"profiles":8}

chip text   : 意图：概念搜索 · 档案：查资料
chip tooltip: 意图：概念搜索 · 档案：查资料（点击切换档案）
判定依据：concept question with no local anchor
```

★ **That is the whole chain, live**: the running daemon → the real HTTP
wire → the desktop's own `rpc` → the desktop's own store → the
desktop's own `Header` component. The chip shows the daemon's verdict
and the daemon's reason, on a turn the daemon classified as a
**read-only concept lookup**（「查资料」/「概念搜索」）.

★ And the reason is the classifier's own sentence:
「concept question with no local anchor」.

### The mutation witnesses

★ **Every mutation has its own witness**, and the witnesses are the
*named* tests that are supposed to go red — not "some test went red".

**Java (14)** — each lands in a **copy** of the tree
(`D:\work\tmp\r412-mutant`), and the restore is proved byte-identical:

| mutation | witness |
|---|---|
| the state drops the reason | `thePolledStateCarriesTheReason` |
| the state drops the intent | `thePolledStateCarriesTheIntent` |
| the state drops the profile | `thePolledStateCarriesTheProfile` |
| the state reports the label under the token key | `theEventAndTheStateAgreeOnTheVerdict` |
| the event drops the reason | `theEventCarriesTheReasonToo` |
| the state drops the override | `thePolledStateCarriesTheOverrideAndTheVocabulary` |
| the state drops the vocabulary | `thePolledStateCarriesTheWholeProfileVocabulary` |
| the state invents a verdict | `aSessionThatHasNotRunYetHasNoVerdict` |
| an unknown session throws | `theStateIsStillUsableWhenTheSessionIsUnknown` |
| the engine never publishes the intent | `theSessionCarriesTheIntentAfterAMainTurn` |
| the engine publishes the profile instead of the prompt | `thePublishedIntentIsTheOneThePostureWasResolvedFrom` |
| the override rewrites the intent | `theUserOverrideDoesNotRewriteTheIntent` |
| ★ **the verdict survives a session switch** | `theVerdictDoesNotSurviveASessionSwitch` |
| ★ **the state reports a profile without a verdict** | `aSessionSwitchTakesTheVerdictWithIt` |

**Desktop (10)** — same rule, on a copy with `node_modules` reached
through a junction:

| mutation | witness |
|---|---|
| the store ignores the polled verdict | `poll fills the chip` |
| the store ignores the polled vocabulary | `picker vocabulary survives` |
| a null verdict erases the live one | `null verdict does NOT erase` |
| the store ignores the override | `override IS applied` |
| the store applies an absent override | `says nothing about overrides` |
| switching sessions keeps the old verdict | `switching sessions clears` |
| the store ignores the event verdict | `run_start of the NEW session` |
| the store drops the reason from the event | `tooltip carries the classifier` |
| the reason hides behind the lock | `reason is shown even when the profile is locked` |
| the reason line is always there | `no reason means no empty reason line` |

★ **The desktop harness also caught one of its OWN mutations.** The
first version of "the reason hides behind the lock" rewrote the
template interpolation as a concatenation:

```diff
- `档案已锁定为 ${profileOverride}（点击切换回自动）${intentReason ? `\n判定依据：${intentReason}` : ''}`
+ `档案已锁定为 ${profileOverride}（点击切换回自动）` + (intentReason ? `\n判定依据：${intentReason}` : '')
```

★ **That is the same program** — so the mutation survived, and the
survivor was *my mutation*, not a hole in the product. The lesson is
the one R407's battery already taught: **changing the text is not
changing the behaviour.** The mutation was rewritten to remove the
reason from the locked branch outright, and it was caught.

★ **And the Java harness caught a TEST that could not see its own
mutation.** "The published intent is the one the posture was resolved
from" asked a question about the local tree ⇒ ANSWER ⇒ the `analyst`
profile. But `classify("analyst")` is **also** ANSWER ⇒ the same
`analyst` profile. So the mutation "publish the profile's
classification instead of the prompt's" produced **the same verdict**,
the invariant still held, and the test stayed green.

⇒ The mutation *was* caught — by two other cases. But **a witness that
only fires for a coincidence is not a witness**: the case was
re-pointed at a code-change prompt (⇒ `builder`, and
`classify("builder")` is *not* a code change), so the two
classifications now differ and the case can see the difference.

★ Both of these are the same shape: **the harness is a measuring
instrument, and an instrument can be the thing that is wrong.** The
rule that follows is the one this round's batteries were built on: a
survivor is a question for the *mutation* first and for the *product*
second.

## What is still open

- **The reason is in English.** The classifier's reasons are English
  free text (`implementation verb: create the`) and the UI is Chinese.
  The verdict is the daemon's, so the renderer must not re-word it
  (R385) — but the daemon could carry a Chinese reason, or a stable
  reason CODE plus a client-side dictionary. Not done here; the
  mismatch is documented instead.
- **The capability axis is still not on the wire.** R411 added
  `requestsWorkspaceChange()` to the verdict, and it decides whether the
  turn got the write channel — but the wire carries the *posture*, not
  the axis. A user cannot see "this turn asked to change the disk".
- **The desktop's chip still has no session-scoped history.** It shows
  the CURRENT session's verdict; a user who wants "what did it decide
  three turns ago" has no place to look.
- **★ The user's profile OVERRIDE still follows the ENGINE, not the
  session.** It lives on the same shared `AppState` as the verdict, and
  R412 deliberately left it alone: it is R401's pre-existing shape, it
  errs towards **more** restriction (the safe direction), and the chip
  does not render at all without a verdict — so it is invisible until
  the new session's first turn. Making it session-scoped means giving
  the `AppState` a per-session slot, which is an R401 question.
- **R413 — the researcher's two contradictory instructions** ("web
  search first" vs "local first") is still open.

## Lessons

- **Lesson 1561 — 一个只活在事件里的判定，等于没有判定。** 事件是
  一瞬间，判定随它流过就没了；而用户问「为什么」的三个时刻（重载、
  重连、切会话）全都发生在事件之后。⇒ **任何要让人「事后看懂」的
  东西，都必须有一个能活过事件的载体。**
- **Lesson 1562 — 一套词汇，两个载体。** 事件和轮询状态用**同一套键
  名**，客户端一个解析器通吃；两处各写一套，客户端就得写两个解析器，
  然后两个解析器都会漂。⇒ **新增一个载体时，键名要从已有载体抄，
  不是另起一套。**
- **Lesson 1563 — 判定是「每会话」的，不是全局的。** 不清 ⇒ 看着 B
  会话、显示 A 会话的判定；这种错配用户**察觉不到**，因为 chip 正是
  他用来相信「当前这一轮」的那个东西。
- **Lesson 1564 — 同一个 `null` 在两个字段里意思相反。** 判定为 null =
  「还没有判定，保留上一个」；override 为 null = 「自动」，是权威答案。
  ⇒ 同一个 null 两种语义，就必须分开处理，**不能用一个「非 null 才
  赋值」的打法糊过去**。
- **Lesson 1565 — 「缺席」不是「自动」。** 老 daemon 不带
  `profileOverride` 这个键；把缺席当自动，会抹掉本客户端已经知道的
  override。⇒ **键在不在，和键的值是不是 null，是两件事。**
- **Lesson 1566 — 一个空的「判定依据：」比没有这行更糟。** 空的依据
  看起来像「daemon 没有理由」，而那是一个断言。⇒ 依据没有就整行
  不出现。
- **Lesson 1567 — 改文本 ≠ 改行为。** 变异台架抓到的第一个「存活」
  是**变异自己写错了**：把模板插值改写成字符串拼接，是同一个程序。
  ⇒ 存活先查变异，再查护栏。
- **Lesson 1568 — 见证只有在「两种实现给出不同答案」时才是见证。**
  一条断言「发布的判定 = 姿态据以解析的那个判定」，如果**输入的档案
  名恰好会被重新分类成同一个 kind**（`analyst` → ANSWER → `analyst`），
  那么「拿档案名去分类」这个变异就**看不出差别** ⇒ 断言恒真。
  ⇒ **挑见证场景时，先确认两种实现在这个场景里给出的答案不同。**
- **Lesson 1569 — 一个引擎服务很多会话 ⇒ 会话级的事实会互相泄漏。**
  daemon 把**同一个引擎**注册到新的 session id 下 ⇒ `AppState` 是共享的
  ⇒ 判定（和 override）跟着引擎走，而不是跟着会话走。
  ⇒ **渲染端清一次是清不掉的：下一次轮询就把旧判定带回来了。**
  ⇒ **会话级的真相要在 daemon 侧切断，不是在渲染端遮。**
- **Lesson 1570 — 「判定」是一个整体，不能只报一半。** 意图和档案说的
  是**同一轮**的事；而档案**故意不随切会话清除**（它是运行中父轮对子
  代理的实时限制，清掉就等于让只读父轮借子代理写盘 ⇒ R401 的洞）。
  ⇒ 只报档案不报意图，就是把**别的会话的姿态**说成这一个的 ⇒
  **没有意图 ⇒ 没有判定 ⇒ 两个都为空。**
- **Lesson 1571 — 实机台架会抓到单元测试抓不到的东西。** 本轮的跨会话
  泄漏**只有**实机跑出来才看得见：单测里的引擎没有「切会话再轮询」
  这条路径。⇒ **凡是结论依赖「时序 + 共享状态」的，都要有一次实机。**
- **Lesson 1572 — 单跑绿、全量红，通常是「清理」而不是「逻辑」。**
  本轮有一条用例单跑绿、全量构建红，报的是
  「另一个程序正在使用此文件」——SQLite 索引文件在最后一次连接关闭
  后还会被持有片刻，JUnit 删 `@TempDir` 时删不掉 ⇒ **用例因为一个跟
  判定毫无关系的文件锁而失败。**
  ⇒ **把要落盘的东西挪出 `@TempDir`，自己带重试地删。**
  ⇒ **「单跑绿」不等于「全量绿」：全量构建才是那把尺子。**
