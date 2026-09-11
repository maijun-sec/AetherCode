/**
 * Phase 6.1 (T-6-03): TUI React hooks for the read side of
 * the JSON-RPC surface.
 *
 *   useSessionList     — listSessions(opts?)
 *   useSessionDetail   — session/show(id)
 *   useSessionTokens   — session/tokens(id)  (poll every 2 s)
 *   useMutation        — for `task/resume`, `task/pause`,
 *                        `task/kill`, `model/set`, etc.
 *
 * The TUI is hook-based but does NOT use TanStack Query —
 * the renderer is Ink, the dependencies are minimal, and
 * hand-rolled hooks (with a `useReducer`-style state
 * machine) keep the bundle small. The hooks accept an
 * injected `JsonRpcClient` so tests pass the mock
 * directly.
 *
 * All hooks honour the standard React "fetch on mount +
 * poll" idiom. `useSessionTokens` polls every 2 s (per
 * spec §7.2). The other hooks fetch once on mount and
 * re-fetch on the supplied `refetchKey` (the renderer
 * bumps it after a mutation).
 */

import { useEffect, useRef, useState, useCallback } from "react";
import { defaultRpcClient, JsonRpcClient } from "./client.js";
import { RpcCallError, type RpcValue } from "./types.js";

// --- Shapes ---------------------------------------------------------

export interface SessionListItem {
  id: string;
  name?: string;
  cwd?: string;
  lastActiveAt?: number;
  messageCount?: number;
  tokenTotal?: number;
  state?: string;
  preview?: string;
}

export interface SessionListResult {
  ok: true;
  sessions: SessionListItem[];
  current: string;
  total?: number;
  returned?: number;
}

export interface SessionDetail {
  ok: true;
  id: string;
  title: string;
  cwd: string;
  model: string;
  startedAt: number;
  lastActiveAt: number;
  state: string;
  tokensIn: number;
  tokensOut: number;
  messageCount: number;
  preview?: string;
  /** TUI: the parent session id (if any). Used by the
   *  re-attach banner to show "spawned by <parent>". */
  parentSessionId?: string | null;
}

export interface SessionTokens {
  ok: true;
  sessionId: string;
  tokensIn: number;
  tokensOut: number;
  byKind: Record<string, number>;
  byTool: Record<string, number>;
  totalCostUsd: number;
  ts: number;
}

// --- State machine -------------------------------------------------

export type QueryState<T> =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; data: T; ts: number }
  | { status: "error"; error: Error; ts: number };

const TOKEN_POLL_MS = 2_000;

function getClient(injected?: JsonRpcClient): JsonRpcClient {
  return injected ?? defaultRpcClient;
}

function reduceQuery<T>(prev: QueryState<T>, next: QueryState<T>): QueryState<T> {
  return next;
}

// --- useSessionList ------------------------------------------------

export interface UseSessionListOptions {
  client?: JsonRpcClient;
  limit?: number;
  withPreview?: boolean;
  refetchKey?: string | number;
}

export function useSessionList(opts: UseSessionListOptions = {}): {
  state: QueryState<SessionListResult>;
  refetch: () => void;
} {
  const client = getClient(opts.client);
  const [state, setState] = useState<QueryState<SessionListResult>>({ status: "idle" });
  const [tick, setTick] = useState(0);
  const aliveRef = useRef(true);
  useEffect(() => () => { aliveRef.current = false; }, []);

  useEffect(() => {
    aliveRef.current = true;
    setState({ status: "loading" });
    const ac = new AbortController();
    client.request<SessionListResult>("session/list", {
      limit: opts.limit ?? null,
      withPreview: opts.withPreview ?? false,
    } as unknown as RpcValue, { timeoutMs: 0 }).then(
      (data) => {
        if (!aliveRef.current) return;
        setState(reduceQuery({ status: "idle" }, { status: "success", data, ts: Date.now() }));
      },
      (err: Error) => {
        if (!aliveRef.current) return;
        setState(reduceQuery({ status: "idle" }, { status: "error", error: err, ts: Date.now() }));
      }
    );
    return () => { aliveRef.current = false; ac.abort(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, opts.limit, opts.withPreview, opts.refetchKey, tick]);

  const refetch = useCallback(() => setTick((t) => t + 1), []);
  return { state, refetch };
}

// --- useSessionDetail ----------------------------------------------

export function useSessionDetail(
  sessionId: string | null,
  opts: { client?: JsonRpcClient; refetchKey?: string | number } = {}
): { state: QueryState<SessionDetail>; refetch: () => void } {
  const client = getClient(opts.client);
  const [state, setState] = useState<QueryState<SessionDetail>>({ status: "idle" });
  const [tick, setTick] = useState(0);
  const aliveRef = useRef(true);
  useEffect(() => () => { aliveRef.current = false; }, []);

  useEffect(() => {
    if (!sessionId) {
      setState({ status: "idle" });
      return;
    }
    aliveRef.current = true;
    setState({ status: "loading" });
    client.request<SessionDetail>("session/show", { sessionId } as unknown as RpcValue, { timeoutMs: 0 }).then(
      (data) => {
        if (!aliveRef.current) return;
        setState({ status: "success", data, ts: Date.now() });
      },
      (err: Error) => {
        if (!aliveRef.current) return;
        setState({ status: "error", error: err, ts: Date.now() });
      }
    );
    return () => { aliveRef.current = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, sessionId, opts.refetchKey, tick]);

  const refetch = useCallback(() => setTick((t) => t + 1), []);
  return { state, refetch };
}

// --- useSessionTokens ----------------------------------------------

export function useSessionTokens(
  sessionId: string | null,
  opts: { client?: JsonRpcClient; pollMs?: number } = {}
): { state: QueryState<SessionTokens>; refetch: () => void } {
  const client = getClient(opts.client);
  const poll = opts.pollMs ?? TOKEN_POLL_MS;
  const [state, setState] = useState<QueryState<SessionTokens>>({ status: "idle" });
  const [tick, setTick] = useState(0);
  const aliveRef = useRef(true);
  useEffect(() => () => { aliveRef.current = false; }, []);

  useEffect(() => {
    if (!sessionId) {
      setState({ status: "idle" });
      return;
    }
    aliveRef.current = true;
    let timer: ReturnType<typeof setInterval> | null = null;
    const fetchOnce = (): void => {
      client.request<SessionTokens>("session/tokens", { sessionId } as unknown as RpcValue, { timeoutMs: 0 }).then(
        (data) => {
          if (!aliveRef.current) return;
          setState({ status: "success", data, ts: Date.now() });
        },
        (err: Error) => {
          if (!aliveRef.current) return;
          setState({ status: "error", error: err, ts: Date.now() });
        }
      );
    };
    fetchOnce();
    if (poll > 0) {
      timer = setInterval(fetchOnce, poll);
    }
    return () => {
      aliveRef.current = false;
      if (timer) clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, sessionId, poll, tick]);

  const refetch = useCallback(() => setTick((t) => t + 1), []);
  return { state, refetch };
}

// --- useMutation ---------------------------------------------------

export type MutationState<T> =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; data: T; ts: number }
  | { status: "error"; error: Error; ts: number };

export interface UseMutationResult<P, T> {
  state: MutationState<T>;
  mutate: (params: P) => Promise<T | undefined>;
  reset: () => void;
}

/** Generic JSON-RPC mutation hook. Used for `task/resume`,
 *  `task/pause`, `task/kill`, `model/set`, `grants/revoke`,
 *  etc. The TUI components (SessionControl, GrantsManager)
 *  wrap this with the right method name. */
export function useMutation<P, T = unknown>(
  method: string,
  opts: { client?: JsonRpcClient } = {}
): UseMutationResult<P, T> {
  const client = getClient(opts.client);
  const [state, setState] = useState<MutationState<T>>({ status: "idle" });
  const aliveRef = useRef(true);
  useEffect(() => () => { aliveRef.current = false; }, []);

  const mutate = useCallback(
    async (params: P): Promise<T | undefined> => {
      setState({ status: "loading" });
      try {
        const data = await client.request<T>(method, params as unknown as RpcValue, { timeoutMs: 0 });
        if (!aliveRef.current) return data;
        setState({ status: "success", data, ts: Date.now() });
        return data;
      } catch (err) {
        if (!aliveRef.current) return undefined;
        const e = err instanceof RpcCallError
          ? err
          : err instanceof Error ? err : new Error(String(err));
        setState({ status: "error", error: e, ts: Date.now() });
        return undefined;
      }
    },
    [client, method]
  );

  const reset = useCallback(() => {
    if (!aliveRef.current) return;
    setState({ status: "idle" });
  }, []);

  return { state, mutate, reset };
}
