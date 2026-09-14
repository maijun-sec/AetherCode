/**
 * Playwright E2E config for the AetherCode desktop renderer.
 *
 * <p>Strategy (chosen 2026-09-14 — see the R-radar-9 / R-E2E-plan
 * doc): the desktop's React app is served by Vite at
 * {@code http://localhost:1420} in dev mode. Playwright drives
 * Chromium against that URL — we deliberately do NOT spin up
 * a real Tauri runtime (no Rust IPC, no WebView2). The desktop
 * code imports {@code @tauri-apps/api/core}'s {@code invoke} /
 * {@code @tauri-apps/api/event}'s {@code listen}, which throw
 * "window.__TAURI_INTERNALS__ is undefined" in a bare-browser
 * context. {@code e2e/_setup.ts} mocks that surface before the
 * React app boots, so the renderer can mount and React can
 * render the welcome / left rail / header without a Tauri host.
 *
 * <p>What this gives us:
 *  - real React rendering (no jsdom / happy-dom shortcuts)
 *  - real CSS layout (left rail, header, welcome tiles)
 *  - real interaction (click, type, scroll)
 *  - real fetch from the daemon? — NO, that's covered by the
 *    TUI's existing e2e-*.mjs and the vitest store tests. The
 *    Playwright layer is for UI-only regressions (R266g's
 *    "SESSIONS section removal" / R267's "switch session
 *    shows history" would have been caught here if it had
 *    existed).
 *
 * <p>What this does NOT cover:
 *  - the Rust IPC bridge (run inside {@code npm run tauri:dev}
 *    for a manual smoke; we don't automate that yet)
 *  - the daemon WS RPC layer (TUI's e2e-*.mjs + vitest store
 *    tests cover that path)
 *  - the cross-platform shell quirks (TUI's t457-e2e-smoke
 *    covers that path)
 *
 * <p>Why not tauri-driver / selenium? — would require
 * MicrosoftWebDriver (Windows Edge) and a full Tauri exe
 * boot per spec. Slow, flaky in CI, and we already have
 * good coverage for the IPC layer via vitest. The UI-only
 * layer is what regresses the most often, so that's what we
 * automate.
 */
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  // The desktop uses jsdom-style globals; tests run in
  // real Chromium against the Vite dev server.
  testDir: './e2e',
  // No retries: a Vite dev server race or a one-off flaky
  // assertion is a real bug, not noise. Re-running masks it.
  retries: 0,
  // CI uses 2 workers locally; bump to 4 once we have >5 specs.
  workers: process.env.CI ? 1 : 2,
  reporter: process.env.CI ? 'github' : 'list',
  // 30s default — Vite dev server can be slow on first
  // request when nothing is cached. The store also does an
  // async RPC burst on mount (getState / getTranscript / etc.);
  // the mock layer in e2e/_setup.ts makes those resolve in ~1ms
  // but the React mount itself is what we're testing.
  timeout: 30_000,
  use: {
    // Vite dev URL — matches the devUrl in tauri.conf.json.
    // We intentionally do NOT use tauri://localhost; the
    // mock __TAURI_INTERNALS__ lives in the regular browser
    // context, not the Tauri protocol handler.
    baseURL: 'http://localhost:1420',
    // Capture the welcome page so a spec failure has the
    // exact frame the test was looking at.
    screenshot: 'only-on-failure',
    // trace dir — same as screenshots, on failure only.
    trace: 'retain-on-failure',
    // The desktop's CommandPalette has its own keyboard
    // shortcuts; we keep actionability's default (10s)
    // because the React app's first paint waits on the
    // mocked Tauri init script to settle.
    actionTimeout: 10_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // Boot Vite as the dev server. We use the `dev` script
  // (vite dev, NOT tauri:dev which would also spawn the
  // Rust host). port: 1420 matches the devUrl in
  // tauri.conf.json. `reuseExistingServer` lets a developer
  // re-run tests against an already-running `npm run dev`
  // without spawning a second Vite.
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:1420',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    // Vite dev writes a lot to stdout; suppress the noise
    // unless the test fails.
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
