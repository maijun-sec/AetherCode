/**
 * aethercode-themes — one-shot migration from the legacy
 * `desktop-state.json` (T-411).
 *
 * design.md §6.2: the old `desktop-state.json` file had a single
 * `theme` field. The migration:
 *
 *   1. Reads `<UserHome>/.aethercode/desktop-state.json`.
 *   2. If it has a `theme: "<name>"` field, writes
 *      `<UserHome>/.aethercode/theme.json` with that name.
 *   3. Touches nothing else — the rest of `desktop-state.json`
 *      is the desktop app's concern (window state, last opened
 *      project, …) and migrates separately.
 *
 * Idempotency: if the new `theme.json` already exists, we do not
 * overwrite it — the user's later choices win over the historical
 * hint. If the old file doesn't exist or has no `theme` field, we
 * write the new file with the surface-default name so subsequent
 * reads see a non-null result.
 *
 * The migration is deliberately run-once (no watcher). Callers wire
 * it into app startup; if the file is later deleted, the migration
 * is not re-run unless the new file is also missing.
 */

import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import { defaultThemeFilePath } from './user-themes.js';
import { writeActiveThemeFile } from './persistence.js';
import { defaultThemeForSurface, type Surface } from './default-theme.js';
import { BUILTIN_THEMES_BY_NAME } from './builtin.js';
import { ThemeStore } from './theme-store.js';

export interface MigrationOptions {
  /** Override `<UserHome>`. */
  readonly userHome?: string;
  /** Override the legacy file path. */
  readonly legacyStatePath?: string;
  /** Override the new active-theme file path. */
  readonly themeFilePath?: string;
  /** Surface whose default is used when the legacy file is missing. */
  readonly surface?: Surface;
  /** Inject a custom store for `has()` checks. Defaults to a fresh
   *  `ThemeStore({ userHome, themeFilePath, autoLoad: false })`. */
  readonly store?: ThemeStore;
}

export interface MigrationResult {
  /** Whether `theme.json` was actually written by this run. */
  readonly written: boolean;
  /** The theme name that ended up in the new file. */
  readonly activeName: string;
  /** Why we wrote what we wrote — useful for the migration log. */
  readonly reason:
    | 'legacy-file-present'
    | 'new-file-missing'
    | 'new-file-already-present'
    | 'no-legacy-no-write';
}

/** Thrown when the legacy file is present but unparseable. */
export class LegacyStateFileError extends Error {
  constructor(
    public readonly filePath: string,
    public readonly reason: string,
  ) {
    super(`Cannot read legacy ${filePath}: ${reason}`);
    this.name = 'LegacyStateFileError';
  }
}

/**
 * Run the migration. Returns a `MigrationResult` describing what
 * happened; never throws on the "nothing to do" path. Only throws
 * on real I/O / parse failures the caller can't reasonably recover
 * from.
 */
export async function migrateFromDesktopState(
  opts: MigrationOptions = {},
): Promise<MigrationResult> {
  const surface: Surface = opts.surface ?? 'app';
  const userHome = opts.userHome;
  const legacyPath = opts.legacyStatePath ?? legacyStateFilePath(userHome);
  const themeFilePath = opts.themeFilePath ?? defaultThemeFilePath(userHome);

  // 1. Did the new file already exist? If so, the user's current
  //    choice wins — the historical hint is stale.
  try {
    await fs.access(themeFilePath);
    return {
      written: false,
      activeName: '<unchanged>',
      reason: 'new-file-already-present',
    };
  } catch {
    // expected on first run
  }

  // 2. Try to read the legacy file.
  const legacyName = await readLegacyThemeName(legacyPath);

  // 3. Resolve the name to write. We use the legacy name only if
  //    it matches a known built-in OR a user theme (validated
  //    via ThemeStore.has). Otherwise fall back to the surface
  //    default so we never write a dangling pointer.
  const store = opts.store ?? new ThemeStore({
    userHome,
    themeFilePath,
    autoLoad: false,
  });

  const fallback = defaultThemeForSurface(surface);
  let activeName = fallback;
  if (legacyName !== null) {
    if (legacyName in BUILTIN_THEMES_BY_NAME || store.has(legacyName)) {
      activeName = legacyName;
    }
  }

  // 4. Write the new file.
  await writeActiveThemeFile(
    { schemaVersion: 1, active: activeName },
    { filePath: themeFilePath, userHome },
  );

  return {
    written: true,
    activeName,
    reason: legacyName !== null ? 'legacy-file-present' : 'new-file-missing',
  };
}

/**
 * Default path of the legacy `desktop-state.json` file. We mirror
 * the desktop app's convention (a single file in the aethercode
 * config dir) without depending on the desktop module.
 */
export function legacyStateFilePath(home?: string): string {
  const h = home ?? os.homedir();
  return path.join(h, '.aethercode', 'desktop-state.json');
}

/**
 * Read the `theme` field from a legacy `desktop-state.json`. Returns
 * `null` when the file doesn't exist, and throws when it exists but
 * is unparseable JSON.
 */
async function readLegacyThemeName(legacyPath: string): Promise<string | null> {
  let text: string;
  try {
    text = await fs.readFile(legacyPath, 'utf8');
  } catch (err: unknown) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return null;
    }
    throw new LegacyStateFileError(legacyPath, (err as Error).message ?? 'I/O error');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (err: unknown) {
    throw new LegacyStateFileError(legacyPath, `invalid JSON: ${(err as Error).message}`);
  }
  if (parsed === null || typeof parsed !== 'object') {
    return null;
  }
  const theme = (parsed as { theme?: unknown }).theme;
  if (typeof theme === 'string' && theme.length > 0) {
    return theme;
  }
  return null;
}
