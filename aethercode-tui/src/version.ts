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

/**
 * The TUI's own version, in ONE place.
 *
 * ## Why this file exists
 *
 * The version string used to live in `src/ac-tui.ts` as a private
 * `const VERSION = "0.2.1"`, which meant:
 *
 *   - the `/update` panel could not show it without importing
 *     `ac-tui.ts` — which imports `tui.tsx` back, so that would have
 *     deepened an existing ESM cycle between the two modules;
 *   - the `--version` banner and the panel would each hold their own
 *     copy the day a second consumer appeared.
 *
 * A leaf module with no imports has neither problem. `ac-tui.ts`
 * imports it, the panel imports it, and there is exactly one string.
 *
 * ## The remaining drift
 *
 * This file and `aethercode-tui/package.json` are still two copies,
 * and `aethercode-cli/.../TuiCommand.java` is a third (it prints
 * `aethercode-tui 0.2.1` in the launcher's own banner). The gate
 * `scripts/test/r374-update-panel.test.mjs` pins THIS file against
 * `package.json`, which catches the copy that is easy to forget when
 * a release bumps the version. The Java one lives in another module
 * and is not reachable from the TUI's test scope.
 */
export const TUI_VERSION = "0.2.74";
