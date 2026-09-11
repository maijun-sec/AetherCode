import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * side_note handler must NOT push unknown engine-control
 * kinds to the chat transcript. The legacy fallback pushed
 * `[${kind}] ${msg}` for every unrecognised kind, which turned
 * the chat into an engine debug log: `[loop-warn-2] ...`,
 * `[todo-step-bump] ...`, `[todo-ask-llm] ...` all interleaved
 * with the user's actual conversation.
 *
 * <p>afterward:
 * <ul>
 *   <li>Known engine-control kinds (`compaction`,
 *       `loop-warn-1`, `loop-warn-2`, `workflow_step`,
 *       `child_session_event`, `task`, `todo-step-bump`,
 *       `todo-ask-llm`) all have explicit branches that
 *       decide per-kind whether to surface a transcript
 *       line. `compaction completion`, `workflow_step`,
 *       `child_session_event`, `loop-warn-1/2` (R200's 100-char
 *       truncation) keep their transcript line because the
 *       scrollback is a useful record. `task`, `todo-step-bump`,
 *       `todo-ask-llm` do NOT — they're pure control signals
 *       already routed to their own UI surfaces (sub-task
 *       card, status bar, etc.) and the engine's SLF4J log.
 *   </li>
 *   <li>The fallback `else` branch in the `case 'side_note'`
 *       switch is a hard contract: it MUST NOT push to
 *       `s.messages`. A refactor that re-introduces the
 *       silent push regresses R208 — this test fails
 *       loudly.
 *   </li>
 * </ul>
 *
 * <p>Test approach: source-pin. The handler logic is inlined
 * in a giant `switch` inside an `rpc.on('stream_event', ...)`
 * callback, so we read the source file and assert structural
 * invariants. Behavioural tests for the individual kinds live
 * in their respective components' test files (e.g.
 * {@code LoopGuardBannerR114.test.ts} for loop-warn-*,
 * {@code permissionAndSessionR204.test.ts} for perm-mode
 * messages).
 */
const storeSrc = readFileSync(
  join(__dirname, '..', 'store', 'index.ts'),
  'utf-8',
);

describe("R208: side_note handler fallback must NOT push to messages", () => {
  it('the fallback else branch in case side_note has no s.messages update', () => {
    // Find the `case 'side_note':` block. The fallback
    // `else` is the LAST `else` arm of the kind chain,
    // immediately before the `break;` that closes the
    // case. The chain shape is:
    //
    //   if (kind === 'compaction') { ... }
    //   else if (kind === 'loop-warn-1' || kind === 'loop-warn-2') { ... }
    //   else if (kind === 'workflow_step') { ... }
    //   else if (kind === 'child_session_event') { ... }
    //   else if (kind === 'task') { ... }
    //   else if (kind === 'todo-step-bump' || kind === 'todo-ask-llm') { ... }   ← R208
    //   else { ... }                                                              ← R208: no messages
    //   break;
    //
    // We grab the entire `case 'side_note': { ... break; }`
    // block, then look at the FINAL `else { ... }` arm and
    // assert it does not contain a `s.messages` reference.
    // A refactor that adds `s.messages` back to the fallback
    // (e.g. for a "compat" mode) fails the test.

    const start = storeSrc.indexOf("case 'side_note':");
    expect(start).toBeGreaterThan(0);
    // Find the matching `break;` that closes the case.
    // The case body has a few `break;`s (inside the switch
    // arms, e.g. `break;` for the 'unknown' default), but
    // the case-level `break;` is right after the last
    // `else { ... }` and before the closing `}` of the
    // `case 'side_note':` block. We pick the first
    // `break;\n      }` after the start — that's the
    // case's own break.
    //
    // Note: the source file is CRLF (Windows line endings),
    // so the regex matches `\r?\n` to stay portable across
    // LF (CI) and CRLF (local).
    const breakMatch = storeSrc.slice(start).match(/\r?\n\s+break;\r?\n\s+\}\r?\n/);
    expect(breakMatch, 'case side_note must end with break; }').toBeTruthy();
    const caseEnd = start + (breakMatch?.index ?? 0) + breakMatch![0].length;
    const caseBody = storeSrc.slice(start, caseEnd);

    // Find the final `else { ... }` — the R208 fallback.
    // We find the LAST `} else {` before the case's
    // `break;`. To stay tolerant of nested braces
    // (e.g. callback bodies inside earlier arms), we walk
    // backward from the case's `break;` and pick the
    // closest `} else {` that's not inside a string.
    const lastElse = caseBody.lastIndexOf('} else {');
    expect(lastElse, 'case side_note must have a final else fallback').toBeGreaterThan(0);
    const fallbackStart = lastElse + '} else {'.length;
    // Match braces from fallbackStart to find the close.
    let depth = 1;
    let i = fallbackStart;
    while (i < caseBody.length && depth > 0) {
      const ch = caseBody[i];
      if (ch === '{') depth++;
      else if (ch === '}') depth--;
      i++;
    }
    const fallbackEnd = i - 1;
    const fallbackBody = caseBody.slice(fallbackStart, fallbackEnd);
    // The fallback body MUST NOT touch s.messages. A
    // console.warn / console.info / lastChunkTs ping is
    // fine; an `s.messages` reference is the regression
    // we're guarding against. (We can't use
    // `expect(fb, msg)` in vitest 2.x because the
    // custom-message overload is not in the public
    // type — the assertion failure prints the actual
    // value either way.)
    expect(fallbackBody).not.toMatch(/s\.messages/);
    expect(fallbackBody).not.toMatch(/messages\s*:/);
  });

  it('todo-step-bump and todo-ask-llm share an explicit else-if arm (R208)', () => {
    // R208 contract: the engine-control kinds that
    // legacy polluted the chat must have an explicit
    // case BEFORE the fallback. Otherwise a refactor
    // that reorders the chain accidentally re-activates
    // the silent-push behaviour.
    const start = storeSrc.indexOf("case 'side_note':");
    expect(start).toBeGreaterThan(0);
    const end = start + 25000; // big enough window
    const window = storeSrc.slice(start, end);
    const todoStepBumpIdx = window.indexOf("'todo-step-bump'");
    const todoAskLlmIdx = window.indexOf("'todo-ask-llm'");
    expect(todoStepBumpIdx).toBeGreaterThan(0);
    expect(todoAskLlmIdx).toBeGreaterThan(0);
    // Both kinds must appear in the same `else if` arm
    // (the R208 case handler).
    const armStart = window.lastIndexOf('else if', todoStepBumpIdx);
    const armEnd = window.indexOf('{', todoStepBumpIdx);
    const arm = window.slice(armStart, armEnd);
    expect(arm).toContain("'todo-step-bump'");
    expect(arm).toContain("'todo-ask-llm'");
  });

  it('the R208 todo-* case does not push to s.messages', () => {
    // Re-find the todo-* arm and assert it doesn't
    // touch messages. (Same source-pin style as the
    // fallback test above, but applied to the R208
    // case body so a future "let's also push the
    // prompt as a system line" refactor fails here.)
    const start = storeSrc.indexOf("case 'side_note':");
    const window = storeSrc.slice(start, start + 25000);
    const todoStepBumpIdx = window.indexOf("'todo-step-bump'");
    const armStart = window.lastIndexOf('else if', todoStepBumpIdx);
    // Walk braces to find the closing `}` of the arm.
    let depth = 0;
    let i = armStart;
    while (i < window.length) {
      const ch = window[i];
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) { i++; break; }
      }
      i++;
    }
    const armBody = window.slice(armStart, i);
    expect(armBody).not.toMatch(/s\.messages/);
  });

  it('the R208 case body has a comment explaining why these kinds are dropped', () => {
    // The "why" matters. Without a comment, a future
    // contributor reading the case chain might re-add
    // the push "for visibility" without realising
    // QueryEngine.java already routes the same content
    // as a real user message (and re-pushing would
    // duplicate the chat line). The R208 comment
    // mentions the engine-side source-of-truth path
    // so the next reader has the context.
    const start = storeSrc.indexOf("case 'side_note':");
    const window = storeSrc.slice(start, start + 25000);
    const todoStepBumpIdx = window.indexOf("'todo-step-bump'");
    const armStart = window.lastIndexOf('else if', todoStepBumpIdx);
    // Pull a generous slice (3000 chars) — the comment
    // is ~30 lines of prose, well within this budget.
    const arm = window.slice(armStart, armStart + 3000);
    // The arm must reference QueryEngine.java (the
    // engine-side source of truth for the ask-llm
    // path) AND mention the duplicate-avoidance
    // rationale.
    expect(arm).toContain('QueryEngine');
    expect(arm.toLowerCase()).toMatch(/duplicate/);
  });
});
