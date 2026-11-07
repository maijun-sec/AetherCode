# R388m — the permission deadline, and ONE width rule

**Date:** 2026-11-07
**Scope:** TUI rendering accuracy + the permission-prompt contract (countdown, non-TTY deny)
**Benchmark:** Claude Code's permission prompt (2-minute countdown; non-TTY = deny-and-tell-the-model)

---

## 0. Summary

Three contracts landed, each closing a real defect found by reading the code
(not by eyeballing the screen):

| # | Contract | Defect it closes |
|---|---|---|
| 1 | **The permission DEADLINE is published by the daemon** | The client had to guess the timeout; a guess that is too short denies a call the user was about to approve |
| 2 | **The countdown ACTUALLY denies** | A card that displays "auto-denies in 1:12" and then sits there for five more minutes is a decoration, not a contract |
| 3 | **"Nobody can approve" is ONE sentence** | Three surfaces each wrote their own denial, and NONE told the model not to retry — so the model re-issued the same tool call forever |
| 4 | **ONE width rule (cells, not characters)** | The product had THREE width rules; a CJK label is twice as wide as its length, so a character-counted column ran past the frame's right border |

Files:

- NEW `aethercode-tui/src/cellWidth.ts` — the ONE width rule
- NEW `aethercode-tui/src/permissionDeadline.ts` — the ONE deadline + deny-wording contract
- NEW `aethercode-tui/scripts/test/r388m-permission-deadline.test.mjs` — 32 cases
- NEW `aethercode/aethercode-protocol/src/test/java/.../JsonRpcPermissionPrompterR388mDeadlineTest.java` — 7 cases

---

## 1. The width rule: a terminal is a grid of CELLS

### 1.1 What was wrong

The product had **three** width rules:

| Rule | Where | Unit |
|---|---|---|
| line input | `src/lineInput.ts` | **cells** (CJK = 2) |
| panel body | `src/components/PanelBody.tsx` (`fitRow`) | **characters** |
| markdown + system-prompt tables | `src/components/Markdown.tsx`, `src/prompt-formatter.ts` | **characters** |

The line input had to count cells — the cursor has to land in the right place
while the user types Chinese. The panel helpers counted characters.

So a panel whose title or path is Chinese was laid out at **half** its
intended width:

```
"名字"  →  2 characters  →  fitRow thinks 2 cells
         →  actually 4 cells on screen
         →  the row runs PAST the frame's right border
```

That is exactly the "ragged / broken dialog box" the user reports. The frame's
edge becomes a staircase and the text overlaps it.

The same defect in the **markdown table**: a Chinese cell is twice as wide as
its character count, so the column was one cell narrower than its content and
the `┃` divider drifted right by one cell per CJK row.

And in the **autocomplete dropdown**: `s.label.slice(0, width - 5)` kept HALF
the visible text of a Chinese file path and still overflowed the row.

### 1.2 The fix

`src/cellWidth.ts` is the ONE rule:

```ts
export function cellWidth(s: string): number;   // visible CELLS (ANSI stripped, CJK = 2)
export function fitCells(text, width, ellipsis?): string;  // truncate + pad to width CELLS
export function padCells(text, width): string;    // pad to width CELLS, never truncate
export function maxCells(values: string[]): number;
```

`fitRow` now delegates to `fitCells`; `lineInput.ts` deleted its private
`cellWidth`/`isWide` copies and imports the shared ones.

Every surface that lays out a **column of user-controlled labels** now pads
with `padCells` instead of `String.padEnd`:

`tui.tsx` (SDD runs, metrics, traces, trace tree, agents, snippets),
`line.ts` (permission modes), `ac-tui.ts` (key bindings), `commands.ts`
(the `/help` gutter), `HelpOverlay`, `EffortPicker`, `ThemePicker`,
`ModelSelector`, `MemoryPanel`, `UpdatePanel`, `Welcome`, `WelcomeBanner`,
`SessionDetailsPanel`.

`commands.ts` is the interesting one: the `/help` gutter was computed from
`label.length`, so a Chinese command name made the whole description column
drift right.

### 1.3 ★ A bug in the new code itself ★

The FIRST version of `fitCells` had the defect in its own padding branch:

```ts
if (cellWidth(plain) <= w) return plain.padEnd(w);   // ← CHARACTERS
```

The truncation branch was already cell-aware; the padding branch was not.
So `"名字"` (2 chars / 4 cells) padded to 12 **characters** = 14 **cells** —
a row 2 cells wider than the frame.

The gate caught it (`fitCells PADS a short row to the full width`), which is
the whole reason the gate asserts on **both** branches.

→ **Lesson 1342 — a shared helper can carry the very defect it was written to
remove.** The truncation branch was right and the padding branch was wrong,
in the same function, in the same commit. Check every branch.

### 1.4 The measurement tool was wrong too (again)

Before changing anything, the layout was measured with a terminal simulator.
The simulator itself was wrong **three times**:

1. it did not implement line wrapping → reported rows 240 and 833 cells wide;
2. `ESC[nC` (cursor forward) did not wrap at the last column;
3. `ESC[nX` (erase n cells) EXTENDED the row instead of clipping it to the
   terminal width.

Each time the instrument was the problem, not the product. After the fix:
**the visible screen is 30 rows, maxCells = 119, 0 rows exceed 120** — the
R388j width contract holds.

Separately: the conhost output was always correct UTF-8 (`e2 8c ac` = `⌬`);
the mojibake came from **PowerShell's `Get-Content`**, i.e. from the reading
step. Read files with `node`, not `Get-Content`.

→ **Lesson 1343 — "the file is mojibake" is a claim about the READER until
proven otherwise.**

---

## 2. The permission deadline

### 2.1 The race the client could not win

The permission card wants to show a countdown. Before R388m the client had to
guess the daemon's timeout, and a guess is a race it can lose in both
directions:

- **too short** → the client denies at 0:00 while the daemon is still willing
  to wait, so the user's keystroke arrives after the decision was made. The
  call the user was about to approve is denied.
- **too long** → the user watches a prompt the daemon already resolved, and
  has no idea whether their click landed.

### 2.2 The fix: the daemon publishes the deadline

`AetherCodeMethods.askPermissionWithDeadline(...)` issues the ask with a
deadline. The `permission_request` notification now publishes:

```json
{ "requestId": "…", "tool": "…", "riskLevel": "high",
  "timeoutMs": 300000, "expiresAtMs": 1760000000000 }
```

`JsonRpcPermissionPrompter` passes **its own** `timeoutMs` — one number, not
two. The client counts down on the same clock the daemon enforces.

The five-argument `askPermission` (no deadline) publishes **no** deadline, so
a client that predates R388m sees nothing new.

### 2.2.1 ★★ An OVERLOAD is a wiring difference nobody can see ★★

The first implementation added a **six-argument**
`askPermission(..., long timeoutMs)` and had the prompter call it directly.

That silently broke **every test stub that overrides the five-argument
`askPermission`**:

```
✖ lowRisk_autoApproveFalse_fallsThroughToAsker
  expected: Allow
  but was:  Deny[message=permission ask for 'file_read' timed out after 300s ...]
```

The symptom was a **timeout message**. The stub's override was never reached
— the prompter called a *different overload* — so the ask went unanswered and
the future expired. The test was measuring a timeout instead of the contract
it was written for.

This is **Lesson 1328's shape**: a shared registration point makes a wiring
difference invisible. There the difference was per-transport-layer; here it
was **per-overload**. The question to ask is not "is the method registered?"
but "**does a call on THIS TRANSPORT / THIS OVERLOAD succeed?**" — and the
only thing that answers it is a real call.

The fix keeps the ask on the **one method everyone overrides**:

```java
public CompletableFuture<PermissionDecision> askPermissionWithDeadline(
        String runId, String toolName, Map<String, Object> input,
        String reason, String riskLevel, long timeoutMs) {
    pendingDeadlineMs.set(timeoutMs > 0 ? timeoutMs : 0L);
    try {
        return askPermission(runId, toolName, input, reason, riskLevel);
    } finally {
        pendingDeadlineMs.set(0L);
    }
}
```

The deadline travels through a **read-and-clear field** that the ask
consumes, so it can never leak into a later ask.

→ **Lesson 1350 — an OVERLOAD is a wiring difference too. A stub that
overrides one overload is not reached by a call on another.**

→ **Lesson 1351 — a timeout message in a test is a SYMPTOM, not a verdict.**
"the ask timed out" reads like "the code under test is slow"; it actually
meant "the call went somewhere the stub cannot see". Read the message, then
ask **which method was actually called**.

### 2.3 ★ The countdown must ACTUALLY deny ★

A card that displays "auto-denies in 1:12" and then sits there for five more
minutes while the daemon's backstop timer runs is a **decoration**, not a
contract. The user cannot tell whether the countdown means anything.

So the client sends the deny itself:

```ts
const timer = setTimeout(() => {
  if (stateRef.current.permissionAsk?.requestId !== ask.requestId) return;
  dispatch({ type: "permissionClear" });
  void client.request("permissionResponse", permissionResponseParams(
      ask.requestId, "deny", undefined, undefined, "TUI"));
}, expiresInMs);
```

Three properties the gate pins:

1. **The guard is on the ask's identity** — a timer that fires after the
   user answered must not deny a *different* ask.
2. **Answering cancels the timer** (`cancelPermissionExpiry` in
   `replyPermission`) — one ask, one timer; and the timer does not keep the
   event loop alive.
3. **The card ticks** — `useTick` runs a 1 Hz interval only while a deadline
   is on screen. A countdown that only re-renders when something ELSE happens
   is a frozen number.

The daemon's own timeout remains the backstop. The client's deny is the
**early, informed** one: the user is told "no response for 'file_write' —
denied" rather than watching a silent stall.

### 2.3.1 ★ A 7th positional parameter was the wrong shape ★

The first implementation threaded the timer map through `replyPermission` as
a 7th argument. Two things went wrong:

- **Prettier exploded every call site into nine lines** — the decision
  handlers became unreadable.
- **It broke the R388l gate's anchor** (`replyPermission(client, ask,
  "allow"`), which turned a *harmless* refactor into a red gate. The gate
  was pinning a spelling, and the reflow moved the spelling.

The registry has exactly **one owner** (the permission flow), so a
module-level `Map` is the honest scope — a React ref would have to be
threaded through a module-level function for no benefit:

```ts
const permissionExpiryTimers = new Map<string, ReturnType<typeof setTimeout>>();
function cancelPermissionExpiry(requestId: string): void { … }
```

The call sites went back to one line, and the R388l gate went green again.

→ **Lesson 1349 — a parameter you have to thread through every call site
is a parameter that was in the wrong place.** And: when a reflow turns a
gate red, ask whether the gate was pinning the *spelling* (Lesson 1233) or
a *real* invariant.

### 2.4 The wording names the consequence

```
auto-denies in 2:05
no response — denying
```

A bare "2:05" leaves the user to guess whether it is a timeout, a clock, or a
countdown. "auto-denies in" names what happens.

`secondsLeft` **floors**: a card that rounds up shows "0:01" while there is
no time left, and the user presses a key that arrives too late.

→ **Lesson 1344 — a countdown that only DISPLAYS is not a contract.**

### 2.5 ★ Verified on a real TTY ★

The unit tests prove the logic; a real terminal proves the **render**. The
probe drives the Ink TUI through a ConPTY (`conhost --headless`), so both
`stdin.isTTY` and `stdout.isTTY` are true, and rebuilds the screen the way
a terminal does it (`ESC[nX` / `ESC[nC` / `ESC[nA` / `ESC[nB` / `ESC[nK` /
`ESC[2J` / `ESC[nG` are **cursor operations**, not text).

```
│ ⚠ PERMISSION REQUIRED · [MEDIUM] risk · auto-denies in 1:49 │
│                                                          │
│ tool: file_write                                        │
│ input: file: package.json                               │
│ reason: file_write requires approval                    │
...
```

Sample 1 read `1:53`, sample 2 read `1:49` — **the countdown ticks**, and
**0 rows exceeded 120 cells**.

### 2.5.1 The demo needed a deadline too

The first probe showed the card rendering perfectly and **no countdown** —
which was *correct*: `/demo` dispatches a synthetic ask with no
`expiresAtMs`, and the rule is "never guess a deadline".

But that made the feature undemonstrable, and the demo exists to
demonstrate the surfaces. So the demo's ask now carries a deadline from the
ONE constant, and the demo waits for the countdown window plus slack
(the old 60 s ceiling was *shorter* than the 120 s countdown, so the demo
would have cut the countdown short).

→ **Lesson 1352 — "honest" and "demonstrable" are both requirements. A
demo that renders the honest absence of a feature demonstrates nothing.**

### 3.1 What was wrong

Three surfaces deny a permission ask when no human can answer it:

| Surface | Old reason | Tells the model not to retry? |
|---|---|---|
| headless `--print` (`ac-tui.ts`) | `"headless --print mode: stdin not a TTY, defaulting to deny"` | **no** |
| line renderer, stdout not a TTY (`line.ts`) | *(none — a bare `{ decision: "deny" }`)* | **no** |
| daemon, no client (`JsonRpcPermissionPrompter`) | `"no JSON-RPC connection"` | **no** |

The model's default reaction to a denial is to **try again**. A retry against
a permission wall fails identically — forever. The classic symptom: the run
never ends, `loopStops = 0`, `errorRate = 0.89`, `turnsCompleted = 0`.

None of the three reasons said the two things the model needs:

1. **nobody can approve** (not "no response" — is the client slow? is the
   daemon broken?), and
2. **do not retry** — plus a way forward, or the model has nothing to do but
   retry.

### 3.2 The fix

`src/permissionDeadline.ts` owns the sentence:

```ts
export function nobodyCanApproveReason(tool: string, surface: string): string;
export function nobodyCanApproveNotice(tool: string, surface: string): string;
```

The reason reads:

> permission denied: no one can approve the 'file_write' call in this session.
> The 'headless --print mode' surface has no interactive user, so the ask was
> answered automatically with deny. DO NOT retry this tool call — the answer
> will be the same every time. Either (1) tell the user what you wanted to do
> so they can re-run it with permission, or (2) pick a different tool or
> approach that does not require permission.

The daemon's own no-client denial uses the same shape (in Java, where the
TS module cannot be imported — the two are pinned by the gate to agree on
the load-bearing phrases: "no one can approve" / "DO NOT retry").

### 3.3 The reply carries the reason

`permissionResponseParams(...)` gained an optional `reason` that **overrides**
the default audit trail:

```ts
reason: reason && reason.trim() ? reason.trim() : `${renderer} picked ${decision}`
```

Without it the model saw only `"line mode picked deny"` — which does not tell
it whether a retry could ever work.

→ **Lesson 1345 — "nobody can approve" is a SENTENCE, and a sentence with
three copies is three sentences.**

---

## 4. The gates

### 4.1 `r388m-permission-deadline.test.mjs` — 32 cases

Covers: the cell arithmetic (ASCII / CJK / emoji / ANSI), `fitCells` on BOTH
branches, `padCells` never truncating, the panel / line-input / markdown /
prompt-formatter surfaces, **no `padEnd` left on a user-controlled string**,
the autocomplete's cell-aware truncation and terminal-derived width, the
deadline parsing (and the "no deadline → no countdown" rule), the floor, the
wording, the daemon publishing, the client denying, the timer cancellation,
the identity guard, and the three deny surfaces.

### 4.2 `JsonRpcPermissionPrompterR388mDeadlineTest` — 7 cases

Real round trips: the deadline is published, it equals the prompter's own
timeout, no deadline is published for the legacy overload, the no-client
denial says "no one can approve" + "DO NOT retry" + names the tool, and the
timeout denial says "DO NOT retry".

### 4.3 ★ Anchoring on the CONSTRUCT ★

The first version of the gate used proximity regexes:

```js
assert.match(tui, /expiryTimersRef[\s\S]{0,2000}?"permissionResponse"/);
```

Two problems: it goes **red on a reflow** (a rename that makes the code
better), and it stays **green** when the deny is moved into an unrelated
function 1500 characters away. The gate now extracts the **block** that
arms the timer and asserts on the block:

```js
const armBlock = blockAfter(tui, "if (deadline)");
assert.match(armBlock, /"permissionResponse"/);
assert.match(armBlock, /"deny"/);
```

→ **Lesson 1346 — a contract check must be anchored on the CONSTRUCT, not on
a string that also appears in prose.**

---

## 5. Lessons

- **Lesson 1339 — a column width computed from `.length` is a width in the
  wrong unit.** A terminal is a grid of CELLS. CJK = 2.
- **Lesson 1340 — a countdown that only DISPLAYS is not a contract.**
- **Lesson 1341 — "nobody can approve" is a sentence, and a sentence with
  three copies is three sentences.**
- **Lesson 1342 — a shared helper can carry the very defect it was written
  to remove.** `fitCells`'s truncation branch was cell-aware and its padding
  branch was not — same function, same commit. Check every branch.
- **Lesson 1343 — "the file is mojibake" is a claim about the READER until
  proven otherwise.** The conhost output was correct UTF-8; PowerShell's
  `Get-Content` was the mojibake.
- **Lesson 1344 — a countdown that floors is honest; one that rounds up
  shows time that is not there.**
- **Lesson 1345 — the model needs to be told TWO things when a permission
  wall denies a call: nobody can approve, and do not retry.** Without the
  second one, the retry loop is the expected outcome.
- **Lesson 1346 — a contract check must be anchored on the CONSTRUCT, not on
  a string that also appears in prose.** A proximity regex goes red on a
  reflow and green on a relocation.
- **Lesson 1347 — the instrument was wrong three times before the product
  was.** Each round of "the layout is broken" turned out to be a bug in the
  terminal simulator (no wrapping, `ESC[nC` past the edge, `ESC[nX`
  extending the row). The product had been correct all along.
- **Lesson 1348 — one number, not two.** The deadline the client counts
  down MUST be the deadline the daemon enforces; publishing a window and
  enforcing a different one is a race the client cannot win.
- **Lesson 1349 — a parameter you have to thread through every call site
  is a parameter that was in the wrong place.** The timer map as a 7th
  positional argument made every call site nine lines AND broke the R388l
  gate's anchor. The registry has one owner, so it is module-level.
  And when a reflow turns a gate red, ask whether the gate was pinning the
  *spelling* (Lesson 1233) or a *real* invariant.
- **Lesson 1350 — an OVERLOAD is a wiring difference too.** A stub that
  overrides one overload is not reached by a call on another. The symptom
  was a timeout message, not a compile error.
- **Lesson 1351 — a timeout message in a test is a SYMPTOM, not a verdict.**
  "the ask timed out" reads like "the code under test is slow"; it actually
  meant "the call went somewhere the stub cannot see". Read the message,
  then ask **which method was actually called**.
- **Lesson 1352 — "honest" and "demonstrable" are both requirements.** A
  demo that renders the honest absence of a feature demonstrates nothing.
  The demo's synthetic ask now carries a deadline from the ONE constant —
  and its wait window was lengthened, because a 60 s ceiling is shorter
  than a 120 s countdown and would truncate the very feature on display.

---

## 6. What was NOT done

- The Claude Code **`/resume` selector** (↑/↓, 1–9, Space preview, Ctrl+R
  rename, Ctrl+A/W/B filters) — a separate round.
- The **checkpoint / rewind menu** (`Esc Esc` → Restore code and
  conversation / …) — a separate round.
- **Literal-text mode indicators** (`⏸ manual mode on`) — a separate round.
- **"Yes, and switch to auto mode"** — a separate round.
- The `CompletionDropdown` still truncates the **description** to the room
  left after the label; it is cell-aware now, but the label/description split
  is a layout decision, not a width bug.
- The daemon's 5-minute timeout was left as it is. The client's 2-minute
  countdown is EARLIER than it, so the client always wins the race and the
  user gets a truthful message rather than a bare timeout.
