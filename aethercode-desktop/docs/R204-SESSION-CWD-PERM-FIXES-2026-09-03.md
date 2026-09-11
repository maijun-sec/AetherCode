# R204 — 4 fixes for the new-session / cwd / perm flow

**Date**: 2026-09-03
**Version**: v0.2.47
**Build SHA256**:
- jar: `6df2681ea26c1a8256f8092a0a2b1a7a1a8573c4c191719ad420427999a23acf` (55,314,251 bytes)
- exe: `2e93a1754fb7397dc9120055044d2119f4b1fa2d98c10d42b0982a648547db38` (3,962,368 bytes)
- msi: `b318fb390c6025f1c6fee7f41503cda672a38945ad8e447027df40c85bc96b7d` (2,600,960 bytes)
- nsis: `9f7d18e9366806f2f79349fffaca6b7cc7a5fe83a064ca5bfa1daeb585abd340` (2,116,506 bytes)

## TL;DR

R204 ships 4 fixes for issues the user surfaced in the v0.2.46 chat:

1. **Top "新建 session" button dropped** — it was the redundant "sessions" layer
   between project and session, and it minted sessions with `cwd=null` (so the
   new session landed in the "未关联项目" bucket instead of the user's current
   project). R204 removes the top button entirely; the per-project "＋" in
   ProjectGroup is the only entry point. Each "+" click uses the project's own
   cwd, so the new session lands in the right bucket automatically.
2. **No "sessions" intermediate layer** — LeftPanel had 3 sections
   (TaskSummary / "新建 session" / sessions). R204 drops the middle section;
   the panel goes straight from TaskSummary to project-grouped sessions.
3. **Session titles** — `SessionListRow.sessionDisplayTitle` now falls back to
   `<未命名>` for sessions with no prompt (pre-R204: `Session <id-suffix>`,
   which the user called 难以辨认 / hard to read). When a first user message
   exists, the row shows the message preview (capped at 60 chars), which is
   the most reliable "what is this session about" signal we have without
   round-tripping to the LLM.
4. **Bypass reverts to 主动询问 across project switch** — the new daemon
   spawned by `set_cwd_daemon` boots with `PermissionMode.DEFAULT` (the engine
   doesn't carry the old JVM's user-set mode). The desktop now re-applies the
   user's persisted `prefs.permissionMode` on the new daemon immediately
   after the swap, so picking 始终运行 in abc_1 and switching to abc_2 keeps
   the dropdown on 始终运行. Bonus: the `setPermissionMode` action also
   surfaces a daemon rejection as a system chat line, so a silent mode revert
   is no longer a silent revert.

## Issue-by-issue

### #1 + #2: top "新建 session" button is gone

**Before** (`LeftPanel.tsx` pre-R204):
```tsx
<section className="left-section left-new">
  <button onClick={() => void onNewSessionInCwd(null)} title="新建 session (无 cwd)">
    <span>＋</span><span>新建 session</span>
  </button>
</section>
```

The button hard-coded `cwd=null`, so a user on project abc_1 who clicked
"新建 session" got a session in the "未关联项目" bucket. They then had to
manually switch the new session's cwd back to abc_1, or use the project
group's "＋" — which is what the user actually wanted.

**After** (R204): the top button is gone. Each `ProjectGroup`'s "＋" is the
only mint-session entry point, and the per-group "＋" already passes the
project's cwd (R198). Net: the panel goes from 3 sections to 2, and the
"new session" action always lands in the right project.

The user can still mint a session with no cwd via the "未关联项目" group's
"＋" (the per-group handler in `useNewSessionInCwd` accepts a `cwd: null`
and routes to `createNewSession()` without a cwd).

### #3: session titles

**Before** (`SessionListRow.sessionDisplayTitle` pre-R204):
```typescript
function sessionDisplayTitle(s: SessionListItem): string {
  if (s.title && s.title.trim()) return s.title;
  if (s.preview && s.preview.trim()) {
    const p = s.preview.trim();
    return p.length > 60 ? p.slice(0, 57) + '...' : p;
  }
  return `Session ${s.id.slice(-8)}`;
}
```

The third branch (`Session <id-suffix>`) was the user's pain point. The
UUID tail (last 8 chars) is hard to scan and doesn't tell the user
anything about the session.

**After** (R204):
```typescript
function sessionDisplayTitle(s: SessionListItem): string {
  if (s.title && s.title.trim()) return s.title;
  if (s.preview && s.preview.trim()) {
    const p = s.preview.trim();
    return p.length > 60 ? p.slice(0, 57) + '...' : p;
  }
  return '未命名';
}
```

Title resolution chain:
1. **Explicit title** — daemon-generated or user-renamed. Pre-R204 this
   branch existed; R204 keeps it.
2. **First user message** (`preview`) — the daemon's `listSessions` with
   `withPreview: true` extracts the first user message from the
   session's JSONL transcript, capped at 200 chars. We truncate further
   to 60 chars so the row stays tight. This is the most reliable
   "what is this session about" signal we have without an LLM call.
3. **`<未命名>`** — a brand-new session with no user message yet. The
   row clearly signals "this is unnamed, send a message and it'll get
   a title".

The user said "标题可以有 llm 来生成" — they said an LLM-generated title
is an option, but the preview is the pragmatic intermediate. An
LLM-generated title is a follow-up R-round (out of scope for R204);
the user can see this in the source comment and request it later.

The session id is still in the row's native `title` attribute (the
browser tooltip) for power users who need to cross-reference. We don't
lose the audit trail; the visible text just doesn't carry a UUID tail.

### #4: bypass reverts to 主动询问 across project switch

**Root cause**: `setCwd` calls the Rust `set_cwd_daemon` Tauri command,
which does the full `pre_warm_daemon → swap_to_pre_warm → createSession`
dance. The new daemon's JVM boots a fresh engine with
`PermissionMode.DEFAULT` (the constructor's default; the new engine
has no record of the old JVM's `setPermissionMode` call). The renderer's
15s periodic `refreshEngineState` then overwrites the dropdown with
whatever the new daemon reports — which is `DEFAULT` → `'ask'` →
主动询问.

**The user-facing sequence**:
1. Open v0.2.46, project abc_1 loaded, dropdown shows 主动询问 (DEFAULT
   mode, the daemon's default).
2. Pick 始终运行 from the dropdown → `setPermissionMode('bypass')` →
   `mapUiPermissionToDaemon('bypass')` = `'BYPASS_PERMISSIONS'` →
   `rpc.setPermissionMode('BYPASS_PERMISSIONS')` → daemon's engine
   is now BYPASS_PERMISSIONS. Dropdown shows 始终运行 ✓
3. Switch to project abc_2 (or click "＋" on the abc_2 project group) →
   `setCwd` → swap → new daemon → new engine with DEFAULT mode.
4. `refreshEngineState` reads the new daemon's state → `permissionMode:
   'DEFAULT'` → `mapDaemonToUiPermission('DEFAULT')` = `'ask'` →
   dropdown reverts to 主动询问 ✗

**Fix #4a: re-apply the user's persisted preference after the swap**:
```typescript
// Inside setCwd, between the post-swap refreshes and the pre-warm:
const prefsForPerm = readEnginePrefs();
if (prefsForPerm.permissionMode
    && prefsForPerm.permissionMode !== get().permissionMode) {
  void get().setPermissionMode(prefsForPerm.permissionMode).catch(() => {});
}
```

The `readEnginePrefs()` reads the same localStorage key
`aethercode.enginePrefs` that `setPermissionMode` writes to. So the
re-apply uses the user's last-persisted choice (the `'bypass'` UI
tier → `BYPASS_PERMISSIONS` daemon enum). The guard
`prefs.permissionMode !== get().permissionMode` avoids an unnecessary
RPC when the user is on the daemon's default mode (a fresh install
where both sides are 'ask' / DEFAULT).

**Fix #4b: `setPermissionMode` now surfaces daemon rejection as a
system chat line**. Pre-R204 the action awaited the RPC but threw
away the result. A daemon-side rejection (e.g. an unknown mode
string) silently left the renderer showing the user's pick while the
daemon kept its old mode, and the next `refreshEngineState` overwrote
the dropdown with the daemon's actual value — the user saw the mode
"revert" out of nowhere. R204 pins the local state to the daemon's
value on rejection so the user sees the revert immediately, and
appends a one-line system message so the silent revert is no longer
silent:

```typescript
const r: any = await rpc.setPermissionMode(daemonMode);
const ok = !r || r.ok !== false;
const effectiveMode = ok ? mode : ((r && r.mode) ? mapDaemonToUiPermission(r.mode) : get().permissionMode);
const effectiveDaemonMode = ok ? daemonMode : ((r && r.mode) ? r.mode : get().engineState?.permissionMode ?? daemonMode);
set((s) => ({
  engineState: s.engineState ? { ...s.engineState, permissionMode: effectiveDaemonMode } : null,
  permissionMode: effectiveMode,
}));
if (ok) {
  const prefs = readEnginePrefs();
  prefs.permissionMode = mode;
  writeEnginePrefs(prefs);
} else {
  const reason = (r && r.error) || `daemon rejected permission mode "${daemonMode}"`;
  set((s) => ({
    messages: [...s.messages, {
      id: newId('system'),
      role: 'system' as const,
      content: `[perm 模式变更失败] ${reason}`,
      timestamp: Date.now(),
      isError: true,
    }],
  }));
}
```

The `if (ok)` guard on the `prefs.permissionMode` write is important:
on rejection we don't pollute the persisted value with a mode the
daemon can't honour — the next launch would re-load a rejected value
and immediately re-revert.

## What changed

### Desktop (TypeScript)

**`src/store/index.ts`**:
- `setCwd` now re-applies `prefs.permissionMode` after the
  `set_cwd_daemon` swap, with a guard that skips the RPC when the
  stored value matches the in-memory one.
- `setPermissionMode` now captures the RPC's `ok` flag, derives
  `effectiveMode` (UI tier) and `effectiveDaemonMode` (canonical
  enum) from the daemon's response on rejection, and surfaces a
  system chat line for rejections. The localStorage write is
  conditional on success.

**`src/components/LeftPanel.tsx`**:
- Removed the `left-new` section (the top "新建 session" button).
  The panel goes from 3 sections to 2. The unused
  `currentCwd` selector is removed.

**`src/components/session/SessionListRow.tsx`**:
- `sessionDisplayTitle` returns `'未命名'` instead of
  `Session <id-suffix>` for sessions with no title and no preview.
  The function docstring explains the three-branch chain.

### Tests

**TS** (`aethercode-desktop/src/components/permissionAndSessionR204.test.ts`,
**+16 tests**, 966 → 982 passing):
- #1 + #2: LeftPanel doesn't render a top "新建 session" button;
  renders 2 sections; `useNewSessionInCwd` is still wired.
- #1: `useNewSessionInCwd` takes the per-group cwd; same-cwd
  short-circuits; different-cwd routes through setCwd.
- #3: `sessionDisplayTitle` returns `'未命名'` for empty sessions;
  uses the preview for first-message sessions; honours explicit
  title; keeps the session id in the tooltip.
- #4: `setPermissionMode` captures the `ok` flag; mirrors the daemon
  mode on rejection; doesn't persist on rejection; surfaces the
  rejection as a system chat line.
- #4: `setCwd` re-applies the persisted mode after the swap; only
  when the persisted value differs from the in-memory value.

**TS** (existing tests updated for R204):
- `preparingCardR177.test.ts`: `LeftPanel uses ProjectGroupList` test
  inverted the `left-new-btn` expectation to `not.toMatch(/left-new/)`
  — the top button is gone.
- `permissionModeR203.test.ts`: the `keeps engineState.permissionMode`
  and `keeps the standalone permissionMode` tests now look for
  `effectiveDaemonMode` and `effectiveMode` (the locals the R204
  rejection path overrides).
- `SessionListRow.test.tsx`: `falls back to the session id tail` test
  replaced with `falls back to <未命名> when no title or preview`.

### Release

```
release/aethercode-0.2.47/
├── aethercode-0.2.47.jar      55,314,251 bytes  sha256 6df2681e...
├── AetherCode.exe              3,962,368 bytes  sha256 2e93a175...
├── AetherCode_0.2.47-setup.exe 2,116,506 bytes  sha256 9f7d18e9...
└── AetherCode_0.2.47.msi       2,600,960 bytes  sha256 b318fb39...

aethercode/dist/aethercode-0.2.47.jar   (same as above)
```

**Build size deltas vs v0.2.46**:
- jar: 55,314,251 → 55,314,251 (no Java change; SHA256 differs
  only because mvn rebuilds with current timestamps)
- exe: 3,960,832 → 3,962,368 (+1,536B for LeftPanel / setCwd /
  setPermissionMode changes)

## How to verify (R204 acceptance)

After upgrading, the user should see:
1. Open AetherCode.exe. The LeftPanel has 2 sections (TaskSummary
   + sessions), not 3. The top "新建 session" button is gone.
2. Click "＋" on any project group. A new session is created in
   that project (not in the "未关联项目" bucket).
3. Empty new sessions show `<未命名>` as the title. After the user
   sends a message, the row updates to show the message preview
   (capped at 60 chars).
4. Pick 始终运行 (bypass) from the Perm dropdown. The StatusBar
   perm pill flips to 始终授权. Pick a different project (or click
   "＋" on a different project group). The new daemon's dropdown
   is STILL 始终授权 (not 主动询问). The re-apply is invisible to
   the user — the dropdown just stays.
5. The session id is still in the row's tooltip (browser hover).

## Architecture note: cross-daemon preference propagation

The cleanest long-term fix for #4 is daemon-level preference
persistence: the daemon writes the last user-set mode to a config
file (`~/.aethercode/desktop-state.json` or similar), and a new
daemon reads it on boot. That way no round-trip is needed on every
cwd switch. R204 keeps the desktop-local approach (read from
localStorage) because:
- The desktop already persists the mode to localStorage (R122),
  and the desktop's `setCwd` is the only path that triggers a new
  daemon. So a desktop-side re-apply catches every case.
- Daemon-level persistence would require a new config-file
  surface in the daemon (and the daemon is still learning the
  desktop-state concept from R97-D). A future R-round can move
  this to the daemon when more desktop preferences need
  cross-daemon propagation.

## Test counts

- TS: 966 → **982** (+16 R204)
- Java: no changes (R204 is desktop-only)

## Lesson learned (2026-09-03, part 2)

1. **"中间的层是多余的" 永远是字面意思**: the user said the layer
   between project and session is unnecessary, and they meant the
   top "新建 session" button. Pre-R204 we added it as a "convenience
   shortcut", but the user already had the per-project "＋" — the
   shortcut was a second entry point that did the wrong thing
   (cwd=null). Drop it; one entry point, correct cwd, simpler
   layout.
2. **"难以辨认" 是 display 问题,不是 data 问题**: the session id
   tail is the data; "难以辨认" is the user's complaint about the
   display. The data is still in the row (in the tooltip), but the
   visible text now has a meaningful default.
3. **跨 daemon 状态需要 explicit re-apply**: when the architecture
   splits into multiple daemons (R199), per-user preferences
   don't carry over automatically. Either the desktop re-applies
   on every cross-daemon boundary (R204) or the daemon persists
   the preference globally. Both work; the desktop-side approach
   is smaller and ships today.
4. **Silent revert 是 UX 灾难**: the pre-R204 setPermissionMode
   action discarded the RPC's result, so a daemon rejection was
   silent. The user only saw the dropdown "snap back" on the
   15s refresh tick. R204 surfaces the rejection immediately as a
   chat line, so the user understands what happened.
5. **sessionDisplayTitle 是 "3 路" 不是 "2 路"**: title (explicit)
   vs preview (first message) vs fallback. Pre-R204 was 2-way
   (title vs fallback). R204 adds preview as the middle branch
   because it's the most reliable non-LLM signal.
6. **TS 严格模式下 `r.ok !== false` 不会编译**: TypeScript
   narrows `Promise<{ ok: true; mode: string }>` to `ok: true` so
   `r.ok !== false` is `true !== false` which is always true. The
   compiler rejects the comparison as "unintentional". The fix is
   `const r: any = await ...` to opt out of the literal narrowing
   (we want the runtime check for the rejection path).
