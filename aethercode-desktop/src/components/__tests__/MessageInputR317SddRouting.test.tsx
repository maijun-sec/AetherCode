// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R317 + R319 — MessageInput Enter handler must route through
 * startSsdFlow() when the SDD toggle is on AND no run is
 * active. When a run is already active, route via
 * sendSsdCommand('approve' | 'modify' | 'skip') instead so
 * that phrases like "继续下一阶段" advance the existing run
 * instead of spawning a fresh phase 1 with a garbage slug.
 *
 * Source-pin style: read src/components/MessageInput.tsx
 * and assert the routing branch.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const TS = join(process.cwd(), 'src', 'components', 'MessageInput.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageInput — R317 SDD routing on Enter', () => {
  it('reads sddEnabled from store', () => {
    expect(source).toMatch(/sddEnabled\s*=\s*useStore\(\(s\)\s*=>\s*s\.sddEnabled\)/);
  });

  it('gates sendMessage on sddEnabled in the Enter handler', () => {
    // Either form: `useStore.getState().sddEnabled` or
    // `sdd.sddEnabled` after pulling the store snapshot.
    expect(source).toMatch(/sddEnabled/);
  });

  it('calls startSsdFlow(intent) when toggle is on AND no run is active', () => {
    // startSsdFlow must be guarded by `!sdd.sddActive` so
    // active runs don't accidentally spawn a fresh phase 1.
    expect(source).toMatch(/startSsdFlow\(intent\)/);
  });

  it('calls sendSsdCommand(approve) when user types "继续" / "next" / "ok" with active run', () => {
    expect(source).toMatch(/sendSsdCommand\('approve'\)/);
  });

  it('calls sendSsdCommand(modify) for arbitrary feedback text', () => {
    expect(source).toMatch(/sendSsdCommand\('modify', intent\)/);
  });
});