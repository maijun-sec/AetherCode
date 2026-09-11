# R205 — readable typography (字体 / 行距 调大)

**Date**: 2026-09-03
**Version**: v0.2.48
**Build SHA256**:
- jar: `6df2681ea26c1a8256f8092a0a2b1a7a1a8573c4c191719ad420427999a23acf` (55,314,251 bytes, unchanged from v0.2.47 — R205 is desktop-only)
- exe: `8cb1aca8440ef071de6f80c22737a87dd5657fb20f80597c4400f1130a975f00` (3,962,368 bytes)
- msi: `df46e41ddb15cb172d21b621fad30c6f8d070eec5938ff832b5c58f1937c7066` (2,600,960 bytes)
- nsis: `9311b65593548b89d1db7cee1a4d3ed87137599b050fc95027cc9613d13c20ca` (2,116,194 bytes)

## TL;DR

Pre-R205 the desktop used IDE-density typography: 13px body,
12px button, 12px input, 1.5 line-height. That's the right
scale for a panel with sub-windows and 18px status pills,
but it's 抠抠搜搜 (stingy / cramped) for full-window reading.
R205 scales the global body / button / input up by ~2px and
adds 0.2 to the line-heights on the main chat reading
surfaces.

| Surface | Pre-R205 | R205 |
| --- | --- | --- |
| `body` | 13px / 1.5 | **15px / 1.7** |
| `button` | 12px / 6px 12px | **14px / 8px 16px** |
| `input` / `select` / `textarea` | 12px / 6px 10px | **14px / 8px 12px** |
| `.message-content` (bubble body) | 14px / 1.65 | **16px / 1.75** |
| `.step-text` (model thinking stream) | 13.5px / 1.6 | **15.5px / 1.7** |
| `.message-assistant .md-body` (legacy) | 14.5px / 1.7 | **16px / 1.8** |
| `.agent-message` (current assistant stream) | 14px / 1.65 | **16px / 1.75** |

The "metadata" sizes (timestamps, tokens, monospace tool
inputs, 10-12px range) are intentionally left small — those
are not the primary reading surface and shrinking them
would make the chat rhythm worse, not better.

## Why

The user said the page font was 抠抠搜搜 and asked for
bigger fonts + bigger line-height for "肉眼看的" (naked-eye
reading). The current 13px / 1.5 was inherited from the
IDE-style "many panels" layout — it makes sense when you
have 14 sub-windows in 1280px, but the AetherCode desktop
is one big chat panel with chrome around it. At full
window, 13px forces the user to lean in.

15px / 1.7 is the same scale as a well-set book page
(Substack, Medium, GitHub markdown body). 16px is the
formal prose pick but 15px matches the panel's chrome
(badges, tabs, dropdown labels) so the page reads as
one scale.

## What changed

### Desktop (TypeScript / CSS)

**`src/styles/global.css`** — `body`, `button`, `input` /
`textarea` / `select`:
- `body` font-size 13px → 15px, line-height 1.5 → 1.7
- `button` font-size 12px → 14px, padding 6px 12px → 8px
  16px (click-target grew proportionally so the button
  is well over the 40px touch-target)
- `input` / `select` / `textarea` font-size 12px → 14px,
  padding 6px 10px → 8px 12px

**`src/components/MessageList.css`** — main chat reading
surfaces:
- `.agent-message` (current assistant stream)
  14px / 1.65 → 16px / 1.75
- `.message-content` (bubble body, user / system
  messages) 14px / 1.65 → 16px / 1.75
- `.step-text` (model thinking stream)
  13.5px / 1.6 → 15.5px / 1.7
- `.message-assistant .md-body` (legacy assistant
  bubble) 14.5px / 1.7 → 16px / 1.8

The "metadata" sizes (timestamps, tokens, status pills,
10-12px range) are intentionally left alone — those are
not the primary reading surface, and shrinking them
would make the chat rhythm worse, not better.

### Tests

**TS** (`aethercode-desktop/src/styles/typographyR205.test.ts`,
**+14 tests**, 982 → 996 passing):
- Source-pin: `body` is 15px / 1.7
- Source-pin: `button` is 14px / 8px 16px
- Source-pin: `input` / `select` / `textarea` is 14px / 8px
  12px
- Source-pin: `.message-content` is 16px / 1.75
- Source-pin: `.step-text` is 15.5px / 1.7
- Source-pin: `.message-assistant .md-body` is 16px / 1.8
- Source-pin: R205 attribution comment is present in
  both `global.css` and `MessageList.css` so a future
  refactor that strips the comment is caught

**TS** (existing test updated for R205):
- `permissionAndSessionR204.test.ts`: the `setPermissionMode`
  test pattern updated to allow the `r: any` annotation
  the R204 rejection path added (TypeScript strict mode
  rejects `r.ok !== false` when `r` is narrowed to `{ok:
  true}`; the `any` cast was the fix in R204, and this
  test pins the new shape).

### Release

```
release/aethercode-0.2.48/
├── aethercode-0.2.48.jar      55,314,251 bytes  sha256 6df2681e...
├── AetherCode.exe              3,962,368 bytes  sha256 8cb1aca8...
├── AetherCode_0.2.48-setup.exe 2,116,194 bytes  sha256 9311b655...
└── AetherCode_0.2.48.msi       2,600,960 bytes  sha256 df46e41d...
```

**Build size deltas vs v0.2.47**:
- jar: 55,314,251 → 55,314,251 (no Java change; same
  SHA256 as v0.2.47 — the bytes are byte-identical)
- exe: 3,962,368 → 3,962,368 (CSS-only change; the
  minified bundle is the same size within rounding,
  the SHA256 differs because tauri embeds a build
  timestamp in the binary)

## How to verify (R205 acceptance)

After upgrading, the user should see:
1. The default font in the chat body is noticeably
   bigger — 15px base + 16px chat body, with 1.7 / 1.75
   line-height. Prose reads comfortably at full-window
   reading distance.
2. Buttons (Mode picker, Tools, +) are readable
   without leaning in. 14px / 8px 16px.
3. The cwd picker / model picker / perm dropdown read
   at the same 14px scale.
4. Metadata (timestamps, token counts, "5h ago") stay
   small — those are intentionally NOT scaled.

## Architecture note: typography tokens vs hard-coded values

The CSS still hard-codes `font-size: 16px` etc. instead
of using a `--font-size-body: 15px` design token. The
hard-coded approach is intentional here:
- The scale is small (3 numbers) and the surface is
  small (3 CSS files). A design-token layer would
  add indirection without buying anything.
- The source-pin tests (typographyR205.test.ts) catch
  a refactor that accidentally shrinks the scale,
  which is the regression we care about.
- The 14 source-pin tests in `typographyR205.test.ts`
  are the "design token contract" — a refactor that
  moves to `:root { --font-size-body: 15px }` should
  update the test regex accordingly.

A future R-round can promote the scale to design
tokens if more surfaces (e.g. a Settings panel theme
editor, a per-component density preference) need
runtime control.

## Test counts

- TS: 982 → **996** (+14 R205)
- Java: no changes (R205 is desktop-only)

## Lesson learned (2026-09-03, part 3)

1. **"抠抠搜搜" 永远是 literal 字号问题**: the user said
   the page was 抠抠搜搜 and asked for bigger. The fix
   is to scale the body and the main reading surface,
   not to add a "comfortable reading mode" toggle. The
   IDE-density numbers were right for the IDE-era
   layout; the chat-first era needs prose-density
   numbers.
2. **不要全文件 18px**: scaling every font uniformly
   makes the page look like a children's book. The
   primary reading surface (chat body) goes to 16px;
   the metadata (timestamps, tokens) stays small. The
   rhythm of "big body / small metadata" is what makes
   the page feel right.
3. **Click target 跟 font 一起涨**: 12px → 14px font
   means 6px 12px padding → 8px 16px padding. Don't
   forget the touch-target guideline; the button
   should still be over 32px tall.
4. **Source-pin test 抓回缩**: the user just said the
   page is too small. A future "let's tighten up
   the CSS" refactor could accidentally shrink it
   back. The 14 source-pin tests in
   `typographyR205.test.ts` catch that — the size
   numbers are the new contract.
5. **`-apple-system, BlinkMacSystemFont, 'Segoe UI',
   Roboto, sans-serif` 已经够用了**: 这次没改
   font-family, 因为跨平台的回退链已经覆盖了
   Windows / macOS / Linux 的默认系统字体。改
   font-size 比改 font-family 影响大,而且
   font-family 改错了会导致 跨平台渲染差异。
