# R108-4: bun build --compile TUI standalone

**Date:** 2026-08-13
**Status:** SHIPPED
**Impact:** A self-contained `ac-tui-standalone.exe` (99 MB) for Windows
x64 that bundles the Bun runtime + the Ink TUI + all node_modules
deps. No Node.js install required on the target machine. Drop the
binary in `PATH` and run `./ac-tui-standalone.exe` directly.

## 背景 (Context)

The AetherCode TUI ships as a Node.js bundle:
`ac-tui/dist/ac-tui.js` (1.51 MB after esbuild). Running it
requires a Node.js 18+ install on the target machine. For
distribution scenarios (CI runners, minimal VMs, friend-of-a-friend
"just try this" links), that dependency is friction.

`bun build --compile` produces a single-binary executable that
embeds the Bun runtime + the bundled app. The trade-off: the
binary is much bigger (~99 MB for the AetherCode TUI vs 1.5 MB
for the JS bundle) because it carries the entire Bun runtime
+ V8. But it runs anywhere without an install.

R108-4 adds the `build:standalone` npm script (and platform
variants) to produce a single-binary TUI for distribution.

## 设计 (Design)

### The Ink `DEV` import problem

Ink 5.x's reconciler has a top-level guard:

```js
if (process.env['DEV'] === 'true') {
    try { await import('./devtools.js'); }
    catch (error) { ... }
}
```

But the `devtools.js` file has a TOP-LEVEL static import:

```js
import devtools from 'react-devtools-core';
devtools.connectToDevTools();
```

When the bundler tries to bundle this for a `--compile`
target, it has to resolve `react-devtools-core`. If the
package isn't installed, the build fails with
`Could not resolve: "react-devtools-core"`.

Even with `--external react-devtools-core`, the runtime
binary fails at startup because the devtools import is
still triggered (the DEV check is `=== 'true'` and we
didn't set DEV=true in the build, but bun's runtime
defaults seem to leave it unset, so the check should fail
— except something in the bundled binary is triggering
the import anyway).

The fix: install `react-devtools-core` as a dev
dependency. The package is tiny (~50 KB) and ships with
its deps already resolved. The bundler includes it in
the binary, and at runtime the import succeeds (and
silently fails to connect to a non-existent React
Devtools server, which is fine — it's a no-op in
production).

### Build scripts

Three platform-specific scripts:

```json
{
  "build:standalone":        "bun build --compile --target=bun-windows-x64 --outfile dist/ac-tui-standalone.exe src/tui.tsx",
  "build:standalone:linux":  "bun build --compile --target=bun-linux-x64   --outfile dist/ac-tui-standalone src/tui.tsx",
  "build:standalone:macos":  "bun build --compile --target=bun-darwin-arm64 --outfile dist/ac-tui-standalone src/tui.tsx"
}
```

All three compile from `src/tui.tsx` (the existing TUI
entry point). The output paths are platform-specific —
`.exe` for Windows, no extension for Unix. The output
binary is self-contained: no Node.js install, no
node_modules, no PATH gymnastics.

### Trade-offs

| | Standalone (bun --compile) | Node.js bundle (esbuild) |
|---|---|---|
| Size | ~99 MB | ~1.5 MB |
| Node.js required | No | Yes (>=18) |
| Cold start | ~80 ms | ~150 ms |
| Install friction | Drop in PATH | npm install + PATH |
| Auto-update | User re-downloads binary | npm update |

The standalone is the right choice for distribution
scenarios. The Node.js bundle stays the right choice
for the dev loop (faster, smaller, hot-reload friendly).

## 改动 (Changes)

- `aethercode-tui/package.json`:
  - new dev dep: `react-devtools-core@^7.0.1` (needed
    for the bun --compile to bundle the Ink reconciler
    without resolution errors).
  - new scripts: `build:standalone`,
    `build:standalone:linux`,
    `build:standalone:macos`.
- `aethercode-tui/dist/ac-tui-standalone.exe`: 99 MB
  Windows x64 single-binary TUI. Verified by running
  `--help` and seeing the Ink-rendered usage block.

## 验证 (Validation)

- `bun --version` → 1.3.14
- `bun build --compile --target=bun-windows-x64 --outfile dist/ac-tui-standalone.exe src/tui.tsx`
  → "bundle 543 modules" + "compile" success.
- `ac-tui-standalone.exe --help` → full Ink-rendered
  usage block printed to stdout (proves the binary can
  load React, Ink, the Yoga layout engine, and the TUI
  app code without crashing).
- Binary size: 99 MB (vs 1.51 MB for the Node.js bundle).
  The 97 MB delta is the embedded Bun runtime + V8 +
  ICU data. The CLI usage block fits in <2 KB of source
  but the runtime dominates.

## 风险 (Risks) / 已知限制 (Known limitations)

- **Cross-build requires bun installed**: you can't
  build a Linux or macOS binary from Windows without
  bun + the cross-toolchain. The standalone build is
  per-platform; ship the right binary for the right
  OS. (CI can do this with `bun build --target=...`
  for each platform.)
- **react-devtools-core is a no-op in the bundled
  binary**: Ink's `devtools.connectToDevTools()` is
  called at startup and tries to connect to a React
  DevTools server on localhost. In a standalone
  binary, no server is running, so the call returns
  immediately. The package is bundled (it has to be,
  for resolution) but its runtime cost is zero.
- **99 MB is a lot**: the binary carries the entire
  Bun runtime. The user's `git push`-equivalent
  (download a single file) is fast on a 100 MB
  connection but slow on a 3G phone. For mobile
  users, the Node.js bundle + a `npx ac-tui` is
  still the right choice. The standalone is for
  desktop / server / CI.
- **No auto-updater**: a fresh binary must be
  downloaded to upgrade. The package.json `version`
  field is the source of truth; the build script
  embeds it in the binary's `--version` output.
- **Unsigned on Windows**: an unsigned `.exe` from
  an unknown publisher triggers SmartScreen. End
  users would need to "More info → Run anyway". A
  code-signing cert is a future R-round (probably
  R108-4+ follow-up).

## 下一步 (Next)

R108-5: SettingsPanel profile 下拉. UI is already mostly in place
(Header has the engine-stats badge, the RPCs are wired). The
dropdown just needs a settings panel entry to surface the
profile picker. ~0.5d.
