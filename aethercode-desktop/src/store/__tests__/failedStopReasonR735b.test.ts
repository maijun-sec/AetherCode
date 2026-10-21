// R735b (2026-10-20) — the run_end handler must classify failures by
// PREFIX, not by exact Set membership.
//
// The bug: `failedStopKinds` was a `Set` and the classification was
// `failedStopKinds.has(stopReason)`. But the engine never emits bare
// enum stopReasons on the error paths — `SpringAiChatClient.java`
// offers a *decorated* RunEnd:
//
//   queue.offer(new StreamEvent.RunEnd(
//       "error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
//       List.of()));
//
// An upstream 500 from api.minimaxi.com therefore arrives as
// `"error: WebClientResponseException$InternalServerError: 500
// Internal Server Error from POST https://api.minimaxi.com/v1/chat/
// completions"` — a ~120-char string. `has("error")` returned false,
// the run was classified as a SUCCESS, `lastFailedPrompt` stayed
// null, and the renderer fell through to the green `● Idle` bucket
// with no error banner and no ↻ retry button.
//
// This survived two prior fix attempts (R735 fixed the missing RunEnd
// emit; R735a added a try/catch in tryAdvance) because neither touched
// the classification logic — both were correct fixes for *other*
// silent-kill paths. The actual trigger was here.
//
// These are source-pin tests: they read store/index.ts and assert the
// classification uses prefix matching and that the full kinds list is
// present.

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// R748: resolve the package root from this file's own location,
// never from the ambient wd — under vitest's jsdom environment
// with several source-pin suites in one run, the ambient wd is
// not reliably the package root.
const __root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const STORE = join(__root, 'src', 'store', 'index.ts');
const source = readFileSync(STORE, 'utf8');

describe('store — R735b failed-stopReason prefix classification', () => {
  it('classifies failures with startsWith, not Set.has', () => {
    // The Set.has() form is the regression. `startsWith` is the fix.
    // Reject any `failedStopKinds.has(` occurrence outright, and
    // require the `.find()` + `startsWith` classification form.
    expect(source).not.toMatch(/failedStopKinds\.has\(/);
    expect(source).toMatch(
      /failedStopKinds\.find\(\s*\(k\)\s*=>\s*stopReason\s*===\s*k\s*\|\|\s*stopReason\.startsWith\(/
    );
  });

  it('prefix-matches both `kind:` and `kind ` delimiters', () => {
    // The engine decorates with `": "` (e.g. `"error: <class>: <msg>"`)
    // but a future kind might use a plain space. Accept both so a
    // future engine-side change can't silently re-break this.
    expect(source).toMatch(/startsWith\(`\$\{k\}:`\)/);
    expect(source).toMatch(/startsWith\(`\$\{k\} `\)/);
  });

  it('includes every known failure kind in the list', () => {
    // `error` — SpringAiChatClient's three error RunEnd paths
    //   (lines 135, 275-279, 326).
    // `loop` / `max_turns` — R348's original set.
    // `same_error_hard_stop` — R181's death-spiral hard-stop (R735).
    // `awaiting_user_decision` — TodoRunController pause (R735).
    // `runtime_exception` — R735a's tryAdvance catch.
    // `stale_force_end` — the 90s stream-stale watchdog (R735).
    // `cancelled` — R721's Thread.interrupt path. Pre-R735b this was
    //   NOT in the failed set, so a user-cancelled run was treated as
    //   a success and cleared the retry slot — arguably correct for
    //   `dismissLoopWarn('user-cancel')`, but a *hard* cancel that
    //   races a real failure would hide the failure. Included so the
    //   failed pill at least shows `● Failed (cancelled)` when the
    //   user did not initiate it.
    for (const kind of [
      'error',
      'loop',
      'max_turns',
      'same_error_hard_stop',
      'awaiting_user_decision',
      'runtime_exception',
      'stale_force_end',
      'cancelled',
    ]) {
      if (!new RegExp(`'${kind}'`).test(source)) {
        throw new Error(`R735b: failedStopKinds must include '${kind}'`);
      }
    }
  });

  it('stores the BARE KIND in lastFailedStopReason, not the decorated string', () => {
    // The decorated stopReason embeds a full URL
    // (`"... from POST https://api.minimaxi.com/v1/chat/completions"`),
    // which would blow out the Header pill. The pill must render
    // `● Failed (error)`, so we store the matched kind.
    expect(source).toMatch(
      /const failedKind = stopReason\s*\?\s*failedStopKinds\.find\(\s*\(k\)\s*=>/,
    );
    expect(source).toMatch(/lastFailedStopReason:\s*failedKind,/);
    expect(source).not.toMatch(/lastFailedStopReason:\s*stopReason/);
  });

  it('does not treat the successful-run branch as a prefix match', () => {
    // The success branch must stay exact-match: a decorated success
    // like `"stop: <something>"` should NOT be classified as failed
    // by accident, and a bare `"stop"` / `"end_turn"` still clears
    // the retry slot. Keep this exact so the two branches can't
    // overlap.
    expect(source).toMatch(
      /stopReason === 'stop' \|\| stopReason === 'end_turn'/,
    );
  });
});