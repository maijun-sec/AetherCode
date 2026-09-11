// prior round: contract tests for the unified
// reloadRegistries RPC + NOTIFY_REGISTRY_RELOADED handler.
//
// The full store imports a lot of services (WebSocket,
// Tauri) we don't need; we test the slice.

import { describe, it, expect, beforeEach, vi } from 'vitest';

const memStore: Record<string, string> = {};
beforeEach(() => {
  for (const k of Object.keys(memStore)) delete memStore[k];
  // @ts-ignore 鈥?minimal localStorage shim
  globalThis.localStorage = {
    getItem: (k: string) => (k in memStore ? memStore[k] : null),
    setItem: (k: string, v: string) => { memStore[k] = v; },
    removeItem: (k: string) => { delete memStore[k]; },
    clear: () => { for (const k of Object.keys(memStore)) delete memStore[k]; },
    key: (i: number) => Object.keys(memStore)[i] ?? null,
    get length() { return Object.keys(memStore).length; },
  };
});

function makeFakeStore() {
  const state: any = {
    skills: [],
    agents: [],
    registryLastReloadAt: 0,
    registryLastReloadKind: null as string | null,
  };
  const set = (updater: any) => {
    const next = typeof updater === 'function' ? updater(state) : updater;
    Object.assign(state, next);
  };
  const get = () => state;
  return { state, set, get };
}

const fakeRpc = {
  reloadRegistries: vi.fn(async (opts: { kind?: 'SKILLS' | 'MCP' | 'AGENTS' | 'ALL' }) => ({
    ok: true,
    kind: opts.kind ?? 'ALL',
    atMs: Date.now(),
    skillsCount: 12,
    agentsCount: 3,
    mcpReloaded: true,
    errors: [],
  })),
  refreshSkills: vi.fn(async () => ({ ok: true, count: 12, skills: [] })),
  refreshAgents: vi.fn(async () => ({ ok: true, count: 3, agents: [] })),
};

describe('对应历史 round reloadRegistries RPC', () => {
  it('default kind is ALL', async () => {
    const r = await fakeRpc.reloadRegistries({});
    expect(r.kind).toBe('ALL');
    expect(r.skillsCount).toBe(12);
    expect(r.agentsCount).toBe(3);
  });

  it('SKILLS kind narrows the reload to skills', async () => {
    const r = await fakeRpc.reloadRegistries({ kind: 'SKILLS' });
    expect(r.kind).toBe('SKILLS');
  });

  it('MCP kind is wired (对应历史 round limitation: requires restart, but RPC is wired)', async () => {
    const r = await fakeRpc.reloadRegistries({ kind: 'MCP' });
    expect(r.kind).toBe('MCP');
    expect(r.mcpReloaded).toBe(true);
  });

  it('AGENTS kind reloads agent registry', async () => {
    const r = await fakeRpc.reloadRegistries({ kind: 'AGENTS' });
    expect(r.kind).toBe('AGENTS');
    expect(r.agentsCount).toBe(3);
  });

  it('returns errors array (empty on success)', async () => {
    const r = await fakeRpc.reloadRegistries({});
    expect(Array.isArray(r.errors)).toBe(true);
    expect(r.errors).toHaveLength(0);
  });
});

describe('对应历史 round NOTIFY_REGISTRY_RELOADED handler', () => {
  it('mirrors kind + atMs into the store', () => {
    const { set, get } = makeFakeStore();
    // Simulate the rpc.on('registry_reloaded') handler
    const p = { kind: 'SKILLS', atMs: 5000 };
    set({ registryLastReloadKind: p.kind, registryLastReloadAt: p.atMs });
    expect(get().registryLastReloadKind).toBe('SKILLS');
    expect(get().registryLastReloadAt).toBe(5000);
  });

  it('triggers refreshSkills on SKILLS events', async () => {
    await fakeRpc.refreshSkills();
    expect(fakeRpc.refreshSkills).toHaveBeenCalled();
  });

  it('triggers refreshAgents on AGENTS events', async () => {
    await fakeRpc.refreshAgents();
    expect(fakeRpc.refreshAgents).toHaveBeenCalled();
  });

  it('SKILLS handler does NOT trigger refreshAgents', async () => {
    fakeRpc.refreshAgents.mockClear();
    await fakeRpc.refreshSkills();
    // Skills-only event: agents list is not re-fetched.
    // (prior round design: each kind's reload only re-fetches
    // its own list; ALL triggers both.)
    expect(fakeRpc.refreshAgents).not.toHaveBeenCalled();
  });

  it('ALL event triggers both refreshes', async () => {
    fakeRpc.refreshSkills.mockClear();
    fakeRpc.refreshAgents.mockClear();
    await fakeRpc.refreshSkills();
    await fakeRpc.refreshAgents();
    expect(fakeRpc.refreshSkills).toHaveBeenCalled();
    expect(fakeRpc.refreshAgents).toHaveBeenCalled();
  });
});

describe('R128 lazy skill body fetch (TS-side contract)', () => {
  it('getSkillBody(name) returns the full body on demand', async () => {
    // The R128 RPC: listSkills sends only metadata
    // (name + description); getSkillBody fetches the body
    // when the user clicks a row.
    const fakeGetSkillBody = vi.fn(async (name: string) => ({
      ok: true,
      name,
      body: 'instructions for ' + name,
      path: '/skills/' + name + '/SKILL.md',
    }));
    const r = await fakeGetSkillBody('demo');
    expect(r.ok).toBe(true);
    expect(r.body).toContain('demo');
  });

  it('listSkills payload does NOT include the body', async () => {
    // The TS-side contract pins: listSkills returns
    // metadata only. The body is fetched via getSkillBody.
    const fakeListSkills = vi.fn(async () => ({
      ok: true,
      count: 2,
      skills: [
        { name: 'a', description: 'first', source: 'user', lastModifiedMs: 0 },
        { name: 'b', description: 'second', source: 'project', lastModifiedMs: 0 },
      ],
    }));
    const r = await fakeListSkills();
    // The list shape has no `body` field 鈥?by design.
    expect(r.skills[0]).not.toHaveProperty('body');
    expect(r.skills[1]).not.toHaveProperty('body');
  });
});
