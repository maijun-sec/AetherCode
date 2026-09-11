/**
 * T-409 integration: provider component for the aethercode-themes
 * bridge. Renamed from aethercodeThemesBridge.tsx →
 * aethercodeThemesBridgeUI.tsx (2026-08-29) to break the
 * build collision with the .ts file of the same name.
 */

import React from "react";
import { ThemeStore, type ListedTheme } from "aethercode-themes";
import {
  AethercodeThemeContext,
  type AethercodeThemeContextValue,
} from "./aethercodeThemesBridgeCore.js";

export interface AethercodeThemeProviderProps {
  store: ThemeStore;
  children: React.ReactNode;
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

export const AethercodeThemeProvider: React.FC<AethercodeThemeProviderProps> = ({
  store,
  children,
}) => {
  const value: AethercodeThemeContextValue = {
    store,
    active: readActiveTheme(store),
    list: store.list(),
  };
  return (
    <AethercodeThemeContext.Provider value={value}>
      {children}
    </AethercodeThemeContext.Provider>
  );
};

export default AethercodeThemeProvider;
