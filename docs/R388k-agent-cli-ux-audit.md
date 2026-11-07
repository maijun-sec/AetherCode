# R388k — Agent CLI/Product UX audit against industry leaders

**Round:** R388k · **Date:** 2026-11-07
**Scope:** the AetherCode TUI (`aethercode-tui`), both renderers — Ink (full TUI) and
line (pure command line) — plus the Desktop centre panel for visual reference.
**Benchmark set:** Claude Code, Cursor (CLI + IDE), OpenCode, Cline, Windsurf
(now Devin Local), Aider. Two facts that shape the whole benchmark:

- **Roo Code is dead.** Its own docs site carries the banner "The Roo Code Extension
  was shut down on May 15th." Do not benchmark it as a live competitor.
- **Windsurf's Cascade is legacy.** `docs.windsurf.com` 308-redirects to
  `docs.devin.ai`; "Devin Desktop no longer starts new Cascade conversations and no
  longer opens old ones." Pin URLs to `docs.devin.ai`.

---

## 0. Summary

Five findings. Three are **functional gaps** (a user literally cannot do the thing),
two are **product-surface gaps** (the thing exists but is invisible or duplicated).

| # | Severity | Finding | Where | Status |
|---|----------|---------|-------|--------|
| 1 | **P0** | **SDD cannot be driven at all in line mode.** `sdd.advance` / `sdd.abort` / `sdd.status` require a `slug`; line mode never sends one and never learns one. The daemon's own error message is unreachable, so the failure reads as a generic rejection. | `line.ts:3214`, `line.ts:3756` | **fixed** |
| 2 | **P0** | **`/help` shows 3 of 89 commands.** The whole command reference is rendered as one truncated `sideNote`; there is no scroll, so commands 4–89 are unreachable from the help surface. | `tui.tsx` help renderer | **fixed** |
| 3 | **P1** | **Line mode refuses 4 of 10 advertised SDD commands outright** ("only the full TUI's file actions do"), so the pure-terminal SDD story is incomplete even after finding 1 is fixed. | `line.ts:3770-3791` | **fixed** |
| 4 | **P1** | **`/sdd-list` is broken in BOTH renderers** — a bare `rpcMethod` with no params, while the daemon requires `{cwd}`. | `commands.ts` | **fixed** |
| 5 | **P1** | **The SDD orchestrator is installed on the HTTP transport ONLY.** The five `sdd.*` RPCs are registered on both, but the orchestrator — the object that does the work — was installed only in `runHttp`. On stdio every call threw `-32603: Internal error: sdd orchestrator not configured`, so the whole SDD surface was unreachable from the TUI's line mode. | `DaemonRunner.java` | **fixed** |
| 6 | **P1** | **One genuine duplicate command** (not a declared alias) hits the same target: `/theme` + `/theme-pick`. | `commands.ts` | **fixed** |
| 7 | ~~P2~~ | ~~"Always allow" is not persisted per project.~~ **WITHDRAWN — it already is.** `P` persists to `<cwd>/.aethercode/permissions.json`, `U` to `~/.aethercode/permissions.json`, and the policy evaluates deny → ask → allow. The draft read the client and inferred the server. | — | **withdrawn** |

### The audit corrected itself once

Finding 7 was written, then **withdrawn** after reading the daemon: the
permission path already implements the benchmark's model (project scope +
deny-first precedence) exactly. The full correction is in §5 — it is kept in
the report rather than deleted, because *how* the audit was wrong is part of
the deliverable.

→ **Lesson 1326 — an audit's claim about a SUBSYSTEM is a claim about
another system's vocabulary, and it cannot verify itself.** Read the server.

### And the audit found one thing only a LIVE run could find

Finding 5 (the stdio wiring gap) was invisible to every static check:
the `sdd.*` methods were registered on both transports, so "is the method
registered?" answered **yes on both**. The orchestrator was the missing
piece, and only a **real call** exposed it — the live probe showed
`-32603: Internal error: sdd orchestrator not configured`.

→ **Lesson 1328 — 一个共享的注册表,会让"每个传输层的接线不同"这个缺陷变得不可见。**
The registry is shared; the wiring is per-transport. So the question "is the
method registered?" is the WRONG question — it is true on both. The question
is "does the CALL succeed on each transport?", and only a real call answers
that.

This is R720's shape one layer out: `memory/*` was registered but the store
was never installed. Here the method is **known** and then fails at runtime —
which is worse than "method not found", because the client believes the
capability exists.

---

## 1. (P0) SDD is dead in line mode — the slug is never sent

### The defect

The daemon's SDD surface is a thin, honest pass-through:

```
sdd.start     {intent, cwd, model?}
sdd.advance   {slug, action: approve|modify|retry|skip|force-skip|abort, feedback?, model?}
sdd.status    {slug, cwd}
sdd.abort     {slug, cwd, reason?}
sdd.listRuns  {cwd}
```

(`AetherCodeMethods.java:1279-1412`; `SddOrchestrator.advance` does
`Objects.requireNonNull(slug, "slug")`.)

**Ink renderer** (`tui.tsx:2539-2613`) keeps the run in reducer state
(`state.sddRun?.slug`) and sends `{slug, action, feedback, cwd}`. Correct.

**Line renderer** (`line.ts:3756-3769`) sends `{action: payload}` and `{}` —
**no slug, no cwd**. The daemon rejects with "slug and action are required".
Even `/sdd-status` is broken: `panelSddStatus` calls `probe(ctx, "sdd.status")`
with **no params at all**, and `sdd.status` requires `{slug, cwd}`.

The net effect in a pure terminal: **every SDD command after `/sdd <intent>` fails**,
and the failure message is indistinguishable from a daemon that doesn't support SDD.

### Why this is the worst kind of failure

The benchmark set makes the standard explicit. Claude Code's headless mode states
the rule in one sentence: *"Calls needing approval are **denied** unless you approve
them with an allow rule, hook, `canUseTool` callback, or `--permission-prompt-tool`."*
And when nobody can answer: *"Claude is told that nobody can approve the request and
**not to retry it**."*

Our line mode is in exactly that position — a non-interactive client with **no way to
ask** — and instead of denying loudly it sends a malformed request and lets the
daemon's generic validation error stand in for the real diagnosis.

### The fix

Line mode must **discover** the slug rather than remember it. The daemon already
exposes the primitive: `sdd.listRuns {cwd}` returns every run under
`<cwd>/.aethercode/sdd/`, each with `slug`, `status`, `currentPhase`, `lastUpdatedAt`.

The rule: **one active run per cwd** — pick the run whose `status` is not
terminal, break ties by the most recent `lastUpdatedAt`. Then every SDD verb in
line mode resolves its slug through that single lookup. No second list, no
hard-coded slug, no session-only memory that breaks across restarts.

This is also what the benchmark says is right: OpenCode's docs are explicit that
"Markdown and JSON commands **share one registry**", and Windsurf's plan artefact
is a **file on disk** that survives the session — not session state. The slug
already lives on disk (`phase-state.json`); reading it is the honest design.

---

## 2. (P0) `/help` shows 3 of 89 commands

### The defect

The command catalog has **89** entries (`SLASH_COMMANDS_DETAILED`, `commands.ts`),
grouped as: session 20 / context 14 / config 23 / MCP & integrations 6 /
workflows & SDD 17 / diagnostics 9.

The help renderer emits the whole reference as a **single `sideNote`**, which the
scrollback truncates to the available height. Measured on a real TTY: **3 lines
visible**, the rest covered by the input box. There is no scroll, no pagination,
no filter. The user cannot reach commands 4–89 from the help surface.

### The benchmark

Every tool in the set solved this, and none of them solved it by truncating:

- **Claude Code** — typing `/` opens a menu that merges built-ins, bundled skills,
  user skills, plugin commands and MCP prompt commands into **one list**;
  `/commands` gives the full list; `?` on an empty input toggles a shortcut panel.
- **Cursor** — a `/` menu plus `/help [command]` for per-command detail.
- **OpenCode** — commands carry a `description` "shown in command lists and
  discovery".
- **Aider** — `/help` renders the **full command table generated from source**
  (`cog`/`get_help_md`), i.e. the help text cannot drift from the command table
  because it *is* the command table.

### The fix

Aider's approach is the one to copy, and it fits an existing project rule: the
TUI already owns **one command registry** (R380: `SLASH_COMMANDS_DETAILED` is the
only list, and the R380 gate checks both directions). The help surface should
**project that registry**, not re-print it as prose.

Concretely: `/help` becomes a **grouped, filterable list panel** — the same
panel machinery the TUI already has (PanelShell / PanelBody, R388j) — showing
group → commands → one-line description, navigable with `↑/↓`, with a filter
box, and `Enter` on a command **runs it**. That matches Claude Code's `/` menu and
Aider's generated table, and it keeps one source of truth.

---

## 3. (P1) Line mode refuses 4 of 10 advertised SDD commands

### The defect

The TUI advertises **10** SDD commands. In line mode, `/sdd-view`, `/sdd-diff`,
`/sdd-snapshot` and `/sdd-clear-cache` all answer *"only the full TUI's file
actions do"* — a refusal. `/sdd-status` is nominally available but broken
(finding 1). So **9 of 10** SDD commands are non-functional in a pure terminal.

The daemon registers only five `sdd.*` methods. The TUI's extra commands are
client-side file operations over `.aethercode/sdd/<slug>/`, and they were only
ever implemented in the Ink renderer.

### The benchmark

The comparable tools all keep the *artefact* accessible outside the rich client:

- **Devin Local** writes the plan to "an external Markdown file" that "lives
  outside your repository and persists for the session" — and explicitly notes it
  "can be particularly useful for continuing work across multiple sessions".
- **Claude Code** keeps plan files in `~/.claude/plans/`, outside the repo.
- **Cursor** exposes `Ctrl+R` review and `/rewind` in the CLI too.

### The fix

The spec artefacts already live in a **predictable, documented location**:
`.aethercode/sdd/<slug>/phase-N.md` (plus `phase-state.json`). A pure terminal
does not need a rich viewer to read a markdown file — it needs `cat`, and it needs
the **path**. So the line-mode fix is not to re-implement a viewer; it is to make
the **path discoverable** and let the shell's own tools render it.

---

## 4. (P1) Three genuine duplicate commands

### The method

Duplicates were found by **executing** each command through the real
`routeSlash` and grouping by the sentinel/RPC actually emitted — not by reading
names. Commands whose catalog entry declares `aliasOf` are *intentional aliases*
and are not defects.

### Genuine duplicates (no `aliasOf`)

| Commands | Same target | Verdict |
|---|---|---|
| `/theme` + `/theme-pick` | `__THEMES_PICK__` | **duplicate** — one of them should be the alias |
| `/continue` + `/pause` | `setContinuationStopped` | **duplicate** — the verbs are the same toggle |
| `/sdd-approve` + `/sdd-skip` | `__SDD_ADVANCE__` | **not a duplicate** — different `action` payloads |

### Intentional aliases (fine)

`/help`+`/?` · `/exit`+`/quit`+`/q` · `/todos`+`/todo` · `/context`+`/ctx` ·
`/tokens`+`/token` · `/no-confirm`+`/noconfirm`+`/yes-do-it` ·
`/consents`+`/grants` · `/continue`+`/resume` · `/stop`+`/kill`.

No `aliasOf` in the catalog points at a name that does not exist.

### The benchmark

Every tool prevents this **structurally**:

- **Cursor** — "Slash commands carry **explicit aliases** rather than duplicate
  entries": `/run-everything` ↔ `/auto-run`, `/clear` ↔ `/new`/`/new-chat`/
  `/newchat`, `/summarize` ↔ `/compress`, `/shell` ↔ `/sh`/`/run`.
- **OpenCode** — one registry; "Later sources replace earlier commands with the
  same name."
- **Aider** — aliases are **labelled as aliases** ("`/edit` is documented as
  'Alias for /editor'").

Aider's own docs still contain a live instance of the bug (both `/edit` and
`/editor` listed with different descriptions), which is exactly why the rule has
to be enforced by a gate, not by good intentions.

---

## 5. WITHDRAWN — the permission scope was already correct

### The first draft of this audit said

> The TUI's permission prompt offers `allow / always_allow / deny / always_deny`,
> and `always_allow` is granted for the session. Nothing is written to a
> project-local file, so the user re-learns the same approval in every repo.

**That was wrong.** The draft was written from the TUI's own key handling, and
the TUI's key handling is only half the story — the *scope* is chosen by the
key, but the *persistence* is done by the daemon.

### What the code actually does

The TUI's decision keys are richer than the draft assumed (`tui.tsx`,
`replyPermission`):

| Key | Decision | Scope | Where it is persisted |
|---|---|---|---|
| `A` / `1` | allow | once | — |
| `T` | allow | this task (session) | in-memory rule |
| `P` | allow | **this project** | `<cwd>/.aethercode/permissions.json` |
| `U` | allow | **this user** | `~/.aethercode/permissions.json` |
| `D` / `3` | deny | once | — |
| `N` / `4` | deny + remember | session | in-memory rule |
| `Y` / `2` | allow | session | in-memory rule |

`P` and `U` install a durable rule through the daemon's
`permissionPolicyOverride` RPC; `AetherCodeEngine` loads both files on boot
("the canonical record of what the user has decided to always allow inside this
repo").

### The precedence is also already the benchmark's

`ProjectPermissionPolicy.check` evaluates in exactly the order every tool in
the comparison uses:

```java
// 1. deny rules
// 2. ask rules
// 3. allow rules
// 4. fall back to mode
```

which is Claude Code's "Rules are evaluated in order: **deny, then ask, then
allow**", Cursor's "Deny rules take precedence over allow rules", and
OpenCode's "Saved approvals never override a configured `deny`".

### Why this correction matters more than the finding

A wrong finding in an audit is worse than no finding: it sends the team
building something that already exists, and it hides the fact that the
existing thing is correct.

→ **Lesson 1326 — an audit's claim about a SUBSYSTEM is a claim about
another system's vocabulary, and it cannot verify itself.** The draft
read the client and inferred the server's behaviour. The server had to
be read — and it had already implemented the model the benchmark
describes.

This is the same shape as R377 (a refusal table claiming a method does not
exist while the daemon served it under a synonym). The recurring defect is
**believing the client's view of the server**.

### What survives of the original observation

One thing is genuinely thinner than the benchmark: the TUI does not offer
"**Yes, and don't ask again**" as a single keystroke, the way Claude Code's
Bash prompt offers "Yes, and switch to auto mode". Today the user presses
`P` to get project scope — which is the same destination, reached by a
letter instead of a sentence. That is a **discoverability** gap, not a
capability gap, and it is deferred with the rest of the P2 items.

---

## 6. What the benchmark says we're already doing well

An audit that only lists gaps is not an audit. **Five** things match the
leaders — and the fifth is the one the audit got wrong:

- **The verdict comes from the daemon** (R383): the engine puts `isError` and
  `stopKind` on the wire, and the renderers read it. Claude Code does the same
  thing with `permission_denials` in the headless result — the client does not
  re-derive the outcome.
- **One command registry** (R380) with a gate that checks **both** directions.
  OpenCode and Cursor both converge on one registry.
- **One mode vocabulary** (R385), checked against the daemon's `PermissionMode`
  enum. Claude Code prints literal mode text (`⏸ manual mode on`,
  `⏵⏵ accept edits on`) rather than icons — greppable and screen-reader legible.
- **Permission scope and precedence are already the benchmark's** (see §5):
  `P` → `<cwd>/.aethercode/permissions.json`, `U` → `~/.aethercode/permissions.json`,
  and `ProjectPermissionPolicy.check` evaluates **deny → ask → allow → mode** —
  the same order Claude Code, Cursor and OpenCode all state in one sentence.
- **Non-interactive denial should be loud** — Claude Code denies rather than
  hangs, and tells the model not to retry. Our R388j work already made the line-mode
  renderer *say* when it degrades ("terminal doesn't support raw mode") instead
  of silently rendering less. That is the same invariant.

---

## 7. Fix plan for this round

Ordered by severity. Each fix ships with a gate that **proves it would go red**
(Lesson 1219), and pins the **invariant** rather than the spelling
(Lesson 1233).

| Fix | Change | Gate | Status |
|---|---|---|---|
| **A** | Line mode resolves the SDD slug through `sdd.listRuns`; `advance`/`abort`/`status` all send `{slug, cwd}`. No active run → a **clear** message naming the fix, not a generic daemon error. | `r388k-line-sdd.test.mjs` | **done** (11 cases) |
| **B** | Line mode exposes the spec-artefact **paths** (and reads them with the shell's own tools) instead of refusing `/sdd-view` / `/sdd-diff` / `/sdd-snapshot` / `/sdd-clear-cache`. | same gate | **done** |
| **C** | `/help` becomes a filterable, grouped **list panel** projected from the one registry, with `Enter` running the selected command. | `r388k-help-panel.test.mjs` | **done** (9 cases) |
| **D** | The genuine duplicate gets an `aliasOf` declaration, so the catalog stops advertising two names for one target. | `r388k-one-name-one-surface.test.mjs` | **done** (5 cases) |
| **E** | `/sdd-list` was broken in BOTH renderers (bare `rpcMethod` with no `cwd`); it is now a local sentinel both renderers intercept. | `r388k-line-sdd.test.mjs` | **done** |
| **G** | The SDD orchestrator is installed on the **stdio** transport too — it was HTTP-only, so every `sdd.*` call on stdio threw `-32603`. | `SddOrchestratorWiringR388kTest` | **done** (5 cases) |
| **F** | Scope `always_allow` to the project, with deny-before-ask precedence. | — | **already correct** (§5) |

### 7.1 What actually changed

**`src/sddRun.ts` (new)** — the SDD run-discovery module. Pure functions, no
Ink: `pickActiveRun`, `isActiveRun`, `extractRuns`, `sddParams`. The slug is
**discovered** from `sdd.listRuns {cwd}` rather than remembered, so it survives
a restart and works in a renderer that has no reducer state.

**`src/line.ts`** — `resolveActiveSddRun()` discovers the active run; every SDD
verb sends `{slug, cwd}`. `/sdd-view` / `/sdd-diff` / `/sdd-snapshot` /
`/sdd-clear-cache` no longer refuse — they show the **artefact path** and let
the shell's own `cat` render it. `/sdd-list` prints the runs on disk.

**`src/tui.tsx`** — `/sdd-list` is handled; `/help` opens the new panel
through the **generic** claim path (`PANEL_BY_INTENT["command-help"]`).

**`src/panelIntents.ts`** — the `command-help` intent, so the sentinel has
a declared owner and the `r362` / `r365` / `r385` gates can DERIVE its
reachability from the renderers' sources rather than trust a declaration.

**`aethercode-cli/src/main/java/.../DaemonRunner.java`** — the SDD
orchestrator is installed on the **stdio** transport too (see §7.3).

**`src/commandHelp.ts` (new)** — the help list's DATA, without React:
`buildHelpRows`, `filterHelpRows`, `helpWindow`. Split out of the component so
the logic is testable without a renderer (Lesson 1323).

**`src/components/CommandHelpPanel.tsx` (new)** — the panel. Filter box,
scroll window, `↑/↓` navigation, `Enter` runs the command through the **same
router** the user would have reached by typing it.

**`src/commands.ts`** — `/help` returns `__HELP_PANEL__`; `/theme-pick` is
declared an alias of `/theme`; `/sdd-list` is a local sentinel.

### 7.2 The live measurement

Captured on a real TTY (`conhost --headless`) from the built bundle, after
the panel was re-routed through the GENERIC panel claim
(`intentForSentinel` → `PANEL_BY_INTENT` → `openPanel`) rather than a
special case:

```
 ╭──────────────────────────────────────────────────────────────────────────╮
 │ ◆ Commands esc │
 │ ───────────────────────────────────────────────────────────────────────── │
 │ > sdd │
 │ ▶ /sdd <intent> start an 8-phase spec-driven run with an intent│
 │   /sdd-status            show the current SDD run state│
 │   /sdd-list              list every SDD run under the current cwd│
 │   /sdd-approve           approve the current phase and advance│
 │   /sdd-modify <feedback> re-run the current phase with your feedback│
 │   /sdd-skip              skip the current phase (required phases refuse)│
 │   /sdd-abort             abort the current SDD run│
 │   /sdd-view [phase]      show the phase artefact and its path│
 │   /sdd-snapshot [phase]  cache the current artefact for a later diff│
 │   /sdd-diff [phase]      diff the cached snapshot against the file on disk│
 │   /sdd-clear-cache       drop every cached per-phase snapshot│
 │  11 of 89 commands│
 │ type to filter ↑/↓: navigate Enter: run Esc: close│
 ╰──────────────────────────────────────────────────────────────────────────╯
```

Before R388k: **3 of 89** visible, no scroll, no filter. After: all 89
reachable, filtered by name or description, and `Enter` runs the chosen
command.

★ The generic path is the better shape ★ — the special case I wrote first
had to be **removed** once `PANEL_BY_INTENT["command-help"]` existed,
because one path claims every panel and a new panel cannot forget to
register. The `r362` / `r365` / `r385` gates then checked the claim
**derived from the renderers' sources** rather than from a declaration.

### 7.3 The daemon-side gap the live probe exposed

The **first** live run of the fixed client still failed:

```
/sdd-list  →  rpc error -32603: Internal error: sdd orchestrator not configured
```

The client was now correct — it sent `{cwd}` — and the daemon still could
not serve it. Because `AetherCodeMethods.registerAll(...)` registers
`sdd.start` / `sdd.advance` / `sdd.status` / `sdd.abort` / `sdd.listRuns`
on **both** transports, the methods were **advertised** on stdio. But the
orchestrator — the object that does the work — was installed only in
`runHttp`:

```java
// runHttp only:
http.methods().setSddOrchestrator(sdd);
```

So on stdio the field stayed null and `requireSdd()` threw.

**The TUI's line renderer talks to the daemon over stdio.** So the entire SDD
surface was unreachable from a pure terminal, and the client fix alone could
never have made it work.

The fix installs the orchestrator on the stdio path too, with the same
best-effort semantics (a daemon built without the `aethercode-sdd` module
logs and continues without `sdd.*`), plus the same `reloadActiveRuns(cwd)`
re-attach so a run that was mid-flight when the daemon died is still
advanceable.

After the fix, the same probe reports:

```
/sdd-list   →  ◆ SDD runs · D:/work/workspace/idea/engine/AetherCode
                     no SDD run under this cwd
                     /sdd <intent>   start one
/sdd-status →  ◆ SDD · status
                     no active SDD run under D:/work/workspace/...
                     /sdd <intent>   start a spec-driven run
                     /sdd-list   list every run on disk
```

Both are honest empty states — the daemon is now reachable, and there
genuinely is no run yet.

→ **Lesson 1328 — a shared registry makes a per-transport wiring gap
INVISIBLE.** "Is the method registered?" is true on both transports, so
that question can never find this defect. Only a **real call** does.

### 7.4 The duplicates — what the audit found, precisely

The audit executed every command through the real `routeSlash` and grouped by
the **effect** (method + params). That produced exactly ONE genuine
duplicate, not three:

| Pair | Effect | Verdict |
|---|---|---|
| `/theme` + `/theme-pick` | `__THEMES_PICK__` + `{}` | **DUPLICATE** → `/theme-pick` is now `aliasOf: "theme"` |
| `/continue` + `/pause` | `setContinuationStopped`, but `stopped:false` vs `stopped:true` | NOT a duplicate — two sides of one toggle |
| `/sdd-approve` + `/sdd-skip` | `__SDD_ADVANCE__`, but `action:"approve"` vs `action:"skip"` | NOT a duplicate — different actions |

Reading the NAMES would have produced the wrong answer in both directions: it
would have missed `/theme` + `/theme-pick` (different-looking names, same
surface) and flagged `/continue` + `/pause` (same RPC, opposite effect). The
gate now compares **effects**, so it agrees with the audit.

---

## 8. New lessons from this round

**Lesson 1318 — a file-wide "does this pattern appear?" check cannot pin a
per-call-site contract.** The first SDD gate asserted that `slug:` and
`cwd: ctx.cwd` appear *somewhere* in `line.ts`. Mutating the
`__SDD_ADVANCE__` handler back to the slug-less shape left the gate GREEN,
because `__SDD_ABORT__` and `__SDD_STATUS__` still carried both. **The other
correct instances vouch for the broken one.** Each verb must be checked in its
own block.

**Lesson 1319 — a check that looks for a WORD finds the word in a comment.**
The next iteration checked "does the arm mention a slug?" — and the mutation
`params: { action }` (the exact pre-R388k shape) survived, because the arm's
own comment contains the word "slug" and it calls `resolveActiveSddRun`. The
contract has to be checked **where it is delivered**: in the expression that
becomes the request params.

**Lesson 1320 — a proximity regex over a shared string binds the check to ONE
occurrence.** With two `sdd.listRuns` call sites, dropping the cwd from one
left the gate green. When a contract applies to every call site, assert on
every call site.

**Lesson 1321 — a contract check must anchor on the CALL, not on a string that
also occurs in prose.** Scanning `src/line.ts` for `sdd.listRuns` found five
occurrences; three were comments, error messages and panel titles.

**Lesson 1322 — an extraction that searches a long block for a label can find
the label of a NEARBY construct.** `paramsExpression(block)` searched a whole
file tail for `params:` and extracted a *different* call's params. The
extraction had to be split into "a block that HAS a `params:` label" and "a
bare argument expression", and the search bounded.

**Lesson 1323 — pure logic inside a component is logic you can only test
through the component.** The help panel's projection and filter lived in a
`.tsx`; the gate could not import it because the bundle pulled in `react` and
`ink`, which a temp-directory bundle cannot resolve. Splitting the data into
`src/commandHelp.ts` made it testable — and made it better code.

**Lesson 1324 — a braceless `case` arm is a different shape, and an extractor
that assumes braces reads the WRONG code.** `case "__SDD_STATUS__":` has no
block; an extractor looking for the next `{` grabbed a later arm's brace and
reported a defect that did not exist.

**Lesson 1325 — the SAME method with DIFFERENT params is not a duplicate.**
`/continue` and `/pause` both call `setContinuationStopped`; they are the two
sides of one toggle. `/sdd-approve` and `/sdd-skip` both emit
`__SDD_ADVANCE__`; they are different actions. A duplicate check that groups by
METHOD alone would have demanded the removal of both — which is why the check
groups by EFFECT (method + params) and excludes declared aliases.

---

## 9. Verification

| Suite | Result |
|---|---|
| Java (workflows / sdk / protocol / cli) | **861 tests, 0 failures, BUILD SUCCESS** (baseline 856 + 5 new `SddOrchestratorWiringR388kTest` cases) |
| TUI | **1577 tests, 1533 pass, 44 fail** — baseline was 1522 / 45 fail, so **+55 tests and ONE FEWER failure**; none of the 44 is new |
| Desktop | **1410 passed, 1 failed** — the same pre-existing `ToolsPanel.test.ts > Header.tsx has a tools button (🔧)` as the baseline |
| TUI typecheck | 0 errors |
| Prettier (`src/**`, `scripts/**`) | clean |
| Live TTY capture | the help panel renders and filters (see §7.2) |
| Live daemon capture | line-mode `/sdd-list` and `/sdd-status` answer (see §7.3) |

The TUI and Desktop suites both carry a **pre-existing** failure baseline
(45 and 1 respectively, from gates that look for files outside the TUI
package). The R388k contribution is measured as **the delta against that
baseline**, not as "all green" — and the delta is **one failure fewer**.

### The gates this round added

| Gate | Cases | What it pins |
|---|---|---|
| `aethercode-tui/scripts/test/r388k-line-sdd.test.mjs` | 11 | every SDD verb in the line renderer carries a `slug` AND a `cwd`; the discovery call is parameterised; the file commands no longer refuse; `/sdd-list` is a local sentinel both renderers claim |
| `aethercode-tui/scripts/test/r388k-help-panel.test.mjs` | 9 | `/help` opens a panel (not text); the panel is mounted and reachable; the rows are PROJECTED from the catalog; the filter works by name and by description; the scroll window never lies; the key table matches the handler |
| `aethercode-tui/scripts/test/r388k-one-name-one-surface.test.mjs` | 5 | no two non-alias commands produce the same EFFECT; every alias points at a real command; `/theme-pick` is declared; every alias is routable; `/continue` and `/pause` stay distinct |
| `aethercode/aethercode-cli/src/test/java/.../SddOrchestratorWiringR388kTest.java` | 5 | the SDD orchestrator is installed on BOTH transports; both re-attach the active runs on boot; the "not configured" guard still exists |

### The mutations that prove each gate would go red

Every gate was proven by **breaking the code on purpose** and watching it
fail. Three of them were caught by the gates themselves:

| Mutation | Gate that caught it |
|---|---|
| `__SDD_ADVANCE__` back to `params: { action }` | `r388k-line-sdd` — "sends params without a slug" |
| `sdd.listRuns` called without `cwd` | `r388k-line-sdd` — "calls sdd.listRuns without cwd" |
| `/sdd-list` back to a bare `rpcMethod` | `r388k-line-sdd` — "is not a local sentinel" |
| `/theme-pick`'s `aliasOf` removed | `r388k-one-name-one-surface` — "theme + theme-pick → local\|__THEMES_PICK__\|{}" |
| the stdio `setSddOrchestrator` call removed | `SddOrchestratorWiringR388kTest` — "the STDIO daemon never installs the SDD orchestrator" |
| `__HELP_PANEL__` with no `PANEL_INTENTS` entry | **r362** (existing) — "panel sentinels with no PANEL_INTENTS entry" |
| the help panel's handler binding `/` | **r381** (existing) — "its handler binds [...] but its key table declares [...]" |
| the help panel's `PANEL_BY_INTENT` entry missing | **r365 / r385** (existing) — "the registry's line column has drifted" |

★ The last three were caught by gates that existed BEFORE this round. ★
That is the point of R381's "both directions" design and of R365's
"derive, don't declare" design: they caught the new surface without being
told about it.

---

## Appendix A — verified benchmark facts (with sources)

### Multi-phase / spec-driven workflows

| Tool | Gate? | Approval | Request changes | Skip |
|---|---|---|---|---|
| Claude Code | Yes — `plan` mode, edits blocked until the plan is approved | `ExitPlanMode` tool | reject the plan and reply with edits in the same turn | no documented verb |
| Cursor | Yes — Plan Mode | "Click to build the plan" on the plan artefact | edit the plan via chat or markdown | undocumented |
| OpenCode | Partial — a `plan` **agent**, not a gate | n/a (`/plan` switches agent) | n/a | switch back to `build` |
| Cline | Yes — Plan/Act | toggle Plan → Act | toggle back | n/a |
| Devin Local | Yes — Plan mode writes a plan file | "Click the Implement button on the plan file" | edit the plan file, click Implement | switch to Code mode |
| Aider | No gate (has architect mode) | `/code` applies edits | n/a | `/ask` |

Headless: **Claude Code** is the only benchmark with a documented headless
phase-approval contract — `claude -p --resume` resumes into plan mode only if
`--permission-prompt-tool` is supplied and no `--permission-mode` /
`--dangerously-skip-permissions` / `--fork-session` is passed, and a
`PermissionRequest` hook or the SDK `canUseTool` callback decides the gate.
**Cursor**: "Cursor has **full write access** in non-interactive mode" — it
auto-approves. **OpenCode**: "Non-interactive clients **must decide** how to handle
approval requests."

### Permission prompts

| Tool | Model | Keys |
|---|---|---|
| Claude Code | tabbed option list | `Yes` · `Yes, and don't ask again for: npm test *` · `Yes, and switch to auto mode` · `No`; **Esc to cancel and Tab to amend**; `Tab` on Yes/No opens a **comment field**; `Enter` submits with the comment; `Esc` = No |
| Cursor CLI | minimal | `y` / `n` |
| OpenCode | wire protocol | `once` · `always` · `reject` ("Reject it and **every other pending permission request** in that session") |
| Cline | category toggles + YOLO | per-category auto-approve |
| Devin Local | 5 modes | `/code` `/smart` `/ask` `/plan` `/bypass` |

Non-TTY: Claude Code **denies and continues** (never hangs, never auto-approves);
Cursor auto-approves; OpenCode pushes the burden to the client. A two-minute
countdown keeps unattended Claude Code sessions alive.

### Session / history

Claude Code's `/resume` picker is the reference implementation: `↑/↓` or `k/j`
navigate, `→/←` expand groups, `Enter` resume, `1`–`9` resume by position,
`Space` preview, `Ctrl+R` rename, any printable char searches, `Ctrl+A` all
projects, `Ctrl+W` all worktrees, `Ctrl+B` current branch, `Esc` exits; each row
shows title, summary, time since last activity, git branch and file size.

### Layout

Claude Code prints **literal mode text** in the status bar; Aider changes the
**prompt itself** (`ask> This is ask mode.`). Cursor keeps a diff side panel
(`Ctrl+R`). Aider's `/diff` prints the diff inline.

### Command surface

Claude Code: one registry, `/` menu merges built-ins + skills + plugin + MCP
commands, `/commands` for the full list. Cursor: explicit aliases, `/help
[command]`. OpenCode: Markdown and JSON commands share one registry, later
sources replace earlier ones. Windsurf: workflows are markdown files, manual-only
by design. Aider: `/help` renders the command table **generated from source**.

## Appendix B — the "worth copying" list

1. **Deny-first, stated in one sentence** — "Rules are evaluated in order: deny,
   then ask, then allow… rule specificity doesn't change the order."
2. **"Yes, and don't ask again" persists to a project file; file-edit approvals
   deliberately do not.** The scoping asymmetry is a safety choice.
3. **Attach a free-text comment to the approval.** `Tab` on Yes/No opens a
   comment field; the comment reaches the model as the reason for a denial.
   A boolean gate becomes a communication channel.
4. **Approve-and-upgrade in one keystroke** — "Yes, and switch to auto mode".
5. **The phase artefact is a real file the human can edit, outside the repo.**
6. **Plan-file reuse across sessions** — "tweak the plan file and click Implement
   to attempt implementation again in a new conversation."
7. **Headless denial is loud and the model is told not to retry.**
8. **A timeout that keeps unattended sessions alive** — two-minute countdown.
9. **Rollback that can also compress context** — one rewind menu with "Restore
   code and conversation" / "Summarize from here" / "Summarize up to here".
10. **The mode indicator is literal text, not an icon.**

## Appendix C — unverified / risks

- **Continue.dev** — not covered. Its `plan-mode` doc URL 404s.
- **Cline** — checkpoints and CLI/headless behaviour not read.
- **OpenCode** — session-picker keybindings not verified.
- **Cursor** — whether a pre-written plan file can be approved from `agent -p` is
  undocumented.
- **Windsurf → Devin** is a moving target; pin URLs to `docs.devin.ai`.
- **Roo Code** is shut down — replace that benchmark row with ZooCode or remove it.
