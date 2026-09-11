# R124-R125 — RPC Palette Polish (2026-08-19)

## Context

R121 shipped the raw-RPC command palette (Ctrl/Cmd+Shift+K).
Two follow-up rounds polish the surface:
- **R124** turns the 64-method flat list into a
  tagged, filterable view so power users can
  narrow "show me the engine-write RPCs" without
  remembering exact method names.
- **R125** gives the R116 diagnostic panel a
  human-readable report export alongside the
  R123 JSONL dump, so the user who'd rather
  *read* a summary than `jq` a file gets one.

| Round | Title | Lines (renderer) | Lines (Java) | New tests |
|---|---|---|---|---|
| R124 | RPC method tags + tag filter | +110 (TSX) + 50 (CSS) + 30 (store) + 25 (methods.ts) | +90 (METHOD_TAGS static block) | +5 Java + +24 TS |
| R125 | Markdown report export | +100 (TSX) + 15 (CSS) | 0 | +17 TS |
| **Total** | | **~330** | **+90** | **+46** |

**Cumulative vitest**: 319 (R121-R123) → **360** (R124-R125, +41 new R124/R125 + 5 R124 unit on the TS side)
**Cumulative Java**: 4240 (R120 4235 + 5 R124 unit)

## R124 — Per-method tags + tag chip filter

The daemon's `/api/methods` endpoint shape
changed from a flat-name list to a tagged
list:

```json
{
  "methods": [
    { "name": "ping", "tags": ["read", "diagnostic"] },
    { "name": "setModel", "tags": ["write", "engine"] },
    ...
  ],
  "tags": ["read", "write", "engine", "session", "permission",
           "loop", "tools", "workflow", "memory", "task",
           "skill", "agent", "project", "diagnostic"]
}
```

### Tag taxonomy (14 fixed strings)

A small set so the chip bar stays one line:

| Tag | Methods carrying it | Why |
|---|---|---|
| `read` | 30 | Read-only, no state change. Filter excludes writes. |
| `write` | 34 | Mutates daemon state. Mutually exclusive with `read`. |
| `engine` | 14 | Engine config (model, permission mode, loop detector, etc.) |
| `session` | 9 | Session lifecycle. |
| `permission` | 5 | Permission policy. |
| `loop` | 2 | Loop detector. |
| `tools` | 2 | Tool pool. |
| `workflow` | 6 | Workflow mgmt. |
| `memory` | 4 | Memory store. |
| `task` | 4 | Task board. |
| `skill` | 3 | Skill registry. |
| `agent` | 6 | Agent registry. |
| `project` | 2 | Project switch. |
| `diagnostic` | 5 | ping, metrics, traces. |

The static block (`AetherCodeMethods.METHOD_TAGS`)
lives in Java; the renderer's `RPC_TAGS` constant
mirrors it. A `R124` test pins that every method
uses only known tag strings and that `read` /
`write` are mutually exclusive.

### Backward compatibility

The pre-R124 flat-name list shape is still
served at `/api/method-names` (a parallel
endpoint, not an alias — keeps the wire
formats distinct so a future change to
either doesn't surprise the other clients).
The TUI and any pre-R124 Tauri code can
keep calling the old path indefinitely.

### Renderer changes

* `lib/methods.ts`: new `RpcMethodInfo` type
  (`{name, tags[]}`) and the `RPC_TAGS`
  constant.
* `store/index.ts`: the `loadRpcMethods`
  action accepts both the new tagged shape
  and the pre-R124 flat-name shape, with
  defensive deduping. New field
  `rpcMethodInfos: RpcMethodInfo[]` on the
  AppState.
* `RpcCommandPalette.tsx`: tag chip bar at
  the top of the modal. Click toggles a tag
  in `activeTags: Set<string>`; the filtered
  list ANDs the substring + tag predicates
  (a method must carry *every* active tag
  to stay in the view). A `clear` chip
  appears only when ≥1 tag is active.
* `RpcCommandPalette.css`: pill-shaped
  chips with the count of matching methods
  in the label. Active chip uses the accent
  colour; the `clear` chip uses a dashed
  border to differentiate from the active
  set.

## R125 — Markdown report export

The R116 diagnostic panel's footer gets a
second button: `Report`. Same save-dialog
plumbing as the R123 `Export` button, but
the content is a markdown report — a
human-readable summary for the user who
wouldn't dream of `jq`-ing a JSONL.

### Report sections

1. **Header** — generation timestamp +
   event count.
2. **Summary** — OK / error count + percent,
   time range, span.
3. **Duration** — mean + p50 / p95 / p99
   percentiles + max.
4. **Slowest 5 calls** — markdown table
   (method, duration, status, time).
5. **Top errors** — bucketed by
   `(method, error-message)` so 50
   instances of "the same thing" surface
   as 1 row.

The percentiles run on the full sorted
`durationMs` array (50 elements max), so
the values are exact — well under a
millisecond to compute.

### Renderer changes

* `RpcDiagnosticsPanel.tsx`: new
  `exportMarkdownReport` function + a
  pure `renderMarkdownReport` helper
  (split out so tests can pin the
  content shape without mocking the
  save dialog). The footer has
  `Export (N)` and `Report` side-by-side;
  Export is the primary (accent blue),
  Report is the secondary (text-dim
  border).
* `RpcDiagnosticsPanel.css`: a
  `.rpc-diag-export-report` style with
  a hover state and a disabled state.

### Why two export formats?

* **JSONL** (R123) — for the user who'll
  pipe the file into `jq` or upload it to
  a search index. One event per line, the
  schema matches the in-memory `RpcEvent`.
* **Markdown** (R125) — for the user who
  opens the file in a preview pane and
  reads it. The summary + percentiles +
  top errors answer the "what just
  happened?" question in one glance.

Both write through the same Rust Tauri
command (`write_text_file`, R123) so the
trust boundary is identical — the OS file
dialog, the path-validity check in Rust,
and the error message rendered in the
footer.

## Cumulative state

* TS / TSX: **360** vitest (R121-R123 319 + 24 R124 + 17 R125)
* Java: **4240** tests (R121-R123 4235 + 5 R124 unit)
* Total wire RPCs: 19 (unchanged)
* Total wire notifications: 13 (unchanged)
* `/api/methods` now returns a tagged list
  (the pre-R124 flat shape is at
  `/api/method-names`)

## Smoke test (R124 + R125 daemon-side)

```text
R124 /api/methods shape:
  total: 64 methods, 0 duplicates
  every entry has name + tags[]: True
  every tags[] is array: True
  every tags is non-empty: True
  every tag is in known set: True

Well-known checks:
  PASS: ping tags=['read', 'diagnostic']
  PASS: setModel tags=['write', 'engine']
  PASS: setLoopDetectorThresholds tags=['write', 'engine', 'loop']
  PASS: setAutoApproveLowRisk tags=['write', 'engine', 'permission']
  PASS: listWorkflows tags=['read', 'workflow']
  PASS: listMemory tags=['read', 'memory']
  PASS: listSkills tags=['read', 'skill']
  PASS: listAgents tags=['read', 'agent']
  PASS: listProjects tags=['read', 'project']
```

TUI smoke test (`ac-tui.exe --print "what is 2+2"`):
spawned the daemon, ran a single turn, returned
"2 + 2 = **4**" via the streaming query path.

## Build notes

* vitest: 4.65s for 360 tests
* tsc --noEmit: clean
* Vite build: 11.68s
* Tauri cargo build: 23.75s incremental (no Rust changes since R123)
* TUI bun build: 962ms compile
* mvn install: 23.83s for 17 modules

## R126+ follow-up candidates

* **R126**: Per-method RpcCommandPalette
  autocomplete — when the user types
  `setLoopDetectorThresholds({window: `, the
  palette suggests the daemon's current
  `loopWindow` value.
* **R127**: "favourite" RPCs in the R121
  palette (the same way VS Code's command
  palette shows recently-used + pinned
  commands). Favourites persist in
  localStorage (the R122 pattern).
* **R128**: "help" text for each RPC
  (description, expected params, sample
  payload). The Java side would emit
  `description` alongside `name` and
  `tags`; the R121 palette renders a
  side panel with the details when an
  RPC is selected.

## Cross-cutting design lessons (R124-R125)

1. **Small fixed tag sets** — 14 tags fit on
   one line in the chip bar. A 30-tag set
   would force the user into a "which of
   these 30 do I want" decision — diminishing
   returns.
2. **Backward compat via parallel endpoint,
   not alias** — `/api/method-names` (the
   pre-R124 flat shape) lives at a different
   path from `/api/methods` (the new tagged
   shape). The renderer picks one at startup;
   a future change to either doesn't surprise
   the other.
3. **Defensive parse on the renderer** —
   the `loadRpcMethods` action accepts both
   the new tagged shape and the pre-R124
   flat-name shape. A user upgrading from
   an older daemon still gets the methods
   list (without tags); a user with the
   latest daemon gets the full experience.
4. **Two export formats, one write path** —
   the R123 JSONL export and the R125 markdown
   report both go through the same Rust
   `write_text_file` command. The trust
   boundary (OS file dialog, Rust path check)
   is in one place; the format choice is
   purely a renderer concern.
5. **Pure function for testable content** —
   `renderMarkdownReport` is a pure function
   (events → string) with no I/O. The
   `exportMarkdownReport` wrapper handles the
   save dialog + invoke. Tests pin the
   content shape without mocking anything.
6. **Date-prefixed default filenames** —
   R125 defaults to `aethercode-rpc-report-YYYY-MM-DD_HH-MM-SS.md`
   to match the R123 JSONL convention. A
   folder of paired exports is self-labelling.
