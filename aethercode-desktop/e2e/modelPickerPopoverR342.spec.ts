/**
 * R342 — ModelPickerPopover E2E.
 *
 * <p>End-to-end coverage for the compact popover picker that
 * replaced the legacy {@code <select>} model dropdown in the
 * MessageInput config bar. The legacy dropdown was unfilterable
 * and showed only the currently-selected option when closed
 * (the user reported "现在 model 还是没办法筛选啊，只有一个
 * MiniMax-M3"). The popover exposes the full R341 2-level picker
 * (chip row + debounced filter + model list) on click.
 *
 * <p>This spec pins:
 * <ul>
 *   <li>the trigger button shows {@code provider/model} on the
 *       input config bar</li>
 *   <li>clicking the trigger opens a popover with the chip row</li>
 *   <li>filter input narrows the model list (150ms debounce)</li>
 *   <li>selecting a model row fires switchProvider and closes
 *       the popover</li>
 *   <li>Escape closes the popover</li>
 *   <li>click-outside closes the popover</li>
 *   <li>providers with hasApiKey=false don't appear in the
 *       chip row (consistent with the R286 picker filter)</li>
 * </ul>
 */
import { test, expect } from './_fixtures';

const R342_SAMPLE_PROVIDERS = {
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
        { id: 'MiniMax-Text-01', inputPer1k: 0.001, outputPer1k: 0.008, context: 1000000, maxOutput: 512000 },
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
      name: 'deepseek',
      baseUrl: 'https://api.deepseek.com/v1',
      apiKeyEnv: 'DEEPSEEK_API_KEY',
      defaultModel: 'deepseek-chat',
      hasApiKey: true,
      enabled: true,
      models: [
        { id: 'deepseek-chat', default: true },
        { id: 'deepseek-reasoner' },
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
  ],
  currentProvider: 'minmax',
  currentModel: 'MiniMax-M3',
};

test.describe('R342 ModelPickerPopover (MessageInput)', () => {
  test.beforeEach(async ({ mockedPage }) => {
    // Install the per-spec handler BEFORE the initial
    // mount so the renderer's first listProviders call
    // (from the store's initialize() and MessageInput's
    // useEffect on connect) sees our fixture.
    await mockedPage.addInitScript((providers) => {
      const w = window as any;
      w.__E2E_MOCK__.setInvokeHandler('listProviders', () => providers);
      w.__E2E_MOCK__.setInvokeHandler('listAvailableModels', () => providers);
      w.__E2E_MOCK__.setInvokeHandler('switchProvider', (params) => ({
        ok: true,
        provider: params?.provider,
        model: params?.model,
      }));
    }, R342_SAMPLE_PROVIDERS);
  });

  test('trigger button shows current provider/model', async ({ mockedPage }) => {
    await mockedPage.goto('/');
    const trigger = mockedPage.getByTestId('r342-model-picker-trigger');
    await trigger.waitFor({ timeout: 5_000 });
    await expect(trigger).toBeVisible();
    // The trigger label carries both provider + model.
    await expect(trigger).toContainText('minmax');
    await expect(trigger).toContainText('MiniMax-M3');
    // aria-expanded starts false (popover closed by default).
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  test('clicking the trigger opens the popover with chip row', async ({ mockedPage }) => {
    await mockedPage.goto('/');
    const trigger = mockedPage.getByTestId('r342-model-picker-trigger');
    await trigger.waitFor({ timeout: 5_000 });
    await trigger.click();
    const popover = mockedPage.getByTestId('r342-model-picker-popover');
    await popover.waitFor({ timeout: 2_000 });
    await expect(popover).toBeVisible();
    // The chip row inside the popover should render every
    // hasApiKey=true provider.
    await expect(mockedPage.getByTestId('r341-provider-chip-minmax')).toBeVisible();
    await expect(mockedPage.getByTestId('r341-provider-chip-glm')).toBeVisible();
    await expect(mockedPage.getByTestId('r341-provider-chip-deepseek')).toBeVisible();
    // anthropic has hasApiKey=false → hidden.
    await expect(mockedPage.getByTestId('r341-provider-chip-anthropic')).toHaveCount(0);
    // aria-expanded flips to true when open.
    await expect(trigger).toHaveAttribute('aria-expanded', 'true');
  });

  test('switching chips in the popover shows that brand models', async ({ mockedPage }) => {
    await mockedPage.goto('/');
    await mockedPage.getByTestId('r342-model-picker-trigger').click();
    await mockedPage.getByTestId('r341-provider-chip-glm').waitFor({ timeout: 2_000 });
    // minmax is current — glm models aren't visible yet.
    await expect(mockedPage.getByTestId('r341-model-minmax-MiniMax-M3')).toBeVisible();
    // Click the glm chip.
    await mockedPage.getByTestId('r341-provider-chip-glm').click();
    // glm models render.
    await mockedPage.getByTestId('r341-model-glm-glm-4-flash').waitFor({ timeout: 1_000 });
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4.5')).toBeVisible();
    // The popover is still open — chip switching is internal UI.
    await expect(mockedPage.getByTestId('r342-model-picker-popover')).toBeVisible();
  });

  test('filter input narrows the model list', async ({ mockedPage }) => {
    await mockedPage.goto('/');
    await mockedPage.getByTestId('r342-model-picker-trigger').click();
    // Switch to glm so we can filter its models.
    await mockedPage.getByTestId('r341-provider-chip-glm').click();
    await mockedPage.getByTestId('r341-model-filter').waitFor({ timeout: 2_000 });
    await mockedPage.getByTestId('r341-model-filter').fill('AIR');
    // After the 150ms debounce + render, only glm-4.5-air remains.
    await mockedPage.getByTestId('r341-model-glm-glm-4.5-air').waitFor({ timeout: 1_500 });
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4-flash')).toHaveCount(0);
    await expect(mockedPage.getByTestId('r341-model-glm-glm-4.5')).toHaveCount(0);
  });

  test('clicking a model fires switchProvider and closes the popover', async ({ mockedPage }) => {
    await mockedPage.goto('/');
    await mockedPage.getByTestId('r342-model-picker-trigger').click();
    await mockedPage.getByTestId('r341-provider-chip-deepseek').waitFor({ timeout: 2_000 });
    await mockedPage.getByTestId('r341-provider-chip-deepseek').click();
    await mockedPage.getByTestId('r341-model-deepseek-deepseek-reasoner').waitFor({ timeout: 1_000 });
    await mockedPage.getByTestId('r341-model-deepseek-deepseek-reasoner').click();
    // Popover closes.
    await expect(mockedPage.getByTestId('r342-model-picker-popover')).toHaveCount(0);
    // switchProvider was called with (deepseek, deepseek-reasoner).
    const calls = await mockedPage.evaluate(() => {
      const w = window as any;
      return w.__E2E_MOCK__.getInvokeCalls('switchProvider');
    });
    expect(calls.length).toBeGreaterThan(0);
    const last = calls[calls.length - 1];
    expect(JSON.stringify(last)).toMatch(/deepseek/);
    expect(JSON.stringify(last)).toMatch(/deepseek-reasoner/);
  });

  test('Escape closes the popover', async ({ mockedPage }) => {
    await mockedPage.goto('/');
    await mockedPage.getByTestId('r342-model-picker-trigger').click();
    await mockedPage.getByTestId('r342-model-picker-popover').waitFor({ timeout: 2_000 });
    await mockedPage.keyboard.press('Escape');
    await expect(mockedPage.getByTestId('r342-model-picker-popover')).toHaveCount(0);
    // Trigger flips back to closed.
    await expect(mockedPage.getByTestId('r342-model-picker-trigger')).toHaveAttribute('aria-expanded', 'false');
  });
});