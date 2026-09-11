# R206 — bypass 真的不问了 + TaskSummary 字号不"米粒"

**Date**: 2026-09-04
**Version**: v0.2.49
**Build SHA256**:
- jar: `59775ac498b39e9f3535c511301dbe06fecb617b9bdc0abd18a67ec5587a45a7` (55,314,417 bytes)
- exe: `3ddb17cc1064ede0bd0f48aba1135f6a219923d2c03b19dac514897870af92b3` (3,962,368 bytes)
- msi: `197dd54e7090639a141a9ace7f525064f11d0bd0cc6676a35b34ebd25f156707` (2,600,960 bytes)
- nsis: `96bef718bd546a624d615c20c10f67f6018cbd942493791ee3747d3eb091d7ae` (2,116,270 bytes)

## TL;DR

R203 + R204 claimed "pick 始终授权 → engine in BYPASS_PERMISSIONS" but
the streaming tool path still consulted a stale policy. The user picked
始终授权, the status bar showed it, the engine's appState reflected it,
yet the very next `todo_write` (or `file_write` / `bash`) call still
asked for permission. R206 fixes the live-policy-swap contract between
`AetherCodeEngine` and `StreamingToolExecutor` so the streaming path
sees the new policy on the very next call.

Plus a UI fix: the top-left TaskSummary panel was using 10-12px
font — the user said the chips looked like 米粒 (grains of rice).
R206 scales them up to 11-14px (label stays one notch under value
to preserve the "label vs data" rhythm).

## Issue 1: bypass 还在问 (R203 was only half a fix)

### Root cause

`AetherCodeEngine` holds the permission policy in two places:

1. `this.policy` (the engine's own field, `volatile`, swappable)
2. `StreamingToolExecutor.policy` (the executor's field, was
   `private final`, captured at construction time)

`setPermissionMode(newMode)` only updated #1:

```java
// pre-R206
public void setPermissionMode(PermissionMode newMode) {
    if (current instanceof ProjectPermissionPolicy ppp) {
        this.policy = ppp.withMode(newMode);  // ← only the engine's field
        ...
    }
    // the executor's `this.policy` still pointed at the old policy
}
```

But the streaming tool path (`StreamingToolExecutor.run` → `policy.check(tool, input, ctx)`)
read the executor's `this.policy`, which was a `final` snapshot of
the original mode. The user picked 始终授权, the engine's
`this.policy` updated to a new `ProjectPermissionPolicy(BYPASS_PERMISSIONS)`,
but the executor kept consulting the pre-swap policy that said
ASK_BEFORE_TOOL. The result: the status bar showed 始终授权
(because the engine's appState was updated), the engine's policy
field was BYPASS, yet the streaming path still prompted for every
non-read tool call.

### Fix

`StreamingToolExecutor.policy` is now `volatile` + mutable. New
`setPolicy(PermissionPolicy)` method:

```java
// R206: post-R206 the field is volatile + mutable.
private volatile PermissionPolicy policy;

public void setPolicy(PermissionPolicy newPolicy) {
    this.policy = newPolicy;
    if (newPolicy != null && currentSubTaskId != null) {
        // re-push the current sub-task id so an
        // ACCEPT_TASK swap (R86) doesn't lose the
        // boundary state mid-run.
        newPolicy.setCurrentSubTaskId(currentSubTaskId);
    }
}
```

`AetherCodeEngine.setPermissionMode` and `swapPolicy` now push
the freshly-built policy down to the executor:

```java
// R206: re-push on every swap.
public void setPermissionMode(PermissionMode newMode) {
    if (current instanceof ProjectPermissionPolicy ppp) {
        this.policy = ppp.withMode(newMode);
        if (this.streamingToolExecutor != null) {
            this.streamingToolExecutor.setPolicy(this.policy);
        }
        ...
    }
}
```

The executor is now a field (was a local in the constructor
pre-R206) so the engine can hold the reference and re-push on
later swaps.

### Verification

After the fix, picking 始终授权 in the dropdown:
1. Engine's `appState.permissionMode` → `BYPASS_PERMISSIONS`
2. Engine's `this.policy` → new `ProjectPermissionPolicy(BYPASS_PERMISSIONS)`
3. Executor receives `setPolicy(...)` with the new policy
4. Next tool call: executor's `policy.check(...)` returns
   `Allow(input)` without invoking the prompter

The user sees no permission prompt for any tool (read, write,
bash, network, todo_write, ...). The status bar still shows
始终授权, and now the streaming path agrees.

## Issue 2: TaskSummary 字号"米粒" 一样

The pre-R206 TaskSummary panel had 10-12px font across the
chips — the user said the chips looked like 米粒 (grains of rice)
and "very挤" (cramped). R206 scales the chips:

| Surface | Pre-R206 | R206 |
| --- | --- | --- |
| `.task-summary` body | 12px | **14px** |
| `.task-pill` chip | 11px | **13px** |
| `.task-pill-label` | 10px | **11px** |
| `.section-header` | 10px | **12px** |
| `.task-pill-icon` | 11px | **13px** |
| `.task-pill` padding | 2px 6px | **4px 8px** |
| `.task-summary-row` padding | 4px 0 2px | **6px 0 4px** |

The label stays one notch under the value (11px vs 13px) so the
"label vs data" rhythm is preserved — the user can scan a row
and tell at a glance which is the field name and which is the
value. R205 scaled the chat body to 16px; the TaskSummary's
14px base keeps the page at one scale (chip value 13px is
between label 11px and body 14px).

## What changed

### Engine (Java)

**`aethercode-core/.../StreamingToolExecutor.java`** — `policy`
field is now `volatile` + mutable, with a new `setPolicy(...)`
method that:
- Replaces the live reference (subsequent `execute()` calls
  consult the new policy).
- Re-pushes the current sub-task id to the new policy so
  ACCEPT_TASK boundary tracking is preserved across swaps.

**`aethercode-sdk/.../AetherCodeEngine.java`** — the executor
is now a field (was a local in the constructor pre-R206);
`setPermissionMode` and `swapPolicy` push the freshly-built
policy down via `streamingToolExecutor.setPolicy(this.policy)`.

### Desktop (CSS)

**`aethercode-desktop/src/components/TaskSummary.css`** — the
chip / label / icon font sizes bumped to 11-14px with
proportional padding growth. The label (11px) stays one notch
under the value (13px) so the "label vs data" rhythm is
preserved.

### Tests

**Java** (`aethercode-core/.../StreamingToolExecutorR206PolicySwapTest.java`,
**+5 tests**):
- `setPolicy` swaps the live reference (the field is mutable)
- `execute` after `setPolicy` consults the new policy
- `setPolicy` pushed by the engine (the contract `AetherCodeEngine`
  relies on)
- `setPolicy` preserves the sub-task boundary (R86 state
  survives a mid-run policy swap)
- `setPolicy(null)` clears the reference (defensive — supports
  external lifecycle resets)

**TS** (no new tests — the change is a CSS file; the existing
`TaskSummary` tests don't assert font sizes, and the `R205
typography` source-pin already pins the global body / button
/ input sizes; the TaskSummary scale-up is a localised CSS
edit that doesn't have a regression contract beyond visual
review)

## How to verify (R206 acceptance)

1. Open AetherCode.exe, switch the Perm dropdown to 始终授权.
2. Send a query that triggers `todo_write` (e.g. "plan a
   3-step refactor"). The model emits `todo_write` calls.
   Pre-R206 a permission prompt appeared. Post-R206 the
   `todo_write` runs without prompting.
3. Send a query that triggers `file_write` or `bash rm` —
   same: no prompt under 始终授权.
4. The status bar perm pill still shows 始终授权.
5. Top-left TaskSummary panel: chips read at 13px (vs the
   pre-R206 11px), padding makes each chip visually distinct
   from its neighbours, the section header is 12px
   (one notch under chip value).

## Architecture note: the policy-swap contract

The fix is the smallest possible change: make the executor's
field mutable + volatile, add a setter, push on every
engine-level swap. We don't refactor to a `MutablePolicy`
wrapper or move the check into the engine — that would
be a larger architectural change for a single-line bug.

The `volatile` keyword is sufficient (no locks): every
tool call reads `this.policy` once, and the writer
side (engine thread) does a single store. The JMM
guarantees a happens-before relationship between the
setPolicy() and the next read.

## Test counts

- Java aethercode-core: 235 → **240** (+5 R206)
- Java aethercode-permission: 303 (unchanged)
- Java aethercode-tools: 249 (unchanged; the 14
  AgentToolTest errors are the pre-R193 known bug, not
  R206)
- TS: 996 (unchanged; R206 is engine + CSS, no TS logic
  changed)

## Lesson learned (2026-09-04)

1. **"完全没生效" 是 streaming 路径 + final 引用**: the user
   said "我选了 始终授权,但是还在问" (I picked 始终授权 but
   it's still asking). The status bar / appState was right.
   The engine's `this.policy` was right. But the
   streaming path used a `final` reference captured at
   construction. The lesson: a swap API without push-to-consumer
   is a one-way ratchet. Either the policy should be
   read through a holder (AtomicReference) or the
   consumer must be notified on every swap.
2. **R203 was the trigger, R206 is the fix**: R203 added
   `setPermissionMode` to the engine. The path works
   for the engine's `this.policy` but missed the
   executor's `this.policy`. The bug is the classic
   "I updated the canonical state but forgot the
   read-through cache". Future R-rounds should audit
   every read of `PermissionPolicy` and make sure
   they go through the same swap path.
3. **Status bar / appState / streaming path 三个地方要一致**:
   the desktop's UI shows appState, the engine's
   check uses `this.policy`, the streaming path uses
   the executor's `this.policy`. Three places, three
   references, one source of truth needed. The R206
   fix is the executor now goes through the same
   swap path as the engine.
4. **米粒字 是 literal 字号问题**: 11px / 10px is
   small. The user is reading this on a normal
   monitor, not a phone. Bump to 13-14px and the
   panel becomes legible at a glance. Keep the
   "label vs value" rhythm — label 11px, value
   13px reads as a coherent scale.
5. **CSS 字号 source-pin 不存在**: the R205
   typography test pins the global body / button /
   input / chat-body sizes, but not the
   component-local chips. A future R-round can
   promote the chip sizes to a source-pin test
   to catch a CSS refactor that silently shrinks
   them back. For now, this is a visual review
   surface.
