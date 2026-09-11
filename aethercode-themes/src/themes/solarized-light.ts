/**
 * solarized-light — Ethan Schoonover's classic warm-light palette.
 * design.md §5.1.2: ships with the app.
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const solarizedLight: Theme = {
  name: 'solarized-light',
  isDark: false,
  colors: {
    background: '#fdf6e3', // base3
    foreground: '#586e75', // base01
    accent: '#268bd2', // blue
    muted: '#93a1a1', // base1
    success: '#859900', // green
    warning: '#b58900', // yellow
    error: '#dc322f', // red
    border: '#eee8d5', // base2
    selection: '#eee8d5',
  },
  font: DEFAULT_FONT,
};
