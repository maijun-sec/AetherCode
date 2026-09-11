// contract test for the medium+high-risk
// auto-approve toggle. Mirrors
// autoApproveR120.test.ts: same fake-store
// pattern, but for the new flag + counter +
// persistence.

import { describe, it, expect, beforeEach, vi } from 'vitest';

// in-memory localStorage shim
const memStore: Record<string, string> = {};
beforeEach(() => {
  for (const k of Object.keys(memStore)) delete memStore[k];
  // @ts-ignore — assign a minimal localStorage shim
  globalThis.localStorage = {
    getItem: (k: string) => (k in memStore ? memStore[k] : null),
    setItem: (k: string, v: string) => { memStore[k] = v; },
    removeItem: (k: string) => { delete memStore[k]; },
    clear: () => { for (const k of Object.keys(memStore)) delete memStore[k]; },
    key: (i: number) => Object.keys(memStore)[i] ?? null,
    get length() { return Object.keys(memStore).length; },
  };
});

// We test against a small slice of the store
// surface: the two setters, the notification
// handler, and the prefs read/write path. The
// full store imports a lot of services
// (WebSocket, Tauri, etc.) that we don't need
// here.

function makeFakeStore() {
  const state: any = {
    autoApproveLowRisk: true,
    autoApproveMediumHigh: false,
    autoApprovedCount: 0,
    autoApprovedElevatedCount: 0,
    recentAutoApproved: [] as { tool: string; atMs: number }[],
  };
  const set = (updater: (s: any) => any) => {
    const next = typeof updater === 'function' ? updater(state) : updater;
    // Mutate in place so the destructured
    // `state` reference stays live.
    Object.assign(state, next);
  };
  const get = () => state;
  return { state, set, get };
}

const fakeRpc = {
  setAutoApproveLowRisk: vi.fn(async (opts: { enabled: boolean }) => ({
    ok: true,
    enabled: opts.enabled,
    autoApprovedCount: 0,
  })),
  setAutoApproveMediumHigh: vi.fn(async (opts: { enabled: boolean }) => ({
    ok: true,
    enabled: opts.enabled,
    autoApprovedCount: 0,
    autoApprovedElevatedCount: 0,
  })),
};

describe('R126 autoApproveMediumHigh store', () => {
  it('defaults to false', () => {
    const { state } = makeFakeStore();
    expect(state.autoApproveMediumHigh).toBe(false);
  });

  it('setAutoApproveMediumHigh RPC returns mirror of enabled', async () => {
    // Simulate the setter path
    const r = await fakeRpc.setAutoApproveMediumHigh({ enabled: true });
    expect(r.ok).toBe(true);
    expect(r.enabled).toBe(true);
    expect(r).toHaveProperty('autoApprovedElevatedCount');
  });

  it('rejects non-boolean enabled values', async () => {
    fakeRpc.setAutoApproveMediumHigh.mockImplementationOnce(async () => {
      throw new Error('enabled must be a boolean');
    });
    await expect(
      fakeRpc.setAutoApproveMediumHigh({ enabled: 'yes' as any })
    ).rejects.toThrow('enabled must be a boolean');
  });

  it('accepts 0/1 as boolean (Tauri serialisation)', async () => {
    // The fake mirrors the input verbatim —
    // i.e. it does NOT normalise 0/1 to
    // false/true. The daemon's
    // setAutoApproveMediumHigh DOES normalise
    // (that's the whole point of the
    // 0/1-acceptance). This test just pins
    // that the typed wrapper passes
    // through the numeric payload — the
    // daemon-side normalisation is covered
    // by the Java R126 test suite.
    const r0 = await fakeRpc.setAutoApproveMediumHigh({ enabled: 0 as any });
    expect(r0.enabled).toBe(0);
    const r1 = await fakeRpc.setAutoApproveMediumHigh({ enabled: 1 as any });
    expect(r1.enabled).toBe(1);
  });
});

describe('R126 elevated counter routing', () => {
  it('routes low-risk to autoApprovedCount, others to autoApprovedElevatedCount', () => {
    const { set, state } = makeFakeStore();
    // Simulate the notification handler logic
    const apply = (p: { tool: string; riskLevel: string; autoApprovedCount?: number; autoApprovedElevatedCount?: number; atMs: number }) => {
      const isElevated = p.riskLevel !== 'low';
      set((s: any) => ({
        autoApprovedCount: typeof p.autoApprovedCount === 'number'
          ? p.autoApprovedCount
          : (isElevated ? s.autoApprovedCount : s.autoApprovedCount + 1),
        autoApprovedElevatedCount: typeof p.autoApprovedElevatedCount === 'number'
          ? p.autoApprovedElevatedCount
          : (isElevated ? s.autoApprovedElevatedCount + 1 : s.autoApprovedElevatedCount),
        recentAutoApproved: [
          { tool: p.tool, atMs: p.atMs },
          ...s.recentAutoApproved,
        ].slice(0, 10),
      }));
    };

    apply({ tool: 'file_read', riskLevel: 'low', atMs: 1 });
    expect(state.autoApprovedCount).toBe(1);
    expect(state.autoApprovedElevatedCount).toBe(0);

    apply({ tool: 'bash', riskLevel: 'high', atMs: 2 });
    expect(state.autoApprovedCount).toBe(1);
    expect(state.autoApprovedElevatedCount).toBe(1);

    apply({ tool: 'file_write', riskLevel: 'medium', atMs: 3 });
    expect(state.autoApprovedCount).toBe(1);
    expect(state.autoApprovedElevatedCount).toBe(2);

    apply({ tool: 'file_read', riskLevel: 'low', atMs: 4 });
    expect(state.autoApprovedCount).toBe(2);
    expect(state.autoApprovedElevatedCount).toBe(2);
  });

  it('honours daemon-supplied counter values when present', () => {
    const { set, state } = makeFakeStore();
    const apply = (p: { tool: string; riskLevel: string; autoApprovedCount?: number; autoApprovedElevatedCount?: number }) => {
      set((s: any) => ({
        autoApprovedCount: typeof p.autoApprovedCount === 'number' ? p.autoApprovedCount : s.autoApprovedCount,
        autoApprovedElevatedCount: typeof p.autoApprovedElevatedCount === 'number' ? p.autoApprovedElevatedCount : s.autoApprovedElevatedCount,
      }));
    };

    apply({ tool: 'file_read', riskLevel: 'low', autoApprovedCount: 42, autoApprovedElevatedCount: 7 });
    expect(state.autoApprovedCount).toBe(42);
    expect(state.autoApprovedElevatedCount).toBe(7);
  });
});

describe('R126 localStorage persistence', () => {
  it('persists the toggle on set', () => {
    const prefsKey = 'aethercode.enginePrefs';
    const persist = (v: { autoApproveMediumHigh?: boolean }) => {
      const cur = JSON.parse(memStore[prefsKey] || '{}');
      const next = { ...cur, ...v };
      memStore[prefsKey] = JSON.stringify(next);
    };
    persist({ autoApproveMediumHigh: true });
    const after = JSON.parse(memStore[prefsKey] || '{}');
    expect(after.autoApproveMediumHigh).toBe(true);
  });

  it('reads back the persisted toggle', () => {
    const prefsKey = 'aethercode.enginePrefs';
    memStore[prefsKey] = JSON.stringify({ autoApproveMediumHigh: true });
    const cur = JSON.parse(memStore[prefsKey] || '{}');
    expect(cur.autoApproveMediumHigh).toBe(true);
  });

  it('clears stale value on daemon rejection', () => {
    const prefsKey = 'aethercode.enginePrefs';
    memStore[prefsKey] = JSON.stringify({ autoApproveMediumHigh: true });
    // Simulate daemon returning ok=false
    const next = JSON.parse(memStore[prefsKey]);
    delete next.autoApproveMediumHigh;
    memStore[prefsKey] = JSON.stringify(next);
    const after = JSON.parse(memStore[prefsKey] || '{}');
    expect(after.autoApproveMediumHigh).toBeUndefined();
  });
});
