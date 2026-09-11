/**
 * T-409: hooks + context for the aethercode-themes bridge.
 *
 * Renamed from aethercodeThemesBridge.ts → aethercodeThemesBridgeCore.ts
 * (2026-08-29) to break a build collision with
 * aethercodeThemesBridge.tsx. Both files used to compile to the
 * same .js output filename, which broke the build under
 * TypeScript's --rootDir src mode (TS5056).
 *
 * T-7-19: the two useSyncExternalStore hooks now cache their
 * getSnapshot result so the SAME reference is returned across
 * renders when nothing changed. Pre-fix, `store.list()` and
 * `store.get(name)` returned fresh objects on every call —
 * useSyncExternalStore detected the "change" and re-rendered,
 * the new render re-ran the snapshot, saw another fresh
 * object, and looped until React's "Maximum update depth
 * exceeded" kicked in. The result was a screen that scrolled
 * 4-5 copies of the header on every mount and never settled.
 */

import React, {
  createContext,
  useContext,
  useRef,
  useSyncExternalStore,
} from "react";
import { ThemeStore, type ListedTheme, type Theme } from "aethercode-themes";

export interface AethercodeThemeContextValue {
  store: ThemeStore;
  /** The currently active theme object (the one matching the on-disk theme.json). */
  active: ListedTheme | null;
  list: readonly ListedTheme[];
}

export const AethercodeThemeContext = createContext<AethercodeThemeContextValue | null>(null);

export function useAethercodeThemeContext(): AethercodeThemeContextValue {
  const ctx = useContext(AethercodeThemeContext);
  if (!ctx) {
    throw new Error(
      "useAethercodeTheme must be used inside <AethercodeThemeProvider>"
    );
  }
  return ctx;
}

function readActiveTheme(store: ThemeStore): ListedTheme | null {
  const name = store.activeNameSnapshot();
  if (!name) return null;
  try {
    return store.get(name) as ListedTheme;
  } catch {
    return null;
  }
}

/**
 * Subscribe to the store's active theme. Re-renders on every
 * "change" event. Returns the current active theme or null when
 * none has been selected.
 *
 * `useSyncExternalStore` requires the snapshot function to
 * return a stable reference for unchanged state; otherwise
 * React detects a "change" and re-renders, triggering another
 * snapshot call, triggering another "change", and so on. We
 * cache the last computed value keyed by the active theme
 * name and invalidate the cache from the subscribe callback
 * on every "change" event.
 */
export function useAethercodeThemeFromStore(store: ThemeStore): ListedTheme | null {
  const cacheRef = useRef<{ name: string | null; theme: ListedTheme | null } | null>(null);
  return useSyncExternalStore(
    (cb) => {
      // Invalidate the cache on every change so the next
      // getSnapshot call recomputes from the new active name.
      return store.on("change", () => {
        cacheRef.current = null;
        cb();
      });
    },
    () => {
      const name = store.activeNameSnapshot();
      if (cacheRef.current && cacheRef.current.name === name) {
        return cacheRef.current.theme;
      }
      const theme = readActiveTheme(store);
      cacheRef.current = { name, theme };
      return theme;
    },
    () => null
  );
}

/**
 * List every theme registered on the store.
 *
 * `store.list()` returns a NEW array on every call (it walks
 * the userThemes / builtins maps and pushes fresh `{...t,
 * origin}` objects). Without a cache, useSyncExternalStore
 * sees a "different" array on every render → infinite loop.
 * The cache is keyed by the active name (a reasonable proxy
 * for "did the theme world change?" — the same change event
 * invalidates both the active theme and the list) plus a
 * monotonic version counter incremented on every change
 * event, so a list change that DOESN'T touch the active
 * name still invalidates the cache.
 */
export function useAethercodeThemeList(store: ThemeStore): readonly ListedTheme[] {
  const cacheRef = useRef<{ version: number; list: readonly ListedTheme[] } | null>(null);
  const versionRef = useRef(0);
  return useSyncExternalStore(
    (cb) => {
      return store.on("change", () => {
        versionRef.current += 1;
        cacheRef.current = null;
        cb();
      });
    },
    () => {
      if (cacheRef.current && cacheRef.current.version === versionRef.current) {
        return cacheRef.current.list;
      }
      const list = store.list();
      cacheRef.current = { version: versionRef.current, list };
      return list;
    },
    () => store.list()
  );
}

/**
 * Map a ListedTheme to the PickerTheme shape used by the TUI's
 * theme picker. Best-effort: missing fields fall back to a known
 * default so the picker never crashes.
 */
export function toPickerTheme(theme: ListedTheme) {
  return {
    id: theme.name,
    name: theme.name,
    description: "",
    isDark: theme.isDark,
    origin: theme.origin,
    colors: theme.colors,
  };
}
