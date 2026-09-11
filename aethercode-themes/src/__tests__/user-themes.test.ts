/**
 * T-406: user-themes loader.
 *  - reads YAML files from the user-themes dir
 *  - user theme with the same name shadows the built-in
 *  - missing directory is not an error
 *  - malformed files are reported, not fatal
 *  - export round-trips through YAML
 *  - coerceTheme validates required fields
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  coerceTheme,
  loadUserThemesFromDir,
  writeThemeYaml,
  InvalidThemeError,
  ThemeStore,
  dark,
} from '../index.js';

let tmp: string;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-themes-test-'));
});

afterEach(async () => {
  await fs.rm(tmp, { recursive: true, force: true });
});

const SAMPLE_YAML = `
isDark: false
colors:
  background: "#ffffff"
  foreground: "#111111"
  accent:     "#ff00ff"
  muted:      "#888888"
  success:    "#00ff00"
  warning:    "#ffaa00"
  error:      "#ff0000"
  border:     "#cccccc"
  selection:  "#eeeeee"
font:
  family: "Fira Code"
  size: 13
  lineHeight: 1.5
  ligatures: false
`;

describe('aethercode-themes — user themes (T-404)', () => {
  it('reads a single YAML file and assigns basename as name', async () => {
    const file = path.join(tmp, 'my-pink.yaml');
    await fs.writeFile(file, SAMPLE_YAML, 'utf8');
    const { themes, errors } = await loadUserThemesFromDir(tmp);
    expect(errors).toEqual([]);
    expect(themes).toHaveLength(1);
    const t = themes[0]!;
    expect(t.name).toBe('my-pink');
    expect(t.isDark).toBe(false);
    expect(t.colors.accent).toBe('#ff00ff');
    expect(t.font.family).toBe('Fira Code');
    expect(t.font.ligatures).toBe(false);
  });

  it('honors an explicit `name:` in the YAML', async () => {
    const file = path.join(tmp, 'whatever.yaml');
    await fs.writeFile(
      file,
      SAMPLE_YAML.replace('isDark: false', 'name: explicit\nisDark: false'),
      'utf8',
    );
    const { themes } = await loadUserThemesFromDir(tmp);
    expect(themes[0]!.name).toBe('explicit');
  });

  it('missing directory returns empty + no error', async () => {
    const ghost = path.join(tmp, 'nope');
    const { themes, errors } = await loadUserThemesFromDir(ghost);
    expect(themes).toEqual([]);
    expect(errors).toEqual([]);
  });

  it('non-YAML files are ignored', async () => {
    await fs.writeFile(path.join(tmp, 'readme.md'), '# notes', 'utf8');
    await fs.writeFile(path.join(tmp, 'real.yaml'), SAMPLE_YAML, 'utf8');
    const { themes } = await loadUserThemesFromDir(tmp);
    expect(themes).toHaveLength(1);
    expect(themes[0]!.name).toBe('real');
  });

  it('malformed file is reported, others still load', async () => {
    await fs.writeFile(path.join(tmp, 'broken.yaml'), 'isDark: yes', 'utf8'); // wrong type
    await fs.writeFile(path.join(tmp, 'good.yaml'), SAMPLE_YAML, 'utf8');
    const { themes, errors } = await loadUserThemesFromDir(tmp);
    expect(themes).toHaveLength(1);
    expect(themes[0]!.name).toBe('good');
    expect(errors).toHaveLength(1);
    expect(errors[0]!.file).toContain('broken.yaml');
  });

  it('coerceTheme throws on missing color keys', () => {
    const bad = { isDark: true, colors: { background: '#000' }, font: { family: 'x', size: 1, lineHeight: 1, ligatures: false } };
    expect(() => coerceTheme(bad, 'x.yaml')).toThrow(InvalidThemeError);
  });

  it('coerceTheme throws when isDark is missing', () => {
    const bad = {
      colors: { background: '#000', foreground: '#fff', accent: '#fff', muted: '#fff', success: '#fff', warning: '#fff', error: '#fff', border: '#fff', selection: '#fff' },
      font: { family: 'x', size: 1, lineHeight: 1, ligatures: false },
    };
    expect(() => coerceTheme(bad, 'x.yaml')).toThrow(/isDark/);
  });

  it('writeThemeYaml → loadUserThemesFromDir round-trips', async () => {
    // Filename is `round.yaml` but the dark theme carries `name: dark`
    // in the YAML, so the reloaded name comes from the YAML (not the
    // filename). That matches `coerceTheme` precedence rules.
    const out = path.join(tmp, 'round.yaml');
    await writeThemeYaml(dark, out);
    const { themes, errors } = await loadUserThemesFromDir(tmp);
    expect(errors).toEqual([]);
    expect(themes).toHaveLength(1);
    expect(themes[0]!.name).toBe('dark');
    expect(themes[0]!.isDark).toBe(dark.isDark);
    expect(themes[0]!.colors).toEqual(dark.colors);
    expect(themes[0]!.font).toEqual(dark.font);
  });
});

describe('aethercode-themes — user override shadows built-in', () => {
  it('ThemeStore.list surfaces the user theme when name collides with a built-in', async () => {
    const file = path.join(tmp, 'light.yaml');
    await fs.writeFile(file, SAMPLE_YAML, 'utf8'); // name defaults to "light"
    const store = new ThemeStore({
      userHome: tmp,
      userThemesDir: tmp,
      themeFilePath: path.join(tmp, 'theme.json'),
      autoLoad: true,
    });
    // give the auto-load promise a tick to resolve
    await store.reloadUserThemes();
    const list = store.list();
    const lightListed = list.find((t) => t.name === 'light')!;
    expect(lightListed.origin).toBe('user');
    expect(lightListed.colors.accent).toBe('#ff00ff');
  });
});
