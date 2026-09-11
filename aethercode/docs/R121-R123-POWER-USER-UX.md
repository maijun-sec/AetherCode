# R121-R123 — Power-User UX Round (2026-08-19)

## Context

R120 closed the round-the-loop work the user asked
for in "auto-approve low risk" (R120 daemon-side).
The next three rounds are power-user / self-diagnosis
follow-ups: a way to call any RPC the UI doesn't
have a button for (R121), a way to keep the user's
engine preferences across reloads (R122), and a way
to ship a "why did the daemon return X" log to a
colleague (R123). All three sit on top of the same
debugging surface — the R116 RPC diagnostic panel.

| Round | Title | Lines (renderer) | Lines (Rust) | New tests |
|---|---|---|---|---|
| R121 | RPC command palette | +250 (TSX) + 150 (CSS) + 20 (store) | 0 | +28 |
| R122 | Persistent engine prefs | +130 (store + helpers) | 0 | +16 |
| R123 | RPC export to .jsonl | +100 (TSX) + 25 (CSS) | +20 | +23 |
| **Total** | | **~700** | **+20** | **+67** |

**Cumulative vitest**: 252 (R120) → **319** (R121-R123)

## R121 — Raw-RPC command palette

A new modal (`RpcCommandPalette.tsx`) opened with
**Ctrl/Cmd+Shift+K**. Distinct from the R88
`CommandPalette` (Ctrl/Cmd+K, sessions) so the
common chord stays one keystroke.

Flow:
1. User types a substring ("setM", "perm", etc.) —
   the 50+ methods from `GET /api/methods` are
   filtered case-insensitive.
2. User picks an RPC. A JSON textarea appears with
   the params placeholder.
3. Live JSON validation: invalid → red border +
   disabled Execute. The error message is the
   `SyntaxError.message` so the user can find the
   typo.
4. Enter on the search input fires Execute; Enter
   on the textarea is a newline (so multi-line JSON
   works).
5. The call goes through `AetherCodeRpc.call` —
   the R116 diagnostic panel's `recentRpcEvents`
   sees it (no special instrumentation needed).
6. Result renders in a collapsible pane with a
   coloured left border (green = ok, red = err).
7. Escape closes the palette. If a result is
   showing, the first Escape clears the result; the
   second closes (R119 lesson applied — a
   state-changing panel shouldn't lose its state on
   a single Esc).

**Lazy load**: `loadRpcMethods` action does a single
GET on first open; subsequent opens are offline. The
fetch goes through `daemonInfo.httpUrl/api/methods`,
which the daemon serves without CORS friction
(localhost-to-localhost).

## R122 — Persistent engine preferences

Persists the user's engine-related toggles
(model, permissionMode, loopWindow, loopThreshold,
autoApproveLowRisk) across reloads via localStorage.
Single JSON blob at `aethercode.enginePrefs` so a
future addition is just one more field.

**On `setX`**: write to localStorage AFTER the
daemon RPC succeeds. Failure leaves the prior value
intact. The in-memory state is the source of truth
(R117 lesson — localStorage is a nice-to-have, not
a contract).

**On `initialize()`**: read prefs once, then for
each non-matching field:
- Push the persisted value to the daemon via the
  matching `setX` RPC.
- If the daemon rejects it (stale model name, model
  retired, mode removed in a downgrade), DELETE the
  stale key from prefs so the next reload starts
  fresh.

The four apply branches are all `try/catch` —
a network blip on one branch must not block the
rest. The in-memory engine state is the canonical
source either way; the localStorage copy is a
recovery snapshot for the next launch.

## R123 — Export RPC events to .jsonl

The R116 diagnostic panel gets an **Export** button
in the footer. Clicking it:
1. Opens the OS save dialog (via
   `tauri-plugin-dialog`'s `save()`).
2. Default filename: `aethercode-rpc-events-YYYY-MM-DD_HH-MM-SS.jsonl`.
3. Body: one event per line, schema matches the
   in-memory `RpcEvent` (method, params, durationMs,
   success, error?, ts) so a `jq '.[] | select(.success == false)'`
   gives the same view as the panel's `err` status
   filter.
4. The actual write goes through a dedicated Rust
   command `write_text_file` (a 10-line Tauri
   command beats pulling in `tauri-plugin-fs` + a
   new capability grant for one tiny write).
5. A transient footer status shows the result
   ("✓ wrote 50 events to /Users/.../Downloads/..." /
   "✗ export failed: <reason>").

**Exports the filtered view, not the raw buffer.**
The user has already narrowed to "the 8 errors in
the last minute"; exporting the unfiltered 50 would
defeat the point of the filter.

**Silent cancel.** If the user dismisses the save
dialog, no error banner — a silent no-op is the
expected behaviour for "I changed my mind".

## R121-R123 cross-cutting design notes

* **Power-user shortcuts are deliberate, not magic.**
  R121 (Ctrl/Cmd+Shift+K) and R123 (the
  RpcDiagnosticsPanel's Export button) are the
  "deliberately obscure" tier — they sit alongside
  the R116 Ctrl/Cmd+` and the R111 Ctrl/Cmd+Shift+P
  as the developer / debugging surface. The user
  who needs them knows; the user who doesn't isn't
  forced to memorise.

* **In-memory state is the source of truth,**
  **localStorage is a recovery snapshot.** This is
  the R117 lesson applied across R122. The daemon
  is the canonical authority (R114-A); the
  renderer's localStorage is a "where do I start
  on next launch" hint. The renderer's in-memory
  state is what the user sees *right now*; never
  the localStorage copy.

* **Best-effort writes, defensive reads.** R122's
  `writeEnginePrefs` swallows quota errors; R122's
  `readEnginePrefs` swallows parse errors; R123's
  export handles `null` (user cancel) silently.
  Three rounds of "the unhappy path must not
  surface a user-facing error unless it changes
  what the user does next".

* **Filter scope = export scope.** R123 exports the
  user's current filtered view, not the raw buffer.
  The user has already done the work of narrowing;
  the export should respect that. The button label
  shows the count ("Export (8)") so the user knows
  what they're about to ship.

* **OS-native file dialogs > custom pickers.**
  R123 uses the OS save dialog (via
  tauri-plugin-dialog), not an HTML form. The user
  gets the file-system trust boundary they expect,
  the path-validity check happens in Rust, and the
  renderer doesn't have to implement "did they pick
  a writable folder?" logic.

## Cumulative state

* TS / TSX: **319** vitest (R120 252 + 28 R121 + 16 R122 + 23 R123)
* Java: **4235** tests (R120 baseline, R121-R123 renderer-only)
* Total wire RPCs: 19 (unchanged)
* Total wire notifications: 13 (unchanged)
* New Tauri command: `write_text_file` (R123, Rust-side)

## Build notes (R121-R123)

* vitest: 6.07s for 319 tests
* tsc --noEmit: clean
* Vite build: 13.30s
* Tauri cargo build: ~3m 30s (incremental)
* TUI bun build: 1.2s compile

## R124+ follow-up candidates

* **R124**: rpc methods search by tag (group 50+ RPCs
  into read / write / engine-state buckets so the
  R121 palette is browsable, not just searchable).
* **R125**: Export RPC events as a markdown report
  (with duration histograms + slowest calls) instead
  of plain JSONL — for the user who'd rather read
  than grep.
* **R126**: Per-method RpcCommandPalette
  autocomplete (when the user types
  `setLoopDetectorThresholds({window: `, the palette
  suggests `12` based on the daemon's current value).
