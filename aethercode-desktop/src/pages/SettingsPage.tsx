// Phase 3: SettingsPage (T-3-09) — tabbed settings view.
//
// The brief calls for three tabs:
//   - permissions (grants + preset)
//   - models      (the active model + a "change model" button)
//   - workflows   (the user's workflow library)
//
// The page reads from the existing `useGrantsList` / `useModelList` /
// `useWorkflowList` hooks and dispatches the mutations to swap
// presets, models, and run workflows. Each tab is its own component
// so the test surface stays small.

import { useEffect, useMemo, useState } from 'react';
import { useApp } from '../state/AppContext';
import {
  useGrantsList,
  useWorkflowList,
} from '../rpc/queries';
import {
  useClearGrants,
  useRevokeGrant,
  useSetModel,
  useSetPreset,
} from '../rpc/mutations';
import type { Grant, PermissionPreset, WorkflowSummary } from '../rpc/types';
// R312: SDD is no longer a desktop-managed flow at all —
//   it lives entirely in chat, driven by the Mavis agent
//   (see doc/user-guide/SDD.md). The old SsdDriver / SddPhaseBar
//   scaffolding was removed with R312; no SDD imports live
//   here anymore.
import { useStore } from '../store';
import type { ProviderInfo } from '../lib/methods';
import './SettingsPage.css';

type Tab = 'permissions' | 'models' | 'workflows';

const TABS: { id: Tab; label: string }[] = [
  { id: 'permissions', label: '权限' },
  { id: 'models', label: '模型' },
  { id: 'workflows', label: '工作流' },
  // R288: SDD tab removed — Spec-Driven Development now
  //   lives as an inline flow attached to MessageInput (a
  //   📐 toggle above the input box) rather than a
  //   settings tab. Putting SDD under Settings conflated
  //   'settings' (model/picker/toggles) with a workflow
  //   control surface; they are different things.
];

const PRESETS: { id: PermissionPreset; label: string; description: string }[] = [
  {
    id: 'permissive',
    label: 'Permissive',
    description: 'Allow read_file, glob_files, grep_files; prompt for everything else.',
  },
  {
    id: 'cautious',
    label: 'Cautious',
    description: 'Prompt for everything except read_file and glob_files. (Recommended)',
  },
  {
    id: 'strict',
    label: 'Strict',
    description: 'Deny bash and write_file until explicitly allowed. Power-user default.',
  },
];

export interface SettingsPageProps {
  /** Optional callback when the user navigates away. The router
   *  wires this to `navigate(-1)`. */
  onClose?: () => void;
  /** Optional initial tab — defaults to 'permissions'. The
   *  router passes this when the user navigates to
   *  /settings/<tab> so the right tab is active on landing.
   *  R288: the `sdd` tab is no longer a valid value — SDD
   *  is now an inline flow above MessageInput. */
  initialTab?: Tab;
}

export function SettingsPage({ onClose, initialTab = 'permissions' }: SettingsPageProps) {
  const { permissionPreset, setPermissionPreset } = useApp();
  const [tab, setTab] = useState<Tab>(initialTab);

  return (
    <div className="settings-page" data-testid="settings-page">
      <header className="settings-page-header">
        <h1>Settings</h1>
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            className="settings-page-close"
            data-testid="settings-page-close"
            aria-label="Close"
          >
            ×
          </button>
        )}
      </header>
      <nav className="settings-page-tabs" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            data-testid={`settings-tab-${t.id}`}
            className={`settings-page-tab ${tab === t.id ? 'active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>
      <div className="settings-page-body">
        {tab === 'permissions' && (
          <PermissionsTab
            activePreset={permissionPreset}
            onSelectPreset={setPermissionPreset}
          />
        )}
        {tab === 'models' && <ModelsTab />}
        {tab === 'workflows' && <WorkflowsTab />}
      </div>
    </div>
  );
}

// --- Tabs ------------------------------------------------------------------

function PermissionsTab({
  activePreset,
  onSelectPreset,
}: {
  activePreset: PermissionPreset;
  onSelectPreset: (p: PermissionPreset) => void;
}) {
  const setPreset = useSetPreset();
  const grants = useGrantsList();
  const revoke = useRevokeGrant();
  const clear = useClearGrants();
  const grants_ = grants.data ?? [];

  return (
    <section className="settings-tab" data-testid="settings-permissions">
      <h2>Permission preset</h2>
      <div className="settings-preset-list">
        {PRESETS.map((p) => (
          <button
            key={p.id}
            type="button"
            data-testid={`preset-${p.id}`}
            className={`settings-preset ${activePreset === p.id ? 'active' : ''}`}
            disabled={setPreset.isPending}
            onClick={() => {
              onSelectPreset(p.id);
              setPreset.mutate({ preset: p.id });
            }}
          >
            <strong>{p.label}</strong>
            <span>{p.description}</span>
          </button>
        ))}
      </div>
      <h2>Active grants</h2>
      {grants.isLoading ? (
        <p data-testid="grants-loading">Loading…</p>
      ) : grants_.length === 0 ? (
        <p data-testid="grants-empty">No grants yet.</p>
      ) : (
        <>
          <ul className="settings-grants" data-testid="grants-list">
            {grants_.map((g) => (
              <GrantRow
                key={g.id}
                grant={g}
                onRevoke={() => revoke.mutate({ id: g.id })}
                disabled={revoke.isPending}
              />
            ))}
          </ul>
          <button
            type="button"
            className="settings-grants-clear"
            data-testid="grants-clear"
            disabled={clear.isPending}
            onClick={() => clear.mutate({})}
          >
            Clear all
          </button>
        </>
      )}
    </section>
  );
}

function GrantRow({
  grant,
  onRevoke,
  disabled,
}: {
  grant: Grant;
  onRevoke: () => void;
  disabled?: boolean;
}) {
  return (
    <li className="settings-grant-row" data-testid={`grant-${grant.id}`}>
      <span className={`grant-decision grant-${grant.decision}`}>{grant.decision}</span>
      <span className="grant-category">{grant.category}</span>
      <span className="grant-scope">{grant.scope}</span>
      {grant.pattern && <code className="grant-pattern">{grant.pattern}</code>}
      <button
        type="button"
        className="grant-revoke"
        data-testid={`grant-revoke-${grant.id}`}
        onClick={onRevoke}
        disabled={disabled}
      >
        Revoke
      </button>
    </li>
  );
}

function ModelsTab() {
  const { selectedModelId, setSelectedModelId } = useApp();
  // R282: the Settings picker reads from the store's
  // availableProviders (populated by listProviders),
  // NOT from useModelList. The legacy model/list RPC
  // didn't exist in production (the daemon HTTP server
  // only handles listModels via AetherCodeMethods) so
  // the picker was always empty in real builds. The
  // new path is:
  //   1. refreshProviders() pulls listProviders,
  //      which carries hasApiKey per provider
  //   2. we filter by hasApiKey (or "show all" if
  //      undefined, for backward compat with older
  //      daemons)
  //   3. within each provider, every submodel renders
  //      — the user might not have purchased every
  //      model, but the API key is configured so they
  //      CAN call any of them.
  const availableProviders = useStore((s) => s.availableProviders);
  const refreshProviders = useStore((s) => s.refreshProviders);
  const setModel = useSetModel();

  // Eagerly refresh on mount so the picker reflects
  // the current env (env vars can change between
  // launches — e.g. user sets GLM_API_KEY after
  // opening the app once). Cheap (~5 KB response).
  useEffect(() => {
    void refreshProviders().catch(() => { /* logged in store */ });
  }, [refreshProviders]);

  const flatModels = useMemo(() => {
    const out: {
      id: string;
      name: string;
      provider: string;
      apiKeyEnv: string;
      hasApiKey: boolean;
      contextWindow: number;
      maxOutput: number;
    }[] = [];
    for (const p of (availableProviders ?? []) as ProviderInfo[]) {
      if (p.hasApiKey === false) continue;
      // After the filter, p.hasApiKey is true or
      // undefined (older daemon). Treat undefined
      // as "show it" — backward compat.
      const rowHasKey = p.hasApiKey === true;
      for (const m of p.models ?? []) {
        out.push({
          id: `${p.name}/${m.id}`,
          name: m.id,
          provider: p.name,
          apiKeyEnv: p.apiKeyEnv,
          hasApiKey: rowHasKey,
          contextWindow: m.context,
          maxOutput: m.context, // legacy ModelInfo lacks maxOutput
        });
      }
    }
    // Sort: provider alphabetical, then model alphabetical.
    out.sort((a, b) => {
      const p = a.provider.localeCompare(b.provider);
      return p !== 0 ? p : a.name.localeCompare(b.name);
    });
    return out;
  }, [availableProviders]);

  if (flatModels.length === 0) {
    // Distinguish "loading" (no refresh has run yet) vs
    // "empty" (refresh ran, no providers with API keys
    // configured). The picker uses a dedicated test id
    // for each so the existing tests stay green.
    return (
      <section className="settings-tab" data-testid="settings-models">
        <h2>Available models</h2>
        <p data-testid="models-empty">
          No models available. Set <code>GLM_API_KEY</code> or <code>DEEPSEEK_API_KEY</code>{' '}
          in your environment to populate the list, or write{' '}
          <code>~/.aethercode/providers.yaml</code>.
        </p>
      </section>
    );
  }

  return (
    <section className="settings-tab" data-testid="settings-models">
      <h2>Available models</h2>
      <p className="settings-models-blurb">
        Showing {flatModels.length} model{flatModels.length === 1 ? '' : 's'} from
        providers with an API key configured in your environment.
      </p>
      <ul className="settings-models" data-testid="models-list">
        {flatModels.map((m) => (
          <li
            key={m.id}
            data-testid={`model-row-${m.provider}-${m.name}`}
            className={`settings-model ${selectedModelId === m.id ? 'active' : ''}`}
            data-provider={m.provider}
          >
            <span className="model-provider-tag">{m.provider}</span>
            <span className="model-name">{m.name}</span>
            <span className="model-context">{(m.contextWindow / 1000).toFixed(0)}k ctx</span>
            <button
              type="button"
              data-testid={`model-pick-${m.provider}-${m.name}`}
              disabled={setModel.isPending}
              onClick={() => {
                setSelectedModelId(m.id);
                setModel.mutate({ id: m.id });
              }}
            >
              {selectedModelId === m.id ? 'Active' : 'Use'}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function WorkflowsTab() {
  const wfs = useWorkflowList();
  const list: WorkflowSummary[] = wfs.data ?? [];
  if (wfs.isLoading) return <p data-testid="workflows-loading">Loading…</p>;
  if (list.length === 0) return <p data-testid="workflows-empty">No workflows yet.</p>;
  return (
    <section className="settings-tab" data-testid="settings-workflows">
      <h2>Workflows</h2>
      <ul className="settings-workflows" data-testid="workflows-list">
        {list.map((w) => (
          <li key={w.name} className="settings-workflow-row" data-testid={`workflow-row-${w.name}`}>
            <strong>{w.name}</strong>
            <span>{w.description}</span>
            <em>{w.scope}{w.builtin ? ' · builtin' : ''}</em>
          </li>
        ))}
      </ul>
    </section>
  );
}
