/**
 * aethercode-themes — change event.
 *
 * design.md §5.1.4: "The TUI reads from the `ThemeStore` and re-renders
 * on a `themeChanged` event."
 *
 * We keep the event layer minimal: a tiny `EventEmitter` wrapper with
 * typed payloads. Consumers call `store.on('change', cb)` to subscribe
 * and `store.setActive(name)` to fire. The TUI's `ThemeProvider` reads
 * `store.load()` inside a `useSyncExternalStore`, so each `setActive`
 * triggers a re-render.
 *
 * The hot-reload watcher lives in `./hot-reload.ts`; this file stays
 * the single source of truth for the typed event bus.
 */

import { EventEmitter } from 'node:events';
import type { Theme } from './types.js';

export interface ThemeChangedEvent {
  /** The previous active theme (or `null` if first activation). */
  readonly previous: Theme | null;
  /** The newly active theme. */
  readonly current: Theme;
}

export type ThemeEventMap = {
  /** Fired after `setActive(name)` resolves to a valid theme. */
  change: (event: ThemeChangedEvent) => void;
};

/**
 * Strongly-typed event emitter. Created internally by `ThemeStore` and
 * exposed via `store.events` (or `store.on` / `store.off` sugar).
 *
 * Why roll our own on top of `node:events`? Two reasons:
 *  1. The TUI layer is browser-typed code; using `node:events` here
 *     keeps the rest of the module tree-shakable for an eventual
 *     web build.
 *  2. A typed surface catches typos at the call site.
 */
export class ThemeEventBus {
  private readonly emitter = new EventEmitter();

  constructor() {
    // T-405: only a few listeners expected (TUI root, picker, settings).
    // Default 10 is fine; bump if a real consumer needs more.
    this.emitter.setMaxListeners(50);
  }

  /** Subscribe to a theme event. Returns an unsubscribe function. */
  on<K extends keyof ThemeEventMap>(event: K, listener: ThemeEventMap[K]): () => void {
    // The node EventEmitter type is intentionally loose; we narrow on the
    // boundary so consumers see a typed callback.
    this.emitter.on(event, listener as (...args: unknown[]) => void);
    return () => this.emitter.off(event, listener as (...args: unknown[]) => void);
  }

  /** Subscribe for a single firing only. */
  once<K extends keyof ThemeEventMap>(event: K, listener: ThemeEventMap[K]): () => void {
    this.emitter.once(event, listener as (...args: unknown[]) => void);
    return () => this.emitter.off(event, listener as (...args: unknown[]) => void);
  }

  /** Unsubscribe a previously registered listener. */
  off<K extends keyof ThemeEventMap>(event: K, listener: ThemeEventMap[K]): void {
    this.emitter.off(event, listener as (...args: unknown[]) => void);
  }

  /** Internal: fire `change`. Synchronous — listeners run in registration order. */
  emitChange(payload: ThemeChangedEvent): void {
    this.emitter.emit('change', payload);
  }

  /** Drop all listeners. Mostly for tests. */
  removeAllListeners(): void {
    this.emitter.removeAllListeners();
  }
}
