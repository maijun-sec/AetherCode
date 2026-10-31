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
// @testing-library/jest-dom adds the custom matchers
// (toBeInTheDocument, toHaveAttribute, toHaveTextContent,
// etc.) onto Vitest's expect. Without this, every React
// test that uses a DOM matcher crashes with
// "Invalid Chai property: <matcher>".
import '@testing-library/jest-dom/vitest';

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
