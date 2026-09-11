// Phase 3: TanStack Query read-side hooks.
//
// The desktop's TanStack Query v5 layer wraps the JsonRpcClient so
// components get cached, deduped, and re-fetched reads for free.
// Tests can either install a `MockRpcServer` into a real client
// (preferred — exercises the full network path) or override the
// `client` prop on `<QueryProvider>` for pure unit tests.

import { useMutation, useQuery, useQueryClient, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query';
import { createElement, createContext, useContext, type ReactNode } from 'react';
import { defaultRpcClient, JsonRpcClient } from './client';
import type {
  Grant,
  ModelInfo,
  ReattachArgs,
  ReattachResult,
  SessionDetail,
  SessionListResult,
  SessionTokens,
  TaskInfo,
  WorkflowSummary,
} from './types';

// Re-export the mutation hooks too so callers can import every
// RPC surface from a single module if they prefer. The actual
// implementations live in `mutations.ts`.
export {
  useSpawnSession,
  useResumeSession,
  useRenameSession,
  useDeleteSession,
  useRestoreSession,
  useTrashSession,
  useSetModel,
  useSetPreset,
  useRevokeGrant,
  useClearGrants,
  useTaskControl,
  useReattach as useReattachMutation,
  useEmptyTrash,
} from './mutations';

/** React context holding the active JsonRpcClient. */
export const JsonRpcClientContext = createContext<JsonRpcClient | null>(null);

export interface RpcProviderProps {
  client?: JsonRpcClient;
  children: ReactNode;
}

export function RpcProvider({ client, children }: RpcProviderProps) {
  // The provider renders the Context with the active client. We
  // use createElement so the file can stay a .ts (some callers
  // expect the .ts extension; the .tsx sibling has the same
  // surface for those who prefer it).
  return createElement(
    JsonRpcClientContext.Provider as any,
    { value: client ?? defaultRpcClient() },
    children,
  );
}

/** Read the active client. Outside a provider the default client
 *  is returned so single-file tests (no provider) can still
 *  call the hooks. */
export function useRpc(): JsonRpcClient {
  const c = useContext(JsonRpcClientContext);
  if (!c) {
    return defaultRpcClient();
  }
  return c;
}

/** Centralised query keys. Components import the constants so
 *  invalidations stay in sync (a `useResumeSession` mutation
 *  invalidates `qkeys.sessionList()` and the sidebar refetches). */
export const qkeys = {
  sessionList: (opts?: { includeTrashed?: boolean; limit?: number; withPreview?: boolean }) =>
    ['sessionList', opts?.includeTrashed ?? false, opts?.limit ?? 200, opts?.withPreview ?? true] as const,
  sessionDetail: (id: string | null) => ['sessionDetail', id] as const,
  sessionTokens: (id: string | null) => ['sessionTokens', id] as const,
  taskList: () => ['task', 'list'] as const,
  taskEvents: (id: string | null) => ['task', 'events', id] as const,
  grants: (scope?: string) => ['grants', scope ?? 'all'] as const,
  models: () => ['model', 'list'] as const,
  workflows: () => ['workflow', 'list'] as const,
};

/** `listSessions` — sidebar. R200: the method name on
 *  the wire is `listSessions` (daemon dispatcher.register),
 *  NOT `session/list`. The legacy query name "session/list"
 *  matched a Java class name (SessionListParams /
 *  SessionListResult) but was never wired into the dispatch
 *  table, so the call returned METHOD_NOT_FOUND, query.data
 *  was undefined, and the LeftPanel rendered "0 sessions"
 *  even while a task was running. */
export function useSessionList(opts?: { includeTrashed?: boolean; limit?: number; withPreview?: boolean }): UseQueryResult<SessionListResult> {
  const client = useRpc();
  return useQuery({
    queryKey: qkeys.sessionList(opts),
    queryFn: () => client.call<SessionListResult>('listSessions', { includeTrashed: opts?.includeTrashed, limit: opts?.limit, withPreview: opts?.withPreview ?? true }),
  });
}

/** `session/show` — drawer + page. */
export function useSessionDetail(id: string | null): UseQueryResult<SessionDetail> {
  const client = useRpc();
  const sessionId = id;
  return useQuery({
    queryKey: qkeys.sessionDetail(id),
    queryFn: () => client.call<SessionDetail>('session/show', { id }),
    enabled: !!id && !!sessionId,
  });
}

/** `session/tokens` — feeds the context meter / token chart. */
export function useSessionTokens(id: string | null): UseQueryResult<SessionTokens> {
  const client = useRpc();
  const sessionId = id;
  return useQuery({
    queryKey: qkeys.sessionTokens(id),
    queryFn: () => client.call<SessionTokens>('session/tokens', { id }),
    enabled: !!id && !!sessionId,
    refetchInterval: 5_000,
  });
}

/** `task/list` — task manager + status badges. */
export function useTaskList(): UseQueryResult<TaskInfo[]> {
  const client = useRpc();
  return useQuery({
    queryKey: qkeys.taskList(),
    queryFn: () => client.call<TaskInfo[]>('task/list'),
  });
}

/** `grants/list` — permissions page. */
export function useGrantsList(scope?: string): UseQueryResult<Grant[]> {
  const client = useRpc();
  return useQuery({
    queryKey: qkeys.grants(scope),
    queryFn: () => client.call<Grant[]>('grants/list', { scope }),
  });
}

/** `model/list` — model picker. */
export function useModelList(): UseQueryResult<ModelInfo[]> {
  const client = useRpc();
  return useQuery({
    queryKey: qkeys.models(),
    queryFn: () => client.call<ModelInfo[]>('model/list'),
  });
}

/** `workflow/list` — workflow picker + tab. */
export function useWorkflowList(): UseQueryResult<WorkflowSummary[]> {
  const client = useRpc();
  return useQuery({
    queryKey: qkeys.workflows(),
    queryFn: () => client.call<WorkflowSummary[]>('workflow/list'),
  });
}

/** Re-attach after disconnect. Calls `task/attached` with the
 *  last seq the client had; the result tells the caller whether
 *  the gap was small (delta) or large (full reload). */
export function useReattach(): UseMutationResult<ReattachResult, Error, ReattachArgs> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args) => client.call<ReattachResult>('task/attached', args),
    onSuccess: (data) => {
      if (data.gap) {
        qc.removeQueries({ queryKey: ['sessionDetail'] });
        qc.removeQueries({ queryKey: ['sessionList'] });
        qc.invalidateQueries({ queryKey: ['sessionDetail'] });
        qc.invalidateQueries({ queryKey: ['sessionList'] });
      }
    },
  });
}
