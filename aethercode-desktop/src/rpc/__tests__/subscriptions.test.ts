import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 3 (T-3-05): useRpcSubscription React hook.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 3 / T-3-05: subscriptions.ts (source-level)', () => {
  it('subscriptions.ts exists', () => {
    expect(existsSync(join(root, 'src/rpc/subscriptions.ts'))).toBe(true);
  });

  it('exports useRpcSubscription React hook', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/export\s+function\s+useRpcSubscription\b/);
  });

  it('uses a bounded ring buffer with a configurable capacity', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/class\s+RingBuffer\b/);
    expect(src).toMatch(/capacity\s*\?:\s*number/);
    expect(src).toMatch(/this\.buf\.shift\(\)/);
  });

  it('supports filtering by single kind or array of kinds', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/kind\?:\s*RpcEventKind\s*\|\s*RpcEventKind\[\]/);
    expect(src).toMatch(/Array\.isArray\(kind\)/);
  });

  it('honours an `enabled` flag for late subscription', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/enabled/);
    expect(src).toMatch(/!sessionId\s*\|\|\s*!enabled/);
  });

  it('subscribes on mount and unsubscribes on unmount', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/useEffect/);
    expect(src).toMatch(/sub\.unsubscribe\(\)/);
  });

  it('exposes counts per kind', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/countsRef\.current\[ev\.kind\]/);
  });

  it('surfaces isReattaching while the SSE source is opening', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/isReattaching/);
    expect(src).toMatch(/setIsReattaching\(true\)/);
  });

  it('uses useSyncExternalStore for the snapshot contract', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/useSyncExternalStore/);
  });

  it('returns events, counts, lastSeq, isReattaching, reset', () => {
    const src = read('src/rpc/subscriptions.ts');
    expect(src).toMatch(/events:\s*state/);
    expect(src).toMatch(/counts:\s*state/);
    expect(src).toMatch(/lastSeq:\s*state/);
    expect(src).toMatch(/isReattaching/);
    expect(src).toMatch(/reset,/);
  });
});
