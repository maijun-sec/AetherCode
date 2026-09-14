/**
 * E2E fixture: a {@code page} with the Tauri mock installed
 * + a shared beforeEach that registers the default RPC
 * stubs every spec needs.
 */
import { test as base, expect } from '@playwright/test';
import { installTauriMock } from './_setup';

export const test = base.extend<{ mockedPage: import('@playwright/test').Page }>({
  mockedPage: async ({ page }, use) => {
    await installTauriMock(page);
    await use(page);
  },
});

export { expect };
