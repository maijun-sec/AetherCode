/**
 * light — the new app default (white background, dark text).
 * design.md §5.1.2: "light (new default for the app)".
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const light: Theme = {
  name: 'light',
  isDark: false,
  colors: {
    background: '#ffffff',
    foreground: '#1f2328',
    accent: '#0969da',
    muted: '#6e7781',
    success: '#1a7f37',
    warning: '#9a6700',
    error: '#cf222e',
    border: '#d0d7de',
    selection: '#b6e3ff',
  },
  font: DEFAULT_FONT,
};
