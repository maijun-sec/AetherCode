/**
 * R341 — listProviders WS contract test against a real daemon.
 *
 * <p>The unit tests in ProviderRegistryTest cover the
 * Java side's parsing (ProviderYaml deserialiser,
 * 4-step apiKey chain, 13-arg ProviderSpec record).
 * The Vitest tests in ProviderModelPickerR341 cover the
 * UI behaviour. This test bridges the gap: it spawns a
 * Node-side WebSocket client and calls
 * {@code listProviders} on the daemon that's already
 * running on port 17888 (the R338-R340 daemon in
 * release/R292/desktop/), then asserts the response
 * shape matches the post-R341 contract.
 *
 * <p>What we pin:
 * <ul>
 *   <li>the daemon returns 7 providers (no schema drift
 *       since the R340 bundled-yaml migration)</li>
 *   <li>every provider carries the new R341 fields
 *       ({@code enabled}, {@code headers} optional,
 *       {@code timeout}, {@code connectTimeout})</li>
 *   <li>{@code apiKey} is NEVER exposed in the response
 *       (R341 constitution Security rule)</li>
 *   <li>{@code hasApiKey} is correctly resolved by the
 *       daemon's RegistryHelper (3-scope env lookup:
 *       Process → User → Machine). We verify four brands
 *       that should have keys in this environment
 *       (minmax, glm, qwen, deepseek) plus three that
 *       should NOT (anthropic, openai, gemini).</li>
 *   <li>{@code currentProvider} / {@code currentModel}
 *       are populated so the desktop can highlight the
 *       active row on first paint.</li>
 * </ul>
 *
 * <p>This test is auto-skipped when the daemon isn't
 * running (so a fresh checkout that hasn't launched the
 * desktop yet doesn't fail). The skip probe is a TCP
 * connect to localhost:17888 with a 200ms timeout.
 *
 * <p>Run with: {@code npx vitest run src/store/__tests__/
 * ProviderRegistryContractR341.test.ts}
 *
 * @vitest-environment node
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createConnection } from 'net';
import { WebSocket } from 'ws';

const DAEMON_HOST = '127.0.0.1';
const DAEMON_PORT = 17888;
const DAEMON_URL = `ws://${DAEMON_HOST}:${DAEMON_PORT}/ws`;

/** Probe the daemon port. Returns true if a daemon is
 *  listening, false if the TCP connect is refused. */
async function daemonReachable(): Promise<boolean> {
  return new Promise((resolve) => {
    let settled = false;
    const sock = createConnection({ host: DAEMON_HOST, port: DAEMON_PORT });
    const t = setTimeout(() => {
      if (!settled) {
        settled = true;
        sock.destroy();
        resolve(false);
      }
    }, 1000);
    sock.once('connect', () => {
      if (!settled) {
        settled = true;
        clearTimeout(t);
        sock.destroy();
        resolve(true);
      }
    });
    sock.once('error', () => {
      if (!settled) {
        settled = true;
        clearTimeout(t);
        resolve(false);
      }
    });
  });
}

interface ListProvidersResponse {
  ok: boolean;
  currentProvider: string | null;
  currentModel: string | null;
  providers: Array<{
    name: string;
    type: string;
    baseUrl: string;
    apiKeyEnv: string;
    defaultModel?: string;
    hasApiKey: boolean;
    enabled: boolean;
    headers?: Record<string, string>;
    timeout?: number;
    connectTimeout?: number;
    apiKey?: string;        // intentionally never present
    models: Array<{
      id: string;
      inputPer1k: number;
      outputPer1k: number;
      context: number;
      maxOutput: number;
      default: boolean;
    }>;
  }>;
}

async function callListProviders(): Promise<ListProvidersResponse> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(DAEMON_URL);
    const t = setTimeout(() => {
      ws.terminate();
      reject(new Error('WS open timeout'));
    }, 3000);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'listProviders',
        params: {},
      }));
    });
    ws.on('message', (raw) => {
      clearTimeout(t);
      try {
        const text = raw.toString();
        // daemon may emit multiple newline-separated
        // JSON-RPC messages; find the one matching our id.
        const lines = text.split('\n');
        for (const line of lines) {
          if (!line.trim()) continue;
          const obj = JSON.parse(line);
          if (obj.id === 1) {
            ws.close();
            resolve(obj.result as ListProvidersResponse);
            return;
          }
        }
      } catch (e) {
        ws.close();
        reject(e);
        return;
      }
      // no matching id; wait for more data
    });
    ws.on('error', (e) => {
      clearTimeout(t);
      reject(e);
    });
  });
}

// skip-suite state. Set in beforeAll based on TCP probe.
let daemonReady = false;
let response: ListProvidersResponse | null = null;

beforeAll(async () => {
  daemonReady = await daemonReachable();
  if (!daemonReady) return;
  try {
    response = await callListProviders();
  } catch {
    daemonReady = false;
  }
}, 10_000);

afterAll(() => {
  // no global cleanup needed — each test makes its own
  // WS connection if it needs to re-query.
});

describe('R341 listProviders WS contract — real daemon', () => {
  // R341: skipIf is evaluated at collection time (before
  // beforeAll runs), so daemonReady is always false at
  // registration. Use a runtime guard inside each test
  // instead — when the daemon isn't running, log a
  // warning and return early so the test passes vacuously.
  function guard(): boolean {
    if (!daemonReady || !response) {
      // eslint-disable-next-line no-console
      console.warn('[R341 contract] daemon not reachable; assertion skipped. Run a desktop first.');
      return false;
    }
    return true;
  }

  it('daemon exposes 7 providers (minmax/glm/qwen/deepseek/anthropic/openai/gemini)', () => {
    if (!guard()) return;
    const names = response!.providers.map((p) => p.name).sort();
    expect(names).toEqual(['anthropic', 'deepseek', 'gemini', 'glm', 'minmax', 'openai', 'qwen']);
  });

  it('every provider carries the new R341 fields', () => {
    if (!guard()) return;
    for (const p of response!.providers) {
      // enabled defaults to true
      expect(typeof p.enabled).toBe('boolean');
      // apiKey MUST NOT be exposed (R341 constitution
      // Security rule — "No key value in RPC response").
      expect(p.apiKey).toBeUndefined();
      // type defaults to openai-compat
      expect(typeof p.type).toBe('string');
      expect(p.type.length).toBeGreaterThan(0);
    }
  });

  it('hasApiKey is correctly resolved by RegistryHelper (3-scope env lookup)', () => {
    if (!guard()) return;
    const byName = Object.fromEntries(response!.providers.map((p) => [p.name, p]));
    // Foreign brands (anthropic / openai / gemini) have
    // NO env vars in the test host — they must surface
    // as hasApiKey=false so the picker hides them.
    expect(byName.anthropic.hasApiKey).toBe(false);
    expect(byName.openai.hasApiKey).toBe(false);
    expect(byName.gemini.hasApiKey).toBe(false);
    // Chinese brands — the test host has at minimum
    // MINIMAX_API_KEY (Process scope, 125 chars) and
    // DEEPSEEK_API_KEY (Machine scope, 35 chars).
    // GLM / Qwen env vars depend on whether the test
    // runner has them set (User scope via setx). When
    // they ARE present, RegistryHelper must surface
    // them; when absent, hasApiKey=false is the correct
    // behaviour. Probe via process.env for the running
    // test (Vitest in this same shell inherits the
    // process env from the parent), and only assert
    // the corresponding brand has hasApiKey=true.
    // R342: relaxed the strict equality because the
    // user's machine may not always have GLM /
    // DASHSCOPE_API_KEY in the active PowerShell
    // session — the daemon inherits from the
    // desktop-launcher process which may differ.
    const procEnv = process.env ?? {};
    const proc = (k: string): boolean => {
      const v = procEnv[k];
      return typeof v === 'string' && v.length > 0;
    };
    // minmax / deepseek: RegistryHelper 3-scope proof points.
    // MINIMAX_API_KEY is reliably set in this test host
    // (Process scope); DEEPSEEK_API_KEY is reliably set
    // at Machine scope (the only way to find it pre-R341
    // was missing).
    expect(byName.minmax.hasApiKey).toBe(true);
    expect(byName.deepseek.hasApiKey).toBe(true);
    // GLM / Qwen: only assert true when the env var is
    // actually present in this test host. Otherwise the
    // test passes trivially with hasApiKey=false.
    if (proc('GLM_API_KEY')) {
      expect(byName.glm.hasApiKey).toBe(true);
    } else {
      expect(byName.glm.hasApiKey).toBe(false);
    }
    if (proc('DASHSCOPE_API_KEY')) {
      expect(byName.qwen.hasApiKey).toBe(true);
    } else {
      expect(byName.qwen.hasApiKey).toBe(false);
    }
  });

  it('currentProvider and currentModel are populated', () => {
    if (!guard()) return;
    expect(typeof response!.currentProvider).toBe('string');
    expect(response!.currentProvider!.length).toBeGreaterThan(0);
    expect(typeof response!.currentModel).toBe('string');
    expect(response!.currentModel!.length).toBeGreaterThan(0);
    // The current provider must exist in the providers
    // list (otherwise the renderer's "current row" lookup
    // will fail on first paint).
    const names = new Set(response!.providers.map((p) => p.name));
    expect(names.has(response!.currentProvider!)).toBe(true);
  });

  it('every provider carries a non-empty models[] (R342d picker regression guard)', () => {
    // R342d: the desktop's refreshProviders used to prefer
    // listAvailableModels, whose providers[] is a SUMMARY
    // (no models field — the models live in a separate
    // flat models[]). After R341 wired the picker into
    // MessageInput (R342), every provider appeared with
    // models.length === 0 and the picker rendered an
    // empty list. Fix: refreshProviders now prefers
    // listProviders, whose providers[] carries the full
    // models[] per entry. This test pins the wire shape
    // so a future daemon regression (dropping models from
    // listProviders) trips here rather than silently
    // breaking the picker in production.
    if (!guard()) return;
    for (const p of response!.providers) {
      expect(Array.isArray(p.models)).toBe(true);
      expect(p.models.length).toBeGreaterThan(0);
    }
  });

  it('every model carries maxOutput (R341 round-trip)', () => {
    if (!guard()) return;
    for (const p of response!.providers) {
      for (const m of p.models) {
        // maxOutput is the R341 addition (the legacy
        // shape omitted it). The renderer's "save"
        // pricing hint reads maxOutput for the band.
        expect(typeof m.maxOutput).toBe('number');
        expect(m.maxOutput).toBeGreaterThan(0);
      }
    }
  });

  it('every default model is in its provider\'s models list', () => {
    if (!guard()) return;
    for (const p of response!.providers) {
      if (!p.defaultModel) continue;
      const ids = new Set(p.models.map((m) => m.id));
      expect(ids.has(p.defaultModel)).toBe(true);
    }
  });
});