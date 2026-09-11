# R101: Intelligent Loop Guard (2026-08-11)

## Pain Point

The engine's `ProgressLoopDetector` (R32-C, R83) hard-stops the
run on the first loop signal with `stopReason="loop_detected"`.
On legitimate iteration (e.g. batch-renaming 50 files in a
folder) the model produces ~4000 chars of "thinking out loud"
plus repeated `bash(ls)` / `read_file` calls. The detector
fires on hit 1, the engine emits a `loop-detected` SideNote,
the user gets a hard stop with no escape hatch.

The user reported:
```
[loop-detected] stopped after 4 turns — model produced 4265
chars of text and only repeats one tool call
(long output + repeated tool call (4265 chars))
```

This is **a tool-unavailable bug**, not a model bug: a 50-file
batch task that legitimately needs 50+ iterations of the
same tool call is indistinguishable from a true stuck loop
on the first hit, and the user has no way to say "yes, this
is intentional — keep going".

## R101: Tiered Warning + User-Driven Ack

R101 converts the hard stop into a **tiered warning**:

| Hit | Old behavior | R101 behavior |
|-----|--------------|---------------|
| 1   | hard stop   | warn (continue) |
| 2   | —            | warn (continue) |
| 3+  | —            | hard stop      |

The first two hits emit a `SideNote("loop-warn-1" /
"loop-warn-2", ...)` instead of `loop-detected`. The
desktop `LoopGuardBanner` reads the SideNote and surfaces
a soft warning + two buttons:

- **继续 (loopAck)** — calls the `loopAck` RPC, which
  calls `engine.currentLoopDetector().acknowledge()` to
  reset the tier counter back to 0. The same pattern can
  fire again on the next batch; the user has effectively
  said "yes, this is intentional — keep going".
- **停止 (cancel)** — calls the existing `cancel` RPC,
  which ends the run with `stopReason="cancel"`.

If neither button is pressed within 8 seconds, the banner
auto-dismisses (the user implicitly let the detector keep
climbing; the next same-pattern hit will be the hard stop).
A system message is always appended to the transcript on
each warn so the user has a permanent record of when the
detector fired.

`user_interrupt` is **not** tiered — it's an immediate hard
stop on the first hit. The user explicitly asked to stop;
warning them twice would be confusing.

## Architecture

### Backend (Java)

| File | Change |
|------|--------|
| `aethercode-core/.../engine/ProgressLoopDetector.java` | `WARN_BEFORE_STOP=2`, `tiered()` helper wraps raw kind as `loop_warn_<N>` for hits 1..N and `loop_detected` for hit N+1. `acknowledge()` resets tier + lastLoopKind + lastErrorKey (but preserves history window). `reset()` now also clears tier+lastLoopKind. `user_interrupt` returns `kind="user_interrupt"` with `shouldStop()=true` (immediate stop, no warn). |
| `aethercode-core/.../engine/QueryEngine.java` | `preInfo` / `postInfo` now branch: `shouldStop()` → existing hard stop; `isWarning()` → emit `SideNote(kind, "warn N/M after T turns — <description>")` and continue. No `finished=true` on a warn. |
| `aethercode-sdk/.../sdk/AetherCodeEngine.java` | New public `currentLoopDetector()` accessor so the RPC layer can reach the per-query detector. |
| `aethercode-protocol/.../methods/AetherCodeMethods.java` | New `loopAck(Object params)` method + `registerAll()` entry. Calls `engine.currentLoopDetector().acknowledge()`. Returns `{ok, tier, kind, wasTier?}` (or `{ok, tier, kind, reason: "no active query"}` when the detector is null). |
| `aethercode-protocol/.../http/HttpJsonRpcServer.java` | `dispatch` switch + `/api/methods` list add `loopAck`. Without these, the WS daemon silently returns `METHOD_NOT_FOUND` even though the stdio daemon serves it. |

### Frontend (TS)

| File | Change |
|------|--------|
| `aethercode-desktop/src/lib/methods.ts` | New `AetherCodeRpc.loopAck({runId?, kind?})` wrapper. |
| `aethercode-desktop/src/store/index.ts` | New `loopWarn: {kind, tier, runId, message, ts} \| null` state. SideNote handler routes `loop-warn-1` / `loop-warn-2` events to this state. New `acknowledgeLoop(kind?)` + `dismissLoopWarn(reason?)` actions. `run_start` clears `loopWarn` so a fresh query starts clean. |
| `aethercode-desktop/src/components/LoopGuardBanner.tsx` (new) | Self-mounting banner: reads `loopWarn`, renders the two buttons, manages 8s auto-dismiss with a 200ms CSS fade-out. Stable handlers ref so the timer reads the latest `acknowledgeLoop` / `dismissLoopWarn` without re-firing the effect. |
| `aethercode-desktop/src/components/LoopGuardBanner.css` (new) | Tier-1 amber, tier-2 red-orange. Same surface tokens as the rest of the app. Slide-in / fade-out transitions. |
| `aethercode-desktop/src/App.tsx` | Mount `<LoopGuardBanner />` between `<AwaitingDecisionBanner />` and `<MessageList />`. |

## Test Coverage

`ProgressLoopDetectorTest` (18 cases, 6 new for R101):

- `r101TieredLongOutputEscalatesFromWarnToStop` — drives
  the detector through 3 long-output turns and verifies
  tier 1 → tier 2 → tier 3 with the right kind / tier /
  shouldStop() at each step.
- `r101AcknowledgeResetsTier` — two warns in a row,
  acknowledge, next hit is tier 1 (not tier 3).
- `r101UserInterruptIsImmediateStop` — `notifyUserInterrupt()`
  + a single `recordBatch` returns `kind="user_interrupt"`
  with `shouldStop()=true`.
- `r101ResetClearsTierAndLastKind` — `reset()` zeros the
  tier counter so a fresh query starts clean.
- `r101AcknowledgePreservesFingerprintHistory` — after
  `acknowledge()`, the next same-pattern hit restarts at
  tier 1 (the rolling history is intact, only the tier
  counter is reset).
- `r101SetTierForTestCapsAtWarnBeforeStopPlusOne` — the
  programmatic escape hatch floors at 0 and caps at
  `WARN_BEFORE_STOP + 1`.

The 3 pre-existing R32-C tests (sameFingerprintTriggersLoop,
sameErrorTriggersLoop, longOutputWithoutToolCallsTriggersLoop)
were updated: their `kind` assertion now expects
`"loop_warn_1"` (the tier-aware wrapper) instead of the raw
cause kind. The raw cause is still in the description.

WS roundtrip smoke test (via `wstest_smoke.cjs`):
- `/api/methods` contains `loopAck` (24 total methods)
- `getState` exposes `loopWindow: 8` / `loopThreshold: 3`
  (unchanged from R32-C config)
- `loopAck({kind: 'all'})` returns
  `{ok: true, tier: 0, kind: 'all', reason: 'no active query'}`
  when no query is in flight (the "engine is idle" path)

## Build / Deploy

Backend shaded jar:
- `D:\work\workspace\idea\engine\AetherCode\aethercode\dist\aethercode-0.2.3.jar` (39.57 MB)
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\resources\aethercode.jar`
- `D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\target\debug\aethercode.jar`

Daemon restart: PID 22292 (0.2.2) → PID 4548 (0.2.3),
port 17888.

Frontend bundle: 446.7 KB JS / 61.8 KB CSS
(444.1 KB / 60.9 KB before R101; +2.6 KB / +0.9 KB for
LoopGuardBanner + store wiring).

## Design Trade-offs

- **WARN_BEFORE_STOP=2 is hard-coded** in
  `ProgressLoopDetector.WARN_BEFORE_STOP`. A future R102+
  could expose this via a build-time constant or even a
  user preference. For R101 the value is set so that a
  50-file batch task (the most common "false positive"
  case) gets through without false-stopping, but a true
  stuck loop (e.g. infinite `read_file` on a path that
  doesn't exist) still escalates after 3 hits.
- **The tier is global, not per-kind.** Acknowledging
  resets the tier for ALL causes (long_output,
  same_fingerprint, same_error). A future R102+ could
  maintain per-kind tier state and let the user ack only
  the cause they want. The `loopAck` RPC already accepts
  a `kind` param for forward-compat.
- **The 8s auto-dismiss is silent.** No RPC, no cancel.
  The user implicitly lets the detector keep climbing.
  The system message in the transcript is the only
  record. This was a deliberate choice — a forced 8s
  checkpoint on every warn would be more annoying than
  helpful; the user can always click the buttons.
- **The banner mounts in `<App />` (above the message
  list), not in `<MessageList />` (the scrolling
  container).** This keeps the banner pinned to the top
  when the user scrolls deep into a long transcript.
  A future R102 could move it into the
  `MessageList`'s sticky header if the user wants it
  to scroll with the messages.

## Open Items

- A user-driven **"ignore this kind forever"** option
  (e.g. "don't warn me about long_output in this session")
  would require a per-session or per-tool override. The
  `permissionPolicyOverride` RPC (R90) is the right
  surface for this — a future R102 could add a
  `disableLoopGuardForTool` RPC.
- The `loopAck` parameter `kind` is currently
  informational only. A future per-kind tier could
  honor it.
- The "8s auto-dismiss" timeout is a constant in
  `LoopGuardBanner.tsx`. A future R102+ could pull
  it from a settings panel (e.g. "auto-dismiss after
  N seconds" with N=0 meaning "no auto-dismiss").
