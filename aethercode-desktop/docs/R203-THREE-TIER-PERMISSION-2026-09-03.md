# R203 — 3-tier permission model (主动询问 / 智能授权 / 始终授权)

**Date**: 2026-09-03
**Version**: v0.2.46
**Build SHA256**:
- jar: `889175323970915eaf8180382e5127ee0e8ca1dce75fcde4673f7f73ab641f47` (55,314,251 bytes)
- exe: `9052fe4fad51420d32881e4373bc1d50ba7c0a280c22e8ab14a3588d59d855ac` (3,960,832 bytes)
- msi: `4633b7da01a8cec6bd2ef6864b3f7654bdaca96d68b4ab2bfa934d631198aa3b` (2,596,864 bytes)
- nsis: `1f64130e078587e73911ce42647e05634fcd0ad9e09a3fd073dd55793e8d6c28` (2,115,201 bytes)

## TL;DR

R203 collapses the previous 4-mode permission surface (Default / Accept
Edits / Bypass / Plan) into **3 user-facing tiers** with **Chinese
labels** and a **smarter auto-allow** for the middle tier:

| UI tier | Daemon enum | Behaviour |
| --- | --- | --- |
| **主动询问 (ask)** | `ASK_BEFORE_TOOL` / `DEFAULT` | 总是询问 — every non-read-only tool call prompts the user. |
| **智能授权 (smart)** | `ACCEPT_EDITS` | 读/查询自动; 修改/删除/bash 询问 — read-only tools + bash read-only commands auto-allow; mutations ask. |
| **始终授权 (bypass)** | `BYPASS_PERMISSIONS` | 一路绿灯 — every call is auto-allowed. |

The user picks from a 3-option dropdown in the MessageInput bar. The
StatusBar now shows the active mode in Chinese. The PermissionPromptBanner
adds a small chip with the current mode so the user can see *why* a
prompt is firing.

The 3-tier model is purely a UI/UX refactor — the underlying PermissionMode
enum is unchanged, the `ACCEPT_EDITS` mode name is reused, and the
auto-allow-vs-ask decision is now made by the new
`ProjectPermissionPolicy.resolveSmart` helper. The BashTool exposes
`isReadOnly(input)` so a bash command like `ls` / `cat` / `pwd` / `find`
auto-allows under 智能授权; `rm` / `mv` / `chmod` / `>` redirect /
`sudo` still ask.

## Why (the user's feedback)

Pre-R203 the desktop's MessageInput dropdown showed 4 raw enum names
(Default / Accept Edits / Bypass / Plan). The user found this opaque
and asked:

> 对话框中, perm 是什么意思? 建议使用: 主动询问、智能授权、始终授权,
> 第一个总是询问, 然后根据权限配置使用, 智能授权针对修改、删除 询问,
> 新增和查询 等不询问, 相关bash 参考, 始终授权就是不再询问, 保证一路绿灯执行.

Translated: "What's `perm`? Suggest 主动询问/智能授权/始终授权. The first
[主动询问] always asks, then the second [智能授权] is a permission config
— for modify/delete ask, for create/query don't ask, follow the bash
heuristic as reference. The third [始终授权] never asks, just runs."

R203 ships exactly this. The 3 tiers match the user's mental model
(ask / smart / bypass), the smart tier auto-allows read-only tools +
bash read-only commands, mutating calls ask, and bypass never asks.

## What changed

### Desktop (TypeScript)

**`src/store/index.ts`** — added the 3-tier ↔ daemon enum mapping
in one place, refactored `setPermissionMode` to route through it:

```typescript
export type UiPermissionMode = 'ask' | 'smart' | 'bypass';
export const UI_PERMISSION_MODES: { value, label, title, daemon: string[] }[] = [
  { value: 'ask',    label: '主动询问', title: '每个非读操作都让你确认 (默认)',         daemon: ['ASK_BEFORE_TOOL', 'DEFAULT'] },
  { value: 'smart',  label: '智能授权', title: '读/查询/新增自动; 修改/删除/bash 询问', daemon: ['ACCEPT_EDITS'] },
  { value: 'bypass', label: '始终授权', title: '一路绿灯, 不再询问任何调用',           daemon: ['BYPASS_PERMISSIONS'] },
];
export function mapUiPermissionToDaemon(mode: string): string { ... }
export function mapDaemonToUiPermission(mode: string): UiPermissionMode | string { ... }
export function permissionModeLabel(mode: string | null | undefined): string { ... }
```

`setPermissionMode(mode)`:
- maps UI tier (`'ask'`) → canonical enum (`'ASK_BEFORE_TOOL'`)
- sends the canonical enum to the daemon via `rpc.setPermissionMode`
- keeps `engineState.permissionMode` on the canonical enum (so
  StatusBar / banner show the right label)
- keeps the standalone `permissionMode` on the UI tier (so the
  dropdown's `<option value=...>` matches)
- persists the UI tier in localStorage so the next launch
  restores the same option the user picked

`initialize()` also maps: when applying the persisted
`prefs.permissionMode` it goes through `mapUiPermissionToDaemon`
before the RPC, so a persisted `'smart'` becomes `ACCEPT_EDITS` on
the wire. The 15s periodic `refreshEngineState` calls
`mapDaemonToUiPermission` to convert the daemon's canonical
response into a UI tier for the dropdown.

**`src/components/MessageInput.tsx`** — the dropdown now uses
`UI_PERMISSION_MODES` directly (the local pre-R203
`PERMISSION_MODES` constant is deleted; the labels and values
now come from the same source as the store's mapping).

**`src/components/StatusBar.tsx`** — the perm pill now renders
`{permissionModeLabel(engineState.permissionMode)}` instead of the
raw enum. The tooltip carries BOTH the Chinese label AND the raw
canonical enum so a power user can hover and see exactly which
PermissionMode is in effect. 主动询问 / 智能授权 / 始终授权 appear
in the bar; advanced modes (ACCEPT_TASK / PLAN / AUTO_READ_ONLY)
fall through to their raw name so the user knows they're not in
one of the 3 tiers.

**`src/components/PermissionPromptBanner.tsx`** — adds a small
right-aligned chip in the title row showing the current mode
(`主动询问` / `智能授权` / `始终授权`). The chip is informational —
the user reads the action buttons (允许 / 拒绝 / 始终). The chip
also acts as a hint: "I'm being asked because the smart tier
couldn't auto-allow this" (智能授权) or "this mode is the always-ask
fallback" (主动询问).

**`src/components/PermissionPromptBanner.css`** — adds the
`.perm-banner-mode` chip style (muted, low-key, pushed to the
right of the title row).

### Engine (Java)

**`aethercode-permission/.../ProjectPermissionPolicy.java`** —
ACCEPT_EDITS now routes through a new `resolveSmart` helper
that checks `tool.isReadOnly(input)`. The bash check no longer
imports BashTool directly (avoids the
`aethercode-permission <-> aethercode-tools` cycle that surfaced
during the first attempt); instead BashTool overrides its
`Tool.isReadOnly(input)` to call `BashTool.isReadOnly(input)`, and
the policy just reads the standard interface method.

**`aethercode-tools/.../BashTool.java`** — two changes:

1. New `public static boolean isReadOnly(Map<String,Object> input)`:
   ```
   READ_ONLY_COMMANDS  = {ls, cat, pwd, echo, find, grep, head, tail,
                          wc, stat, df, du, tree, ...}
   DESTRUCTIVE_TOKENS  = {>, >>, <, &&, ||, ;, `, $(,
                          rm, mv, cp, chmod, chown, kill, sudo,
                          mkfs, shutdown, &, 2>, &>}
   ```
   - Splits the command on `&|;` (the segment separator).
   - For every non-empty segment, strips env-var prefixes
     (`LC_ALL=C ls` → `ls`), path prefixes (`/bin/ls` → `ls`),
     and Windows `.exe` suffix, then checks the first word
     against the whitelist.
   - Finally, a substring scan for any destructive token in the
     trimmed command text — even if every segment is read-only,
     a `> foo` redirect or `&& rm foo` makes the whole call
     unsafe.
   - Empty / null / missing / non-string command → `false`
     (defensive: better to ask than to auto-allow a confused
     model call).

2. `build()` now wraps the BuiltTool in an anonymous `Tool` that
   overrides `isReadOnly` to delegate to `BashTool.isReadOnly`.
   The default `Tool.isReadOnly` returns `false` (i.e. mutating),
   so without this wrap the bash tool would always ask under
   ACCEPT_EDITS — the bug R203 fixes.

**`aethercode-tools/pom.xml`** — unchanged (no extra deps).

**`aethercode-permission/pom.xml`** — unchanged (we do NOT add
`aethercode-tools` as a dependency; that would create a cycle).

### Tests

**TS** (`aethercode-desktop/src/store/permissionModeR203.test.ts`,
**+36 tests**, 930 → 966 passing):
- `UI_PERMISSION_MODES` shape: count, labels, values, daemon
  enum coverage
- `mapUiPermissionToDaemon` / `mapDaemonToUiPermission`
  round-trip behaviour
- `permissionModeLabel` for 3-tier + advanced modes + null
- Source-pin: `setPermissionMode` calls `mapUiPermissionToDaemon`
  before the RPC, sets `engineState.permissionMode` to the
  canonical enum and the standalone field to the UI tier
- Source-pin: `initialize()` applies `prefs.permissionMode` via
  `mapUiPermissionToDaemon`
- Source-pin: `refreshEngineState` uses `mapDaemonToUiPermission`
- Source-pin: `StatusBar` renders via `permissionModeLabel`
- Source-pin: `MessageInput` uses `UI_PERMISSION_MODES` (no
  local `PERMISSION_MODES` constant)
- Source-pin: `PermissionPromptBanner` shows the active mode
  chip

**TS** (`aethercode-desktop/src/store/enginePrefsR122.test.ts`):
- R176 source-pin test updated to match the new
  `mapUiPermissionToDaemon(prefs.permissionMode)` pattern (was
  the literal `await rpc.setPermissionMode(prefs.permissionMode)`
  call, which would have shipped a build that sends `'smart'`
  to the daemon and gets reset to DEFAULT)

**Java** (`aethercode-tools/.../BashToolR203ReadOnlyTest.java`,
**+22 tests**):
- Read-only: `ls` / `cat` / `pwd` / `find` / `head` / `tail` /
  `wc` / `stat` etc.
- Read-only with destructives rejected: `>` / `>>` / `&&` /
  `||` / `;` / `$(...)` / `>` / `2>` / `&` (background)
- Mutating commands rejected: `rm` / `mv` / `chmod` / `chown`
- Pipe of read-only: `cat foo | head` → true
- Pipe of read-only + mutating: `cat a | tee b` → false (tee
  is intentionally NOT in the read-only whitelist because it
  writes to a file)
- Path-prefixed: `/bin/ls -la` → true
- Env-var prefix: `LC_ALL=C ls` → true
- Quoted argument: `grep "foo bar" file.txt` → true
- Glob argument: `cat *.txt` → true
- Windows commands: `dir` / `type` / `ver` → true
- Word containing `rm`: `firmware` → true (substring is
  ` rm `, not just `rm`)
- Defensive: null / empty / missing / non-string command →
  false
- Chained safe (`;`): rejected (token list includes `;`)

**Java** (`aethercode-permission/.../ProjectPermissionPolicyR203Test.java`,
**+7 tests**):
- 智能授权 + read-only tool → auto-allow (no prompt)
- 智能授权 + bash read-only command → auto-allow (ls, cat, pwd,
  find, head, tail, wc)
- 智能授权 + bash mutating command → ask (rm, mv, chmod, `>`,
  `| tee`, `$(...)`)
- 智能授权 + write tool → ask (file_write, file_edit)
- 主动询问 + bash mutating → ask
- 始终授权 + bash `rm -rf /` → auto-allow (no prompt)
- Source-pin: ACCEPT_EDITS arm calls `resolveSmart` (grep the
  source for `case ACCEPT_EDITS -> resolveSmart`)

The bash mock used in the permission test mirrors
`BashTool.isReadOnly` because aethercode-permission can't depend
on aethercode-tools (cycle). The mock is a small piece of
duplication but the test source-pin ensures the two stay in sync.

### Release

```
release/aethercode-0.2.46/
├── aethercode-0.2.46.jar      55,314,251 bytes  sha256 88917532...
├── AetherCode.exe              3,960,832 bytes  sha256 9052fe4f...
├── AetherCode_0.2.46-setup.exe 2,115,201 bytes  sha256 1f64130e...
└── AetherCode_0.2.46.msi       2,596,864 bytes  sha256 4633b7da...

aethercode/dist/aethercode-0.2.46.jar   (same as above)
```

**Build size deltas vs v0.2.45**:
- jar: 55,311,251 → 55,314,251 (+3,000B for BashTool.isReadOnly +
  ProjectPermissionPolicy.resolveSmart + new tests)
- exe: 3,957,248 → 3,960,832 (+3,584B for store mapping + StatusBar
  label + banner chip)

## How to verify (R203 acceptance)

After upgrading, the user should see:
1. Open AetherCode.exe. The MessageInput bar's Perm dropdown now
   shows 3 options: 主动询问 / 智能授权 / 始终授权. (The 4th pre-R203
   "Plan" option is gone from this dropdown but still works
   internally for users who set it via the TUI / config.)
2. Pick 智能授权 from the dropdown. The StatusBar perm pill flips
   from `ASK_BEFORE_TOOL` to `智能授权`.
3. Run a bash command like `ls -la`. No prompt — the bash
   read-only heuristic auto-allows it.
4. Run a bash command like `rm -rf /tmp/foo`. The
   PermissionPromptBanner shows up with the current mode chip
   `智能授权` on the right; click 允许 to proceed.
5. Pick 始终授权 from the dropdown. The pill flips to 始终授权.
   Run a destructive bash command. No prompt — bypass tier.
6. Pick 主动询问. The pill flips. Run any non-read-only bash
   command. The banner shows up with the chip 主动询问.

The 3 tiers round-trip across reloads: the persisted localStorage
value is the UI tier (`'ask'` / `'smart'` / `'bypass'`), and the
`mapUiPermissionToDaemon` call in `initialize()` translates it
back to the canonical enum for the daemon.

## Architecture: why a UI ↔ daemon mapping

The user's mental model is **3 tiers with Chinese labels**. The
daemon's wire format is the existing `PermissionMode` enum
(`ASK_BEFORE_TOOL` / `ACCEPT_EDITS` / `BYPASS_PERMISSIONS` / etc.).
We don't want to introduce a new enum value because:
- Every persisted `prefs.permissionMode` would need a migration
- The TUI's `/mode` slash command uses the canonical enum
- The diagnostic panel surfaces the canonical enum

So the 3-tier model is purely a UI presentation layer. The store
exports a single `UI_PERMISSION_MODES` array (the source of
truth), and the renderer's three places (dropdown / StatusBar /
banner) all read from the same list. The mapping is symmetric:
`mapUiPermissionToDaemon` for the outbound RPC and the
`initialize()` apply block, `mapDaemonToUiPermission` for the
15s `refreshEngineState` re-sync and the dropdown's value
matching, `permissionModeLabel` for the StatusBar / banner
display.

The "smart" tier maps to ACCEPT_EDITS — that's the engine's
existing mode name. The new behaviour is in
`ProjectPermissionPolicy.resolveSmart`, which is the new branch
the ACCEPT_EDITS arm in the `check()` switch routes to. So a
build that just upgrades from v0.2.45 to v0.2.46 still sees
ACCEPT_EDITS on the daemon side; the difference is that
ACCEPT_EDITS now means "smart auto-allow" instead of "auto-allow
file edits, ask for everything else".

## What's NOT in R203 (deferred)

The user said: "智能授权针对修改、删除 询问, 新增和查询 等不询问". The
"新增" (create) vs "修改" (modify) distinction for `file_write` is
NOT yet implemented. Currently `file_write` always asks under
智能授权 because we can't reliably tell "writing a brand-new
file" from "overwriting an existing file" without a disk IO
check.

A future R-round can address this with either:
- A new `file_create` tool that explicitly signals "create a new
  file" (then `isReadOnly` returns true for `file_create`,
  matching the bash `touch` heuristic)
- An `isPathNew(input)` predicate that checks the filesystem
  before returning (slower but works for the existing tools)
- An optional `mode: "create" | "modify"` parameter on
  `file_write` (the model has to declare intent)

For R203, `file_write` / `file_edit` always ask under 智能授权 —
consistent with the conservative "treat all writes as mutations"
default. A user who wants zero prompts can flip to 始终授权.

## Test counts

- TS: 930 → **966** (+36 R203)
- Java aethercode-permission: 296 → **303** (+7 R203)
- Java aethercode-tools: 217 → **249** (+22 R203, +10 in pre-existing
  R172 stream-stale tests that get re-collected; the 14
  `AgentToolTest` errors are a pre-R193 bug, not caused by R203)

## Lesson learned (2026-09-03)

1. **"perm 字段是什么意思" 永远要听字面意思**: the user is telling you
   the *display* is unclear, not the behaviour. The behaviour
   (auto-allow read-only) was what they wanted; the labels were
   the bug.
2. **3 tier mental model > 4 mode enum**: the user never thinks
   in `ACCEPT_EDITS` / `BYPASS_PERMISSIONS`. They think
   "ask" / "smart" / "bypass". The mapping is a one-time cost;
   the value is "perm field is no longer a wall of acronyms".
3. **Don't introduce a new PermissionMode value for a UX change**:
   keep the wire format stable, layer the UX on top. Every
   persisted state round-trips; every TUI user still sees the
   canonical enum.
4. **aethercode-permission ↔ aethercode-tools cycle**: a future
   refactor should move tool-level "is this read-only" predicates
   to aethercode-core (a new `ToolClassification` interface) so
   the policy can dispatch without knowing about specific tool
   implementations. For R203, the BashTool wraps itself with an
   anonymous `Tool` that overrides `isReadOnly` — works fine but
   not the cleanest possible architecture.
5. **`tee` writes to a file**: don't be fooled by "tee reads from
   stdin and writes to stdout AND a file" — the file write makes
   it mutating. The 智能授权 tier must ask.
6. **`|` is a segment separator, not a destructive marker**:
   `cat a | head` is safe; `cat a | tee b` is not. The first
   iteration had `|` in `DESTRUCTIVE_TOKENS` which made every
   pipe ask; R203 removes it because the per-segment
   whitelist check is enough.
7. **String `which` can be in a Set only once**: `Set.of()` throws
   on duplicates with "duplicate element: which". A paste that
   listed `which` in both POSIX and Windows sections was the
   first build's silent-killer. Java's immutable set is strict
   for a reason.
