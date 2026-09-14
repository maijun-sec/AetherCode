# desktop E2E (Playwright + Vite dev server)

## Why

The desktop's vitest suite covers store / reducer / component
logic in isolation (1042 tests). It does NOT exercise the
real renderer — a real React mount, a real CSS layout, a
real Vite HMR pipeline. The most common regressions on the
desktop have been UI-shape bugs (a label moved, a header
re-appeared, a click handler stopped firing) that vitest
cannot catch.

Playwright fills that gap. The renderer is driven by
Chromium against the Vite dev server at
`http://localhost:1420` (the same URL the Tauri webview
uses in dev mode). We deliberately do NOT spin up a real
Tauri runtime — see `playwright.config.ts` for the rationale.

## Layout

- `playwright.config.ts` — Vite dev server boot, Chromium
  project, screenshot + trace on failure
- `e2e/_setup.ts` — Tauri mock (`window.__TAURI_INTERNALS__`
  + `__TAURI_EVENT_PLUGIN_INTERNALS__` shim, per-spec
  override hook on `window.__E2E_MOCK__`)
- `e2e/_fixtures.ts` — `test` fixture that installs the
  mock on every page before navigation
- `e2e/hello.spec.ts` — smoke: render the welcome page,
  render the header
- `e2e/sessions.spec.ts` — pins R266g (no inner SESSIONS
  header inside a project group) + R267 (clicking a
  historical session loads its transcript into the chat)

## Running

```bash
# from aethercode-desktop/
npm run test:e2e          # headless, ~15s
npx playwright test --ui  # interactive
npx playwright test e2e/sessions.spec.ts  # one file
```

`npm run dev` must NOT be running separately — Playwright
boots Vite itself (`webServer.command: 'npm run dev'`),
with `reuseExistingServer: true` so a manual `npm run dev`
during development is shared.

## What this does NOT cover

- the Rust IPC bridge — a real Tauri exe boot is needed
  for that. We exercise it manually via
  `npm run tauri:dev` + a click-through smoke. The TUI
  layer's existing `e2e-*.mjs` and the daemon's
  integration tests cover the WS RPC contract that
  crosses the IPC bridge.
- the daemon process — same as above; the store is
  tested with mocked RPC responses here, and the real
  daemon is tested via the TUI's e2e harness and the
  `aethercode-cli` integration tests.
- cross-platform shell quirks (Windows ConPTY vs Linux
  pty vs macOS pty) — covered by the TUI's
  `t457-e2e-smoke` and the per-platform
  `BashToolSandboxTest` in `aethercode-tools`.

## Adding a new spec

1. Use the `mockedPage` fixture (it auto-installs the
   Tauri mock) — `import { test, expect } from './_fixtures'`.
2. Override RPC responses with
   `await mockedPage.addInitScript(...)` if the test
   needs a non-default state (e.g. seeded sessions).
3. Pin structural assertions, not exact strings. The
   UI is bilingual (CN + EN) and the user explicitly
   asked to keep it that way. Pin CSS classes and
   structural shapes; let the wording shift.
4. Don't pin Chinese strings unless the test is
   specifically about a CN label. Use
   `getByText(/regex/i)` for case-insensitive matches
   on stable English keywords ("AetherCode", "Idle",
   "MiniMax-M3", etc.).

## Maintenance

The mock in `e2e/_setup.ts` mirrors the `rpc_call` shapes
the daemon's `AetherCodeMethods` returns. If a new
method is added to the daemon, add a default
`switch (method)` arm to the mock so the renderer's
first paint doesn't crash on the new RPC.
