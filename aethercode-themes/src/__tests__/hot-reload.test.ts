/**
 * T-409: hot-reload + ThemeStore.invalidateCache / reloadFromDisk.
 *
 *  - invalidateCache clears the memoised active
 *  - reloadFromDisk re-reads theme.json and fires change if the
 *    name differs from the last emit
 *  - reloadFromDisk is a no-op when the name is unchanged
 *  - ThemeHotReloader can be constructed, started, and stopped
 *  - ThemeHotReloader.reload() is a safe synchronous no-op once
 *    stop() has been called
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  ThemeStore,
  ThemeHotReloader,
  dark,
  light,
  type ThemeChangedEvent,
} from '../index.js';

let tmp: string;
let themeFile: string;
let themesDir: string;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-hotreload-'));
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

describe('aethercode-themes — hot-reload (T-409)', () => {
  it('invalidateCache forces a re-read on the next load()', async () => {
    const s = makeStore();
    await s.setActive('dark');
    expect((await s.load()).name).toBe('dark');
    // External write to theme.json (simulating CLI edit).
    await fs.writeFile(
      themeFile,
      JSON.stringify({ schemaVersion: 1, active: 'light' }),
      'utf8',
    );
    // Without invalidation, the cache still serves "dark".
    expect((await s.load()).name).toBe('dark');
    s.invalidateCache();
    expect((await s.load()).name).toBe('light');
  });

  it('reloadFromDisk fires change when the new active differs from the last emit', async () => {
    const s = makeStore();
    await s.setActive('light');
    const seen: ThemeChangedEvent[] = [];
    s.on('change', (e) => seen.push(e));
    // External edit.
    await fs.writeFile(
      themeFile,
      JSON.stringify({ schemaVersion: 1, active: 'dark' }),
      'utf8',
    );
    await s.reloadFromDisk();
    expect(seen).toHaveLength(1);
    expect(seen[0]!.current.name).toBe('dark');
    expect(seen[0]!.previous).toBe(light);
  });

  it('reloadFromDisk does NOT re-emit when the active is unchanged', async () => {
    const s = makeStore();
    await s.setActive('dark');
    const seen: ThemeChangedEvent[] = [];
    s.on('change', (e) => seen.push(e));
    // No file change, just call reload.
    await s.reloadFromDisk();
    expect(seen).toEqual([]);
  });

  it('reloadFromDisk re-emits when called twice with a real change in between', async () => {
    const s = makeStore();
    await s.setActive('light');
    const seen: string[] = [];
    s.on('change', (e) => seen.push(e.current.name));
    await fs.writeFile(
      themeFile,
      JSON.stringify({ schemaVersion: 1, active: 'dark' }),
      'utf8',
    );
    await s.reloadFromDisk();
    await fs.writeFile(
      themeFile,
      JSON.stringify({ schemaVersion: 1, active: 'solarized-dark' }),
      'utf8',
    );
    await s.reloadFromDisk();
    expect(seen).toEqual(['dark', 'solarized-dark']);
  });

  it('store.paths exposes the on-disk locations', () => {
    const s = makeStore();
    expect(s.paths.themeFilePath).toBe(themeFile);
    expect(s.paths.userThemesDir).toBe(themesDir);
    expect(s.paths.userHome).toBe(tmp);
  });

  it('ThemeHotReloader can be started and stopped', () => {
    const s = makeStore();
    const reloader = new ThemeHotReloader({ store: s, coalesceMs: 1 });
    const stop = reloader.start();
    expect(typeof stop).toBe('function');
    stop();
    // start/stop idempotent: stop is safe to call twice.
    expect(() => stop()).not.toThrow();
  });

  it('ThemeHotReloader.reload() picks up a disk edit', async () => {
    const s = makeStore();
    await s.setActive('light');
    const seen: string[] = [];
    s.on('change', (e) => seen.push(e.current.name));
    const reloader = new ThemeHotReloader({ store: s, coalesceMs: 1 });
    reloader.start();
    try {
      await fs.writeFile(
        themeFile,
        JSON.stringify({ schemaVersion: 1, active: 'dark' }),
        'utf8',
      );
      await reloader.reload();
      expect(seen).toContain('dark');
    } finally {
      reloader.stop();
    }
  });

  it('ThemeHotReloader.reload() is a safe no-op after stop()', async () => {
    const s = makeStore();
    const reloader = new ThemeHotReloader({ store: s, coalesceMs: 1 });
    reloader.start();
    reloader.stop();
    // Should not throw.
    await expect(reloader.reload()).resolves.toBeUndefined();
  });

  it('start() twice throws', () => {
    const s = makeStore();
    const reloader = new ThemeHotReloader({ store: s, coalesceMs: 1 });
    reloader.start();
    expect(() => reloader.start()).toThrow();
    reloader.stop();
  });

  it('reloads the user-themes dir as part of the reload', async () => {
    // Write a user theme to disk after the store was created.
    await fs.writeFile(
      path.join(themesDir, 'from-disk.yaml'),
      [
        'name: from-disk',
        'isDark: true',
        'colors:',
        '  background: "#000"',
        '  foreground: "#fff"',
        '  accent: "#ff0"',
        '  muted: "#888"',
        '  success: "#0f0"',
        '  warning: "#ff0"',
        '  error: "#f00"',
        '  border: "#444"',
        '  selection: "#222"',
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
    // The autoLoad is off, so the user theme is not yet in the map.
    expect(s.has('from-disk')).toBe(false);
    await s.reloadFromDisk();
    expect(s.has('from-disk')).toBe(true);
    // Cross-check the theme is what we wrote.
    const t = s.get('from-disk');
    expect(t.colors.accent).toBe('#ff0');
    expect(t).not.toBe(dark);
  });
});
