/**
 * aethercode-themes — theme hot-reload (T-409).
 *
 * Watches `<UserHome>/.aethercode/theme.json` (and the user-themes
 * directory) for out-of-band edits, re-reads the file via
 * `ThemeStore.reloadFromDisk`, and lets the store's own `change`
 * event fire. TUI consumers therefore see identical behaviour
 * whether the user picked the theme in-app, from the CLI, or by
 * hand-editing the JSON.
 *
 * Design choices:
 *  - We watch the **directory** not the file, because most editors
 *    do `write tmp, rename` and a file-only watcher misses that.
 *  - We debounce — editors fire multiple `change` events for one
 *    save, and a `rename` + `chmod` sequence produces two.
 *  - We never re-write the file (we use `reloadFromDisk`, not
 *    `setActive`), so a watcher echo of our own write doesn't
 *    cause an infinite loop.
 *  - The watcher is best-effort: if `fs.watch` errors (sandbox,
 *    missing dir), the store still works; you just lose live
 *    out-of-process propagation.
 */

import { watch as fsWatch } from 'node:fs';
import { ThemeStore, ActiveThemeFileError } from './theme-store.js';

export interface ThemeHotReloaderOptions {
  /** The store to reload on file changes. */
  readonly store: ThemeStore;
  /** Optional debounce window in ms. Default 50. */
  readonly coalesceMs?: number;
}

export class ThemeHotReloader {
  private readonly store: ThemeStore;
  private readonly coalesceMs: number;
  private readonly watchers: import('node:fs').FSWatcher[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;
  private closed = false;
  private running = false;

  constructor(opts: ThemeHotReloaderOptions) {
    this.store = opts.store;
    this.coalesceMs = opts.coalesceMs ?? 50;
  }

  /**
   * Begin watching. Returns an unsubscribe function. Calling
   * `start()` more than once throws.
   */
  start(): () => void {
    if (this.closed) throw new Error('ThemeHotReloader: already closed');
    if (this.running) throw new Error('ThemeHotReloader: start() called twice');
    this.running = true;
    this.installWatchers();
    return () => this.stop();
  }

  /** Stop watching and release file handles. Idempotent. */
  stop(): void {
    this.closed = true;
    for (const w of this.watchers) {
      try {
        w.close();
      } catch {
        // best-effort
      }
    }
    this.watchers.length = 0;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /**
   * Force a reload right now. The hot-reload watcher calls this
   * after the debounce window; tests can call it directly to skip
   * the file-system wait. Safe to call after `stop()`.
   */
  async reload(): Promise<void> {
    if (this.closed) return;
    try {
      await this.store.reloadFromDisk();
    } catch (err: unknown) {
      if (err instanceof ActiveThemeFileError) {
        // Unparseable / unreadable — let the store's own
        // self-heal fire on the next user-driven load.
        return;
      }
      throw err;
    }
  }

  private installWatchers(): void {
    const { themeFilePath, userThemesDir } = this.store.paths;
    const dir = dirOf(themeFilePath);
    try {
      this.watchers.push(fsWatch(dir, { persistent: false }, () => this.schedule()));
    } catch {
      // best-effort
    }
    if (userThemesDir !== dir) {
      try {
        this.watchers.push(
          fsWatch(userThemesDir, { persistent: false }, () => this.schedule()),
        );
      } catch {
        // best-effort
      }
    }
  }

  private schedule(): void {
    if (this.closed) return;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.reload();
    }, this.coalesceMs);
  }
}

function dirOf(p: string): string {
  // `path.dirname` would import a module just for this; we mirror
  // it inline to keep the dep graph flat. POSIX + Windows both
  // split on the last separator.
  const m = /[\\/]/.exec(p);
  if (m === null) return '.';
  const idx = m.index;
  return idx <= 0 ? '/' : p.slice(0, idx);
}
