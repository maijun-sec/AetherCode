/**
 * aethercode-themes — ThemeStore.
 *
 * design.md §5.1.1 / §5.1.3 / §5.1.4:
 *   - list(): all known themes (built-in + user), user wins on name conflict
 *   - get(name): single theme by name
 *   - setActive(name): writes <UserHome>/.aethercode/theme.json
 *   - load(): current active theme
 *   - importFromYaml(path): adds a YAML file as a user theme
 *   - exportToYaml(name, path): writes a theme to YAML
 *   - on('change', ...): live switch notification
 *
 * Construction:
 *   - `new ThemeStore()` reads from the real user home.
 *   - `new ThemeStore({ userHome, themeFilePath })` overrides paths
 *     — used by tests and by the desktop app which may sandbox the
 *     home dir.
 *
 * Concurrency: all mutating methods are async and serialize their
 * own I/O. Multiple `setActive` calls in flight are last-write-wins
 * (each writes the full file).
 */

import { promises as fs } from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import { BUILTIN_THEMES, BUILTIN_THEMES_BY_NAME, DEFAULT_THEME_NAME } from './builtin.js';
import { ThemeEventBus, type ThemeChangedEvent } from './events.js';
import {
  coerceTheme,
  defaultThemeFilePath,
  defaultUserThemesDir,
  loadUserThemesFromDir,
  writeThemeYaml,
  InvalidThemeError,
} from './user-themes.js';
import type { ActiveThemeFile, ListedTheme, Theme } from './types.js';

export interface ThemeStoreOptions {
  /** Override `<UserHome>`. Defaults to `os.homedir()`. */
  readonly userHome?: string;
  /** Override the path of `theme.json`. Defaults to `<userHome>/.aethercode/theme.json`. */
  readonly themeFilePath?: string;
  /** Override the user-themes dir. Defaults to `<userHome>/.aethercode/themes/`. */
  readonly userThemesDir?: string;
  /**
   * If true (default), eagerly load user themes at construction.
   * Set to false in tests where you want to control the directory
   * state after instantiation.
   */
  readonly autoLoad?: boolean;
}

/** Thrown by `setActive` when the requested name is unknown. */
export class UnknownThemeError extends Error {
  constructor(public readonly name: string) {
    super(`Unknown theme: ${name}`);
    this.name = 'UnknownThemeError';
  }
}

/** Thrown when `theme.json` is unreadable / unparseable. */
export class ActiveThemeFileError extends Error {
  constructor(
    public readonly filePath: string,
    public readonly reason: string,
  ) {
    super(`Cannot read ${filePath}: ${reason}`);
    this.name = 'ActiveThemeFileError';
  }
}

export class ThemeStore {
  private readonly eventBus = new ThemeEventBus();
  private readonly userHome: string;
  private readonly themeFilePath: string;
  private readonly userThemesDir: string;

  /** User themes keyed by name. Mutated by `importFromYaml` / `reloadUserThemes`. */
  private readonly userThemes: Map<string, Theme> = new Map();
  /** Last load error per file, so callers can warn at startup. */
  private userThemeLoadErrors: ReadonlyArray<{ file: string; error: Error }> = [];
  /** Current active theme, cached after first `load()`. */
  private active: Theme | null = null;
  /** T-409: last theme emitted via the `change` event. Used by
   *  `reloadFromDisk` to suppress no-op re-emits. Null until the
   *  first `setActive` / `reloadFromDisk` call. */
  private lastEmitted: Theme | null = null;

  constructor(opts: ThemeStoreOptions = {}) {
    this.userHome = opts.userHome ?? os.homedir();
    this.themeFilePath = opts.themeFilePath ?? defaultThemeFilePath(this.userHome);
    this.userThemesDir = opts.userThemesDir ?? defaultUserThemesDir(this.userHome);

    if (opts.autoLoad !== false) {
      // Fire-and-forget — the constructor is sync. Tests that need
      // deterministic state should pass `autoLoad: false` and call
      // `await store.reloadUserThemes()` themselves.
      void this.reloadUserThemes();
    }
  }

  // ------------------------------------------------------------------ read

  /**
   * All known themes, deduplicated by name. User themes take
   * precedence over built-ins with the same name (design.md §5.1.3).
   */
  list(): ReadonlyArray<ListedTheme> {
    const result: ListedTheme[] = [];
    const seen = new Set<string>();
    for (const t of this.userThemes.values()) {
      result.push({ ...t, origin: 'user' });
      seen.add(t.name);
    }
    for (const t of BUILTIN_THEMES) {
      if (seen.has(t.name)) continue;
      result.push({ ...t, origin: 'builtin' });
    }
    return result;
  }

  /** Look up a single theme by name. Throws if not found. */
  get(name: string): Theme {
    const userTheme = this.userThemes.get(name);
    if (userTheme) return userTheme;
    const builtin = BUILTIN_THEMES_BY_NAME[name];
    if (builtin) return builtin;
    throw new UnknownThemeError(name);
  }

  /** Whether a theme with this name exists. */
  has(name: string): boolean {
    return this.userThemes.has(name) || name in BUILTIN_THEMES_BY_NAME;
  }

  /**
   * Returns the active theme, reading `<UserHome>/.aethercode/theme.json`
   * on first call. If the file is missing or unparseable, falls back
   * to the built-in default (`light`).
   */
  async load(): Promise<Theme> {
    if (this.active) return this.active;
    const name = await this.readActiveName();
    try {
      this.active = this.get(name);
    } catch (err: unknown) {
      // `theme.json` references a missing theme (e.g. user deleted a
      // user-themes file). Fall back to the default and overwrite the
      // stale pointer so we don't keep complaining.
      this.active = this.get(DEFAULT_THEME_NAME);
      if (err instanceof UnknownThemeError) {
        await this.writeActiveFile({ schemaVersion: 1, active: DEFAULT_THEME_NAME });
      } else {
        throw err;
      }
    }
    return this.active;
  }

  // ------------------------------------------------------------------ write

  /**
   * Set the active theme. Writes `<UserHome>/.aethercode/theme.json`
   * and fires a `change` event on success.
   */
  async setActive(name: string): Promise<Theme> {
    const previous = this.active;
    const next = this.get(name); // throws UnknownThemeError
    await this.writeActiveFile({ schemaVersion: 1, active: next.name });
    this.active = next;
    this.lastEmitted = next;
    const event: ThemeChangedEvent = { previous, current: next };
    this.eventBus.emitChange(event);
    return next;
  }

  /**
   * Read a YAML file and add it as a user theme. If a user theme with
   * the same name already exists, it is replaced. Does NOT touch the
   * active-theme file.
   */
  async importFromYaml(yamlPath: string): Promise<Theme> {
    const text = await fs.readFile(yamlPath, 'utf8');
    const { parse: parseYaml } = await import('yaml');
    const parsed = parseYaml(text);
    const theme = coerceTheme(parsed, yamlPath);
    this.userThemes.set(theme.name, theme);
    return theme;
  }

  /** Write a theme to a YAML file. The target directory is created if missing. */
  async exportToYaml(name: string, targetPath: string): Promise<void> {
    const theme = this.get(name); // throws UnknownThemeError
    await writeThemeYaml(theme, targetPath);
  }

  /**
   * Re-scan the user-themes dir and rebuild the in-memory map.
   * Useful for tests and for the "Reload user themes" menu item.
   */
  async reloadUserThemes(): Promise<ReadonlyArray<{ file: string; error: Error }>> {
    const { themes, errors } = await loadUserThemesFromDir(this.userThemesDir);
    this.userThemes.clear();
    for (const t of themes) {
      this.userThemes.set(t.name, t);
    }
    this.userThemeLoadErrors = errors;
    return errors;
  }

  /** Errors encountered during the last `reloadUserThemes` call. */
  get userThemeErrors(): ReadonlyArray<{ file: string; error: Error }> {
    return this.userThemeLoadErrors;
  }

  // --------------------------------------------------------------- events

  /** Subscribe to `change` events. Returns an unsubscribe function. */
  on = this.eventBus.on.bind(this.eventBus);
  /** Subscribe to `change` for a single firing. */
  once = this.eventBus.once.bind(this.eventBus);
  /** Unsubscribe a previously registered listener. */
  off = this.eventBus.off.bind(this.eventBus);

  // -------------------------------------------------------------- internals

  /** T-409: drop the in-memory `active` cache so the next `load()`
   *  re-reads `<UserHome>/.aethercode/theme.json` from disk. The
   *  hot-reload watcher calls this whenever the file changes
   *  out-of-band. Does NOT touch the in-process `setActive` cache
   *  (those are different — the user just switched themes, we
   *  don't want to second-guess them). */
  invalidateCache(): void {
    this.active = null;
  }

  /**
   * T-422 (TUI bridge): return the active theme name synchronously,
   * or `null` if `load()` hasn't been awaited yet. Used by
   * `useSyncExternalStore` consumers in the React layer where we
   * can't await a Promise inside a snapshot function. Does not
   * trigger any I/O.
   */
  activeNameSnapshot(): string | null {
    return this.active?.name ?? null;
  }

  /**
   * T-409: re-read the active theme from disk and fire `change`
   * if the name differs from the last `change` we fired. Returns
   * the (possibly unchanged) active theme. Used by the hot-reload
   * watcher — `setActive` would also write the file back, which
   * would re-trigger the watcher.
   */
  async reloadFromDisk(): Promise<Theme> {
    this.invalidateCache();
    try {
      await this.reloadUserThemes();
    } catch {
      // best-effort — a broken user-themes dir should not stop
      // the active-theme reload.
    }
    const next = await this.load();
    const last = this.lastEmitted;
    if (last === null || last.name !== next.name) {
      const previous = last;
      this.lastEmitted = next;
      this.eventBus.emitChange({ previous, current: next });
    }
    return next;
  }

  /** T-409: read-only view of the store's on-disk locations. Used
   *  by the hot-reload watcher to know which paths to watch. */
  get paths(): {
    readonly userHome: string;
    readonly themeFilePath: string;
    readonly userThemesDir: string;
  } {
    return {
      userHome: this.userHome,
      themeFilePath: this.themeFilePath,
      userThemesDir: this.userThemesDir,
    };
  }

  private async readActiveName(): Promise<string> {
    let text: string;
    try {
      text = await fs.readFile(this.themeFilePath, 'utf8');
    } catch (err: unknown) {
      if ((err as NodeJS.ErrnoException).code === 'ENOENT') {
        return DEFAULT_THEME_NAME;
      }
      throw new ActiveThemeFileError(
        this.themeFilePath,
        (err as Error).message ?? 'I/O error',
      );
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch (err: unknown) {
      throw new ActiveThemeFileError(this.themeFilePath, `invalid JSON: ${(err as Error).message}`);
    }
    if (
      parsed === null ||
      typeof parsed !== 'object' ||
      (parsed as ActiveThemeFile).schemaVersion !== 1 ||
      typeof (parsed as ActiveThemeFile).active !== 'string'
    ) {
      throw new ActiveThemeFileError(this.themeFilePath, 'expected {schemaVersion:1, active:string}');
    }
    return (parsed as ActiveThemeFile).active;
  }

  private async writeActiveFile(payload: ActiveThemeFile): Promise<void> {
    const dir = path.dirname(this.themeFilePath);
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(this.themeFilePath, JSON.stringify(payload, null, 2) + '\n', 'utf8');
  }
}

// Re-export for convenience — callers may want to `instanceof` check.
export { InvalidThemeError };
