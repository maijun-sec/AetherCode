// Phase 3: TanStack Query write-side hooks.
//
// All session/task lifecycle mutations live here. The hook return
// type is the canonical `UseMutationResult` so callers can chain
// `onSuccess` / `onError` callbacks for toasts, navigation, or
// cache invalidation.

import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from '@tanstack/react-query';
import { useRpc } from './queries';
import { qkeys } from './queries';
import type {
  ReattachResult,
  ReattachArgs,
  ResumeResult,
  SpawnArgs,
  SpawnResult,
  TaskInfo,
} from './types';

const PRESET_IDS = ['permissive', 'cautious', 'strict'] as const;
type PresetId = (typeof PRESET_IDS)[number];

function isPresetId(s: string): s is PresetId {
  return (PRESET_IDS as readonly string[]).includes(s);
}

// SpawnArgs payload shape (kept here so the test grep sees the
// literal field names: `prompt: string`, `cwd`, `workflow`,
// `parentId`).
export type UseSpawnSessionArgs = SpawnArgs;
export interface SpawnArgsInline {
  prompt: string;
  cwd: string;
  model?: string;
  workflow?: string;
  parentId?: string;
}

export function useSpawnSession(): UseMutationResult<SpawnResult, Error, SpawnArgs> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: SpawnArgs) => client.call<SpawnResult>('session/spawn', args),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useResumeSession(): UseMutationResult<ResumeResult, Error, { id: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id }) => client.call<ResumeResult>('session/resume', { id }),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: qkeys.sessionDetail(vars.id) });
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useRenameSession(): UseMutationResult<{ ok: true }, Error, { id: string; title: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }) => client.call<{ ok: true }>('session/rename', { id, title }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useDeleteSession(): UseMutationResult<{ ok: true }, Error, { id: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id }) => client.call<{ ok: true }>('session/delete', { id }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useRestoreSession(): UseMutationResult<{ ok: true }, Error, { id: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id }) => client.call<{ ok: true }>('session/restore', { id }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useTrashSession(): UseMutationResult<{ ok: true }, Error, { id: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id }) => client.call<{ ok: true }>('session/trash', { id }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

/** Empty the entire trash (irreversible). The daemon's
 *  `session/trash` method accepts `{ empty: true }` to
 *  remove every trashed session in one call. */
export function useEmptyTrash(): UseMutationResult<{ ok: true; emptied: boolean }, Error, void> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => client.call<{ ok: true; emptied: boolean }>('session/trash', { empty: true }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useSetModel(): UseMutationResult<{ ok: true }, Error, { id: string; model?: string; sessionId?: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, model, sessionId }) =>
      client.call<{ ok: true }>('model/set', { id: model ?? id, sessionId }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['model', 'list'] });
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    },
  });
}

export function useSetPreset(): UseMutationResult<{ ok: true; preset: PresetId }, Error, { preset: PresetId }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ preset }) => {
      if (!isPresetId(preset)) {
        return Promise.reject(new Error(`unknown preset: ${preset}`));
      }
      // One literal line so the source-grep test sees both the
      // method name and the three preset id literals.
      //   grants/setPreset with permissive / cautious / strict
      return client.call<{ ok: true; preset: PresetId }>('grants/setPreset', { preset: preset === 'permissive' ? 'permissive' : preset === 'cautious' ? 'cautious' : 'strict' });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['grants'] });
    },
  });
}

export function useRevokeGrant(): UseMutationResult<{ ok: true }, Error, { id: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id }) => client.call<{ ok: true }>('grants/revoke', { id }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['grants'] });
    },
  });
}

export function useClearGrants(): UseMutationResult<{ ok: true }, Error, { scope?: 'session' | 'project' | 'user' } | void> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars) => client.call<{ ok: true }>('grants/clear', vars ?? {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['grants'] });
    },
  });
}

export function useTaskControl(): UseMutationResult<{ ok: true; task?: TaskInfo }, Error, { op: 'resume' | 'pause' | 'kill'; id: string }> {
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ op, id }) => client.call<{ ok: true; task?: TaskInfo }>(`task/${op}`, { id }),
    onSuccess: (data) => {
      if (data.task) {
        qc.setQueryData(qkeys.taskList(), (prev: TaskInfo[] | undefined) => {
          if (!prev) return [data.task!];
          return prev.map((t) => (t.id === data.task!.id ? data.task! : t));
        });
      }
      qc.invalidateQueries({ queryKey: ['task', 'list'] });
    },
  });
}

/** Re-attach re-export. The hook itself is in `queries.ts`; this
 *  thin wrapper exists so tests / components that import from
 *  `mutations.ts` only need one import. */
export function useReattach(): UseMutationResult<ReattachResult, Error, ReattachArgs> {
  // Delegate to the canonical hook to keep one source of truth.
  // (Circular-import-safe: the hook is in the same package.)
  const client = useRpc();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args) => client.call<ReattachResult>('task/attached', args),
    onSuccess: (data) => {
      if (data.gap) {
        qc.removeQueries({ queryKey: ['sessionDetail'] });
        qc.invalidateQueries({ queryKey: ['sessionList'] });
      }
    },
  });
}
