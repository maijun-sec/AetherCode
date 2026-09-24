/**
 * R341 — ProviderModelPicker E2E.
 *
 * <p>End-to-end coverage for the new 2-level picker that
 * replaced the legacy single-dropdown listing every
 * model. The test boots a real Vite dev server, drives
 * Chromium against it, mocks the Tauri surface (so the
 * renderer can mount without a Rust host), and verifies:
 *
 * <ul>
 *   <li>the picker renders provider chips for every brand
 *       returned by the daemon's listProviders RPC</li>
 *   <li>clicking a chip switches the model list to that
 *       brand's models</li>
 *   <li>the filter input narrows the model list
 *       (case-insensitive substring match)</li>
 *   <li>clicking a model row fires switchProvider with
 *       the correct (provider, model) pair</li>
 *   <li>the (no key) badge appears for unconfigured
 *       providers when "Show all" is on</li>
 *   <li>disabled providers (enabled=false) never show up</li>
 * </ul>
 *
 * <p>The legacy listProviders RPC shape is unchanged —
 * the picker only consumes what the daemon already sends.
 * The new R341 fields (enabled / headers / timeout /
 * connectTimeout) are exposed via the daemon's response
 * but the picker only reads enabled / hasApiKey for now.
 * The WS contract test (ProviderRegistryContractR341.test.ts)
 * pins the full shape; this E2E test pins the UI path.
 */
import { test, expect } from './_fixtures';

const R341_SAMPLE_PROVIDERS = {
  ok: true,
  providers: [
    {
      name: 'minmax',
      baseUrl: 'https://api.minimaxi.com/v1',
      apiKeyEnv: 'MINIMAX_API_KEY',
      defaultModel: 'MiniMax-M3',
      hasApiKey: true,
      enabled: true,
      models: [
        { id: 'MiniMax-M3', inputPer1k: 0.001, outputPer1k: 0.008, context: 1000000, maxOutput: 512000, default: true },
        { id: 'MiniMax-M1', inputPer1k: 0.001, outputPer1k: 0.008, context: 1000000, maxOutput: 512000 },
      ],
    },
    {
      name: 'glm',
      baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
      apiKeyEnv: 'GLM_API_KEY',
      defaultModel: 'glm-4-flash',
      hasApiKey: true,
      enabled: true,
      models: [
        { id: 'glm-4-flash', inputPer1k: 0, outputPer1k: 0, context: 1000000, maxOutput: 1000000, default: true },
        { id: 'glm-4.5', inputPer1k: 0.0006, outputPer1k: 0.002, context: 128000, maxOutput: 128000 },
        { id: 'glm-4.5-air', inputPer1k: 0.0002, outputPer1k: 0.0006, context: 128000, maxOutput: 128000 },
      ],
    },
    {
      name: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      apiKeyEnv: 'ANTHROPIC_API_KEY',
      defaultModel: 'claude-sonnet-4-5',
      hasApiKey: false,
      enabled: true,
      models: [
        { id: 'claude-sonnet-4-5', default: true },
      ],
    },
    {
      name: 'hidden',
      baseUrl: 'https://hidden.example/v1',
      apiKeyEnv: 'HIDDEN_API_KEY',
      defaultModel: 'hidden-1',
      hasApiKey: true,
      enabled: false,
      models: [{ id: 'hidden-1' }],
    },
  ],
  currentProvider: 'glm',
  currentModel: 'glm-4-flash',
};

test.describe('R341 ProviderModelPicker', () => {
  test.beforeEach(async ({ mockedPage }) => {
    // install the per-spec handler BEFORE the initial
    // mount so the renderer's first listProviders call
    // (from the store's initialize()) sees our fixture.
    await mockedPage.addInitScript((providers) => {
      const w = window as any;
      w.__E2E_MOCK__.setInvokeHandler('listProviders', () => providers);
      w.__E2E_MOCK__.setInvokeHandler('listAvailableModels', () => providers);
      w.__E2E_MOCK__.setInvokeHandler('switchProvider', (params) => ({
        ok: true,
        provider: params?.provider,
        model: params?.model,
      }));
    }, R341_SAMPLE_PROVIDERS);
  });

  // Open the Settings panel by clicking the header gear
  // button. The button has no data-testid today; we use
  // its title attribute as the stable selector.
  async function openSettings(page: any): Promise<void> {
    await page.goto('/');
    await page.locator('button[title="Settings"]').click();
  }

  test('renders a chip for every configured provider', async ({ mockedPage }) => {
    await openSettings(mockedPage);
    await mockedPage.getByTestId('r341-provider-chip-minmax').waitFor({ timeout: 5_000 });
    await expect(mockedPage.getByTestId('r341-provider-chip-minmax')).toBeVisible();
    await expect(mockedPage.getByTestId('r341-provider-chip-glm')).toBeVisible();
    // anthropic has hasApiKey=false → hidden by default.
    await expect(mockedPage.getByTestId('r341-provider-chip-anthropic')).toHaveCount(0);
    // hidden has enabled=false → never shown.
    await expect(mockedPage.getByTestId('r341-provider-chip-hidden')).toHaveCount(0);
  });

  test('clicking a chip switches the model list to that brand', async ({ mockedPage }) => {
    await openSettings(mockedPage);
    await mockedPage.getByTestId('r341-provider-chip-minmax').waitFor({ timeout: 5_000 });
    // The daemon-current is glm; glm models render.
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4-flash')).toBeVisible();
    await mockedPage.getByTestId('r341-provider-chip-minmax').click();
    // After clicking minmax, the model list switches.
    await expect(mockedPage.getByTestId('r341-model-minmax-MiniMax-M3')).toBeVisible();
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4-flash')).toHaveCount(0);
  });

  test('filter input narrows the model list (case-insensitive)', async ({ mockedPage }) => {
    await openSettings(mockedPage);
    await mockedPage.getByTestId('r341-model-filter').waitFor({ timeout: 5_000 });
    // glm is current; glm models render.
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4-flash')).toBeVisible();
    await mockedPage.getByTestId('r341-model-filter').fill('AIR');
    // After the 150ms debounce + render, only glm-4.5-air remains.
    await mockedPage.getByTestId('r341-model-glm-glm-4.5-air').waitFor({ timeout: 1_000 });
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4-flash')).toHaveCount(0);
  });

  test('clicking a model row fires switchProvider with (provider, model)', async ({ mockedPage }) => {
    await openSettings(mockedPage);
    await mockedPage.getByTestId('r341-model-glm-glm-4.5').waitFor({ timeout: 5_000 });
    await mockedPage.getByTestId('r341-model-glm-glm-4.5').click();
    // The mock captured the switchProvider call. Inspect
    // window.__E2E_MOCK__.getInvokeCalls('switchProvider')
    // and assert the params.
    const calls = await mockedPage.evaluate(() => {
      const w = window as any;
      return w.__E2E_MOCK__.getInvokeCalls('switchProvider');
    });
    expect(calls.length).toBeGreaterThan(0);
    const last = calls[calls.length - 1];
    // The desktop wraps every switchProvider call as
    // an rpc_call (see methods.ts switchProvider wiring)
    // — the inner params carry provider + model.
    expect(JSON.stringify(last)).toMatch(/glm/);
    expect(JSON.stringify(last)).toMatch(/glm-4\.5/);
  });

  test('"Show all providers" surfaces the (no key) badge for unconfigured', async ({ mockedPage }) => {
    await openSettings(mockedPage);
    // The Settings panel has a settings-show-all-providers
    // toggle (R286). Toggle it on, then anthropic should
    // render with the (no key) badge.
    await mockedPage.getByTestId('settings-show-all-providers').waitFor({ timeout: 5_000 });
    await mockedPage.getByTestId('settings-show-all-providers').check();
    await mockedPage.getByTestId('r341-provider-chip-anthropic').waitFor({ timeout: 2_000 });
    await expect(mockedPage.getByTestId('r341-provider-chip-anthropic')).toBeVisible();
    await expect(mockedPage.getByTestId('r341-provider-chip-anthropic')).toHaveAttribute('data-has-key', '0');
    // The "hidden" provider (enabled=false) still does not appear.
    await expect(mockedPage.getByTestId('r341-provider-chip-hidden')).toHaveCount(0);
  });
});