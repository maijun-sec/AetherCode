/**
 * dark — the previous default look. Borrowed from the existing
 * AetherCode TUI (prior round): warm amber primary on a near-black
 * background.
 *
 * design.md §5.1.2: "dark (existing)".
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const dark: Theme = {
  name: 'dark',
  isDark: true,
  colors: {
    background: '#0d1117',
    foreground: '#e6edf3',
    accent: '#FFB347', // AetherCode brand amber
    muted: '#7d8590',
    success: '#3fb950',
    warning: '#d29922',
    error: '#f85149',
    border: '#30363d',
    selection: '#1f6feb',
  },
  font: DEFAULT_FONT,
};
