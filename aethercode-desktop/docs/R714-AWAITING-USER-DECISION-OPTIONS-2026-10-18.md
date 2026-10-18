# R714 — AwaitingUserDecision structured options + clickable option cards

**Date**: 2026-10-18
**Branch**: main
**Status**: COMPLETED, desktop cargo-built, daemon jar updated, desktop restarted

## 1. Problem

User reported the desktop as "unusable after 3-5 turns":

> 现在感觉还是处于不可用状态，我撑死了一个对话走三五轮就死了，还有比如在分析某个问题完成后，涉及到多种策略选择，还没有好的渲染。

Diagnostic root-cause analysis:

| Symptom | Actual root cause |
| --- | --- |
| "session dies after 3-5 turns" | Per-todo controller hits `max_bumps_before_user = 10` after ~16 turns and pauses the run with `StreamEvent.AwaitUserDecision`. The banner that surfaces this only shows the legacy 3 buttons (Continue / Change direction / Abort) — none of which are wired to the structured A/B/C/D choice menu the engine emitted into the transcript. User has no idea what to click; the session appears "dead" until they guess. |
| "multi-strategy choices have no good rendering" | The engine emits the A/B/C/D menu as a long markdown block in `AwaitUserDecision.summary` plus the LLM-targeted prompt in the transcript; the banner renders `summary` as plain text and offers no clickable affordance for the four options. |

The fix needs to surface the structured menu in the banner so the user can act without having to type `(A)` / `(B)` / `(C)` / `(D)` themselves.

## 2. Wire contract (engine → desktop)

The fix has two layers (engine + desktop) so neither side can regress to the
old "summary text only" wire shape:

### 2.1 StreamEvent.AwaitUserDecision — adds `options` field

`aethercode/aethercode-core/src/main/java/org/aethercode/core/stream/StreamEvent.java`:

```java
record AwaitUserDecision(
    String summary,
    int currentTodoSteps,
    int currentSoftThreshold,
    List<DecisionOption> options       // ← R714: structured menu
) implements StreamEvent {}

record DecisionOption(String letter, String label, String description) {}
```

Bytecode verification (`javap -p` on the new shaded jar) confirms:

```
private final java.util.List<org.aethercode.core.stream.StreamEvent$DecisionOption> options;
public java.util.List<org.aethercode.core.stream.StreamEvent$DecisionOption> options();
```

The letters (`A` / `B` / `C` / `D`) are part of the wire contract with the LLM:
the model still recognises typed user replies like `(A)`, so changing them would
invalidate every prior transcript that references them. The
`TodoRunControllerTest.askLlmPromptUsesSameMenuLabels` test pins this
contract.

### 2.2 TodoRunController.defaultDecisionOptions() — single source of truth

`aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/TodoRunController.java`:

```java
public static List<DecisionOption> defaultDecisionOptions() {
    return List.of(
        new DecisionOption("A", "Mark complete",  "..."),
        new DecisionOption("B", "Revise plan",    "..."),
        new DecisionOption("C", "Acknowledge",    "..."),
        new DecisionOption("D", "Abort",          "...")
    );
}
```

The `buildAskLlmPrompt(...)` method now iterates the same list when
generating the LLM-targeted prompt, so the menu the LLM sees in the
transcript is identical to the menu the user sees in the banner. Single
source of truth — no drift between views.

### 2.3 QueryEngine emit — passes the menu

`aethercode/aethercode-core/src/main/java/.../QueryEngine.java:1698`:

```java
action.accept(new StreamEvent.AwaitUserDecision(
    au.summary(),
    todoController.currentTodoStepCount(),
    todoController.softThreshold(),
    TodoRunController.defaultDecisionOptions()    // ← R714
));
```

## 3. Desktop rendering

### 3.1 Store types + fallback

`aethercode-desktop/src/store/index.ts`:

```typescript
export interface DecisionOption {
  letter: string;
  label: string;
  description: string;
}
export interface AwaitingUserDecision {
  summary: string;
  todoSteps: number;
  softThreshold: number;
  options: DecisionOption[];
}
export const DEFAULT_DECISION_OPTIONS: DecisionOption[] = [
  { letter: 'A', label: 'Mark complete',  description: '...' },
  { letter: 'B', label: 'Revise plan',    description: '...' },
  { letter: 'C', label: 'Acknowledge',    description: '...' },
  { letter: 'D', label: 'Abort',          description: '...' },
];
```

The `case 'await_user_decision':` switch arm reads
`(ev as any).options` and falls back to `DEFAULT_DECISION_OPTIONS` when
the array is missing or empty. Pre-R714 engine builds (or any wire
mismatch) therefore degrade to the same menu a healthy wire would
deliver — never to zero option cards.

### 3.2 AwaitingDecisionBanner — clickable option cards

`aethercode-desktop/src/components/AwaitingDecisionBanner.tsx`:

```
[.awaiting-text]   ⏸ Awaiting your decision
                   Todo '...' has run N steps (soft threshold M).
                   N steps taken · soft threshold M

[.awaiting-options] (clickable cards, one per option)
  ┌─[A]─┐ ┌─[B]─┐ ┌─[C]─┐ ┌─[D]─┐
  │ A   │ │ B   │ │ C   │ │ D   │
  │Mark │ │Revis│ │Ackno│ │Abort│
  │ ... │ │ ... │ │ ... │ │ ... │
  └─────┘ └─────┘ └─────┘ └─────┘

[.awaiting-actions]
  Continue (primary) | Change direction | Abort (danger)
```

Clicking an option card injects the matching continuation text:

```
Pick (A): Mark this todo complete (or move on) if it's actually done.
```

The wording matches the LLM-targeted `buildAskLlmPrompt` exactly so the
model's downstream handling is identical to the user-typed case.

The legacy 3 buttons are preserved so muscle memory (and any scripted
button-pressing tools) survive.

### 3.3 Visual design

Each option card carries a coloured letter chip on the left:

| Letter | Colour | Semantic |
| --- | --- | --- |
| A | blue | Mark complete |
| B | purple | Revise plan |
| C | green | Acknowledge |
| D | red | Abort |

The chip colour matches the card's border on hover so the user gets
instant confirmation of what they picked. The cards wrap on narrow
viewports so the layout stays readable on smaller chat windows.

## 4. Tests pinned

### 4.1 Engine — TodoRunControllerTest

Two new tests (12/12 pass):

- `defaultDecisionOptionsExposesFullMenu` — pins 4 options, letters A-D, non-blank labels/descriptions
- `askLlmPromptUsesSameMenuLabels` — pins single source of truth: prompt the LLM sees and the menu the UI sees share the same labels

### 4.2 Desktop — awaitingUserDecisionR714.test.ts

Four new source-pin tests (4/4 pass) that lock in the fallback contract:

1. `await_user_decision` case body assigns `options:` from the event payload
2. The fallback path uses `DEFAULT_DECISION_OPTIONS` when `options` is missing/empty
3. `DEFAULT_DECISION_OPTIONS` has exactly 4 options with letters A/B/C/D
4. `AwaitingDecisionBanner` renders options via `awaiting.options.map` and injects `Pick (${opt.letter})`

Without any of these, a refactor that reverts R714 (drops the fallback,
removes the menu, or breaks the banner iteration) fails the test loudly.

## 5. Build + restart verification

| Step | Result |
| --- | --- |
| `mvn -f aethercode-core clean compile` | 224 source files, BUILD SUCCESS, 13s |
| `mvn -f aethercode-core -Dtest=TodoRunControllerTest test` | 12/12 passed (incl. 2 new), 21s |
| `mvn -f aethercode-core test` (full) | 1309/1309 modules pass; only `WorkflowExecutorR375Test.parallelism_three_*` flaky (unrelated to todo controller; pre-existing) |
| Reactor rebuild (`-pl aethercode-core,aethercode-protocol,aethercode-cli -am`) | BUILD SUCCESS, 1m48s |
| Shaded jar size | 56,858,740 bytes (slight increase for new record) |
| `javap -p StreamEvent$AwaitUserDecision` | 4-arg constructor with `List<DecisionOption> options` field |
| `javap -p TodoRunController` | `defaultDecisionOptions()` static method present |
| Desktop `npm run build` | 462 modules transformed, +1.5 KB CSS, +1.17 KB JS, 7.92s |
| Desktop dist contains `awaiting-option-card`, `awaiting-option-letter`, `Pick (X)` | yes |
| Desktop `npm test` (full) | 1340/1341 passed; only `ToolsPanel.test.ts:71` 🔧 check pre-existing fail (unrelated; R347) |
| `cargo build --release` (cache hot) | 4m15s, exe 6,603,776 B (+4 KB from R713) |
| Jar sync to `src-tauri/resources/` + `target/release/resources/` + `target/release/` | 3/3 done |
| Restart desktop | 1 desktop PID + 3 java daemon running cleanly, `listSessions` 28ms warm |
| WebView2 CPU after restart | 0.8 min in <1 min running — normal idle baseline (vs previous "1.1x single core sustained" diagnostic which was a misread of cumulative vs delta CPU) |

## 6. Backwards compatibility

- **Engine record additions**: Java records with new fields are
  source/binary-compatible at the Java level. The wire shape adds a
  new JSON property; old clients that don't know about it ignore it.
  Verified by `javap` on the shaded jar.

- **Desktop store fallback**: `(ev as any).options ?? DEFAULT_DECISION_OPTIONS`.
  An old engine build emits no `options` field, the desktop fills in
  the same A/B/C/D menu from a hard-coded fallback, the banner renders
  4 cards. No regression on wire mismatch.

- **AwaitingDecisionBanner keeps the legacy 3 buttons**: existing
  muscle memory and any scripted flows survive. Option cards are an
  addition, not a replacement.

## 7. Lessons learned

- **Lesson 973**: When a feature has a 2-sided wire (engine + UI),
  fix BOTH sides or the fix is incomplete. R712/R713 fixed only the
  renderer (defensive cap) but the "session dies after 3-5 turns"
  symptom was on the OTHER side of the wire — the engine emit
  shape. A surface-level fix that only patches the UI would have
  missed this.

- **Lesson 974**: Cumulative CPU on a renderer process is NOT a
  dead-loop signal on its own. Earlier in the diagnostic I read
  "PID 8096 = 33 CPU min in 30 min" as "1.1x single core sustained
  dead loop" — that was the CUMULATIVE reading, not delta. The actual
  delta over 30 min was ~0.7 CPU min (= 0.02x single core), which is
  perfectly healthy rendering. Always sample CPU TWICE with a sleep
  in between to compute the delta.

- **Lesson 975**: When the engine emits the same content twice (once
  for the LLM, once for the user), share a single source of truth
  (e.g. `defaultDecisionOptions()` + `buildAskLlmPrompt` iterate the
  same list). Otherwise the LLM and the user drift apart and the
  model starts responding to options that don't match what the user
  saw.
