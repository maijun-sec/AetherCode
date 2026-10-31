// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
