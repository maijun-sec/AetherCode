# R-E2E: desktop Playwright E2E suite

## Trigger
The user asked (2026-09-14, after 0.2.67 release) to run the
test suite and, if possible, automate the desktop's app
operations. The desktop had a 1042-test vitest suite covering
store / reducer / component logic, but no end-to-end check
that the React app actually mounts + renders in a real
browser. UI-only regressions (R266g "SESSIONS section
reappeared" / R267 "switching session shows blank chat")
would have been caught by an E2E layer.

## Choice
Picked **Playwright + Tauri dev server (option A)** from the
questionnaire because:
- same Node ecosystem as the TUI's existing `node --test`
  e2e harness (`scripts/test/e2e-*.mjs`); one tool, one CI
- no new driver dependency (no MicrosoftWebDriver /
  selenium), no platform-specific config
- Vite dev server was already on port 1420 matching
  `tauri.conf.json`'s `devUrl`, so the test target is
  exactly the same URL the Tauri webview loads in dev

Rejected **B (tauri-driver)** — requires Edge WebView2
driver per platform, slow CI boot, redundant with the
vitest store tests for the IPC surface.

Rejected **C (CDP + ad-hoc)** — too low-level for the
common cases (typing into a text field, clicking a
button); Playwright already wraps the same primitives.

## Implementation

- `playwright.config.ts` (97 lines) — Vite dev boot via
  `webServer`, baseURL=http://localhost:1420, Chromium
  project, screenshot+trace on failure, 30s timeout.
- `e2e/_setup.ts` (180 lines) — installs
  `window.__TAURI_INTERNALS__` + the event plugin shim
  before React boots. Per-spec RPC overrides go through
  `window.__E2E_MOCK__.setInvokeHandler(method, fn)`.
  Default RPC responses for the 14 methods the store's
  init burst calls (getState / listTools / listSessions
  / getTranscript / etc.) are shaped so the renderer
  never NPEs.
- `e2e/_fixtures.ts` — `test` fixture that wires the
  setup into every page.
- `e2e/hello.spec.ts` — smoke: AetherCode brand h1
  visible, 3 welcome tiles render, header status badge
  shows the model pill.
- `e2e/sessions.spec.ts` — pins the two recent UI fixes
  so they can never silently regress:
  - **R266g**: `session-list .section-header` has 0
    elements (the inner "SESSIONS" bar is gone inside
    a project group).
  - **R267**: clicking a historical session row makes
    the historical user message land in the chat log
    within 5 seconds (the sync commit + async hydrate
    + render path).
- `e2e/README.md` — what this does, what it doesn't, how
  to add a new spec.
- `package.json` — new `test:e2e` script:
  `playwright test`.

## Numbers
- 4 Playwright tests, ~11s for the whole suite
- vitest regression: 1042/1042 still pass
- mvn regression: aethercode-protocol 269/269 still
  pass (R266h + test fix); other modules unchanged
- 0 new dependencies beyond `@playwright/test` (Playwright
  bundles its own browser download tool)

## What is still NOT covered
- Rust IPC bridge — needs a real Tauri exe boot, exercised
  manually via `npm run tauri:dev` + click-through smoke
- Daemon WS RPC contract — covered by the TUI's existing
  `e2e-*.mjs` harness and the aethercode-cli integration
  tests
- Cross-platform shell quirks — covered by the TUI's
  `t457-e2e-smoke` and the per-platform `BashToolSandboxTest`

## Maintenance
The mock in `e2e/_setup.ts` mirrors the `rpc_call` shapes
the daemon's `AetherCodeMethods` returns. When a new
method is added to the daemon, add a default
`switch (method)` arm so the renderer's first paint
doesn't crash on the new RPC. The shape is also pinned
in `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
— keep them in sync.
