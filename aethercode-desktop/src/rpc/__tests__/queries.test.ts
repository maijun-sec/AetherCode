import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 3 (T-3-03): TanStack Query hooks (queries.ts).
 *
 * Source-level checks: each hook must wrap a known JSON-RPC
 * method, expose a sensible query key, and feed TanStack
 * Query the right options.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 3 / T-3-03: queries.ts (source-level)', () => {
  it('queries.ts exists', () => {
    expect(existsSync(join(root, 'src/rpc/queries.ts'))).toBe(true);
  });

  it('exports useSessionList, useSessionDetail, useSessionTokens', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/export\s+function\s+useSessionList\b/);
    expect(src).toMatch(/export\s+function\s+useSessionDetail\b/);
    expect(src).toMatch(/export\s+function\s+useSessionTokens\b/);
  });

  it('useSessionList calls listSessions with includeTrashed + limit + withPreview', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/client\.call<SessionListResult>\(\s*['"]listSessions['"]/);
    expect(src).toMatch(/includeTrashed/);
    expect(src).toMatch(/withPreview/);
  });

  it('useSessionList queryKey uses qkeys.sessionList', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/qkeys\.sessionList/);
  });

  it('useSessionDetail is disabled when id is null', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/enabled/);
    expect(src).toMatch(/!!sessionId/);
  });

  it('useSessionTokens polls every 5 s by default', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/refetchInterval/);
    expect(src).toMatch(/5_?000/);
  });

  it('useSessionTokens calls session/tokens', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/client\.call<SessionTokens>\(\s*['"]session\/tokens['"]/);
  });

  it('uses TanStack Query v5 useQuery', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/useQuery\b/);
    expect(src).toMatch(/@tanstack\/react-query/);
  });

  it('exports a centralised qkeys object', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/export\s+const\s+qkeys\b/);
    expect(src).toMatch(/sessionList/);
    expect(src).toMatch(/sessionDetail/);
    expect(src).toMatch(/sessionTokens/);
  });

  it('exports an RpcProvider (so the rest of the tree can swap clients)', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/export\s+function\s+RpcProvider\b/);
    expect(src).toMatch(/useRpc\b/);
  });
});

describe('Phase 3 / T-3-12: re-attach (useReattach)', () => {
  it('queries.ts exports useReattach', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/export\s+function\s+useReattach\b/);
  });

  it('useReattach calls task/attached with sessionId + lastSeq', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/useMutation/);
    expect(src).toMatch(/client\.call<ReattachResult>\(\s*['"]task\/attached['"]/);
    expect(src).toMatch(/ReattachArgs/);
  });

  it('ReattachResult type surfaces from -> to + gap', () => {
    const src = read('src/rpc/types.ts');
    expect(src).toMatch(/export\s+interface\s+ReattachResult\b/);
    expect(src).toMatch(/from:\s*number/);
    expect(src).toMatch(/to:\s*number/);
    expect(src).toMatch(/gap:\s*boolean/);
  });

  it('useReattach invalidates the session caches on gap=true', () => {
    const src = read('src/rpc/queries.ts');
    expect(src).toMatch(/data\.gap/);
    expect(src).toMatch(/removeQueries|qc\.removeQueries/);
  });
});
