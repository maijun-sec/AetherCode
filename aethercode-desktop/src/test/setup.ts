// Vitest setup file. Runs once before any test file in the
// project. Used to declare a few globals that the page /
// e2e tests reference without an explicit import (a
// small DX shortcut that mirrors Jest's `globalSetup`
// pattern).
//
// Hoisted as a `__autoCleanup` global: every test file
// in the project calls `__autoCleanup()` at the top of
// its describe block. The body registers a vitest
// `afterEach(cleanup)` so the @testing-library/react
// tree is torn down between tests — without it, the
// store / QueryClient state leaks across cases.

import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

declare global {
  // eslint-disable-next-line no-var
  var __autoCleanup: () => void;
}

// Always register an afterEach(cleanup) here. The bare
// `cleanup()` is what @testing-library/react ships to
// unmount every component mounted via `render`. The
// auto-cleanup via __autoCleanup() is a no-op when this
// is already set; we register it here so even files
// that don't call __autoCleanup get the isolation.
afterEach(() => {
  cleanup();
  // Belt and braces: also wipe body in case a stray
  // container survived (e.g. if a test threw mid-mount).
  if (typeof document !== 'undefined' && document.body) {
    document.body.innerHTML = '';
  }
});

(globalThis as any).__autoCleanup = () => {
  // No-op now that the setup file has registered
  // afterEach(cleanup) globally. Kept for backwards
  // compat with test files that still call it.
};
