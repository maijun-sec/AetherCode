/**
 * T-411: legacy desktop-state.json → theme.json migration.
 *
 *  - missing legacy file → write the surface-default theme
 *  - present legacy file with a known theme → write that theme
 *  - present legacy file with an unknown theme → fall back to default
 *  - already-migrated → do not overwrite
 *  - explicit surface parameter is honoured
 *  - result struct reports `written` and `reason` correctly
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import {
  migrateFromDesktopState,
  legacyStateFilePath,
  LegacyStateFileError,
  readActiveThemeFile,
} from '../index.js';

let tmp: string;
let legacy: string;
let newFile: string;

beforeEach(async () => {
  tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'aethercode-migrate-'));
  legacy = legacyStateFilePath(tmp);
  newFile = path.join(tmp, '.aethercode', 'theme.json');
  await fs.mkdir(path.dirname(legacy), { recursive: true });
});

afterEach(async () => {
  await fs.rm(tmp, { recursive: true, force: true });
});

async function writeLegacy(payload: Record<string, unknown> | string): Promise<void> {
  await fs.writeFile(legacy, typeof payload === 'string' ? payload : JSON.stringify(payload), 'utf8');
}

describe('aethercode-themes — migration (T-411)', () => {
  it('writes the app default when no legacy file exists', async () => {
    const r = await migrateFromDesktopState({
      userHome: tmp,
      legacyStatePath: legacy,
      themeFilePath: newFile,
      surface: 'app',
    });
    expect(r.written).toBe(true);
    expect(r.activeName).toBe('light');
    expect(r.reason).toBe('new-file-missing');
    const onDisk = await readActiveThemeFile({ filePath: newFile });
    expect(onDisk).toEqual({ schemaVersion: 1, active: 'light' });
  });

  it('writes the tui default when no legacy file exists and surface=tui', async () => {
    const r = await migrateFromDesktopState({
      userHome: tmp,
      legacyStatePath: legacy,
      themeFilePath: newFile,
      surface: 'tui',
    });
    expect(r.written).toBe(true);
    expect(r.activeName).toBe('dark');
    expect(r.reason).toBe('new-file-missing');
  });

  it('extracts the theme hint from the legacy file', async () => {
    await writeLegacy({ theme: 'solarized-dark', windowState: { x: 1 } });
    const r = await migrateFromDesktopState({
      userHome: tmp,
      legacyStatePath: legacy,
      themeFilePath: newFile,
      surface: 'app',
    });
    expect(r.written).toBe(true);
    expect(r.activeName).toBe('solarized-dark');
    expect(r.reason).toBe('legacy-file-present');
  });

  it('falls back to surface default when the legacy hint is unknown', async () => {
    await writeLegacy({ theme: 'totally-fake-theme' });
    const r = await migrateFromDesktopState({
      userHome: tmp,
      legacyStatePath: legacy,
      themeFilePath: newFile,
      surface: 'app',
    });
    expect(r.written).toBe(true);
    expect(r.activeName).toBe('light');
  });

  it('does not overwrite when the new file already exists', async () => {
    await fs.writeFile(newFile, JSON.stringify({ schemaVersion: 1, active: 'dark' }), 'utf8');
    await writeLegacy({ theme: 'light' });
    const r = await migrateFromDesktopState({
      userHome: tmp,
      legacyStatePath: legacy,
      themeFilePath: newFile,
      surface: 'app',
    });
    expect(r.written).toBe(false);
    expect(r.reason).toBe('new-file-already-present');
    const onDisk = await readActiveThemeFile({ filePath: newFile });
    expect(onDisk?.active).toBe('dark');
  });

  it('ignores a legacy file that has no theme field', async () => {
    await writeLegacy({ windowState: { x: 1, y: 2 } });
    const r = await migrateFromDesktopState({
      userHome: tmp,
      legacyStatePath: legacy,
      themeFilePath: newFile,
      surface: 'app',
    });
    expect(r.written).toBe(true);
    expect(r.activeName).toBe('light');
    expect(r.reason).toBe('new-file-missing');
  });

  it('legacyStateFilePath defaults to <home>/.aethercode/desktop-state.json', () => {
    expect(legacyStateFilePath('/h')).toBe(path.join('/h', '.aethercode', 'desktop-state.json'));
  });

  it('throws LegacyStateFileError when the legacy file is unparseable', async () => {
    await writeLegacy('not json');
    await expect(
      migrateFromDesktopState({
        userHome: tmp,
        legacyStatePath: legacy,
        themeFilePath: newFile,
        surface: 'app',
      }),
    ).rejects.toBeInstanceOf(LegacyStateFileError);
  });
});
