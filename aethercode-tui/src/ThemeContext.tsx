/**
 * theme context.
 *
 * Lets components access the active palette without prop-drilling
 * through every level. The App component sets the provider value
 * from state.themeName; descendants call useTheme() to get the
 * current palette.
 *
 * We keep `t` from theme.ts as the *default* palette so existing
 * code (that doesn't opt in to theming) keeps working. New code
 * should call useTheme() and use the returned palette.
 */

import React, { createContext, useContext } from "react";
import { t as DEFAULT } from "./theme.js";
import { pickPalette, type Palette, type ThemeName } from "./themes.js";

const ThemeContext = createContext<Palette>(DEFAULT);

export const ThemeProvider: React.FC<{ themeName: ThemeName; children: React.ReactNode }> = ({
  themeName, children,
}) => {
  const palette = pickPalette(themeName);
  return <ThemeContext.Provider value={palette}>{children}</ThemeContext.Provider>;
};

export function useTheme(): Palette {
  return useContext(ThemeContext);
}
