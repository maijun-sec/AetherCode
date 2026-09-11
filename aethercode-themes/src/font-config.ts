/**
 * aethercode-themes — user font config (T-401 extension).
 *
 * spec.md §5.1 / design.md §5.1: the user can override the default
 * font via `<UserHome>/.aethercode/font.yaml`:
 *
 *   family: "JetBrains Mono"
 *   size: 14
 *   lineHeight: 1.4
 *   ligatures: true
 *
 * Each key is optional; missing keys fall back to `DEFAULT_FONT`.
 * The file is loaded once at startup (no watcher) — a font change
 * requires a restart, which matches the existing desktop app
 * behaviour (font is a startup-only concern).
 */

import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import { parse as parseYaml } from 'yaml';
import { DEFAULT_FONT } from './defaults.js';
import type { ThemeFont } from './types.js';

/** Default location of the user font config. */
export function defaultFontConfigPath(home: string = os.homedir()): string {
  return path.join(home, '.aethercode', 'font.yaml');
}

/** Thrown when the user font config is present but unparseable. */
export class InvalidFontConfigError extends Error {
  constructor(
    public readonly filePath: string,
    public readonly reason: string,
  ) {
    super(`Invalid font config at ${filePath}: ${reason}`);
    this.name = 'InvalidFontConfigError';
  }
}

interface YamlFontInput {
  family?: unknown;
  size?: unknown;
  lineHeight?: unknown;
  ligatures?: unknown;
}

/**
 * Read the user font config. Missing file → returns `DEFAULT_FONT`.
 * Malformed fields → throws `InvalidFontConfigError` (the caller
 * decides whether to warn and fall back, or surface the error).
 */
export async function loadFontConfig(
  filePath: string = defaultFontConfigPath(),
): Promise<ThemeFont> {
  let text: string;
  try {
    text = await fs.readFile(filePath, 'utf8');
  } catch (err: unknown) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return DEFAULT_FONT;
    }
    throw err;
  }
  let parsed: unknown;
  try {
    parsed = parseYaml(text);
  } catch (err: unknown) {
    throw new InvalidFontConfigError(filePath, `invalid YAML: ${(err as Error).message}`);
  }
  return coerceFont(parsed, filePath);
}

/**
 * Validate + normalize a parsed YAML value into a `ThemeFont`,
 * filling missing fields with the corresponding `DEFAULT_FONT` key.
 */
export function coerceFont(input: unknown, filePath: string = '<font-config>'): ThemeFont {
  if (input === null || typeof input !== 'object') {
    throw new InvalidFontConfigError(filePath, 'top-level value must be a mapping');
  }
  const obj = input as YamlFontInput;
  // Build a mutable record first, then freeze at the end. Going
  // through `ThemeFont` would hit `readonly` errors under TS
  // strict.
  const out: { -readonly [K in keyof ThemeFont]: ThemeFont[K] } = {
    family: DEFAULT_FONT.family,
    size: DEFAULT_FONT.size,
    lineHeight: DEFAULT_FONT.lineHeight,
    ligatures: DEFAULT_FONT.ligatures,
  };

  if (obj.family !== undefined) {
    if (typeof obj.family !== 'string' || obj.family.length === 0) {
      throw new InvalidFontConfigError(filePath, 'family must be a non-empty string');
    }
    out.family = obj.family;
  }
  if (obj.size !== undefined) {
    if (typeof obj.size !== 'number' || !Number.isFinite(obj.size) || obj.size <= 0) {
      throw new InvalidFontConfigError(filePath, 'size must be a positive number');
    }
    out.size = obj.size;
  }
  if (obj.lineHeight !== undefined) {
    if (typeof obj.lineHeight !== 'number' || !Number.isFinite(obj.lineHeight) || obj.lineHeight <= 0) {
      throw new InvalidFontConfigError(filePath, 'lineHeight must be a positive number');
    }
    out.lineHeight = obj.lineHeight;
  }
  if (obj.ligatures !== undefined) {
    if (typeof obj.ligatures !== 'boolean') {
      throw new InvalidFontConfigError(filePath, 'ligatures must be a boolean');
    }
    out.ligatures = obj.ligatures;
  }

  return Object.freeze(out);
}

/** Write a `ThemeFont` to the user font config file. */
export async function writeFontConfig(
  font: ThemeFont,
  filePath: string = defaultFontConfigPath(),
): Promise<void> {
  const dir = path.dirname(filePath);
  await fs.mkdir(dir, { recursive: true });
  const { stringify: stringifyYaml } = await import('yaml');
  await fs.writeFile(
    filePath,
    stringifyYaml(
      {
        family: font.family,
        size: font.size,
        lineHeight: font.lineHeight,
        ligatures: font.ligatures,
      },
      { lineWidth: 0 },
    ),
    'utf8',
  );
}
