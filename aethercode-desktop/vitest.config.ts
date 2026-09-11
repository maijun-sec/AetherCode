/// <reference types="vitest" />
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // The pre-existing tests (rpc, status bar, settings panel, etc.) all
    // run under node — they don't need jsdom because they exercise the
    // Zustand store / pure reducers, not React. The Phase 3-7 additions
    // (AppContext, pages, ConsentModal, etc.) need a DOM to mount React,
    // so we declare the per-file environment via a comment in the test
    // (`// @vitest-environment jsdom`). Default stays node for the
    // existing 483 tests to keep their runtime fast.
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
    // Provide a couple of common globals for the React tests so they
    // don't have to import them in every file.
    globals: false,
    // Hoist the `__autoCleanup` global so the page / e2e tests
    // can call it without an explicit import.
    setupFiles: ['./src/test/setup.ts'],
  },
});
