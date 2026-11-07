# R388h — The TUI should look like the desktop's centre panel, not like a log

> Round: R388h
> Date: 2026-11-06
> Touches: `aethercode-tui/src/components/Scrollback.tsx`,
> `aethercode-tui/src/components/InputBox.tsx`,
> `aethercode-tui/src/components/StatusBar.tsx`,
> `aethercode-tui/src/tui.tsx`,
> `aethercode-tui/scripts/test/r168-multiline-input.test.mjs`,
> `aethercode-tui/scripts/test/phase-6-t6-14.test.mjs`,
> `aethercode-tui/scripts/test/r371-context-meter.test.mjs`,
> `aethercode-tui/scripts/test/t441-statusbar-contextmeter.test.mjs`

---

## The ask

> "我觉得并不好看，其实更希望展示成类似于 desktop 中间那部分的展示的效果。
> 我遇到过好几款产品，都是做成那种效果的"

"I don't think it looks good. I'd rather it look like the desktop's
middle section. I've used several products that all do it that way."

The reference is not "prettier chrome". It is a **shape**. The desktop's
centre panel (`aethercode-desktop/src/components/ChatView.tsx`) is
literally two things:

```tsx
<MessageList />   {/* flex: 1, scrollable, flat */}
<MessageInput />  {/* pinned to the bottom */}
```

A conversation that reads as a **document**, with the input as the only
framed element on screen. Claude Code, Cursor and Windsurf all do this:
the transcript is the page, and the input is a pill floating at the
bottom.

## What the TUI was doing instead

The old TUI main view was a **dashboard**: an ASCII wordmark box, a
shortcuts grid, a `│` gutter on every turn, a timestamp on every turn,
a boxed transcript, a `────` rule above the input, and a status block
that occupied three lines at the bottom.

None of that is wrong on its own. Together it reads as a *log*, and a
log is not a conversation.

## The rules the redesign holds

### 1. The markdown IS the UI

No frame around the transcript. No gutter column. No per-line
timestamp. The desktop's `.agent-message` is a plain `<div>` with
typography; the assistant's prose **is** the surface.

### 2. The reading column is narrow and centred

The desktop caps the transcript at `max-width: 820px` and centres it.
A terminal is 80–200 columns wide; a full-width transcript at 200
columns is unreadable.

```
CHAT_COLUMN_WIDTH = 96;   // the terminal analogue of 820px
COLUMN_INSET = 2;
```

### 3. The user turn is a speech act, not a log row

The desktop right-aligns the user's bubble. A terminal cannot
right-align reliably, so the user turn gets a distinct `▌` accent bar
— the visual equivalent of "that was you".

### 4. The empty state is ONE line

The desktop shows a single centred italic "Start a conversation…". The
TUI showed a boxed ASCII wordmark plus a shortcuts grid — a wall of
chrome before the user had said anything.

Implementation note: Ink's `alignItems="center"` is unreliable inside
an unbounded column, so the centring is computed by hand — the label is
padded with a `centre()` helper that works out the left inset from the
terminal width.

### 5. Banners stay boxed

The stop banner and the error card **keep** their borders. A run that
died silently is the worst outcome, and those two must be unmissable.
Boxed exceptions are exactly why they read as exceptions.

## The status strip

Three changes, all in service of "one line":

| before | after |
|---|---|
| `Continue [c] Pause [p] Stop [s]` rendered **always** | rendered **only while a run is live** (`state.submitting \|\| state.continuationPaused`) |
| context meter on its **own** row (`width={24}`) | a compact `ctx [░░░░░░░░░░]` chip **inline** (`width={10}`) |
| `space-between` between the two halves | an explicit `·` separator |

Two of those are lessons:

- **Lesson 1305** (`space-between` is not a separator). Once the meter
  moved onto the status row, a long left half ("running tool… · 1 tool
  call in flight") pushed the two boxes together and the text read as
  `in flightctx[░░░░░]`. `space-between` only inserts space *while
  there is room*. A separator can never be squeezed out.
- **The run-control row was three buttons for actions that would all
  do nothing.** The desktop shows its run controls only while a run is
  live; the TUI spent a whole line on them at idle.

## The input

- A **rounded pill** (`borderStyle="round"`), matching the desktop's
  `MessageInput.css`.
- The `────` rule above it is **gone** — the pill is the frame.
- The completion dropdown moved **above** the pill (it would otherwise
  be clipped by the bottom edge).
- A `↵` send affordance on the right, dimmed when there is nothing to
  send.

## The gates

Four gates pinned the old shape. They were **updated to pin the
intent**, not deleted and not weakened — a pin on a location goes red
on any refactor, including one that improves the code (Lesson 1233).

| gate | before | after |
|---|---|---|
| `r168-multiline-input` | "InputBox no longer uses borderStyle" | "InputBox is a rounded **PILL**" (`borderStyle="round"` + `↵`) |
| `r168-multiline-input` | "▌ accent + thin `─` rule" | "InputBox uses a left-edge `▌` accent" — and asserts the `─` rule is **gone** |
| `phase-6-t6-14` | pinned `marginTop={1}`'s spelling | asserts **order**: the footer index is after the markdown index |
| `r371-context-meter` | pinned `width={24}` | asserts the **intent**: `width` is explicit and `≤ 24` |
| `t441-statusbar-contextmeter` | pinned `width={24}` | asserts the **intent**: an explicit numeric width that is `≤ 24` |

The last one was the only gate that went red during this round, and it
went red for the right reason: the meter genuinely moved from a
dedicated row onto the status row.

## Verifying the renderer

To prove the TUI picked **Ink** and not line mode, the frame has to be
captured under a real ConPTY TTY:

```
conhost --headless -- cmd /c node <bundle> --jar <jar> --tui --cwd <cwd>
```

with the child's stdio as pipes, so the ConPTY output is continuously
drained — a redirect to a file deadlocks, because the ConPTY blocks once
its buffer fills.

The captured frame:

```
AetherCode v0.2.1
⌬ MiniMax-M3 ·  ASK                            default · ● · 14s
 ────────────────────────────────────────────────────────────
  ↳ .../engine/RAG Start a conversation…
                                     MiniMax-M3 · engine/RAG
 ╭──────────────────────────────────────────────────────────╮
 │ ▌ › type a prompt — Enter to send, … Tab to accept, Ctrl-? for help▍↵ │
 ╰──────────────────────────────────────────────────────────╯
 ────────────────────────────────────────────────────────────
 ▌ ● ✓ ready   · ctx [░░░░░░░░░░] · in — · out — · — · 14s · jar aethercode-cli-0.1.0-SNAPSHOT.jar
```

## Lessons

- **Lesson 1233** — a gate that pins a LOCATION goes red on a
  REFACTOR. Update it to pin the intent, and record why.
- **Lesson 1305** — `space-between` is not a separator; it is a hint
  that there is room. Use an explicit separator when the row can fill.
- **Lesson 1306** — a UI element that is only meaningful in one state
  should be rendered only in that state. Three buttons for actions that
  would all do nothing is a line of chrome, not a feature.
- **Lesson 1307** — "the reference product" is a SHAPE, not a style.
  The user said "like the desktop's middle section"; the desktop's
  middle section is two components. Copying its *shape* (flat
  transcript + pinned pill) beats copying its *pixels* (a 820px cap
  means 96 columns here, not 820).
