# R388l — the permission comment reaches the model

**Round:** R388l
**Date:** 2026-11-07
**Reference:** Claude Code's permission prompt (per the R388k audit, Appendix A)
**Touches:**
- `aethercode-tui/src/permissionComment.ts` (new)
- `aethercode-tui/src/tui.tsx`, `src/components/InputBox.tsx`, `src/line.ts`
- `aethercode-tui/src/state.ts`, `src/componentKeys.ts`
- `aethercode-core/.../permission/PermissionResult.java`, `.../engine/StreamingToolExecutor.java`
- `aethercode-protocol/.../permissions/JsonRpcPermissionPrompter.java`, `.../methods/AetherCodeMethods.java`

---

## 1. What the R388k audit pointed at

R388k's Appendix B listed ten Claude Code behaviours worth copying. Number 3
was:

> **Attach a free-text comment to the approval.** `Tab` on Yes/No opens a
> comment field; the comment reaches the model as the reason for a denial.
> A boolean gate becomes a communication channel.

This round implements it — on **both** renderers, and end to end through the
daemon.

## 2. What we already had, and where the hole was

The wire already carried a `reason`:

```
permissionResponse { requestId, decision, reason }
```

and the **deny** path already carried it to the model:

```
reason → PermissionDecision.reason
       → PermissionResult.Deny.of(reason)
       → Event.Completed(call.id(), d.message(), true)   ← the tool_result
```

So on a denial the reason was already model-facing. But the **approval**
path threw it away:

```java
// JsonRpcPermissionPrompter.thenApply
if (decision.isAllow()) {
    return new PermissionResult.Allow(input);   // ← the reason is GONE
}
return PermissionResult.Deny.of(decision.reason());
```

`PermissionResult.Allow` had no field to put it in — the record was
`Allow(Map updatedInput)`.

### The shape of the defect

A user who wrote **"yes, but write to /tmp first"** would have had their
comment reach the daemon and then **evaporate**. The user believes they said
something; the model never sees it.

That is the silent-degradation shape (Lesson 1300): the feature *looks*
present on the client, and the thing the user believes they did simply does
not happen.

### Why the wire needs TWO fields, not one

The obvious shortcut is to reuse `reason`. That is wrong:

| field     | whose voice            | example                          |
| --------- | ---------------------- | -------------------------------- |
| `reason`  | the **machine's**      | `TUI picked deny`                |
| `comment` | the **user's own**     | `no — use the read-only tool`    |

If they were one field, the executor would have to present `"TUI picked
deny"` to the model *as if the user had said it* — or the user would have to
type boilerplate to get their words through. Both are wrong.

So R388l adds `comment` as a **separate, optional** field:

```java
public record PermissionDecision(String decision, String reason, String comment) {
    public PermissionDecision(String decision, String reason) {   // pre-R388l shape
        this(decision, reason, null);
    }
    public String comment() {
        return comment == null || comment.isBlank() ? null : comment;
    }
}
```

and `PermissionResult.Allow` gains the same treatment:

```java
public record Allow(Map<String, Object> updatedInput, String note) implements PermissionResult {
    public Allow(Map<String, Object> updatedInput) { this(updatedInput, null); }  // pre-R388l shape
    public String note() { return note == null || note.isBlank() ? null : note; }
}
```

★ **Both records keep their old constructor.** 15 production and ~40 test
call sites of `new PermissionResult.Allow(input)` keep compiling and keep
their meaning ("approved, nothing to say"). Adding a component to a Java
record is source-compatible for the two-argument form; keeping the
one-argument overload is what makes the *whole* tree compatible.

## 3. Where the comment lands

### On a denial

The comment is the headline, because the denial message IS what the model
reads:

```
user said: use the clean target instead (denied by client: line mode picked deny)
```

The user's words first; the mechanical reason after, clearly attributed.

### On an approval

The comment rides on the **tool_result**, next to the tool's own output —
the only place the model will read it:

```
file written

[user's note] but keep the original header
```

`StreamingToolExecutor.appendApprovalNote(output, note)` does this in ONE
place, used by **both** execution paths (the queue-based one and the
sink-based one). A comment that reaches one path but not the other is a
comment that reaches the model *sometimes*.

## 4. The client contract

### Ink (the TUI)

The permission card is INLINE in the scrollback and the input box stays live.
So the comment box is a **mode of the input box**, not a modal:

| key (ask pending)       | behaviour                                        |
| ----------------------- | ------------------------------------------------ |
| `A`/`T`/`P`/`U`/`D`/`N` | reply immediately (**unchanged** — muscle memory) |
| `Tab`                  | open the comment box                             |
| *(in the box)* chars    | append to the comment                            |
| *(in the box)* Backspace| delete the last char                             |
| `Ctrl+A/T/P/U/D/N`      | **arm** the decision the comment is sent with    |
| `Enter`                 | send the armed decision + the comment            |
| `Esc`                   | close the box, ask stays **pending**             |

Three decisions worth stating:

**The decision keys are SUSPENDED while the box is open.** Otherwise typing
"d" would deny instead of writing the letter d. The comment branch sits
*before* the decision branch in the handler and returns.

**`Ctrl+<key>` arms the decision** rather than the plain letter, because the
plain letters are text while the box is open. The armed decision is shown in
the box header, so the user sees what they are about to send.

**The default armed decision is DENY.** A user who opens a comment box is
almost always saying "not exactly this" — and a silent default that *allows*
would be the dangerous direction to be wrong in.

**`Esc` cancels WITHOUT answering.** The ask stays pending. A cancelled
comment must not be indistinguishable from an answer.

### Line mode (cmd.exe)

Same contract, and the shape is closer to Claude Code's because the line
picker already has a ↑/↓ option list:

| key                | behaviour                                  |
| ------------------ | ------------------------------------------ |
| `↑`/`↓`, `1`-`4`   | pick the option                            |
| `Tab`              | open the comment field (the option is armed)|
| chars / Backspace  | edit the comment                           |
| `Enter`            | send option + comment                      |
| `Esc`              | back to the option list, ask still pending |

Both renderers build their reply params with the **same** function
(`permissionResponseParams` in `src/permissionComment.ts`), so two renderers
cannot hand-roll two wire shapes.

## 5. The wire

```ts
permissionResponseParams(requestId, decision, scope, comment, renderer)
// → { requestId, decision, reason: "TUI picked deny" }
// → { requestId, decision, reason: "TUI picked deny", comment: "use the clean target instead" }
```

★ **An empty comment is OMITTED, not sent as `""`.** A reply with no comment
must produce byte-identical params to the pre-R388l reply, so a daemon on the
old wire sees nothing new.

## 6. The gates

Two gates, because **a client-side gate cannot stand in for a daemon-side
one**:

| gate | proves |
| ---- | ------ |
| `scripts/test/r388l-permission-comment.test.mjs` (19 cases) | the client collects a comment, puts it on the wire, suspends the decision keys, cancels without answering — AND the daemon carries it |
| `aethercode-protocol/.../JsonRpcPermissionPrompterR388lCommentTest.java` (11 cases) | the daemon's real round trip: `permissionResponse` → `PermissionDecision` → `PermissionResult` → the note on the tool_result |

The TUI gate also asserts the **daemon** side (the prompter carries the
comment; the executor appends it), because a client that sends a comment the
daemon drops is a comment that reaches the model never.

### The mutation battery

`scripts/test/r388l-mutate.mjs` (NOT part of `npm test`) breaks one thing at
a time and requires the gate to go red. **10/10 caught**, including the
original defect:

- the daemon drops the comment on the approval branch ← *the original defect*
- the daemon never reads the comment off the wire
- the executor stops appending the note to the tool_result
- the Ink app lets the decision keys fire while the comment box is open
- Tab no longer opens the comment box
- the InputBox swallows the comment (the `decisionPending` guard wins)
- the line picker never opens a comment field
- the wire always sends a comment field, even when empty
- the comment is folded into the reason (boilerplate becomes the user's voice)
- `Esc` in the comment box also answers the ask

### ★ The battery found a hole in the GATE, not just the product ★

The "line picker never opens a comment field" case survived the first
version of the gate. The reason was a **mutation** bug, and it is worth
recording because the same shape will recur:

- the mutation was `s.replace('        if (c === "\\t") {', '        if (false) {')`
- there are **TWO** Tab handlers in the picker: the one that OPENS the
  comment field, and the one INSIDE the comment mode (a no-op, so the user
  cannot get stuck)
- the opening one is indented 8 spaces; the no-op one is indented **10** —
  and **10 spaces CONTAINS the 8-space pattern as a substring**, so
  `String.replace` hit the *no-op* handler
- the real opening handler was untouched → the gate stayed green → correct,
  but the mutation had proved nothing

The fix was to anchor the mutation on what the handler **does**
(`commentMode = true`) rather than on the text of its condition, and to
scope the gate's assertion to the part of the picker body *after* the
comment-mode block.

→ **Lesson 1322 — in a big block, searching for a label can find a
neighbouring construct's label.** And the corollary, which cost the same
round: **a mutation that changes the wrong occurrence is a no-op in
disguise** — and a no-op mutation reports "the gate is weak" when the gate
is fine.

## 6.1 ★ The EXISTING gates caught four more defects ★

This round's own gate was green on the first run — and four real defects
were still there, found by the gates that were **already** in the tree:

1. **The key-binding registry could not see `Ctrl+A`.** The registry
   derives its chords from the app handler by scanning for a letter
   compared against the input inside a Ctrl window. The handler wrote
   `armDecision(box, input)` — a bare `input`, invisible to the scanner.
   The help would then have promised Ctrl+A / Ctrl+D / … that the registry
   could not see bound. The handler now spells the chords out, and the
   registry documents them with a `when` clause ("a permission comment box
   is open") — the same shape the existing `d` binding uses.

2. **A phantom `\t` chord.** The handler had `key.tab || input === "\t"`.
   The scanner turns every `input === "<literal>"` into a chord token, so
   the literal became a **second** chord beside the real `tab` — and the
   help owed the user a binding nobody documented. `key.tab` alone is what
   the app handler already uses elsewhere.

3. **The InputBox hand-wrote a key hint.** The comment box's header spelled
   out "Ctrl+A/T/P/U/D/N to change · Enter send · Esc cancel" as a literal
   string. The component registry forbids that (a hand-written hint drifts
   from the handler); the text now comes from `componentKeys.ts`.

4. **A comment that quotes code reads as code.** The registry's scanner
   reads the **raw** source — comments included. Two explanatory comments
   in the handler quoted the comparison they described, and the scanner
   picked them up as two more unbound chords. The comments were reworded.

→ **Lesson 1327 (again) — the existing bidirectional gates catch what your
own new gate cannot see.** A new feature touches the keyboard, and the
keyboard has FOUR registries that must agree: the keybinding registry, the
component-key registry, the panel-intent registry and the command catalog.
A new gate that only checks the new feature's own module sees none of
that.

→ **Lesson 1335 — a scanner that reads the raw source reads your COMMENTS
too.** A comment that quotes the code it describes becomes a second
occurrence of that code. When a scanner is the source of truth, the prose
around the code is part of the input.

## 7. New lessons

**Lesson 1329 — a gate that only checks the CLIENT proves the comment is
sent, not that it arrives.** The client and the daemon are two systems; a
comment can die at the boundary between them. Both ends need a gate, and
neither is allowed to stand in for the other.

**Lesson 1330 — an approval that carries no note is a silent drop.** The
permission path had a `reason` on the wire and a place to put it on the
DENY branch, and nowhere to put it on the ALLOW branch. "The field exists"
is not "the field is used" (Lesson 1230, one layer up: the approval branch
*dropped* the reason it had been given).

**Lesson 1331 — the machine's reason and the user's words are different
things and must not share a field.** `reason` is the audit trail; `comment`
is the human sentence. One field would force the executor to present
boilerplate as the user's voice, or force the user to type boilerplate.

**Lesson 1332 — a mode whose keys are hidden is a mode the user cannot
use.** The comment box names its control keys in its own header, and the
armed decision is visible before the user sends. The state of a pending
decision is not a private implementation detail.

**Lesson 1333 — a mutation that changes the wrong occurrence is a no-op in
disguise.** `String.replace` with a string pattern replaces the FIRST
match, and an 8-space pattern matches inside a 10-space indentation. The
mutation reported a hole in the gate that was not there; the real hole was
in the mutation.

**Lesson 1334 — the default of a "modify the answer" affordance must be the
SAFE direction.** A comment box defaults to DENY, because a silent default
that ALLOWS is the dangerous direction to be wrong in.

**Lesson 1335 — a scanner that reads the raw source reads your COMMENTS
too.** The key-binding registry derives its chords from the app handler by
scanning the raw file — comments included. Two explanatory comments that
quoted the comparison they described became two more "unbound chords". When
a scanner is the source of truth, the prose around the code is part of the
input.

**Lesson 1336 — a chord the registry cannot SEE is a chord the help cannot
promise.** The handler resolved the decision key through a function
(`armDecision(box, input)`), which is the right design — but the registry
learns the chords by looking for explicit comparisons, so it saw nothing.
The registry is derived FROM the handler, so the handler has to be readable
to it: spell the chords out, and let the registry document them.

**Lesson 1337 — a jar that is the same size after a rebuild is a jar that
was not rebuilt.** The shade plugin's staleness check left an artifact from
an older build configuration in place; three `mvn package` runs each moved
its mtime and changed nothing. The tell was the `original-…jar` (the
PRE-shade jar) being the same size as the "shaded" one.

**Lesson 1338 — "BUILD SUCCESS" does not name WHICH artifact.** The build
succeeded; the file that was checked was a different file from the one that
was shipped. Verify the artifact's **CONTENT** — `javap` the class out of
the jar and look for a string only the new code has — not just its
timestamp.

## 8. What is NOT done

- The **desktop** renderer has no comment box yet. The daemon side is
  renderer-independent (the wire carries `comment`), so the desktop only
  needs to send the field — the daemon work is already done.
- The `Ctrl+<key>` arming in the Ink renderer has no equivalent in the
  desktop.
- Claude Code's **"Yes, and switch to auto mode"** (one keystroke to approve
  AND upgrade the mode) is still not implemented. It was R388k's item 4, and
  it remains a discoverability gap rather than a capability gap.

## 9. Verification

### The gates

| gate | cases | what it proves |
| ---- | ----- | -------------- |
| `scripts/test/r388l-permission-comment.test.mjs` | 21 | the client collects, suspends, arms, sends, cancels; AND the daemon carries it |
| `scripts/test/r388l-mutate.mjs` | 13 mutations, **13/13 caught** | the gate can go red |
| `JsonRpcPermissionPrompterR388lCommentTest` | 11 | the daemon's real wire round trip |
| `StreamingToolExecutorR388lApprovalNoteTest` | 2 | the note is on the tool_result the MODEL reads |

The chain has **three** owners and each one has its own test:

```
client collects  →  daemon carries  →  executor shows
   (TUI gate)        (protocol test)     (core test)
```

A test of the first two says nothing about the third.

### The suites

| suite | result | baseline |
| ----- | ------ | -------- |
| Java (core + sdk + protocol + cli + workflows) | **874** tests, 0 failures | 861 (+13) || TUI (`node --test scripts/test/*.test.mjs`) | **1598** tests, **44** failures | 1577 / 44 (**+21 tests, +0 new failures**) |
| Desktop (`vitest run`) | **1410** passed, 1 failed | 1410 / 1 (same pre-existing failure) |
| TUI typecheck | 0 errors | — |
| prettier (`src/**`, `scripts/**`) | clean | — |

★ **The 44 TUI failures are the pre-existing baseline**, byte for byte. The
diff against the previous round's run is:

```
=== NEW failures ===   (none)
=== FIXED ===          5
  every chord the handler binds is DOCUMENTED in the registry
  every GLOBAL chord a component shows is really offered by the app
  every key a prose sentence names is really offered
  every TUI source file is prettier-clean
  no REGISTERED component hand-writes a key hint
```

Those five were **real defects this round introduced** and the existing
gates caught them — see §6.1.

### The shipped package

Both release directories are in sync and MD5-verified:

| file | bytes | MD5 |
| ---- | ----- | --- |
| `ac-tui.js` | 2,377,672 | `3E0F8A291C1102741EE0153D3695DA7D` |
| `aethercode-cli-0.1.0-SNAPSHOT.jar` | 56,907,685 | `B32FD50EED2F257B1A1DC3F202052B47` |
| `README.md` | 10,183 | `7BE03081C9D955CD8FD410611BE43ACD` |

### ★ A real TTY, not a mock ★

The Ink TUI was driven inside a real ConPTY (`conhost --headless`), through
the `/demo` flow (which emits a REAL permission ask), and the screen was
rebuilt the way a terminal rebuilds it. All six steps passed:

```
OK   ready
OK   permission card
OK   comment box
OK   comment typed
OK   armed allow
OK   control keys named
```

The captured screen (the box-drawing characters come through as mojibake
because the capture reads UTF-8 as GBK — the R388j lesson about measuring
a terminal still applies):

```
✎ comment to the model · allow once · Ctrl+A/T/P/U/D/N to change what it is
sent with, Enter to send, Esc to cancel

▌ ✎ use the clean target instead
```

`allow once` in the header is the proof that `Ctrl+A` armed the decision —
the header follows the armed decision, so the user sees what they are about
to send before they send it.

### ★ A build trap worth recording ★

The shaded jar's output is `aethercode-cli-0.1.0-SNAPSHOT.jar` — **not**
`…-shaded.jar`. A `…-shaded.jar` file was sitting in `target/` from an
older build configuration, and the shade plugin's staleness check left it
alone: three successive `mvn package` runs produced a jar with the same
size and the same **stale** classes, and the jar's mtime moved every time.

The tell was that `original-aethercode-cli-0.1.0-SNAPSHOT.jar` (the
PRE-shade jar) was the same size as the "shaded" one — impossible for a
real shade, which grows the jar by ~57 MB.

→ **Lesson 1337 — a jar that is the same size after a rebuild is a jar that
was not rebuilt.** And the check is cheap: `javap` the class out of the jar
and look for a string only the NEW code has (`"user said"` here).

→ **Lesson 1338 — "BUILD SUCCESS" does not name WHICH artifact.** Say the
file name, and verify the artifact's CONTENT, not just its timestamp.
