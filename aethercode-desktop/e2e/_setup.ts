/**
 * E2E bootstrap: inject a mock {@code __TAURI_INTERNALS__}
 * before the React app boots.
 *
 * <p>The desktop renderer imports
 * {@code import { invoke } from '@tauri-apps/api/core'} at
 * module-load time. The {@code @tauri-apps/api/core} shim
 * reads {@code window.__TAURI_INTERNALS__} on the first call
 * and throws when it's missing. A bare Chromium that loaded
 * the Vite dev server would crash on the first store init
 * (the store calls {@code invoke('rpc_call', …)} from
 * {@code initialize()}).
 *
 * <p>This helper installs a minimal mock: {@code invoke} is a
 * promise-returning function that the test can override
 * per-spec; {@code listen} is a no-op that returns an
 * unlisten fn. The mock is also exposed as
 * {@code window.__E2E_MOCK__} so specs can swap behaviour
 * mid-test (e.g. set
 * {@code window.__E2E_MOCK__.setInvokeHandler(method, fn)}).
 *
 * <p>Spec-level RPC stubs live in {@code _rpcStubs.ts} — the
 * setup file just wires the surface.
 */
import type { Page } from '@playwright/test';

declare global {
  interface Window {
    __TAURI_INTERNALS__: {
      invoke: (cmd: string, args?: any, options?: any) => Promise<any>;
      transformCallback: (callback: (response: any) => void, once: boolean) => number;
      metadata: { currentWindow: { label: string } };
      plugins: Record<string, any>;
    };
    __TAURI_EVENT_PLUGIN_INTERNALS__: {
      unregisterListener: (event: string, eventId: number) => void;
    };
    __E2E_MOCK__: {
      setInvokeHandler: (method: string, fn: (params: any) => Promise<any> | any) => void;
      getInvokeCalls: (method?: string) => Array<{ method: string; params: any }>;
      clearCalls: () => void;
    };
  }
}

/**
 * Install the Tauri mock on a Playwright page. Must be called
 * BEFORE the page's first navigation — Playwright's
 * {@code addInitScript} does exactly that: the script runs
 * in every new page just after the BrowserContext is created
 * but before any user code (including the dev server's
 * bundle).
 */
export async function installTauriMock(page: Page): Promise<void> {
  await page.addInitScript(() => {
    // the in-page scratch space. Survives navigations
    // within the same context.
    const calls: Array<{ method: string; params: any }> = [];
    const handlers = new Map<string, (params: any) => Promise<any> | any>();

    // Default invoke handler: returns a value that varies by
    // method. Most RPCs need an empty default so the
    // store's init burst (getState / getTranscript /
    // listSessions / etc.) doesn't crash. The default RPC
    // response for any method we don't override is:
    //   { ok: true, value: null }
    // which is what `rpc_call` returns on success.
    //
    // Specs can override per-method via the global
    // window.__E2E_MOCK__.setInvokeHandler('listSessions',
    // (params) => ({ sessions: [...], total: 2 })).
    const defaultInvoke = async (cmd: string, args: any) => {
      if (cmd !== 'rpc_call') {
        // Tauri host commands (write_text_file, read_file,
        // …) — we don't exercise any of these in the
        // renderer E2E; return a sane empty.
        return null;
      }
      const method = args?.method ?? '';
      const params = args?.params ?? null;
      calls.push({ method, params });
      const handler = handlers.get(method);
      if (handler) {
        // The Tauri shim unwraps the Rust response and
        // returns just the value to JS. We mirror that
        // here: the caller's awaited result is the
        // return value, not { ok, value }.
        return await handler(params);
      }
      // Non-RPC Tauri commands (`ensure_daemon`,
      // `discard_pre_warm`, `start_pre_warm`,
      // `pre_warm_daemon`, `app_state`, etc.). The
      // default for the renderer is a sane empty for
      // everything except the daemon-spawn commands,
      // which the store treats as a DaemonInfo with cwd
      // + port (the daemonInfo.port, info.port, etc.
      // accesses blow up on null otherwise).
      console.log('[mock] invoke', cmd, args);
      if (cmd === 'ensure_daemon') {
        return { cwd: null, port: 17888, version: '0.0.0-mock' };
      }
      if (cmd === 'pre_warm_daemon') {
        // store/index.ts line 5184 reads `info.port`
        // after the pre-warm; null would crash the
        // initialize() flow.
        return { cwd: args?.path ?? null, port: 17889 };
      }
      // Default RPC responses: a couple of common ones
      // return shaped objects so the store doesn't NPE on
      // them; everything else returns null.
      switch (method) {
        case 'getState':
          // store expects a state object with cwd /
          // sessionId / model / etc. — nulls are safe
          // defaults; the store treats missing fields as
          // 'not yet loaded'.
          return {
            sessionId: null,
            cwd: null,
            model: 'MiniMax-M3',
            permissionMode: 'BYPASS_PERMISSIONS',
            // the desktop parses these into a Set /
            // number respectively. nulls are safe.
            alwaysAllowedTools: [],
            config: {},
          };
        case 'getTranscript':
          return { sessionId: null, messages: [] };
        case 'listSessions':
          return { sessions: [], total: 0 };
        case 'listProjects':
          return [];
        case 'listProviders':
          return [];
        case 'getActiveEngine':
          return null;
        case 'getSkipStats':
          return { consumed: 0, armed: 0, promptsTotal: 0, byTool: {} };
        case 'getMetrics':
          return {};
        case 'getTraces':
          return [];
        case 'getPermissionStatus':
          return { pending: [] };
        case 'getSystemPrompt':
          return '';
        case 'getEngineStats':
          return {};
        case 'listTools':
          // store/index.ts line 3219 reads `tools.tools
          // ?? []` — the mock must return an object with
          // a `tools` array, not null, or the destructure
          // throws and the React app's first paint blows
          // up.
          return { tools: [] };
        case 'listToolActions':
          return { tools: [] };
        case 'listTasks':
          // store/index.ts line 3236 reads
          // `tasks.tasks ?? []` — the mock must return
          // a shaped object, not null.
          return { tasks: [] };
        case 'listProjects':
          return { projects: [] };
        case 'listWorkflows':
          return { workflows: [] };
        case 'loopAck':
          return { ok: true };
        default:
          return null;
      }
    };

    // The @tauri-apps/api/event shim calls
    // window.__TAURI_INTERNALS__.transformCallback to get
    // a callback id, then expects a `listen` plugin call.
    // We expose just enough of the surface so the shim
    // returns a no-op unlisten fn.
    let callbackId = 0;
    const transformCallback = (callback: any, _once: boolean) => {
      const id = ++callbackId;
      // Stash the callback for later if we ever need to
      // fire it from a test; the spec API is
      // window.__E2E_MOCK__.fireEvent('transcript_event', payload).
      (window as any).__E2E_CALLBACKS__ = (window as any).__E2E_CALLBACKS__ ?? {};
      (window as any).__E2E_CALLBACKS__[id] = callback;
      return id;
    };

    (window as any).__TAURI_INTERNALS__ = {
      invoke: defaultInvoke,
      transformCallback,
      metadata: { currentWindow: { label: 'main' } },
      plugins: {
        // event plugin shim: listen(event, handler) returns
        // { unregister: () => void }.
        event: {
          listen: async () => () => {},
          emit: async () => {},
        },
      },
    };

    // expose a test API for per-spec overrides. The
    // helpers all read the in-page globals, so the spec
    // can call them from page.evaluate() to push state
    // into the running app.
    (window as any).__E2E_MOCK__ = {
      setInvokeHandler: (method: string, fn: any) => {
        handlers.set(method, fn);
      },
      getInvokeCalls: (method?: string) => {
        if (method) return calls.filter((c) => c.method === method);
        return calls.slice();
      },
      clearCalls: () => {
        calls.length = 0;
      },
      fireEvent: (event: string, payload: any) => {
        const cbs = (window as any).__E2E_CALLBACKS__ ?? {};
        for (const id of Object.keys(cbs)) {
          try {
            cbs[id]({ event, payload, id: Number(id) });
          } catch {
            // ignore — a broken callback shouldn't poison
            // the rest of the dispatch.
          }
        }
      },
    };
  });
}
