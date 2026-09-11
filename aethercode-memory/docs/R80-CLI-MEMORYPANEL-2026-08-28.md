# R80 — TUI MemoryPanel + CLI (T-080 to T-099, Phase 1 §1.8 / §1.9)

**Date**: 2026-08-28
**Branch**: round-4-memory-cli
**Scope**: 20 tasks (T-080 to T-099) — the TUI MemoryPanel + the
`aethercode memory *` CLI.

---

## Files

### New
- `aethercode-tui/src/components/MemoryPanel.tsx` — TUI panel
  (T-080 to T-084). 3 tabs (Global/Project/Session), read-only
  list view, Enter / `e` opens the edit dialog (T-083), `c` +
  "Compact now" button with progress (T-084).
- `aethercode-tui/scripts/test/r80-memory-panel.test.mjs` — 13
  source-level + compile-smoke tests (T-086).
- `aethercode-memory/src/cli.ts` — `runCli` / `runCliAsync` /
  `parseArgs` + 4 subcommands (T-090 to T-093). 33 KB.
- `aethercode-memory/src/__tests__/cli.test.ts` — 37 vitest
  cases covering the parser, every subcommand, and error paths
  (T-094).

### Modified
- `aethercode-tui/src/commands.ts` — added `/memory`,
  `/memory-edit`, `/memory-compact` to `SLASH_COMMANDS` +
  `SLASH_HELP` + `handleSlash` (T-085). The `/memory-compact`
  subcommand forwards to the new `memory/compact` RPC; the
  other two are local-only tokens that the App's useInput
  handler matches on (panel open / edit dialog).
- `aethercode-memory/src/index.ts` — re-exported the CLI
  surface (`runCli`, `runCliAsync`, `parseArgs`, `main`,
  `CliUsageError`, `HELP_TEXT`, types).
- `aethercode-memory/package.json` — added `ac-mem` bin entry
  pointing at `dist/cli.js` so the CLI can be invoked as a
  standalone binary.

---

## Design

### TUI MemoryPanel (T-080 to T-085)
- **Tabs** (T-081): three scopes (`global` / `project` /
  `session`) with `1` / `2` / `3` keyboard shortcuts, `←/→` /
  `Tab` to cycle. The active tab is driveable by the App via
  `activeTab` + `onTabChange` props (panel also has local
  fallback state for the standalone case).
- **Read-only list view** (T-082): each entry renders as a
  Box+Text pair with a kind icon, kind label, and the value.
  Focused row shows a `▶` marker and a metadata line
  (`source · relative-ts · id`).
- **Edit dialog** (T-083): Enter or `e` on a focused row fires
  `onEditFact(scope, entry)`. The App is responsible for the
  modal UI; the panel just signals intent.
- **Compact now button** (T-084): footer renders the button
  + 5-state progress (`idle` / `running` / `ok` / `skipped` /
  `error`). `c` key or the button fires `onCompact`; the App
  performs the RPC and pipes the result back via
  `compactProgress`.

### CLI (T-090 to T-094)
- `memory show [--global|--project|--session] [--format=text|json]`
  — text (grouped by fact/rule/change/breadcrumb) or JSON
  payload. T-090.
- `memory edit --project` — opens the project memory file in
  `$EDITOR` (notepad on Windows, `vi` elsewhere). T-091.
- `memory compact [--force]` — forces a project-memory
  compression pass. With no LLM client, surfaces a clear
  "skipped" message instead of failing. T-092.
- `memory reset --scope=<global|project|session|all> [--yes]`
  — wipes a layer (or all three). Confirmation gate: refuses
  to wipe without `--yes` or `--force`. T-093.

### Argument parser
Minimal, dependency-free. Recognises `--key=value` and
`--key value` interchangeably. Validates scopes, formats, and
unknown subcommands with friendly `CliUsageError`s. The HELP
text is the same one the bin prints for `--help`.

### Testing
- **TUI**: source-level + compile smoke (r321 pattern). 13
  tests cover the component's exports, the three tabs, the
  read-only list, the edit-dialog action, the compact button,
  the `useInput` keyboard map, the ink primitives, the
  docstring's RPC contract, the slash-command dispatch in
  `commands.ts`, and the SLASH_HELP / SLASH_COMMANDS
  registration.
- **CLI**: vitest cases for every subcommand and the parser.
  Uses in-memory streams + a fake `CliDeps` so no real disk
  is touched. 37 tests cover the parser grammar, every
  subcommand's success / error path, the `--yes` confirmation
  gate, JSON output, and the Windows / Unix path safety.

---

## Results

```
aethercode-memory:   217 tests pass (180 + 37 new)   ✅
aethercode-tui/r80:   13 tests pass                  ✅
aethercode-tui build: TS strict mode, no errors     ✅
aethercode-memory build: tsc -p tsconfig.build.json, dist/cli.js present  ✅
ac-mem bin:          `node dist/cli.js --help` works end-to-end  ✅
```

Pre-existing failures (unrelated to R80):
- `aethercode-tui/r94e-prompt.test.mjs` — Java `final` →
  `volatile` refactor in a later round.
- `aethercode-tui/r46-welcome.test.mjs` — R87 collapsed the
  welcome screen; test file not updated.
- `aethercode-tui/r77-r79-*.test.mjs` — R77/R78/R79 expect
  MetricsCollector / TraceRecorder Java classes that haven't
  landed yet (scheduled for the Phase 1 §2 round).

---

## Followups (Phase 1 §1.8 / §1.9 leftovers)

None. All 20 tasks in the spec are complete.

The Phase 6 (`aethercode-protocol`) integration is
explicitly out of scope for this round — the T-076 wiring
that registers the new `memory/compact` RPC on the daemon
side will land with the daemon's protocol round.
