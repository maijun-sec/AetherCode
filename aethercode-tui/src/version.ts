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
export const TUI_VERSION = "0.2.1";
