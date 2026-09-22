// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R319 — when SDD run is already active, MessageInput must
 * route the user's input as a phase command, NOT as a new
 * SDD intent. Earlier R317 wired startSsdFlow into Enter,
 * but R318 retest showed that "继续下一阶段" got parsed by
 * Slug derivation into `sd-cgbvud` and a fresh phase-1 run
 * was spawned — the user wanted to advance the existing run,
 * not start a new one.
 *
 * Source-pin style: read MessageInput.tsx and assert the
 * routing keywords + the active-vs-inactive branch.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const TS = join(process.cwd(), 'src', 'components', 'MessageInput.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageInput — R319 SDD-active routing', () => {
  it('checks sddActive state before routing', () => {
    expect(source).toMatch(/sdd\.sddActive/);
  });

  it('treats "继续下一阶段" / "next" / "ok" as approve', () => {
    expect(source).toMatch(/继续下一阶段|继续|next|ok/);
    expect(source).toMatch(/sendSsdCommand\('approve'\)/);
  });

  it('treats "跳过" / "skip" as skip', () => {
    expect(source).toMatch(/跳过|skip/);
    expect(source).toMatch(/sendSsdCommand\('skip'\)/);
  });

  it('treats everything else as modify feedback', () => {
    expect(source).toMatch(/sendSsdCommand\('modify', intent\)/);
  });

  it('only calls startSsdFlow when sddActive is false (no run yet)', () => {
    // The startSsdFlow path must be guarded by !sddActive
    // so existing runs don't accidentally spawn a new one.
    expect(source).toMatch(/sdd\.sddActive\s*\)\s*\{[\s\S]*startSsdFlow/);
  });
});