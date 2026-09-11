/**
 * high-contrast — accessibility theme. Maximum contrast between
 * foreground and background, bright saturated accents, thick
 * borders. Follows WCAG AAA where possible (≥7:1 fg/bg).
 * design.md §5.1.2.
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const highContrast: Theme = {
  name: 'high-contrast',
  isDark: true,
  colors: {
    background: '#000000',
    foreground: '#ffffff',
    accent: '#00ffff', // cyan
    muted: '#c0c0c0', // silver
    success: '#00ff00', // lime
    warning: '#ffff00', // yellow
    error: '#ff4040', // bright red
    border: '#ffffff',
    selection: '#0000ff', // pure blue
  },
  font: DEFAULT_FONT,
};
