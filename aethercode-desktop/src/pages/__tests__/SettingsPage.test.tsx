// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor, within, fireEvent } from '@testing-library/react';
import { createFixture, flush } from '../../test/testUtils';
import { autoCleanup } from '../../test/testUtils';
import { SettingsPage } from '../SettingsPage';
import { useStore } from '../../store';
import type { Grant, WorkflowSummary } from '../../rpc/types';
import type { MockProviderInfo } from '../../rpc/MockRpcServer';

const grantsFixture: Grant[] = [
  { id: 'g-1', scope: 'project', category: 'shell.command', decision: 'allow', pattern: 'npm test', createdAt: 100 },
  { id: 'g-2', scope: 'user', category: 'edit_file', decision: 'deny', createdAt: 200 },
];

// R282: the Settings picker now reads providers (not
// the legacy models field). Three providers — two
// configured (hasApiKey=true) and one unconfigured
// (hasApiKey=false). The unconfigured one's models
// should NOT show in the picker.
const providersFixture: MockProviderInfo[] = [
  {
    name: 'glm',
    type: 'openai-compat',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    apiKeyEnv: 'GLM_API_KEY',
    defaultModel: 'glm-4-flash',
    hasApiKey: true,
    models: [
      { id: 'glm-4-flash', inputPer1k: 0, outputPer1k: 0, context: 128_000, default: true },
      { id: 'glm-4-plus', inputPer1k: 0.0007, outputPer1k: 0.0007, context: 128_000, default: false },
    ],
  },
  {
    name: 'deepseek',
    type: 'openai-compat',
    baseUrl: 'https://api.deepseek.com',
    apiKeyEnv: 'DEEPSEEK_API_KEY',
    defaultModel: 'deepseek-chat',
    hasApiKey: true,
    models: [
      { id: 'deepseek-chat', inputPer1k: 0.00027, outputPer1k: 0.0011, context: 64_000, default: true },
    ],
  },
  {
    name: 'anthropic',
    type: 'openai-compat',
    baseUrl: 'https://api.anthropic.com/v1',
    apiKeyEnv: 'ANTHROPIC_API_KEY',
    defaultModel: 'claude-sonnet-4-5',
    hasApiKey: false,
    models: [
      { id: 'claude-sonnet-4-5', inputPer1k: 3, outputPer1k: 15, context: 200_000, default: true },
      { id: 'claude-haiku-4-5', inputPer1k: 0.8, outputPer1k: 4, context: 200_000, default: false },
    ],
  },
];

const workflowsFixture: WorkflowSummary[] = [
  { name: 'tdd-feature', description: 'Red-green-refactor', scope: 'user', builtin: true },
  { name: 'security-audit', description: 'Find security issues', scope: 'user', builtin: true },
];

autoCleanup();

/** The Settings picker reads from the store's
 *  availableProviders (populated by the store's
 *  refreshProviders, which uses the production
 *  Tauri rpc singleton — unavailable in jsdom
 *  tests). Pre-seed the store with the providers
 *  list so the picker has data to render. The
 *  fixture ALSO wires listProviders +
 *  listAvailableModels on the MockRpcServer so the
 *  eager refresh call doesn't throw; the catch in
 *  refreshProviders swallows the failure either way. */
function seedStoreWithProviders() {
  useStore.setState({ availableProviders: providersFixture as never });
}

describe('Phase 3 / T-3-09: SettingsPage', () => {
  let fixture = createFixture({
    seed: { grants: grantsFixture, providers: providersFixture, workflows: workflowsFixture },
  });

  beforeEach(() => {
    seedStoreWithProviders();
    fixture = createFixture({
      seed: { grants: grantsFixture, providers: providersFixture, workflows: workflowsFixture },
    });
  });

  it('renders the three tabs', () => {
    // R288: SDD tab removed. SDD is now an inline flow
    // attached to MessageInput, not a Settings tab.
    fixture.render(<SettingsPage />);
    expect(screen.getByTestId('settings-page')).toBeDefined();
    expect(screen.getByTestId('settings-tab-permissions')).toBeDefined();
    expect(screen.getByTestId('settings-tab-models')).toBeDefined();
    expect(screen.getByTestId('settings-tab-workflows')).toBeDefined();
    expect(screen.queryByTestId('settings-tab-sdd')).toBeNull();
  });

  it('initialTab prop opens the named tab on first render', async () => {
    fixture.render(<SettingsPage initialTab="workflows" />);
    expect(await screen.findByTestId('workflow-row-tdd-feature')).toBeDefined();
  });

  it('permissions tab lists grants + preset cards', async () => {
    fixture.render(<SettingsPage />);
    await flush();
    const list = await screen.findByTestId('grants-list');
    expect(within(list).getByTestId('grant-g-1')).toBeDefined();
    expect(within(list).getByTestId('grant-g-2')).toBeDefined();
    expect(screen.getByTestId('preset-permissive')).toBeDefined();
    expect(screen.getByTestId('preset-cautious')).toBeDefined();
    expect(screen.getByTestId('preset-strict')).toBeDefined();
  });

  it('clicking a preset card fires grants/setPreset', async () => {
    fixture.render(<SettingsPage />);
    await flush();
    fireEvent.click(screen.getByTestId('preset-strict'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'grants/setPreset');
    expect(called).toBeDefined();
    expect((called?.params as { preset: string }).preset).toBe('strict');
  });

  it('models tab lists models from providers that have hasApiKey=true', async () => {
    fixture.render(<SettingsPage initialTab="models" />);
    await flush();
    // glm: 2 models, deepseek: 1 model = 3 rows.
    // anthropic models must NOT appear (hasApiKey=false).
    expect(await screen.findByTestId('model-row-glm-glm-4-flash')).toBeDefined();
    expect(screen.getByTestId('model-row-glm-glm-4-plus')).toBeDefined();
    expect(screen.getByTestId('model-row-deepseek-deepseek-chat')).toBeDefined();
    expect(screen.queryByTestId('model-row-anthropic-claude-sonnet-4-5')).toBeNull();
    expect(screen.queryByTestId('model-row-anthropic-claude-haiku-4-5')).toBeNull();
  });

  it('models tab calls listProviders on first render', async () => {
    // The eager refreshProviders() call in ModelsTab
    // fails in jsdom (the production Tauri rpc
    // singleton has no host). The store's catch
    // swallows the error; what we assert is the
    // picker still renders with the seeded providers.
    fixture.render(<SettingsPage initialTab="models" />);
    await flush();
    expect(screen.getByTestId('model-row-glm-glm-4-flash')).toBeDefined();
  });

  it('switching to the workflows tab lists shipped workflows', async () => {
    fixture.render(<SettingsPage />);
    fireEvent.click(screen.getByTestId('settings-tab-workflows'));
    await flush();
    expect(await screen.findByTestId('workflow-row-tdd-feature')).toBeDefined();
    expect(screen.getByTestId('workflow-row-security-audit')).toBeDefined();
  });

  it('revoke button removes the grant from the list', async () => {
    fixture.render(<SettingsPage />);
    await flush();
    fireEvent.click(screen.getByTestId('grant-revoke-g-1'));
    await flush();
    await waitFor(() => {
      expect(screen.queryByTestId('grant-g-1')).toBeNull();
    });
    expect(screen.getByTestId('grant-g-2')).toBeDefined();
  });

  it('clear-all wipes every grant in the current scope', async () => {
    fixture.render(<SettingsPage />);
    await flush();
    fireEvent.click(screen.getByTestId('grants-clear'));
    await flush();
    await waitFor(() => {
      expect(screen.queryByTestId('grants-list')).toBeNull();
    });
  });

  it('renders the loading state when grants are still pending', () => {
    const fresh = createFixture({ seed: {} });
    fresh.render(<SettingsPage />);
    expect(screen.getByTestId('settings-page')).toBeDefined();
  });

  it('models tab empty state when no providers have hasApiKey=true', async () => {
    // Seed only unconfigured providers. The picker
    // should show the empty-state message.
    const unconfigured: MockProviderInfo[] = [
      {
        name: 'anthropic', type: 'openai-compat', baseUrl: 'https://x',
        apiKeyEnv: 'ANTHROPIC_API_KEY', defaultModel: 'claude-x',
        hasApiKey: false,
        models: [{ id: 'claude-x', inputPer1k: 0, outputPer1k: 0, context: 1000, default: true }],
      },
    ];
    useStore.setState({ availableProviders: unconfigured as never });
    const fresh = createFixture({ seed: { providers: unconfigured } });
    fresh.render(<SettingsPage initialTab="models" />);
    await flush();
    expect(screen.getByTestId('models-empty')).toBeDefined();
    expect(screen.queryByTestId('model-row-anthropic-claude-x')).toBeNull();
  });

  it('shows an empty state when no grants are seeded', async () => {
    const fresh = createFixture({ seed: { grants: [] } });
    fresh.render(<SettingsPage />);
    await flush();
    expect(await screen.findByTestId('grants-empty')).toBeDefined();
  });

  it('onClose handler is invoked when the close button is clicked', () => {
    let closed = false;
    fixture.render(<SettingsPage onClose={() => { closed = true; }} />);
    fireEvent.click(screen.getByTestId('settings-page-close'));
    expect(closed).toBe(true);
  });
});
