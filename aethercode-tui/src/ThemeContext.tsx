/**
 * theme context.
 *
 * ## What this file is FOR
 *
 * R386 found that the TUI had THREE theme systems that never met:
 *
 *   1. `src/theme.ts` exported a STATIC token table `t`. Thirty
 *      components import it and render with it. It never changed.
 *   2. `src/themes.ts` had five palettes and a `pickPalette()`.
 *   3. THIS file published a palette through a React context — and
 *      `useTheme()` had **zero call sites**, so the computed palette
 *      was discarded on the floor.
 *
 * The symptom the user saw: `/theme solarized` printed "theme →
 * solarized" and **the screen did not move**.
 *
 * ## How the palette reaches the screen now
 *
 * `t`'s palette roles are GETTERS over the active palette
 * (`src/themes.ts`), so a palette is all it takes for every component
 * that reads `t` to repaint — no component was changed.
 *
 * The palette is published DURING render, not in an effect, and that
 * ordering is the whole point:
 *
 *   - React calls a component's body, THEN renders its children. So
 *     publishing during the App's body means every element the App
 *     builds — and every component below it — reads the palette of
 *     THIS frame.
 *   - An effect runs after the commit, so the new palette would land
 *     one repaint late — the user would see the old theme flash
 *     before the new one, which is the same "it said it worked and
 *     nothing happened" defect wearing a one-frame delay.
 *
 * `useActiveTheme` is the ONE publish site; `ThemeProvider` supplies
 * the context value that `useTheme()` reads.
 */

import React, { createContext, useContext } from "react";
import { PALETTES, pickPalette, publishTheme, type Palette, type ThemeName } from "./themes.js";

const ThemeContext = createContext<Palette>(PALETTES.default);

/**
 * Publish the palette for the frame being built, and return it.
 *
 * The App calls this at the TOP of its body — before it builds the
 * JSX. That position matters: an expression like `color={t.warn}` in
 * the App's own tree READS the token table while the tree is being
 * built, which is BEFORE any provider inside it renders. Publishing
 * only from the provider would leave those elements on the previous
 * palette until the next repaint.
 *
 * The publish is idempotent (the same name publishes the same
 * palette), so React rendering the App twice — StrictMode, a
 * concurrent retry — changes nothing.
 */
export function useActiveTheme(themeName: ThemeName): Palette {
  return publishTheme(themeName);
}

/**
 * Supplies the palette through React context.
 *
 * The context is the React-idiomatic accessor for a component that
 * needs the WHOLE palette (including the surface colour, which is
 * not a token). Everything else should read `t`.
 */
export const ThemeProvider: React.FC<{ themeName: ThemeName; children: React.ReactNode }> = ({
  themeName,
  children,
}) => {
  const palette = pickPalette(themeName);
  return <ThemeContext.Provider value={palette}>{children}</ThemeContext.Provider>;
};

/**
 * The palette the tree is rendering in.
 *
 * Prefer `t` (from `./theme.js`) for the chrome roles: it is a plain
 * object, so a component can read it outside a render. Reach for
 * this when the WHOLE palette is needed — the surface colour, or a
 * role the token table does not carry.
 */
export function useTheme(): Palette {
  return useContext(ThemeContext);
}
