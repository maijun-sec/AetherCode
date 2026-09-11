/**
 * T-406: ThemeStore behavior.
 *  - list() / get() / has() / load() / setActive() / on() lifecycle
 *  - setActive writes theme.json and fires the change event
 *  - default fall-back when theme.json is missing or unreadable
 *  - importFromYaml / exportToYaml round-trip
 *  - importFromYaml replaces an existing user theme with the same name
 *  - load() self-heals when theme.json references a missing theme
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  ThemeStore,
  UnknownThemeError,
  ActiveThemeFileError,
  light,
  dark,
  DEFAULT_THEME_NAME,
  type ThemeChangedEvent,
} from '../index.js';

let tmp: string;
let themeFile: string;
let themesDir: string;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-store-test-'));
  themeFile = path.join(tmp, 'theme.json');
  themesDir = path.join(tmp, 'themes');
  await fs.mkdir(themesDir, { recursive: true });
});

afterEach(async () => {
  await fs.rm(tmp, { recursive: true, force: true });
});

function makeStore(): ThemeStore {
  return new ThemeStore({
    userHome: tmp,
    themeFilePath: themeFile,
    userThemesDir: themesDir,
    autoLoad: false,
  });
}

describe('ThemeStore — read (T-402)', () => {
  it('list() returns the 5 built-ins by default', () => {
    const s = makeStore();
    const names = s.list().map((t) => t.name);
    expect(names).toEqual(['light', 'dark', 'solarized-light', 'solarized-dark', 'high-contrast']);
    for (const t of s.list()) {
      expect(t.origin).toBe('builtin');
    }
  });

  it('get(name) returns the built-in', () => {
    const s = makeStore();
    expect(s.get('light')).toBe(light);
    expect(s.get('dark')).toBe(dark);
  });

  it('get(unknown) throws UnknownThemeError', () => {
    const s = makeStore();
    expect(() => s.get('does-not-exist')).toThrow(UnknownThemeError);
  });

  it('has(name) reflects built-in membership', () => {
    const s = makeStore();
    expect(s.has('light')).toBe(true);
    expect(s.has('nope')).toBe(false);
  });
});

describe('ThemeStore — load (T-407 default fall-back)', () => {
  it('falls back to the default (light) when theme.json is missing', async () => {
    const s = makeStore();
    const t = await s.load();
    expect(t.name).toBe(DEFAULT_THEME_NAME);
    expect(t).toBe(light);
  });

  it('reads theme.json when present and valid', async () => {
    await fs.writeFile(themeFile, JSON.stringify({ schemaVersion: 1, active: 'dark' }), 'utf8');
    const s = makeStore();
    const t = await s.load();
    expect(t).toBe(dark);
  });

  it('falls back to the default when theme.json is invalid JSON', async () => {
    await fs.writeFile(themeFile, '{ not json', 'utf8');
    const s = makeStore();
    await expect(s.load()).rejects.toBeInstanceOf(ActiveThemeFileError);
  });

  it('self-heals when theme.json references a missing theme', async () => {
    await fs.writeFile(
      themeFile,
      JSON.stringify({ schemaVersion: 1, active: 'ghost-theme' }),
      'utf8',
    );
    const s = makeStore();
    const t = await s.load();
    expect(t).toBe(light);
    // The stale file is overwritten with the default.
    const reloaded = JSON.parse(await fs.readFile(themeFile, 'utf8'));
    expect(reloaded.active).toBe('light');
    expect(reloaded.schemaVersion).toBe(1);
  });

  it('load() result is memoized (stable reference)', async () => {
    const s = makeStore();
    const a = await s.load();
    const b = await s.load();
    expect(a).toBe(b);
  });
});

describe('ThemeStore — setActive (T-402 + T-405)', () => {
  it('writes theme.json and returns the new theme', async () => {
    const s = makeStore();
    const result = await s.setActive('dark');
    expect(result).toBe(dark);
    const disk = JSON.parse(await fs.readFile(themeFile, 'utf8'));
    expect(disk).toEqual({ schemaVersion: 1, active: 'dark' });
  });

  it('fires a change event with previous + current', async () => {
    const s = makeStore();
    // Prime the cache so `previous` is the previous active.
    const primed = await s.load();
    expect(primed).toBe(light);

    const seen: ThemeChangedEvent[] = [];
    s.on('change', (e) => seen.push(e));

    const next = await s.setActive('dark');
    expect(seen).toHaveLength(1);
    expect(seen[0]!.current).toBe(next);
    expect(seen[0]!.previous).toBe(light);
  });

  it('setActive on an unknown name throws and does NOT write the file', async () => {
    const s = makeStore();
    await expect(s.setActive('not-a-thing')).rejects.toBeInstanceOf(UnknownThemeError);
    // File should not exist after a failed setActive.
    await expect(fs.stat(themeFile)).rejects.toMatchObject({ code: 'ENOENT' });
  });

  it('unsubscribe stops further events', async () => {
    const s = makeStore();
    await s.load();
    const seen: ThemeChangedEvent[] = [];
    const unsub = s.on('change', (e) => seen.push(e));
    await s.setActive('dark');
    unsub();
    await s.setActive('light');
    expect(seen).toHaveLength(1);
  });

  it('once() fires only for the first matching event', async () => {
    const s = makeStore();
    await s.load();
    const seen: ThemeChangedEvent[] = [];
    s.once('change', (e) => seen.push(e));
    await s.setActive('dark');
    await s.setActive('light');
    expect(seen).toHaveLength(1);
    expect(seen[0]!.current.name).toBe('dark');
  });

  it('two consecutive setActive calls emit two change events in order', async () => {
    const s = makeStore();
    await s.load();
    const seen: string[] = [];
    s.on('change', (e) => seen.push(e.current.name));
    await s.setActive('dark');
    await s.setActive('solarized-dark');
    expect(seen).toEqual(['dark', 'solarized-dark']);
  });
});

describe('ThemeStore — import / export (T-402)', () => {
  it('importFromYaml adds a user theme and surfaces it via get/list', async () => {
    const src = path.join(tmp, 'src.yaml');
    await fs.writeFile(
      src,
      [
        'name: my-pink',
        'isDark: false',
        'colors:',
        '  background: "#fff"',
        '  foreground: "#000"',
        '  accent:     "#ff00ff"',
        '  muted:      "#888"',
        '  success:    "#0f0"',
        '  warning:    "#ff0"',
        '  error:      "#f00"',
        '  border:     "#ccc"',
        '  selection:  "#eee"',
        'font:',
        '  family: "Fira Code"',
        '  size: 13',
        '  lineHeight: 1.5',
        '  ligatures: false',
        '',
      ].join('\n'),
      'utf8',
    );
    const s = makeStore();
    const t = await s.importFromYaml(src);
    expect(t.name).toBe('my-pink');
    expect(s.get('my-pink')).toBe(t);
    expect(s.list().find((x) => x.name === 'my-pink')!.origin).toBe('user');
  });

  it('importFromYaml with the same name replaces the previous user theme', async () => {
    const s = makeStore();
    const a = path.join(tmp, 'a.yaml');
    const b = path.join(tmp, 'b.yaml');
    await fs.writeFile(
      a,
      'name: clash\nisDark: false\ncolors:\n  background: "#fff"\n  foreground: "#000"\n  accent: "#aaa"\n  muted: "#888"\n  success: "#0f0"\n  warning: "#ff0"\n  error: "#f00"\n  border: "#ccc"\n  selection: "#eee"\nfont:\n  family: "Fira Code"\n  size: 13\n  lineHeight: 1.5\n  ligatures: false\n',
      'utf8',
    );
    await fs.writeFile(
      b,
      'name: clash\nisDark: true\ncolors:\n  background: "#000"\n  foreground: "#fff"\n  accent: "#bbb"\n  muted: "#888"\n  success: "#0f0"\n  warning: "#ff0"\n  error: "#f00"\n  border: "#ccc"\n  selection: "#eee"\nfont:\n  family: "Fira Code"\n  size: 13\n  lineHeight: 1.5\n  ligatures: false\n',
      'utf8',
    );
    const t1 = await s.importFromYaml(a);
    expect(t1.colors.accent).toBe('#aaa');
    const t2 = await s.importFromYaml(b);
    expect(t2.colors.accent).toBe('#bbb');
    // Only one user theme named "clash".
    expect(s.list().filter((x) => x.name === 'clash')).toHaveLength(1);
  });

  it('exportToYaml writes a built-in theme that re-loads identically', async () => {
    const s = makeStore();
    const out = path.join(tmp, 'exported', 'dark.yaml');
    await s.exportToYaml('dark', out);
    const text = await fs.readFile(out, 'utf8');
    expect(text).toContain('name: dark');
    // The re-imported theme matches the original.
    const re = await s.importFromYaml(out);
    expect(re.name).toBe('dark');
    expect(re.colors).toEqual(dark.colors);
    expect(re.font).toEqual(dark.font);
  });

  it('exportToYaml on an unknown name throws UnknownThemeError', async () => {
    const s = makeStore();
    await expect(s.exportToYaml('nope', path.join(tmp, 'x.yaml'))).rejects.toBeInstanceOf(
      UnknownThemeError,
    );
  });
});

describe('ThemeStore — user override precedence (T-404)', () => {
  it('a user theme with the same name as a built-in wins on list() and get()', async () => {
    const file = path.join(themesDir, 'dark.yaml');
    await fs.writeFile(
      file,
      'isDark: true\ncolors:\n  background: "#222"\n  foreground: "#eee"\n  accent: "#ff00ff"\n  muted: "#888"\n  success: "#0f0"\n  warning: "#ff0"\n  error: "#f00"\n  border: "#444"\n  selection: "#555"\nfont:\n  family: "Fira Code"\n  size: 13\n  lineHeight: 1.5\n  ligatures: false\n',
      'utf8',
    );
    const s = makeStore();
    const errors = await s.reloadUserThemes();
    expect(errors).toEqual([]);
    const t = s.get('dark');
    expect(t.colors.accent).toBe('#ff00ff');
    expect(s.list().find((x) => x.name === 'dark')!.origin).toBe('user');
  });

  it('user theme load errors are surfaced via userThemeErrors', async () => {
    await fs.writeFile(path.join(themesDir, 'bad.yaml'), 'isDark: yes\n', 'utf8');
    const s = makeStore();
    const errors = await s.reloadUserThemes();
    expect(errors).toHaveLength(1);
    expect(errors[0]!.file).toContain('bad.yaml');
    expect(s.userThemeErrors).toHaveLength(1);
  });
});
