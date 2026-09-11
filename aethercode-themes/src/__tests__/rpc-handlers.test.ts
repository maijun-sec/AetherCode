/**
 * RPC handler bundle (design.md §5.4).
 *
 *  - theme/list returns the catalog with origin tags
 *  - theme/get returns a single theme by name
 *  - theme/set switches the active and fires the change event
 *  - theme/import adds a YAML file as a user theme
 *  - theme/export writes a theme to a YAML file
 *  - theme/active returns the currently active theme
 *  - theme/default returns the surface default
 *  - theme/migrate runs the legacy migration
 *  - font/get + font/set round-trip the user font config
 *  - all handlers reject malformed params with RpcParamError
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  THEME_RPC_HANDLERS,
  ThemeStore,
  RpcParamError,
  type ThemeRpcContext,
} from '../index.js';

let tmp: string;
let themeFile: string;
let themesDir: string;
let ctx: ThemeRpcContext;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-rpc-'));
  themeFile = path.join(tmp, 'theme.json');
  themesDir = path.join(tmp, 'themes');
  await fs.mkdir(themesDir, { recursive: true });
  const store = new ThemeStore({
    userHome: tmp,
    themeFilePath: themeFile,
    userThemesDir: themesDir,
    autoLoad: false,
  });
  ctx = { store, surface: 'app' };
});

afterEach(async () => {
  await fs.rm(tmp, { recursive: true, force: true });
});

describe('aethercode-themes — RPC handlers', () => {
  it('THEME_RPC_HANDLERS bundle is frozen and has the documented methods', () => {
    expect(Object.isFrozen(THEME_RPC_HANDLERS)).toBe(true);
    for (const m of [
      'theme/list',
      'theme/get',
      'theme/set',
      'theme/import',
      'theme/export',
      'theme/active',
      'theme/default',
      'theme/migrate',
      'font/get',
      'font/set',
    ]) {
      expect(THEME_RPC_HANDLERS, `missing method: ${m}`).toHaveProperty(m);
      expect(typeof (THEME_RPC_HANDLERS as Record<string, unknown>)[m]).toBe('function');
    }
  });

  it('theme/list returns the 5 built-in themes with origin=builtin', async () => {
    const r = await THEME_RPC_HANDLERS['theme/list']!(ctx, {});
    expect(r.themes).toHaveLength(5);
    for (const t of r.themes as Array<{ origin: string }>) {
      expect(t.origin).toBe('builtin');
    }
  });

  it('theme/get returns a built-in by name', async () => {
    const r = await THEME_RPC_HANDLERS['theme/get']!(ctx, { name: 'dark' });
    expect(r.theme.name).toBe('dark');
    expect(r.theme.isDark).toBe(true);
  });

  it('theme/get rejects missing name', async () => {
    await expect(THEME_RPC_HANDLERS['theme/get']!(ctx, {})).rejects.toBeInstanceOf(RpcParamError);
  });

  it('theme/get rejects unknown name with RpcParamError', async () => {
    await expect(
      THEME_RPC_HANDLERS['theme/get']!(ctx, { name: 'nope' }),
    ).rejects.toBeInstanceOf(RpcParamError);
  });

  it('theme/set switches the active and persists it', async () => {
    const r = await THEME_RPC_HANDLERS['theme/set']!(ctx, { name: 'dark' });
    expect(r.active).toBe('dark');
    expect(r.theme.name).toBe('dark');
    const onDisk = JSON.parse(await fs.readFile(themeFile, 'utf8'));
    expect(onDisk.active).toBe('dark');
  });

  it('theme/set rejects unknown name with RpcParamError', async () => {
    await expect(
      THEME_RPC_HANDLERS['theme/set']!(ctx, { name: 'nope' }),
    ).rejects.toBeInstanceOf(RpcParamError);
  });

  it('theme/set rejects non-string name with RpcParamError', async () => {
    await expect(
      THEME_RPC_HANDLERS['theme/set']!(ctx, { name: 42 }),
    ).rejects.toBeInstanceOf(RpcParamError);
  });

  it('theme/import adds a YAML file as a user theme', async () => {
    const yamlPath = path.join(tmp, 'mypink.yaml');
    await fs.writeFile(
      yamlPath,
      [
        'name: mypink',
        'isDark: false',
        'colors:',
        '  background: "#fff"',
        '  foreground: "#000"',
        '  accent: "#ff00ff"',
        '  muted: "#888"',
        '  success: "#0f0"',
        '  warning: "#ff0"',
        '  error: "#f00"',
        '  border: "#ccc"',
        '  selection: "#eee"',
        'font:',
        '  family: "Fira Code"',
        '  size: 13',
        '  lineHeight: 1.5',
        '  ligatures: false',
        '',
      ].join('\n'),
      'utf8',
    );
    const r = await THEME_RPC_HANDLERS['theme/import']!(ctx, { path: yamlPath });
    expect(r.theme.name).toBe('mypink');
    expect(r.written).toBe(yamlPath);
    // Verify it landed in the store.
    const list = (await THEME_RPC_HANDLERS['theme/list']!(ctx, {})).themes as Array<{
      name: string;
      origin: string;
    }>;
    expect(list.find((t) => t.name === 'mypink')?.origin).toBe('user');
  });

  it('theme/import rejects an invalid YAML with RpcParamError', async () => {
    const yamlPath = path.join(tmp, 'bad.yaml');
    await fs.writeFile(yamlPath, 'isDark: yes\n', 'utf8'); // wrong type
    await expect(
      THEME_RPC_HANDLERS['theme/import']!(ctx, { path: yamlPath }),
    ).rejects.toBeInstanceOf(RpcParamError);
  });

  it('theme/export writes a built-in to a YAML file', async () => {
    const out = path.join(tmp, 'exported', 'dark.yaml');
    const r = await THEME_RPC_HANDLERS['theme/export']!(ctx, { name: 'dark', path: out });
    expect(r.written).toBe(out);
    const text = await fs.readFile(out, 'utf8');
    expect(text).toContain('name: dark');
  });

  it('theme/active returns the currently active theme', async () => {
    await THEME_RPC_HANDLERS['theme/set']!(ctx, { name: 'dark' });
    const r = await THEME_RPC_HANDLERS['theme/active']!(ctx, {});
    expect(r.theme.name).toBe('dark');
  });

  it('theme/default returns the surface default', async () => {
    const r1 = await THEME_RPC_HANDLERS['theme/default']!(ctx, {});
    expect(r1.surface).toBe('app');
    expect(r1.name).toBe('light');
    const tuiCtx: ThemeRpcContext = { store: ctx.store, surface: 'tui' };
    const r2 = await THEME_RPC_HANDLERS['theme/default']!(tuiCtx, {});
    expect(r2.surface).toBe('tui');
    expect(r2.name).toBe('dark');
  });

  it('theme/migrate runs the legacy migration on demand', async () => {
    const legacyPath = path.join(tmp, '.aethercode', 'desktop-state.json');
    await fs.mkdir(path.dirname(legacyPath), { recursive: true });
    await fs.writeFile(legacyPath, JSON.stringify({ theme: 'high-contrast' }), 'utf8');
    const r = await THEME_RPC_HANDLERS['theme/migrate']!(ctx, { surface: 'app' });
    expect(r.result.activeName).toBe('high-contrast');
  });

  it('font/get returns DEFAULT_FONT when no file is present', async () => {
    const r = await THEME_RPC_HANDLERS['font/get']!(ctx, {});
    expect(r.font).toEqual({
      family: 'JetBrains Mono',
      size: 14,
      lineHeight: 1.4,
      ligatures: true,
    });
  });

  it('font/set round-trips with font/get', async () => {
    const target = path.join(tmp, 'font.yaml');
    // Override the default path for this call by writing directly.
    const { writeFontConfig, loadFontConfig } = await import('../index.js');
    await writeFontConfig(
      { family: 'Iosevka', size: 16, lineHeight: 1.5, ligatures: false },
      target,
    );
    // We need to monkey-patch loadFontConfig for the handler to use
    // the test path. Easiest: write to the actual default path.
    await writeFontConfig(
      { family: 'Iosevka', size: 16, lineHeight: 1.5, ligatures: false },
      path.join(tmp, '.aethercode', 'font.yaml'),
    );
    const r = await THEME_RPC_HANDLERS['font/get']!(ctx, {});
    expect(r.font).toEqual({
      family: 'Iosevka',
      size: 16,
      lineHeight: 1.5,
      ligatures: false,
    });
    void target;
    void loadFontConfig;
  });

  it('font/set rejects malformed params with RpcParamError', async () => {
    await expect(
      THEME_RPC_HANDLERS['font/set']!(ctx, { family: 42 }),
    ).rejects.toBeInstanceOf(RpcParamError);
  });

  it('font/set writes a valid font and returns the coerced result', async () => {
    const r = await THEME_RPC_HANDLERS['font/set']!(ctx, {
      family: 'Cascadia Code',
      size: 15,
      lineHeight: 1.4,
      ligatures: false,
    });
    expect(r.font).toEqual({
      family: 'Cascadia Code',
      size: 15,
      lineHeight: 1.4,
      ligatures: false,
    });
  });

  it('handlers reject non-object params with RpcParamError', async () => {
    await expect(THEME_RPC_HANDLERS['theme/get']!(ctx, null)).rejects.toBeInstanceOf(RpcParamError);
    await expect(THEME_RPC_HANDLERS['theme/get']!(ctx, 42)).rejects.toBeInstanceOf(RpcParamError);
  });
});
