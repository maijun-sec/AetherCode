/**
 * aethercode-themes — user themes loader.
 *
 * design.md §5.1.3: "<UserHome>/.aethercode/themes/<name>.yaml" — loaded
 * at startup, takes precedence over bundled themes with the same name.
 *
 * YAML format matches the `Theme` shape exactly:
 *
 *   name: my-theme
 *   isDark: true
 *   colors:
 *     background: "#000000"
 *     foreground: "#ffffff"
 *     accent:     "#00ffff"
 *     muted:      "#888888"
 *     success:    "#00ff00"
 *     warning:    "#ffff00"
 *     error:      "#ff0000"
 *     border:     "#444444"
 *     selection:  "#222222"
 *   font:
 *     family: "JetBrains Mono"
 *     size: 14
 *     lineHeight: 1.4
 *     ligatures: true
 *
 * If `<name>` is omitted, the basename of the file is used.
 */

import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import { parse as parseYaml } from 'yaml';
import type { Theme } from './types.js';

/** Where user themes live. Defaults to `<UserHome>/.aethercode/themes/`. */
export function defaultUserThemesDir(home: string = os.homedir()): string {
  return path.join(home, '.aethercode', 'themes');
}

/** Where the active-theme file lives. `<UserHome>/.aethercode/theme.json`. */
export function defaultThemeFilePath(home: string = os.homedir()): string {
  return path.join(home, '.aethercode', 'theme.json');
}

/**
 * A loose YAML-friendly shape. We validate + normalize via
 * `coerceTheme` so partial / stringly-typed YAML still parses.
 */
interface YamlThemeInput {
  name?: unknown;
  isDark?: unknown;
  colors?: Partial<Record<keyof Theme['colors'], unknown>>;
  font?: Partial<Record<keyof Theme['font'], unknown>>;
}

/** All color role keys, for exhaustive validation. */
const COLOR_KEYS = [
  'background',
  'foreground',
  'accent',
  'muted',
  'success',
  'warning',
  'error',
  'border',
  'selection',
] as const;

/** Throws when a YAML file does not contain a valid `Theme` object. */
export class InvalidThemeError extends Error {
  constructor(
    public readonly filePath: string,
    public readonly reason: string,
  ) {
    super(`Invalid theme at ${filePath}: ${reason}`);
    this.name = 'InvalidThemeError';
  }
}

/**
 * Normalize + validate a parsed YAML value into a `Theme`.
 * Throws `InvalidThemeError` on missing/invalid fields.
 *
 * The rules:
 *   - `name` defaults to the filename (basename without `.yaml`).
 *   - `isDark` is required; the YAML must commit to light or dark.
 *   - All 9 color keys are required.
 *   - `font` is fully required; defaults would silently override
 *     the user's font choice.
 */
export function coerceTheme(input: unknown, fileName: string): Theme {
  if (input === null || typeof input !== 'object') {
    throw new InvalidThemeError(fileName, 'top-level value must be a mapping');
  }
  const obj = input as YamlThemeInput;

  // --- name ---
  const name =
    typeof obj.name === 'string' && obj.name.length > 0 ? obj.name : deriveNameFromFile(fileName);

  // --- isDark ---
  if (typeof obj.isDark !== 'boolean') {
    throw new InvalidThemeError(fileName, '`isDark` must be true or false');
  }

  // --- colors ---
  if (obj.colors === null || typeof obj.colors !== 'object') {
    throw new InvalidThemeError(fileName, '`colors` must be a mapping');
  }
  const colorsIn = obj.colors;
  const colors: Record<string, string> = {};
  for (const key of COLOR_KEYS) {
    const v = colorsIn[key];
    if (typeof v !== 'string' || v.length === 0) {
      throw new InvalidThemeError(fileName, `colors.${key} must be a non-empty string`);
    }
    colors[key] = v;
  }

  // --- font ---
  if (obj.font === null || typeof obj.font !== 'object') {
    throw new InvalidThemeError(fileName, '`font` must be a mapping');
  }
  const fontIn = obj.font;
  const family = fontIn.family;
  if (typeof family !== 'string' || family.length === 0) {
    throw new InvalidThemeError(fileName, 'font.family must be a non-empty string');
  }
  const size = fontIn.size;
  if (typeof size !== 'number' || !Number.isFinite(size) || size <= 0) {
    throw new InvalidThemeError(fileName, 'font.size must be a positive number');
  }
  const lineHeight = fontIn.lineHeight;
  if (typeof lineHeight !== 'number' || !Number.isFinite(lineHeight) || lineHeight <= 0) {
    throw new InvalidThemeError(fileName, 'font.lineHeight must be a positive number');
  }
  const ligatures = fontIn.ligatures;
  if (typeof ligatures !== 'boolean') {
    throw new InvalidThemeError(fileName, 'font.ligatures must be a boolean');
  }

  return Object.freeze({
    name,
    isDark: obj.isDark,
    colors: Object.freeze({
      background: colors.background!,
      foreground: colors.foreground!,
      accent: colors.accent!,
      muted: colors.muted!,
      success: colors.success!,
      warning: colors.warning!,
      error: colors.error!,
      border: colors.border!,
      selection: colors.selection!,
    }),
    font: Object.freeze({ family, size, lineHeight, ligatures }),
  });
}

/** Strip the `.yaml` / `.yml` extension and return the basename. */
function deriveNameFromFile(filePath: string): string {
  const base = path.basename(filePath);
  return base.replace(/\.(yaml|yml)$/i, '');
}

/**
 * Read all `*.yaml` / `*.yml` files from a directory and return
 * parsed `Theme` objects. Malformed files are skipped and reported
 * via the second tuple element so the caller can warn the user
 * without aborting startup.
 */
export async function loadUserThemesFromDir(
  dir: string = defaultUserThemesDir(),
): Promise<{ themes: ReadonlyArray<Theme>; errors: ReadonlyArray<{ file: string; error: Error }> }> {
  let entries: string[];
  try {
    entries = await fs.readdir(dir);
  } catch (err: unknown) {
    // ENOENT = no user themes dir yet; treat as empty. Other errors
    // (EACCES, EBUSY) are surfaced so the caller can warn.
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
      return { themes: [], errors: [] };
    }
    throw err;
  }

  const yamlFiles = entries.filter((f) => /\.(yaml|yml)$/i.test(f)).sort();
  const themes: Theme[] = [];
  const errors: { file: string; error: Error }[] = [];
  for (const file of yamlFiles) {
    const full = path.join(dir, file);
    try {
      const text = await fs.readFile(full, 'utf8');
      const parsed = parseYaml(text);
      themes.push(coerceTheme(parsed, full));
    } catch (err: unknown) {
      errors.push({ file: full, error: err as Error });
    }
  }
  return { themes, errors };
}

/**
 * Write a `Theme` to a YAML file at `filePath`. The directory is
 * created if missing. Used by `ThemeStore.exportToYaml` and by the
 * `aethercode theme export` CLI (T-403 follow-up).
 */
export async function writeThemeYaml(theme: Theme, filePath: string): Promise<void> {
  const dir = path.dirname(filePath);
  await fs.mkdir(dir, { recursive: true });
  const doc = {
    name: theme.name,
    isDark: theme.isDark,
    colors: { ...theme.colors },
    font: { ...theme.font },
  };
  const { stringify: stringifyYaml } = await import('yaml');
  await fs.writeFile(filePath, stringifyYaml(doc, { lineWidth: 0 }), 'utf8');
}
