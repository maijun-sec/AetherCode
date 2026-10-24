# R374 — `/update` and `/update-deps`: three honest verbs, and no dead button

**Date:** 2026-10-24
**Scope:** `aethercode-tui/src` (Ink + line mode), `aethercode-tui/scripts/test`
(gate + mutation battery). No daemon change: the daemon has nothing to change.

## The gap this closes

For three rounds the registry row `update-available` carried `blocker: 'not-wired'`.
That was **wrong**, and the R365 blocker vocabulary is load-bearing:

| blocker | meaning | what it tells you to do |
| --- | --- | --- |
| `not-wired` | the backend works, nobody wrote the UI | write the UI — a task with a known destination |
| `backend-missing` | no such RPC exists on the daemon | do **NOT** write the UI; it would be a dead button |

The daemon has **no update surface at all**: no `update/check`, no
`update/refreshDeps`, no `update/install`, and no updater module in the Maven
reactor. Verified before writing a line of code — `DaemonRunner.runInternal`
(stdio) registers `AetherCodeMethods`, `HttpJsonRpcServer` routes a hand-rolled
switch, and neither mentions an update method; `rg "update/"` over the reactor
returns nothing.

So the honest blocker was `backend-missing`, and the rule that follows from it is
"don't write the UI".

## What R374 deliberately did NOT do

It did **not** mount `UpdateAvailable` / `UpdateConfirm` / `UpdateProgress`. All
three exist in the tree, all three are complete, and all three stay unmounted —
because each one needs a fact this daemon cannot produce:

| component | the fact it needs | can this daemon produce it? |
| --- | --- | --- |
| `UpdateAvailable` | a `PendingUpdate` — "an update is available" + the actions to take | no |
| `UpdateConfirm` | `current` and `latest` versions to confirm a move between | no |
| `UpdateProgress` | a download in flight | no |

Mounting any of them would put a button on screen that cannot do anything —
precisely the "dead button" the `backend-missing` blocker exists to prevent.

## The industry grounding

Four references, each fetched and quoted on 2026-10-24.

**1. Electron's `autoUpdater`** —
<https://www.electronjs.org/docs/latest/api/auto-updater>

- Platform Notices: *"Currently, only macOS and Windows are supported. There is
  no built-in support for auto-updater on Linux, so it is recommended to use the
  distribution's package manager to update your app."*
- The event flow is `checking-for-update` → `update-available` /
  `update-not-available` → `update-downloaded` → `quitAndInstall()`.
- *"It is not strictly necessary to call this function to apply an update, as a
  successfully downloaded update will always be applied the next time the
  application starts."*

Three things follow. The **check** is read-only and the **install** is the
mutating step. "A new version takes effect the next time the app starts" is a
first-class answer, not a cop-out. And "update it yourself, the way you
installed it" is a *recommendation the vendor itself makes* for platforms with
no built-in updater.

**2. `electron-updater`** —
<https://www.electron.build/docs/api/electron-updater.class.appupdater>

- `autoDownload: boolean = true` — *"Whether to automatically download an update
  when it is found."*
- `autoInstallOnAppQuit: boolean = true` — *"Whether to automatically install a
  downloaded update on app quit (if quitAndInstall was not called before)."*
- `downloadUpdate()` — *"Start downloading update manually. You can use this
  method if autoDownload option is set to false."*

Both flags exist for one reason: so an app can make **"nothing happens until the
user confirms"** the default. That is the shape the R374 panel takes: it never
downloads, never installs, never writes.

**3. Homebrew's three verbs** — the Homebrew FAQ states the sequence plainly:
*"First update the formulae and Homebrew itself: `brew update` / You can now
find out what is outdated with: `brew outdated` / Upgrade everything with:
`brew upgrade`."* (<https://github.com/Homebrew/brew/blob/master/docs/FAQ.md>,
manual at <https://docs.brew.sh/>)

`brew update` refreshes the index, `brew upgrade` installs, `brew outdated` only
reports. Merging them is a UX bug — and the R372/R373 notes already called
"merging `/update` and `/update-deps` into one modal" a bug. Homebrew is the
proof that three verbs is the right number, not two.

## The design: one panel, three verbs

| verb | how the user reaches it | endpoint probed | what it says |
| --- | --- | --- | --- |
| `check` | `/update` | `update/check` | whether a newer release exists |
| `install` | `/update install` | `update/install` | how to apply one |
| `deps` | `/update-deps` | `update/refreshDeps` | how to refresh the dependency index |

Three sentinels, **one** `PanelId` ("update"), one component, one shared copy
module (`src/updateInfo.ts`) — the R370 pattern: a FLAG, not a second panel.
`State.panelUpdateVerb` is written once, at `openPanel`, so the probe and the
render can never disagree about which verb the user asked for.

Four rules the implementation enforces:

1. **The gap statement is DERIVED from a probe, never hard-coded.** The panel
   calls `UPDATE_ENDPOINTS[verb]`; a `-32601` (JSON-RPC `METHOD_NOT_FOUND`)
   means "this daemon has no such operation". The day the daemon grows an
   update RPC, the same panel reports it without a code change. A panel that
   hard-coded "this daemon has no update RPC" would keep lying forever.
2. **A panel that cannot act must not offer an action that pretends to.** There
   is no "Install" row, because there is nothing to install. The one
   interactive row is the changelog URL — and printing a URL is something a
   terminal can genuinely do. (The panel does not spawn a browser: that is a
   side effect the user did not ask for.)
3. **Three verbs, three sentences, three manual paths, three titles.** Merging
   them was the original bug; the gate asserts all four are distinct.
4. **`null` is not `0`.** The daemon's `ping` answers `version` from the jar
   manifest's `Implementation-Version`, which a plain `mvn package` does not
   write — so "not reported" is the NORMAL case, and it renders as
   *"not reported by the daemon"*, never `0.0.0`.

The gap sentences say **what did not happen**, because "update failed" is a lie
when nothing failed:

> **check** — "This daemon has no update-check operation, so whether a newer
> release exists cannot be answered here. No release feed was contacted and
> nothing was changed."
>
> **install** — "This daemon has no install operation. Nothing was downloaded,
> nothing was replaced, and nothing was written."
>
> **deps** — "This daemon has no dependency-refresh operation. No index was
> refreshed and nothing was changed."

## The catalog was lying too

T-430's `/help` entries were *"review an available update"* and *"confirm a
dependency refresh"*. Both are false: there is no available update to review
(the daemon has no update RPC) and nothing to confirm (no dependency-refresh
RPC). A description is the first thing the user reads — and it is read *before*
the panel can correct it.

And `/update install` — the third verb — has existed since T-430 and was
reachable, but the catalog advertised **one** shape for a command that has
**two**, so the install verb was invisible. The catalog now carries
`args: "[install]"`, which is what renders `/update [install]` in `/help`.

```
/update [install]     check for a newer release (add 'install' to see how to apply one)
/update-deps          check how to refresh dependencies
```

## The version constant

`src/version.ts` is a leaf module with no imports, and it is the single source
of the TUI's version. `ac-tui.ts` imports it for the `--version` banner and the
panel imports it for the "tui version" row — before R374 they were the same
string in two files, and the panel could not have used `ac-tui.ts` anyway
(it imports `tui.tsx` back, deepening an existing ESM cycle).

The gate pins `src/version.ts` against `aethercode-tui/package.json`. A third
copy lives in `aethercode-cli/.../TuiCommand.java`; it is in another Maven
module and is out of the TUI's test scope, so it is documented rather than
pinned.

## The files

| file | what changed |
| --- | --- |
| `src/version.ts` | **new** — the TUI version, one leaf module |
| `src/updateInfo.ts` | **new** — the pure copy module: verbs, endpoints, headlines, manual paths, `buildUpdatePanel` |
| `src/components/UpdatePanel.tsx` | **new** — the Ink surface (changelog action, no install action) |
| `src/state.ts` | `PanelId "update"`, `panelUpdateVerb`, `updateFacts`, `setUpdateFacts`; `openPanel` writes the verb, `closePanel` clears it |
| `src/tui.tsx` | the panel's fetch (verb endpoint + `ping`, in parallel), the render case, the sentinel→verb claim |
| `src/line.ts` | `panelUpdate(ctx, verb)` rebuilt on the shared builder; the three sentinels get three verbs |
| `src/panelIntents.ts` | the row becomes `ink: 'mounted'` / `line: 'rendered'` with **no** blocker, and keeps a `gapNote` that opens with `R374:` |
| `src/commands.ts` | honest descriptions + the `[install]` arg hint |
| `src/ac-tui.ts` | imports `TUI_VERSION` instead of holding its own copy |
| `src/components/UpdateAvailable.tsx` | `DEFAULT_CHANGELOG_URL` repointed from an upstream project's release notes to AetherCode's |

The registry row keeps a `gapNote` with **no** `blocker`, and that is not a
contradiction: the `ink`/`line` columns answer "is there a UI surface?" (yes),
the `gapNote` answers "does the underlying capability exist?" (no). Two
different questions.

## The compile mode the gates use is NOT the project's

The full `npm test` run found **five** failures that no focused gate had
caught: `R43`, `R77`, `R78`, `R79` and `R94-E` — every one of them a test
that compiles TypeScript.

The root cause is worth writing down, because it will bite the next person
who adds a `.ts` file to this repo:

```js
// scripts/test/r43-layout.test.mjs (and r77/r78/r79/r94-e)
spawnSync(tscBin, [
  "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
  join(root, "src", "tui.tsx"),
]);
```

When files are named on the **command line**, `tsconfig.json` is ignored
entirely. So the gates compile with the compiler's *defaults* — `strict` off,
`strictNullChecks` off — while the project's own `tsc --noEmit` compiles with
`strict: true` from `tsconfig.json`.

And under `strictNullChecks: false`, **TypeScript does not narrow a union by a
boolean discriminant**:

```ts
// A — boolean discriminant: does NOT compile without --strict
declare const p: { ok: true; raw: number } | { ok: false; err: unknown };
export const A = !p.ok && f(p.err);      // TS2339: Property 'err' does not exist

// D — string discriminant: compiles in BOTH modes
declare const q: { state: "ok"; raw: number } | { state: "err"; err: unknown };
export const D = q.state === "err" && f(q.err);
```

The R374 probe result was `{ ok: true, raw } | { ok: false, err }`. It compiled
cleanly under the project's `strict: true` — and **five** gates, compiling with
the defaults, could not compile it at all. The fix is a **string discriminant**
(`state: "ok" | "err"`), which narrows in both modes.

Verified with a 40-line probe compiled both ways: the boolean variants fail
without `--strict`; the string variant passes both.

**New TypeScript in this repo must compile under BOTH the project's
`tsc --noEmit` AND a bare `tsc <file>` with default options.** The two
compilers disagree about more than you would expect, and only the second one
is what the gates actually run.

## The gate, and the four holes the battery found

`scripts/test/r374-update-panel.test.mjs` — 34 assertions. Most of them are
**behavioural**: `updateInfo.ts` is a pure module, so the gate compiles it with
`tsc` and RUNS it, then asserts on the copy the user actually reads. A source
pin could only say "the code mentions `update/check`"; the behaviour
assertion says "the check verb probes `update/check`, the install verb probes
`update/install`, and the deps verb probes `update/refreshDeps`".

`scripts/test/r374-mutate.mjs` — 46 cases, every one caught. Four of the holes
it found were in the **gate**, not the product:

1. **`buildUpdatePanel` vs `buildUpdatePanel(`.** The first version asserted
   the identifier was present — and an `import { buildUpdatePanel }` line
   satisfies that, so a renderer that stopped *calling* the shared builder
   still passed. R373's Lesson 1170, verbatim. Fixed by pinning the call form.
2. **"cites a round" was satisfied by an aside.** The gap note's prose
   explains that R365's blocker was wrong, so `/R\d{3}/` matched the note's own
   aside even after the note's opening attribution was deleted. Fixed: the note
   must **open** with the round that established it.
3. **A `case` arm owns no braces.** The extractor brace-matched from the
   `case "__UPDATE_AVAILABLE__":` header and walked straight into the next
   arm — so the gate was reading a `sdd.start` case while asking about
   `/update`. Fixed with a `caseArm()` extractor that slices to the next
   `case` at the same indent.
4. **The verb mapping had a single derivation.** Muting the mapping
   assertion and collapsing `/update install` back onto `check` left the gate
   green — because the headline and manual-path tests call
   `updateHeadline("install", …)` **directly**, never through the mapping.
   Fixed by adding a second derivation that goes *through* the mapping:
   `updateHeadline(updateVerbForSentinel(s))` must differ per sentinel.

Hole 4 is the general shape worth keeping: **a property with one derivation
can be silently neutered**. A GATE mutation is only meaningful when a second
independent derivation still sees the defect.

## Two more no-ops — in the OTHER batteries

Running all eleven batteries (not just this round's) surfaced two more, and
both were cases that had been **measuring the wrong thing**:

**`r365-mutate.mjs` — "drop the blocker from a blocked row" was a NO-OP.**
Its regex was

```js
/(id: 'workflow-picker',[\s\S]*?)\n    blocker: 'not-wired',/
```

`[\s\S]*?` is lazy, but it will happily skip past the end of the
`workflow-picker` row to find the next `blocker:` **anywhere later in the
file** — which was the `update-available` row. So the case had been mutating
a *different* row than the one it named, and the gate went red for the wrong
reason. R374 removed that row's blocker (correctly — the daemon has no update
RPC, so `backend-missing` is the honest classification, and R374 built the
surface), which left the regex with nothing to match. The registry now has
**no row with a `blocker` field at all**, so the case is unexpressible
against the live table. It is deleted — and the mechanism it wanted to cover
("a placeholder with no command behind it must not be advertised") is covered
by a **synthetic** row in `r371-context-meter.test.mjs`, which constructs the
row instead of depending on one happening to ship in that state.

**`r373-mutate.mjs` — "the Ink panel is not reachable through the sentinel"
was a NO-OP.** R374 hoisted `bareSentinel(panelLocal)` into a local `bare`,
so the literal the case replaced no longer existed. Retargeted at
`intentForSentinel(bare)`.

**A case that measures the wrong thing is worse than a case that measures
nothing**: it reports "caught" and hides the fact that the property is
untested. Scope a row-targeting regex to the row (or use a structural
extractor), and let the no-op detector be the thing that tells you a target
rotted.

## Verification

- `tsc --noEmit` (project, `strict: true`): 0 errors.
- `tsc … src/tui.tsx` (gate-style, default options): 0 errors — **both** modes.
- `r374-update-panel.test.mjs`: 34/34.
- `r374-mutate.mjs`: 46/46 caught.
- Focused R362–R374 gates: 15/15 files green.
- Full `npm test`: 1312 tests / 1266 pass / **46 fail — the same 46 as the
  R373 baseline** (pre-existing: `docs-structure.test.mjs` expects a
  `docs/user-guide/` tree this repo does not have, R344's status-bar redesign
  invalidated several source pins, and the R77/R78/R79 Java-path checks look
  for modules in a different module).
- Mutation batteries r362–r374: every case caught, run **serially** after the
  test suite (R373 Lesson 1166: a battery rewrites product source in place).
- No Java change, so the Maven suites are untouched.

## Lessons

- **A `not-wired` blocker is a claim, and a wrong one costs three rounds.**
  The blocker vocabulary is load-bearing because each value implies a
  *different action*. Re-derive the blocker from the backend, not from the
  shape of the UI that is missing.
- **"Don't build the UI" is a real deliverable.** The honest answer to a
  missing backend is a surface that says what did not happen and points at
  the path that works — not a modal with a dead button.
- **Split the verbs the way the mature tools split them.** Electron splits
  check / download / install and Homebrew splits update / upgrade / outdated.
  One blended sentence is a UX bug, and the fix is three answers.
- **Derive the gap from a probe, never declare it.** A hard-coded "unavailable"
  keeps lying after the backend lands. Probe the endpoint, read the JSON-RPC
  error code, and let the panel upgrade itself.
- **A description is read before the panel can correct it.** The `/help` text
  is the first truth the user meets; a description that promises an update
  which cannot exist is a lie the panel never gets to fix.
- **A verb that is reachable but not advertised is half a feature.** The
  catalog's `args` field is what makes an optional sub-verb visible; use it.
- **One property, one derivation, one neutered assertion.** Pin the call, not
  the identifier; check the *opening* of a note, not that it mentions a round
  somewhere; and make sure a behavioural assertion reaches its subject through
  the same path production does.
- **The project compiler and the gate compiler are not the same compiler.**
  `tsc --noEmit` uses `tsconfig.json` (`strict: true`); the gates pass the
  file on the command line, so `tsconfig.json` is ignored and the defaults
  apply (`strictNullChecks: false`). Under the defaults a union is **not**
  narrowed by a boolean discriminant — so use a **string** discriminant
  (`state: "ok" | "err"`), which narrows in both modes. New TypeScript here
  must satisfy both compilers.
