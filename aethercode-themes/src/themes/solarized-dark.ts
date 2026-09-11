/**
 * solarized-dark — Ethan Schoonover's classic dark palette.
 * design.md §5.1.2: ships with the app.
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const solarizedDark: Theme = {
  name: 'solarized-dark',
  isDark: true,
  colors: {
    background: '#002b36', // base03
    foreground: '#93a1a1', // base1
    accent: '#2aa198', // cyan
    muted: '#586e75', // base01
    success: '#859900', // green
    warning: '#b58900', // yellow
    error: '#dc322f', // red
    border: '#073642', // base02
    selection: '#073642',
  },
  font: DEFAULT_FONT,
};
