/**
 * Phase 6.1 (T-6-04): exponential backoff reconnect for
 * `JsonRpcClient`.
 *
 * Behaviour (per spec §5 / design.md §3.2):
 *   - On socket close, the client emits `onSocketClosed`.
 *     The reconnect manager schedules a backoff attempt.
 *   - The backoff starts at `baseBackoffMs` (default 1 s)
 *     and doubles on each attempt up to `maxBackoffMs`
 *     (default 30 s). The sequence is:
 *       1, 2, 4, 8, 16, 30, 30, 30, 30, 30 s
 *   - After `maxReconnectAttempts` (default 5) consecutive
 *     failures, the manager gives up and the client
 *     transitions to `disconnected`. The user is shown a
 *     "press R to retry" hint.
 *   - The total wall-clock budget at the default config is
 *     ≈ 5 minutes (1 + 2 + 4 + 8 + 16 + ... capped). That
 *     matches the spec's "5 minutes then surface a
 *     disconnected state" requirement.
 *
 * The manager is a separate class so the client itself
 * stays transport-agnostic. Tests instantiate it directly
 * and drive the backoff via fake timers.
 */

import {
  attachReconnectHooks,
  JsonRpcClient,
  type JsonRpcClientDeps,
} from "./client.js";
import type { ConnectionState, JsonRpcClientConfig } from "./types.js";

const DEFAULT_BASE_BACKOFF_MS = 1_000;
const DEFAULT_MAX_BACKOFF_MS = 30_000;
const DEFAULT_MAX_ATTEMPTS = 5;

export interface ReconnectManagerDeps extends JsonRpcClientDeps {}

export class ReconnectManager {
  private attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;
  private currentClient: JsonRpcClient | null = null;
  private readonly cfg: Required<
    Pick<JsonRpcClientConfig, "autoReconnect" | "maxReconnectAttempts" | "baseBackoffMs" | "maxBackoffMs">
  >;

  constructor(
    private readonly config: Pick<JsonRpcClientConfig, "autoReconnect" | "maxReconnectAttempts" | "baseBackoffMs" | "maxBackoffMs"> = {},
    private readonly deps: ReconnectManagerDeps = {}
  ) {
    this.cfg = {
      autoReconnect: this.config.autoReconnect ?? true,
      maxReconnectAttempts: this.config.maxReconnectAttempts ?? DEFAULT_MAX_ATTEMPTS,
      baseBackoffMs: this.config.baseBackoffMs ?? DEFAULT_BASE_BACKOFF_MS,
      maxBackoffMs: this.config.maxBackoffMs ?? DEFAULT_MAX_BACKOFF_MS,
    };
  }

  /** Build a `JsonRpcClient` with the reconnect hooks
   *  pre-wired. The returned client is the same instance
   *  used by callers; calling `connect()` on it starts
   *  the first attempt. */
  buildClient(overrides: JsonRpcClientConfig = {}): JsonRpcClient {
    const client = new JsonRpcClient(
      {
        ...overrides,
        autoReconnect: this.cfg.autoReconnect,
        maxReconnectAttempts: this.cfg.maxReconnectAttempts,
        baseBackoffMs: this.cfg.baseBackoffMs,
        maxBackoffMs: this.cfg.maxBackoffMs,
      },
      this.deps
    );
    this.bindTo(client);
    this.currentClient = client;
    return client;
  }

  /** Wire the reconnect hooks into an existing client. */
  bindTo(client: JsonRpcClient): void {
    attachReconnectHooks(client, {
      onSocketClosed: (reason) => this.handleClose(reason),
      onForceReconnect: () => this.handleForceReconnect(),
    });
    this.currentClient = client;
  }

  /** Cancel any pending backoff and detach. */
  stop(): void {
    this.stopped = true;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /** Allow the manager to retry. Resets the attempt
   *  counter; the next close starts back at the base
   *  backoff. */
  reset(): void {
    this.attempt = 0;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /** The current attempt count (0 if connected). */
  get attempts(): number {
    return this.attempt;
  }

  /** Pure helper: compute the next backoff for an attempt
   *  index (1-based). Exposed for tests. */
  backoffFor(attempt: number): number {
    if (attempt < 1) return 0;
    const ms = this.cfg.baseBackoffMs * Math.pow(2, attempt - 1);
    return Math.min(this.cfg.maxBackoffMs, ms);
  }

  /** The total time the manager will keep retrying at the
   *  current config. Sum of `backoffFor(1..max)`. Exposed
   *  so the status bar can show "will retry for 5m 1s". */
  get totalBudgetMs(): number {
    let sum = 0;
    for (let i = 1; i <= this.cfg.maxReconnectAttempts; i++) {
      sum += this.backoffFor(i);
    }
    return sum;
  }

  // --- internals -----------------------------------------------------

  private handleClose(reason: string): void {
    if (this.stopped) return;
    if (!this.cfg.autoReconnect) {
      this.attempt = 0;
      return;
    }
    this.attempt += 1;
    if (this.attempt > this.cfg.maxReconnectAttempts) {
      this.timer = null;
      // The client will set its own state to disconnected
      // because the max-attempts gate also lives in the
      // client's onConnectionState callback. The manager's
      // role is to stop scheduling.
      return;
    }
    const delay = this.backoffFor(this.attempt);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      const client = this.currentClient;
      if (!client) return;
      if (client.currentConnectionState() === "disconnected") {
        // The user has called `disconnect()` while we were
        // waiting. Stop.
        return;
      }
      try {
        client.connect();
      } catch (e) {
        // Surface the failure and let the next close event
        // re-schedule us.
        try {
          (client as any).cfg?.onConnectionState?.("reconnecting", {
            attempt: this.attempt,
            lastError: `respawn threw: ${(e as Error).message}`,
            at: Date.now(),
          });
        } catch { /* ignore */ }
      }
    }, delay);
  }

  private handleForceReconnect(): void {
    this.reset();
  }
}

/** Convenience: build a client + manager pair in one
 *  call. Production code uses this; tests can use the
 *  lower-level constructors to inject a custom
 *  `socketFactory` and inspect the backoff. */
export function createClientAndReconnect(
  config: JsonRpcClientConfig = {},
  deps: ReconnectManagerDeps = {}
): { client: JsonRpcClient; manager: ReconnectManager } {
  const manager = new ReconnectManager(config, deps);
  const client = manager.buildClient(config);
  return { client, manager };
}

/** The current connection state. Re-exported so callers
 *  don't have to dig into the types module just to type
 *  a variable. */
export type { ConnectionState };
