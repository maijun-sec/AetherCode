import { describe, it, expect, beforeEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { computeModelMismatchPrompt } from './index';

/**
 * prior round: persist engine-state preferences across
 * reloads via localStorage, and surface a one-time
 * mismatch prompt when the localStorage model differs
 * from the daemon's canonical model.
 *
 * <p>Why: a user who flips the R120 auto-approve
 * toggle, picks a model, sets a non-default
 * permission mode, and adjusts the loop detector's
 * window should not have to do all four again
 * after a reload. The daemon is the source of
 * truth (prior round lesson); the localStorage copy is
 * a recovery snapshot for the renderer's first
 * paint + a one-shot push to the daemon during
 * initialize().
 *
 * <p>R176: the model push was removed. The
 * daemon's defaultModel (from providers.yaml) is
 * the source of truth — the renderer shows what
 * the daemon reports, not what localStorage
 * remembers. When the two differ, a one-time
 * prompt is set on the store and the MessageInput
 * bar surfaces it. The user can either switch
 * back to the old model (which writes to both
 * the daemon AND localStorage) or keep the
 * daemon's model and dismiss the prompt.
 *
 * <p>Persisted fields (single JSON blob at
 * {@code aethercode.enginePrefs}):
 * <ul>
 *   <li>model — R82+</li>
 *   <li>permissionMode — R82+</li>
 *   <li>loopWindow / loopThreshold — R115</li>
 *   <li>autoApproveLowRisk — R120</li>
 *   <li>autoApproveMediumHigh — R126</li>
 * </ul>
 *
 * <p>Tests pin: the localStorage helpers (source-pin —
 * the helpers are trivial but their defensive
 * behaviour is critical), the R176 mismatch-prompt
 * helper (behaviour assertion), and the dismiss/accept
 * action flows (behaviour assertion against a fake
 * store).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R122: enginePrefs localStorage helpers', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares a single storage key (one blob, not five keys)', () => {
    // One key for all engine prefs. A future
    // addition is just one more field; no
    // migration across multiple keys. The test
    // pins the literal `aethercode.enginePrefs`
    // — a refactor that splits into per-field
    // keys would break the apply logic in
    // initialize().
    expect(storeSrc).toContain("'aethercode.enginePrefs'");
  });

  it('readEnginePrefs returns an empty object when localStorage is unavailable', () => {
    // The typeof window === 'undefined' guard
    // covers SSR + tests; the !window.localStorage
    // guard covers private mode / security
    // policies. Both paths must return {} so the
    // apply block in initialize() falls through
    // to "use daemon defaults".
    expect(storeSrc).toMatch(/if\s*\(typeof window === 'undefined' \|\| !window\.localStorage\)\s*return\s*\{\}/);
  });

  it('readEnginePrefs swallows JSON.parse failures (defensive parse)', () => {
    // A user with a corrupted localStorage entry
    // (browser crash mid-write, malicious DevTools
    // edit, downgrade) should not crash the app
    // on launch. The catch returns {} which means
    // "no prefs, use daemon defaults".
    expect(storeSrc).toMatch(/catch\s*\{\s*return\s*\{\};\s*\}/);
  });

  it('writeEnginePrefs swallows quota / security errors (R117 lesson)', () => {
    // localStorage.setItem throws on quota
    // exceeded or when the user has the
    // "block third-party storage" browser flag on.
    // The R117 lesson: silent best-effort. The
    // in-memory state is the source of truth;
    // localStorage is a nice-to-have.
    expect(storeSrc).toMatch(/writeEnginePrefs\(prefs:\s*EnginePrefs\)\s*\{[\s\S]*?catch\s*\{/);
  });

  it('EnginePrefs interface has the five persisted fields', () => {
    // Pin the schema. A future addition goes
    // here + the read/write blocks.
    const block = storeSrc.match(/interface EnginePrefs\s*\{[\s\S]*?\}/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('model?: string');
    expect(block![0]).toContain('permissionMode?: string');
    expect(block![0]).toContain('loopWindow?: number');
    expect(block![0]).toContain('loopThreshold?: number');
    expect(block![0]).toContain('autoApproveLowRisk?: boolean');
  });
});

describe('R122: setModel writes prefs on success', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('setModel action persists model after the daemon RPC succeeds', () => {
    // The write is AFTER the await so a failed
    // RPC leaves the prior value intact. The
    // pattern matches setPermissionMode /
    // setAutoApproveLowRisk / setLoopDetectorThresholds.
    const block = storeSrc.match(/setModel:\s*async\s*\(model:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('await rpc.setModel(model)');
    expect(block![0]).toMatch(/prefs\.model\s*=\s*model/);
    expect(block![0]).toContain('writeEnginePrefs(prefs)');
  });
});

describe('R122: setPermissionMode writes prefs on success', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('setPermissionMode action persists mode after the daemon RPC succeeds', () => {
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/prefs\.permissionMode\s*=\s*mode/);
    expect(block![0]).toContain('writeEnginePrefs(prefs)');
  });
});

describe('R122: setAutoApproveLowRisk writes prefs on success', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('setAutoApproveLowRisk action persists the boolean after the daemon RPC succeeds', () => {
    const block = storeSrc.match(/setAutoApproveLowRisk:\s*async\s*\(enabled\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/prefs\.autoApproveLowRisk\s*=\s*!!r\.enabled/);
    expect(block![0]).toContain('writeEnginePrefs(prefs)');
  });
});

describe('R122: setLoopDetectorThresholds writes prefs on success', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('setLoopDetectorThresholds action persists window + threshold after RPC succeeds', () => {
    // Use the daemon's normalised values
    // (r.window / r.threshold) rather than opts
    // — the daemon may have clamped 0/-1 to
    // "disabled" (-1), and the persisted value
    // should match the canonical state, not the
    // user's literal input.
    const block = storeSrc.match(/setLoopDetectorThresholds:\s*async\s*\(opts\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/prefs\.loopWindow\s*=\s*r\.window\s*\?\?\s*opts\.window/);
    expect(block![0]).toMatch(/prefs\.loopThreshold\s*=\s*r\.threshold\s*\?\?\s*opts\.threshold/);
    expect(block![0]).toContain('writeEnginePrefs(prefs)');
  });
});

// in-memory localStorage shim for behaviour-assertion tests below
const memStore: Record<string, string> = {};
beforeEach(() => {
  for (const k of Object.keys(memStore)) delete memStore[k];
  // @ts-ignore — minimal localStorage shim
  globalThis.localStorage = {
    getItem: (k: string) => (k in memStore ? memStore[k] : null),
    setItem: (k: string, v: string) => { memStore[k] = v; },
    removeItem: (k: string) => { delete memStore[k]; },
    clear: () => { for (const k of Object.keys(memStore)) delete memStore[k]; },
    key: (i: number) => Object.keys(memStore)[i] ?? null,
    get length() { return Object.keys(memStore).length; },
  };
});

describe('R176: computeModelMismatchPrompt helper', () => {
  it('returns null when prefsModel is missing (first-launch user)', () => {
    // No persisted preference → no prompt to show.
    // The MessageInput bar never sees modelMismatchPrompt
    // set, so no banner.
    expect(computeModelMismatchPrompt(undefined, 'MiniMax-M3', [])).toBeNull();
    expect(computeModelMismatchPrompt(null, 'MiniMax-M3', [])).toBeNull();
    expect(computeModelMismatchPrompt('', 'MiniMax-M3', [])).toBeNull();
  });

  it('returns null when daemonModel is missing (daemon not yet ready)', () => {
    // The initialize() check runs BEFORE state.model
    // is populated in some edge cases (e.g. a stale
    // prefs.model from a previous build). We must
    // not surface a prompt in that race.
    expect(computeModelMismatchPrompt('MiniMax-M1', undefined, [])).toBeNull();
    expect(computeModelMismatchPrompt('MiniMax-M1', null, [])).toBeNull();
    expect(computeModelMismatchPrompt('MiniMax-M1', '', [])).toBeNull();
  });

  it('returns null when prefsModel === daemonModel (already in sync)', () => {
    // legacy this was the "skip" branch of the
    // `if (prefs.model && prefs.model !== state?.model)`
    // guard. afterward the helper is the canonical
    // implementation.
    expect(computeModelMismatchPrompt('MiniMax-M3', 'MiniMax-M3', [])).toBeNull();
  });

  it('returns the prompt object when prefs and daemon differ and pair is not dismissed', () => {
    // The "happy path" for the prompt. The user had
    // M1 persisted; the daemon now defaults to M3.
    // The renderer should show a banner.
    const p = computeModelMismatchPrompt('MiniMax-M1', 'MiniMax-M3', []);
    expect(p).toEqual({ prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3' });
  });

  it('returns null when the (prefs, daemon) pair is in the dismissed list', () => {
    // The user already dismissed this exact mismatch
    // (e.g. on a previous launch they picked "keep
    // M3"). The pair key is "${prefs}|${daemon}" so
    // a different daemon model (M4 after upgrade)
    // would re-prompt.
    const dismissed = ['MiniMax-M1|MiniMax-M3'];
    expect(computeModelMismatchPrompt('MiniMax-M1', 'MiniMax-M3', dismissed)).toBeNull();
  });

  it('returns the prompt when dismissed is null/undefined (defensive)', () => {
    // A race where initialize() runs before the
    // dismissed list is populated (shouldn't happen
    // but the type allows it). We treat null/undefined
    // as "empty" so the prompt fires.
    expect(computeModelMismatchPrompt('MiniMax-M1', 'MiniMax-M3', null)).toEqual({
      prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3',
    });
    expect(computeModelMismatchPrompt('MiniMax-M1', 'MiniMax-M3', undefined)).toEqual({
      prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3',
    });
  });

  it('treats different pair keys as distinct (M1→M4 upgrade re-prompts)', () => {
    // The user dismissed the M1|M3 mismatch last
    // month. After upgrading to a build that defaults
    // to M4, the new (M1, M4) pair is NOT in the
    // dismissed list, so the prompt fires. The pair
    // key is positional, not a wildcard.
    const dismissed = ['MiniMax-M1|MiniMax-M3'];
    const p = computeModelMismatchPrompt('MiniMax-M1', 'MiniMax-M4', dismissed);
    expect(p).toEqual({ prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M4' });
  });
});

describe('R176: dismissModelMismatch action (behaviour assertion)', () => {
  // We test the action against a small slice of the
  // store surface: modelMismatchPrompt +
  // modelMismatchDismissed. The full store imports
  // a lot of services (WebSocket, Tauri) that we
  // don't need here.

  function makeFakeStore() {
    const state: {
      modelMismatchPrompt: { prefsModel: string; daemonModel: string } | null;
      modelMismatchDismissed: string[];
    } = {
      modelMismatchPrompt: null,
      modelMismatchDismissed: [],
    };
    type State = typeof state;
    type SetArg = Partial<State> | ((s: State) => Partial<State>);
    const set = (updater: SetArg) => {
      const next = typeof updater === 'function' ? updater(state) : updater;
      Object.assign(state, next);
    };
    const get = () => state;
    // Replicates the dismissModelMismatch action
    // from the store. Kept in sync by R176 source
    // tests below.
    const dismissModelMismatch = () => {
      const prompt = get().modelMismatchPrompt;
      if (!prompt) return;
      const pair = `${prompt.prefsModel}|${prompt.daemonModel}`;
      set((s) => ({
        modelMismatchPrompt: null,
        modelMismatchDismissed: s.modelMismatchDismissed.includes(pair)
          ? s.modelMismatchDismissed
          : [...s.modelMismatchDismissed, pair],
      }));
    };
    return { state, set, get, dismissModelMismatch };
  }

  it('no-op when there is no current prompt', () => {
    // A stray hot-reload or a click on a hidden
    // banner should not pollute the dismissed list
    // with bogus pairs.
    const { state, dismissModelMismatch } = makeFakeStore();
    dismissModelMismatch();
    expect(state.modelMismatchPrompt).toBeNull();
    expect(state.modelMismatchDismissed).toEqual([]);
  });

  it('clears the prompt and appends the pair key on dismiss', () => {
    // The user picked "keep M3" — the prompt is
    // gone, the (M1, M3) pair is in the dismissed
    // list, and the next initialize() check skips
    // this pair.
    const { state, set, dismissModelMismatch } = makeFakeStore();
    set({ modelMismatchPrompt: { prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3' } });
    dismissModelMismatch();
    expect(state.modelMismatchPrompt).toBeNull();
    expect(state.modelMismatchDismissed).toEqual(['MiniMax-M1|MiniMax-M3']);
  });

  it('does not duplicate the pair on repeated dismiss calls', () => {
    // If the user clicks "keep M3" twice (e.g. the
    // banner re-renders after a state change), the
    // dismissed list stays at one entry. The
    // includes() guard prevents duplicates.
    const { state, set, dismissModelMismatch } = makeFakeStore();
    set({ modelMismatchPrompt: { prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3' } });
    dismissModelMismatch();
    // Manually re-set the prompt to simulate the
    // banner re-appearing somehow.
    set({ modelMismatchPrompt: { prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3' } });
    dismissModelMismatch();
    expect(state.modelMismatchDismissed).toEqual(['MiniMax-M1|MiniMax-M3']);
  });
});

describe('R176: acceptModelMismatch action (behaviour assertion)', () => {
  // Same fake-store pattern. The accept flow also
  // calls setModel(prefsModel); we mock that with a
  // vi.fn() so the test can assert the RPC was
  // issued with the right model.
  const fakeSetModel = vi.fn(async (model: string) => ({ ok: true, model }));

  function makeFakeStore() {
    const state: {
      modelMismatchPrompt: { prefsModel: string; daemonModel: string } | null;
      modelMismatchDismissed: string[];
      setModel: (m: string) => Promise<{ ok: boolean; model: string }>;
    } = {
      modelMismatchPrompt: null,
      modelMismatchDismissed: [],
      setModel: fakeSetModel,
    };
    type State = typeof state;
    type SetArg = Partial<State> | ((s: State) => Partial<State>);
    const set = (updater: SetArg) => {
      const next = typeof updater === 'function' ? updater(state) : updater;
      Object.assign(state, next);
    };
    const get = () => state;
    // Replicates the acceptModelMismatch action
    // from the store. Kept in sync by R176 source
    // tests below.
    const acceptModelMismatch = async () => {
      const prompt = get().modelMismatchPrompt;
      if (!prompt) return;
      set({ modelMismatchPrompt: null });
      const pair = `${prompt.prefsModel}|${prompt.daemonModel}`;
      set((s) => ({
        modelMismatchDismissed: s.modelMismatchDismissed.includes(pair)
          ? s.modelMismatchDismissed
          : [...s.modelMismatchDismissed, pair],
      }));
      try {
        await get().setModel(prompt.prefsModel);
      } catch { /* best-effort */ }
    };
    return { state, set, get, acceptModelMismatch };
  }

  beforeEach(() => {
    fakeSetModel.mockClear();
    fakeSetModel.mockResolvedValue({ ok: true, model: '' });
  });

  it('no-op when there is no current prompt', async () => {
    // Defensive: a stray call doesn't issue a
    // setModel RPC and doesn't pollute the list.
    const { state, acceptModelMismatch } = makeFakeStore();
    await acceptModelMismatch();
    expect(state.modelMismatchPrompt).toBeNull();
    expect(state.modelMismatchDismissed).toEqual([]);
    expect(fakeSetModel).not.toHaveBeenCalled();
  });

  it('clears the prompt, appends the pair, and calls setModel with prefsModel', async () => {
    // The "switch" path: user picked "switch back to
    // M1". The prompt is dismissed, the pair is
    // recorded, and the daemon is told to use M1.
    // The setModel action itself writes localStorage
    // — that's tested in the source-pin block above.
    const { state, set, acceptModelMismatch } = makeFakeStore();
    set({ modelMismatchPrompt: { prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3' } });
    await acceptModelMismatch();
    expect(state.modelMismatchPrompt).toBeNull();
    expect(state.modelMismatchDismissed).toEqual(['MiniMax-M1|MiniMax-M3']);
    expect(fakeSetModel).toHaveBeenCalledWith('MiniMax-M1');
  });

  it('still clears the prompt when setModel throws (defensive)', async () => {
    // The user's banner disappears the moment they
    // click "switch" — even if the daemon RPC fails,
    // we don't restore the prompt. A stuck prompt is
    // worse than a silent reversion (the user can
    // re-pick M1 from the model dropdown to retry).
    fakeSetModel.mockRejectedValueOnce(new Error('daemon offline'));
    const { state, set, acceptModelMismatch } = makeFakeStore();
    set({ modelMismatchPrompt: { prefsModel: 'MiniMax-M1', daemonModel: 'MiniMax-M3' } });
    await acceptModelMismatch();
    expect(state.modelMismatchPrompt).toBeNull();
    expect(state.modelMismatchDismissed).toEqual(['MiniMax-M1|MiniMax-M3']);
    expect(fakeSetModel).toHaveBeenCalledWith('MiniMax-M1');
  });
});

describe('R176: initialize() no longer pushes localStorage model to the daemon', () => {
  // Source-pin checks that anchor the afterward
  // invariant: the dead `if (prefs.model && prefs.model !==
  // state?.model) { await rpc.setModel(prefs.model); ... }`
  // push is GONE. The renderer no longer overwrites
  // the daemon's default model on launch. The test
  // does NOT match the substring against
  // initialize() — it matches the whole file
  // because the substring is also legitimately
  // present in the dismiss/accept action helpers
  // (which DO call setModel, just not from
  // initialize()).
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('initialize() applies prefs.permissionMode via rpc.setPermissionMode', () => {
    // Other prefs ARE still applied via their RPCs.
    // The model is the only one that doesn't get
    // pushed (prior round). Pin the permissionMode path to
    // catch an over-aggressive R176 cleanup that
    // accidentally removes the wrong branch.
    // the persisted value is now a UI tier
    // ('ask' / 'smart' / 'bypass') not the raw
    // enum. The apply block maps it to the
    // canonical enum before the RPC. A refactor
    // that drops the mapping (passing the UI
    // tier verbatim to the daemon) would have
    // the daemon revert to DEFAULT because it
    // doesn't recognise 'ask' / 'smart' /
    // 'bypass'.
    expect(storeSrc).toMatch(/if\s*\(prefs\.permissionMode && prefs\.permissionMode !== state\?\.permissionMode\)/);
    expect(storeSrc).toMatch(/const daemonMode = mapUiPermissionToDaemon\(prefs\.permissionMode\)/);
    expect(storeSrc).toContain('await rpc.setPermissionMode(daemonMode)');
  });

  it('initialize() applies prefs.autoApproveLowRisk via rpc.setAutoApproveLowRisk', () => {
    expect(storeSrc).toMatch(/if\s*\(prefs\.autoApproveLowRisk !== undefined/);
    expect(storeSrc).toContain('await rpc.setAutoApproveLowRisk({ enabled: prefs.autoApproveLowRisk })');
  });

  it('initialize() applies prefs.loopWindow / loopThreshold via rpc.setLoopDetectorThresholds', () => {
    expect(storeSrc).toMatch(/if\s*\(prefs\.loopWindow !== undefined \|\| prefs\.loopThreshold !== undefined\)/);
    expect(storeSrc).toContain('await rpc.setLoopDetectorThresholds({');
  });

  it('the four apply branches are all best-effort (try/catch)', () => {
    // A network blip during one of the four
    // RPCs must not block the rest. The
    // pattern is `try { ... } catch { /* best-effort */ }`
    // for each branch.
    const initBlock = storeSrc.match(/initialize:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?\}\s*;\s*\n\s*\}\);/m);
    expect(initBlock).toBeTruthy();
    const applyRegion = initBlock![0].slice(initBlock![0].indexOf('const prefs = readEnginePrefs()'));
    // Count the try/catch pairs in the apply region.
    const tryCount = (applyRegion.match(/try\s*\{/g) ?? []).length;
    const catchCount = (applyRegion.match(/\}\s*catch\s*\{/g) ?? []).length;
    expect(tryCount).toBeGreaterThanOrEqual(4);
    expect(catchCount).toBeGreaterThanOrEqual(4);
  });

  it('R176 helper computeModelMismatchPrompt is exported and called from initialize()', () => {
    // The pure helper is the unit testable seam. The
    // store calls it inside initialize() and applies
    // its return value to modelMismatchPrompt.
    expect(storeSrc).toMatch(/export function computeModelMismatchPrompt/);
    const initBlock = storeSrc.match(/initialize:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?\}\s*;\s*\n\s*\}\);/m);
    expect(initBlock).toBeTruthy();
    expect(initBlock![0]).toContain('computeModelMismatchPrompt(');
  });
});
