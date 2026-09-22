// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R317 — MessageInput Enter handler must route through
 * startSsdFlow() when the SDD toggle is on. Earlier R317 wired
 * startSsdFlow to be called from MessageInput, but missed the
 * Enter-key wiring — the user's intent was sent via plain
 * sendMessage() and the SDD skill never fired. This test pins
 * the source so a future refactor that drops the sddEnabled
 * branch will fail immediately.
 *
 * Source-pin style: read src/components/MessageInput.tsx
 * and assert the Enter handler routes through startSsdFlow
 * when sddEnabled is true.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const TS = join(process.cwd(), 'src', 'components', 'MessageInput.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageInput — R317 SDD routing on Enter', () => {
  it('reads sddEnabled from store', () => {
    expect(source).toMatch(/sddEnabled\s*=\s*useStore\(\(s\)\s*=>\s*s\.sddEnabled\)/);
  });

  it('checks sddEnabled in the Enter handler before sendMessage', () => {
    // The Enter handler (around the if (isStreaming) cancelQuery()
    // / else if (isConnected) {...} block) must gate the call to
    // startSsdFlow on sddEnabled.
    expect(source).toMatch(/useStore\.getState\(\)\.sddEnabled/);
  });

  it('calls startSsdFlow(intent) instead of plain sendMessage when toggle is on', () => {
    // Look for the routing line: `await useStore.getState().startSsdFlow(intent)`
    expect(source).toMatch(/useStore\.getState\(\)\.startSsdFlow\(intent\)/);
  });
});