/**
 * aethercode-themes — active-theme file persistence (T-408).
 *
 * design.md §5.1.1 / §6.2: writes the active theme to
 * `<UserHome>/.aethercode/theme.json`. The on-disk shape is:
 *
 *   { "schemaVersion": 1, "active": "dark" }
 *
 * We use a temp-file + atomic-rename so a partial write can never
 * corrupt an existing valid file. This module is a low-level
 * primitive; `ThemeStore` composes it.
 */

import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import { defaultThemeFilePath } from './user-themes.js';
import { ActiveThemeFileError } from './theme-store.js';
import type { ActiveThemeFile } from './types.js';

export interface PersistenceOptions {
  /** Override the path. Defaults to `<UserHome>/.aethercode/theme.json`. */
  readonly filePath?: string;
  /** Override `<UserHome>`. */
  readonly userHome?: string;
}

/** Read the active-theme file. Returns `null` when the file is missing. */
export async function readActiveThemeFile(
  opts: PersistenceOptions = {},
): Promise<ActiveThemeFile | null> {
  const filePath = opts.filePath ?? defaultThemeFilePath(opts.userHome);
  let text: string;
  try {
    text = await fs.readFile(filePath, 'utf8');
  } catch (err: unknown) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return null;
    }
    throw new ActiveThemeFileError(filePath, (err as Error).message ?? 'I/O error');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (err: unknown) {
    throw new ActiveThemeFileError(filePath, `invalid JSON: ${(err as Error).message}`);
  }
  validateActiveFile(parsed, filePath);
  return parsed as ActiveThemeFile;
}

/**
 * Atomically write the active-theme file. Writes to `<file>.tmp`
 * first, then `rename`s over the target. On POSIX this gives us
 * the same atomicity guarantee as a single `write` syscall.
 */
export async function writeActiveThemeFile(
  payload: ActiveThemeFile,
  opts: PersistenceOptions = {},
): Promise<void> {
  const filePath = opts.filePath ?? defaultThemeFilePath(opts.userHome);
  const dir = path.dirname(filePath);
  await fs.mkdir(dir, { recursive: true });
  const tmp = `${filePath}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(payload, null, 2) + '\n', 'utf8');
  await fs.rename(tmp, filePath);
  // 0600 on POSIX so other local users can't read which theme the
  // user has picked. The Electron desktop app also enforces this
  // for sibling files (sessions.db, grants.json, …).
  try {
    await fs.chmod(filePath, 0o600);
  } catch {
    // chmod is best-effort — on Windows or some sandboxes it errors.
  }
}

/** Strict structural check on the parsed JSON. Throws on shape mismatch. */
function validateActiveFile(parsed: unknown, filePath: string): void {
  if (
    parsed === null ||
    typeof parsed !== 'object' ||
    (parsed as ActiveThemeFile).schemaVersion !== 1 ||
    typeof (parsed as ActiveThemeFile).active !== 'string' ||
    (parsed as ActiveThemeFile).active.length === 0
  ) {
    throw new ActiveThemeFileError(
      filePath,
      'expected {schemaVersion: 1, active: <non-empty string>}',
    );
  }
}
