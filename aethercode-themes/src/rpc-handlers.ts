/**
 * aethercode-themes — JSON-RPC handler surface.
 *
 * design.md §5.4: the daemon exposes four theme-related RPCs —
 *   - `theme/list`   — return the catalog (name, origin, isDark)
 *   - `theme/get`    — return one theme by name
 *   - `theme/set`    — switch the active theme
 *   - `theme/import` — install a YAML as a user theme
 *   - `theme/export` — write a theme to YAML
 *
 * Each handler is a small `async` function that takes a
 * `ThemeRpcContext` (the runtime dependencies) plus an untyped
 * `params` object (the JSON-RPC payload), validates the params,
 * and returns a plain JSON object. The daemon's RPC dispatcher
 * wires these into the global method table; we keep them here so
 * the surface is testable in isolation and the aethercode-protocol
 * module can re-export them as the official handler bundle.
 */

import * as path from 'node:path';
import { ThemeStore, UnknownThemeError } from './theme-store.js';
import { InvalidThemeError } from './user-themes.js';
import { defaultThemeFilePath } from './user-themes.js';
import { coerceFont, InvalidFontConfigError, defaultFontConfigPath } from './font-config.js';
import { migrateFromDesktopState, legacyStateFilePath, type MigrationResult } from './migration.js';
import { defaultThemeForSurface, type Surface } from './default-theme.js';
import type { Theme } from './types.js';

/** Per-call context. The daemon injects a live `ThemeStore`. */
export interface ThemeRpcContext {
  readonly store: ThemeStore;
  /**
   * The surface the caller is running on. The TUI's daemon uses
   * `'tui'`, the desktop's daemon uses `'app'`. The handlers use
   * this only for the `theme/default` RPC; everything else
   * operates on the store.
   */
  readonly surface?: Surface;
}

export class RpcParamError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RpcParamError';
  }
}

// ---- shape helpers ----

/** Strip functions, freeze, and trim unknown keys. */
function toListedJson(t: Theme & { origin: 'builtin' | 'user' }): Record<string, unknown> {
  return Object.freeze({
    name: t.name,
    isDark: t.isDark,
    origin: t.origin,
    colors: { ...t.colors },
    font: { ...t.font },
  });
}

function themeToJson(t: Theme): Record<string, unknown> {
  return Object.freeze({
    name: t.name,
    isDark: t.isDark,
    colors: { ...t.colors },
    font: { ...t.font },
  });
}

// ---- handlers ----

/** `theme/list` — no params. */
export async function themeList(
  ctx: ThemeRpcContext,
  _params: unknown,
): Promise<{ themes: ReadonlyArray<Record<string, unknown>> }> {
  const themes = ctx.store.list().map(toListedJson);
  return { themes };
}

/** `theme/get` — `{ name: string }`. */
export async function themeGet(
  ctx: ThemeRpcContext,
  params: unknown,
): Promise<{ theme: Record<string, unknown> }> {
  const name = requireString(params, 'name');
  try {
    return { theme: themeToJson(ctx.store.get(name)) };
  } catch (err: unknown) {
    if (err instanceof UnknownThemeError) {
      throw new RpcParamError(`unknown theme: ${err.name}`);
    }
    throw err;
  }
}

/** `theme/set` — `{ name: string }`. */
export async function themeSet(
  ctx: ThemeRpcContext,
  params: unknown,
): Promise<{ active: string; theme: Record<string, unknown> }> {
  const name = requireString(params, 'name');
  try {
    const next = await ctx.store.setActive(name);
    return { active: next.name, theme: themeToJson(next) };
  } catch (err: unknown) {
    if (err instanceof UnknownThemeError) {
      throw new RpcParamError(`unknown theme: ${err.name}`);
    }
    throw err;
  }
}

/** `theme/import` — `{ path: string, overwrite?: boolean }`. */
export async function themeImport(
  ctx: ThemeRpcContext,
  params: unknown,
): Promise<{ theme: Record<string, unknown>; written: string }> {
  const p = requireString(params, 'path');
  const overwrite = optionalBoolean(params, 'overwrite') ?? false;
  const abs = path.resolve(p);
  let theme: Theme;
  try {
    theme = await ctx.store.importFromYaml(abs);
  } catch (err: unknown) {
    if (err instanceof InvalidThemeError) {
      throw new RpcParamError(`invalid theme: ${err.reason}`);
    }
    throw err;
  }
  if (overwrite) {
    // importFromYaml already replaced; we just write back to the
    // original file so the file on disk matches the in-memory
    // copy. Skipped otherwise to leave the user's file alone.
    const { writeThemeYaml } = await import('./user-themes.js');
    await writeThemeYaml(theme, abs);
  }
  return { theme: themeToJson(theme), written: abs };
}

/** `theme/export` — `{ name: string, path: string }`. */
export async function themeExport(
  ctx: ThemeRpcContext,
  params: unknown,
): Promise<{ name: string; written: string }> {
  const name = requireString(params, 'name');
  const p = requireString(params, 'path');
  const abs = path.resolve(p);
  await ctx.store.exportToYaml(name, abs);
  return { name, written: abs };
}

/** `theme/active` — returns the currently active theme. */
export async function themeActive(
  ctx: ThemeRpcContext,
  _params: unknown,
): Promise<{ theme: Record<string, unknown> }> {
  const t = await ctx.store.load();
  return { theme: themeToJson(t) };
}

/** `theme/default` — returns the surface default (no I/O). */
export async function themeDefault(
  ctx: ThemeRpcContext,
  _params: unknown,
): Promise<{ surface: Surface; name: string }> {
  const surface: Surface = ctx.surface ?? 'app';
  return { surface, name: defaultThemeForSurface(surface) };
}

/** `theme/migrate` — run the desktop-state.json migration on demand. */
export async function themeMigrate(
  ctx: ThemeRpcContext,
  params: unknown,
): Promise<{ result: MigrationResult }> {
  const surface = optionalSurface(params, 'surface') ?? ctx.surface ?? 'app';
  const result = await migrateFromDesktopState({
    store: ctx.store,
    surface,
    userHome: ctx.store.paths.userHome,
    themeFilePath: ctx.store.paths.themeFilePath,
    legacyStatePath: legacyStateFilePath(ctx.store.paths.userHome),
  });
  return { result };
}

/** `font/get` — read `<UserHome>/.aethercode/font.yaml`. */
export async function fontGet(
  ctx: ThemeRpcContext,
  _params: unknown,
): Promise<{ font: Record<string, unknown> }> {
  const { loadFontConfig } = await import('./font-config.js');
  const font = await loadFontConfig(defaultFontConfigPath(ctx.store.paths.userHome));
  void ctx; // ctx is here for symmetry / future per-user overrides
  return { font: { ...font } };
}

/** `font/set` — write `<UserHome>/.aethercode/font.yaml`. */
export async function fontSet(
  ctx: ThemeRpcContext,
  params: unknown,
): Promise<{ font: Record<string, unknown> }> {
  if (params === null || typeof params !== 'object') {
    throw new RpcParamError('params must be an object');
  }
  let font;
  try {
    font = coerceFont(params);
  } catch (err: unknown) {
    if (err instanceof InvalidFontConfigError) {
      throw new RpcParamError(`invalid font: ${err.reason}`);
    }
    throw err;
  }
  const { writeFontConfig } = await import('./font-config.js');
  await writeFontConfig(font, defaultFontConfigPath(ctx.store.paths.userHome));
  return { font: { ...font } };
}

// ---- param helpers ----

function requireString(params: unknown, key: string): string {
  if (params === null || typeof params !== 'object') {
    throw new RpcParamError('params must be an object');
  }
  const v = (params as Record<string, unknown>)[key];
  if (typeof v !== 'string' || v.length === 0) {
    throw new RpcParamError(`params.${key} must be a non-empty string`);
  }
  return v;
}

function optionalBoolean(params: unknown, key: string): boolean | undefined {
  if (params === null || typeof params !== 'object') return undefined;
  const v = (params as Record<string, unknown>)[key];
  if (v === undefined) return undefined;
  if (typeof v !== 'boolean') {
    throw new RpcParamError(`params.${key} must be a boolean`);
  }
  return v;
}

function optionalSurface(params: unknown, key: string): Surface | undefined {
  if (params === null || typeof params !== 'object') return undefined;
  const v = (params as Record<string, unknown>)[key];
  if (v === undefined) return undefined;
  if (v !== 'app' && v !== 'tui') {
    throw new RpcParamError(`params.${key} must be 'app' or 'tui'`);
  }
  return v;
}

// ---- bundle for aethercode-protocol to import ----

/**
 * The bundle the daemon imports and registers. Keys are the
 * fully-qualified RPC method names; values are `(ctx, params) => Promise<json>`.
 */
export const THEME_RPC_HANDLERS = Object.freeze({
  'theme/list': themeList,
  'theme/get': themeGet,
  'theme/set': themeSet,
  'theme/import': themeImport,
  'theme/export': themeExport,
  'theme/active': themeActive,
  'theme/default': themeDefault,
  'theme/migrate': themeMigrate,
  'font/get': fontGet,
  'font/set': fontSet,
} satisfies Record<string, RpcHandler>);

export type RpcHandler = (ctx: ThemeRpcContext, params: unknown) => Promise<unknown>;

/** Default path the daemon will hand the bundle. Kept here so the
 *  test suite and the daemon agree on the schema. */
export function themeFilePathFor(userHome: string): string {
  return defaultThemeFilePath(userHome);
}
