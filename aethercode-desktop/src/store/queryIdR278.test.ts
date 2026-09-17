// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R278 (2026-09-17): split the MessageList timeline's preamble
 * grouping by `queryId`. Two consecutive user prompts (both
 * without a sub-task) used to merge their steps into one
 * preamble event with the FIRST step's ts, so the second
 * prompt's user bubble sorted AFTER the merged content —
 * the user's persistent "second-prompt leak" complaint.
 *
 * The fix tags every step with `queryId` (= the daemon's
 * `runId` from the run_start stream event) at creation time,
 * then groups preamble steps by queryId so each user prompt
 * gets its own preamble block with its own startedAt.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R278: preamble grouping by queryId — each user prompt gets its own preamble', () => {
  it('ChatStep declares an optional `queryId` field', () => {
    const src = readSrc('src/store/index.ts');
    // Source-pin the field. A future refactor that drops queryId
    // would silently re-introduce the second-prompt leak.
    const stepIface = src.match(/export\s+interface\s+ChatStep\s*\{[\s\S]*?\n\}/);
    expect(stepIface, 'ChatStep interface must exist').not.toBeNull();
    expect(stepIface![0]).toMatch(/\bqueryId\??:\s*string/);
  });

  it('run_start handler stamps new steps with the daemon runId', () => {
    const src = readSrc('src/store/index.ts');
    // The run_start case must read ev.runId and write it onto
    // the new step's queryId field.
    const runStartCase = src.match(/case\s+'run_start':\s*\{[\s\S]*?const newQueryId[\s\S]*?queryId:\s*newQueryId/);
    expect(
      runStartCase,
      'run_start case must extract ev.runId into newQueryId and tag the new step with queryId',
    ).not.toBeNull();
    // The case must handle the legacy / non-string runId case
    // (fall back to a fresh newId('query')).
    expect(runStartCase![0]).toMatch(/newId\('query'\)/);
  });

  it('MessageList timeline groups preamble steps by queryId (NOT one bucket)', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // Slice from the timeline useMemo body to the next closing brace.
    const tmIdx = src.indexOf('const timeline = useMemo');
    const tmEnd = src.indexOf('}, [messages, steps, subTasks', tmIdx);
    expect(tmIdx, 'timeline useMemo must exist').toBeGreaterThan(0);
    expect(tmEnd, 'timeline deps list must exist').toBeGreaterThan(tmIdx);
    const body = src.slice(tmIdx, tmEnd);
    // The new code groups by queryId. Find the preByQuery block.
    expect(
      body,
      'timeline must group preamble steps by queryId (preByQuery Record)',
    ).toMatch(/preByQuery\s*:\s*Record<string,\s*ChatStep\[\]>/);
    // Each queryId bucket emits its own preamble event.
    expect(
      body,
      'timeline must emit one preamble event per queryId bucket',
    ).toMatch(/for\s*\(\s*const\s+qSteps\s+of\s+Object\.values\(preByQuery\)/);
    // The legacy single-preamble path (`events.push({ kind: 'preamble', ts: pre[0].startedAt, ... })`)
    // is gone — it would have produced the bug we're fixing.
    expect(
      body,
      'the legacy single-preamble event push (ts: pre[0].startedAt) must NOT appear any more',
    ).not.toMatch(/ts:\s*pre\[0\]\.startedAt/);
  });
});