# R108-5: SettingsPanel profile 下拉

**Date:** 2026-08-13
**Status:** SHIPPED
**Impact:** Settings panel now exposes a Concurrency profile
dropdown (low / normal / high) bound to the engine's
`ConcurrencyController` via the existing
`setConcurrencyProfile` RPC. The dropdown shows the
in-flight query / tool / branch counts and the
throttle / backpressure state so the user picks a
profile with full context.

## 背景 (Context)

R107-G added the `ConcurrencyController` and the
`setConcurrencyProfile` RPC (low / normal / high). The
backend was complete; the renderer was missing the
UI to drive it. The StatusBar's memory / throttle
pills showed the live state, but the only way to
switch profiles was the CLI flag
(`--concurrency-profile=low`) which required a daemon
restart.

R108-5 adds the dropdown in the existing Settings
panel. It's a 0.5d piece of UI plumbing that
completes the R107-G feature: backend ✓, RPC ✓, live
stats ✓, controls ✓.

## 设计 (Design)

### Where it lives

The existing `SettingsPanel.tsx` already has Model +
Permission mode dropdowns. The profile dropdown goes
in the same `<form>` section so the user sees all
three controls in one place — no need to grow the
settings surface for a single new field.

### The bound value

`engineStats.concurrencyProfile` is the source of
truth. The StatusBar already polls it every 5s via
`refreshEngineStats`; the dropdown reads the same
field. Switching the dropdown calls
`requestConcurrencyProfile(name)` which delegates to
`rpc.setConcurrencyProfile(name)`. The next 5s poll
reflects the new profile in the dropdown (so a
round-trip via another window — or a CLI restart with
`--concurrency-profile=high` — is visible).

### The hint line

Each dropdown is a `<label>` with a short description
("Low (1q / 2t / 1b — low-end machines)"). The
profile dropdown also shows a `.settings-hint`
element below the `<select>` with the live
in-flight counts and any throttle / backpressure
markers. The hint is small grey text with
`font-variant-numeric: tabular-nums` so the numbers
align even when they change (1q → 12q).

## 改动 (Changes)

### Frontend (TypeScript)

- `aethercode-desktop/src/components/SettingsPanel.tsx`:
  - new `CONCURRENCY_PROFILES` constant with the
    three options + a one-line description each.
  - new `profile` local state bound to
    `engineStats?.concurrencyProfile ?? 'normal'`.
  - `useEffect` on `currentProfile` keeps the
    local state in sync with daemon-side changes
    (e.g. CLI flag, or another open window).
  - new `<select>` between Permission mode and the
    Save button; the value dispatches via
    `requestConcurrencyProfile`.
  - new `.settings-hint` element under the select
    showing live in-flight counts.
- `aethercode-desktop/src/components/SettingsPanel.css`:
  new `.settings-hint` style (small grey,
  tabular-nums).

## 验证 (Validation)

- aethercode-core: 36/36 tests still pass.
- aethercode-protocol: 5/5 HttpJsonRpcServerTest still
  pass.
- aethercode-desktop: `npm run build` clean
  (476.67 KB JS / 80.47 KB CSS, +0.83 KB JS /
  +0.15 KB CSS for the dropdown and the hint line).
- `tsc -b --noEmit`: clean.
- `build.ps1 -SkipTests`: clean
  (`aethercode-0.2.1.jar` 39.64 MB at `dist/`).

## 风险 (Risks) / 已知限制 (Known limitations)

- **5s polling delay**: the dropdown reads
  `engineStats.concurrencyProfile` which is
  refreshed every 5s by the existing
  `refreshEngineStats` poll. Switching profiles
  → 5s lag → dropdown reflects new value. Not
  noticeable for the human eye but a programmatic
  caller (`window.test`) might want a faster
  refresh. R108-5 doesn't change the polling rate.
- **No "auto" option**: some users want a profile
  that picks itself based on the host's memory
  budget. The `ConcurrencyController` supports
  the three explicit profiles; an "auto" mode
  would need a 4th profile enum. R108-5 doesn't
  add it; the user can pick the closest match
  manually.
- **Settings panel is the only surface**: there's
  no quick-toggle in the Header / StatusBar. The
  StatusBar's existing throttle / backpressure
  pills remain read-only; clicking them does
  nothing. A future R-round could make them
  clickable as a shortcut to the Settings panel
  with the profile dropdown auto-focused.

## R108 总结 (R108 Wrap-up)

All 5 R108 candidates shipped. Summary:

| # | Candidate | Status | Doc |
|---|-----------|--------|-----|
| 1 | localStorage cache 退役 | ✅ | R108-1-LOCALSTORAGE-RETIRE-2026-08-13.md |
| 2 | 子 session 事件流到 workflow sink | ✅ | R108-2-CHILD-SESSION-EVENTS-2026-08-13.md |
| 3 | multica-protocol 兼容 (Kanban 风格) | ✅ (基础架构) | R108-3-KANBAN-TASKS-2026-08-13.md |
| 4 | bun build --compile TUI standalone | ✅ | R108-4-BUN-TUI-STANDALONE-2026-08-13.md |
| 5 | SettingsPanel profile 下拉 | ✅ | R108-5-SETTINGS-PROFILE-DROPDOWN-2026-08-13.md |

**新增文件 / 大改**:
- 后端: 1 new RPC pair (transcript_event + getTranscript), 1 new RPC pair (task_event + createTask + updateTaskStatus), 2 new engine fields (transcriptPush + taskPush), 1 new Message.toMap()
- 前端: 1 new component (KanbanPanel), 4 new store actions (hydrateTranscript, createTask, updateTaskStatus, requestConcurrencyProfile already exists), 3 new WS subscribers (transcript_event, task_event, child_session_event)
- TUI: 99 MB standalone binary + 3 new build scripts

**Build artifacts**:
- `aethercode-0.2.1.jar` 39.64 MB (shaded CLI)
- `ac-tui-standalone.exe` 99 MB (Windows x64 standalone TUI)
- 36/36 aethercode-core tests + 9/9 aethercode-tasks tests + 5/5 protocol tests pass

**Test additions**:
- MessageTest: +3 tests (toMap wire shape, Jackson roundtrip, tool_use preservation)
- AppStateListenersTest: +4 tests (onMessageAppend fan-out contract)
- WorkflowExecutorChildSessionTest: +3 tests (child session event forwarding)
