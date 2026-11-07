# R388g — The TUI was dying on its own, and the transcript was a dashboard

> Round: R388g
> Date: 2026-11-06
> Touches: `aethercode-memory/src/cli.ts`, `aethercode-tui/src/components/Scrollback.tsx`,
> `aethercode-tui/src/components/InputBox.tsx`, `aethercode-tui/src/components/StatusBar.tsx`,
> `aethercode-tui/scripts/test/r388g-memory-cli-bundle.test.mjs`

---

## Part 1 — "还是进入不了" (still can't get in)

### The symptom

The user reported, twice, that the TUI would not get past connecting. The
status bar showed `connecting…` and the jar name was empty. A frame
captured under a real ConPTY showed `✓ ready` — so the connection state
*did* update — but `jar` was still `—`, and the process was gone a second
later.

The earlier round (R388f) had "fixed" this by adding a state replay to the
JSON-RPC client: the client is started *before* React mounts, so the
`connecting` / `connected` events fired before anybody was listening. The
replay made the status bar say `✓ ready`. **It did not make the TUI work** —
it made the TUI look like it was working for the one second before it died.

### The investigation

Eleven measurements, in order:

| # | Measurement | Result |
|---|---|---|
| 1 | `client.jarPath` | **non-empty** — the client was constructed correctly |
| 2 | `client.connectionState()` replay | `{"state":"connected"}` — the replay worked |
| 3 | `getState` over stdio, fresh client | **OK** — the daemon answered |
| 4 | `getState` right after `ping` | **OK** |
| 5 | `getState` as the *first* request | **OK** |
| 6 | `getState` after a 5 s delay | **OK** |
| 7 | Raw `spawn` + stdin write | **OK** — the daemon answered both requests |
| 8 | Two requests back-to-back in one tick | **OK** — and the daemon answers **out of order** |
| 9 | `jstack` of the daemon | reader thread blocked in `System.in.read()`, rpc workers **idle** |
| 10 | `getState` **before** `render()` | **OK** |
| 11 | `getState` **after** `render()` | **HUNG** — sent, never answered, `writableLength` stuck |

Measurement 11 was the fork. The same request, on the same client, over
the same pipe, worked before `render()` and hung after it.

### The bisect

Replacing the real `<App/>` with a trivial `<Box/>` made everything work.
Adding `useInput` to the trivial component: still worked. Adding a
`client.request()` from a `useEffect` in the trivial component: still
worked.

So it was the App's *content* — and, more precisely, the App's **mount
effects**, which import `aethercode-memory`.

### The root cause

`aethercode-memory/src/cli.ts` ends with:

```ts
if (isDirectInvocation()) {
  main().then((code) => { process.exit(code); });
}
```

and `isDirectInvocation()` was:

```ts
const invoked = fileURLToPath(import.meta.url);
if (process.argv[1] === invoked) return true;
return realpathSync(process.argv[1]) === realpathSync(invoked);
```

The TUI ships as ONE esbuild bundle (`dist/ac-tui.js`) and imports the
memory package's **library** surface. `index.ts` re-exports `./cli.js`, so
esbuild **inlines the CLI module** into the bundle — and inside a bundle,
`import.meta.url` is the **bundle's** url.

When the user runs `node dist/ac-tui.js`, `process.argv[1]` **is** that
same path. So the guard returned **true**, the memory CLI's `main()` ran
with the TUI's argv, found no recognised subcommand, and called
**`process.exit(2)`** — killing the TUI about a second after it painted its
first frame.

The `jar —` was empty because the process exited before the `getState`
reply could be applied.

**Lesson 1302 — a module URL is not an identity. Inside a bundle every
module shares the bundle's URL, so "is `import.meta.url` equal to
`argv[1]`?" cannot distinguish "the user ran me" from "some other program
imported me". The discriminator has to be about the FILE — compare
basenames.**

### The fix

`aethercode-memory/src/cli.ts` now refuses to auto-run unless its own file
is named like the CLI entry point (`cli.js` / `cli.mjs` / `ac-mem`):

```ts
const invokedName = basename(invoked).toLowerCase();
const isCliEntry =
  invokedName === "cli.js" || invokedName === "cli.mjs" || invokedName === "ac-mem";
if (!isCliEntry) return false;
```

A bundle named `ac-tui.js` is not the CLI, whatever its argv says.

### The gate

`aethercode-tui/scripts/test/r388g-memory-cli-bundle.test.mjs` — three
assertions, and **all three go red on the pre-fix code**:

1. **SOURCE** — the guard compares basenames and gates on an explicit
   CLI-entry-name check.
2. **BEHAVIOUR** — esbuild-bundle the memory library into a file named
   `ac-tui.js` and RUN it: the process must still be alive after 5 s.
   (Pre-fix it exited with code 2.)
3. **THE REAL BIN STILL WORKS** — running `aethercode-memory/dist/cli.js`
   directly still reaches the CLI's own dispatcher.

The behaviour test is the one with teeth. It builds the bundle with the
**esbuild JS API** — the same API `scripts/bundle.mjs` uses, so the bundle
shape matches the shipped one — and runs it with `better-sqlite3` left
external and the CWD set to the memory package, so the native module
resolves exactly as it does at runtime.

**Lesson 1303 — a gate that can only SKIP is a gate that proves nothing.**
The first version of this test skipped, because esbuild's binary is not
where the test expected and the bundle could not resolve its own native
module. Both were reported as "skipped" — which would have shipped a gate
that never ran. A gate that silently skips is worse than no gate, because
it reads as coverage.

---

## Part 2 — "我觉得并不好看" (I don't think it looks good)

> "其实更希望展示成类似于 desktop 中间那部分的展示的效果。我遇到过好几款
> 产品,都是做成那种效果的"
>
> *I'd rather it looked like the middle section of the desktop. I've used
> several products and they all do it that way.*

### Reading the target

The desktop's centre panel is **two components**
(`aethercode-desktop/src/components/ChatView.tsx`):

```tsx
<div className="chat-view">
  <MessageList />
  <MessageInput />
</div>
```

So "that way" is not a palette. It is a **shape**:

- a **flat** transcript — no frame around it, no card per turn
- the **markdown IS the UI** (`.agent-message` is a plain `<div>` with
  typography; the assistant's prose is the surface)
- a **narrow, centred reading column** (`max-width: 820px` in CSS)
- the **input pinned at the bottom** as the only framed element
- an **empty state of one centred italic line** (`.message-list-empty`)

### What the TUI had instead

| TUI (before) | Desktop centre panel | Why it read as "not good" |
|---|---|---|
| `borderStyle="single"` frame around the whole transcript | no frame | the conversation looked like a **panel**, not a document |
| a `│` gutter column on **every** turn | no gutter | a log, not a conversation |
| a timestamp on **every** turn | no per-turn clock | a log, not a conversation |
| assistant turns prefixed with `💬 Reply` | a small dim `Assistant` label | chrome where prose should start |
| the boxed ASCII wordmark + a shortcuts grid as the empty state | one centred italic line | a wall of chrome before the user had said anything |
| the input was a bare `▌ ›` line under a `────` rule | a rounded "pill" with a send affordance | the input did not read as the **one** place to act |
| `Continue [c] Pause [p] Stop [s]` on **every** screen | run controls only while a run is live | three buttons for actions that would all do nothing |
| the context meter on its **own line** | a thin options strip | the bottom of the screen was three rows of chrome |

### What changed

**`components/Scrollback.tsx`**
- the outer frame is **gone**; the transcript is a plain column
- the `│` gutter is **gone**
- `fmtTime` is **gone** — no per-turn timestamp
- the assistant turn is a dim `Assistant` label + the markdown document
- the user turn is a `▌` accent bar + the text (the terminal's stand-in for
  the desktop's right-aligned bubble)
- the reading column is **96 columns wide and centred** — the terminal
  analogue of the desktop's 820px
- the empty state is **one centred italic line** + a dim context line
- the stop banner and the error card **keep their borders**: a run that
  died silently is the worst outcome, and those two must be unmissable.
  They are EXCEPTIONS to the flat transcript, which is exactly why they
  read as exceptions.

**`components/InputBox.tsx`**
- the input is a **rounded pill** (`borderStyle="round"`) — the desktop's
  `.message-input-wrap`
- a **send affordance** (`↵`) anchored to the right of the last line,
  dimmed when there is nothing to send, so it never promises an action
  that would do nothing
- the dropdown moved **above** the pill, so it points at the text it is
  completing

**`components/StatusBar.tsx`**
- the run-control row appears only while a run is in flight or the
  continuation is paused
- the context meter became a compact chip on the **same row** as the
  status strip, with an explicit separator (see below)

### The crash the redesign exposed

Driving `/demo` through the real TUI surfaced a crash that had been there
all along:

```
TypeError: Cannot read properties of undefined (reading 'suggestions')
    at pick (Autocomplete.tsx)
    at acceptSuggestion (InputBox.tsx)
```

`InputBox.acceptSuggestion()` pulled `pick` off the controller and called
it **bare**:

```ts
const pickFn = (active as { pick?: () => ... }).pick;
const picked = pickFn ? pickFn() : null;   // ← `this` is undefined
```

A detached method call loses the receiver. `pick()` reads `this.suggestions`
→ `undefined` → throw. The TUI **died the moment the user pressed Tab or
Enter with the completion dropdown open**. Three more unbound calls in the
same file (`rangeFn()`, `moveFn(-1)`, `moveFn(1)`) had the same shape.

**Lesson 1304 — a method pulled off an object and called bare has no
`this`. It is not a style question; it is a crash. Every `fn()` that came
from `obj.fn` needs `fn.call(obj)`.**

### The status-bar collision

With the meter moved onto the status row, the row read:

```
▌● running tool… · 1 tool call in flightctx[░░░░░░░░░░]
```

`justifyContent="space-between"` only inserts space while the row still
has room; with a long left half the two boxes butted up against each
other. A separator that cannot be squeezed out is the fix.

**Lesson 1305 — `space-between` is not a separator. It is a HINT that
there is room. When the row fills up, the two halves touch and the text
reads as one word.**

---

## Verification

| Check | Result |
|---|---|
| `aethercode-memory` build | OK |
| TUI typecheck (`tsc --noEmit`) | 0 errors |
| TUI prettier | 0 |
| TUI suite | see the round summary |
| R388g gate | 3/3 green — and **all three red on the pre-fix code** |
| TUI under ConPTY | `✓ ready` + `jar aethercode-cli-0.1.0-SNAPSHOT.jar`; stays alive past 20 s |
| `/demo` conversation | renders; the autocomplete crash is gone |

## The lessons

- **1302** — a module URL is not an identity. Inside a bundle every module
  shares the bundle's URL, so "is `import.meta.url` equal to `argv[1]`?"
  cannot distinguish "the user ran me" from "some other program imported
  me". The discriminator has to be about the FILE — compare basenames.
- **1303** — a gate that can only SKIP is a gate that proves nothing. Both
  skip paths in the first version of the R388g gate were reported as
  "skipped", which reads as coverage and is not.
- **1304** — a method pulled off an object and called bare has no `this`.
  It is not a style question; it is a crash.
- **1305** — `space-between` is not a separator. It is a hint that there
  is room. When the row fills up, the two halves touch and the text reads
  as one word.
