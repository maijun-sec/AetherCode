// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * aethercode-themes — default font.
 *
 * The user can override any theme's font via the YAML, but the
 * defaults here match spec.md §5.1: 14 px JetBrains Mono, ligatures
 * on, line height 1.4.
 */

import type { ThemeFont } from './types.js';

export const DEFAULT_FONT: ThemeFont = {
  family: 'JetBrains Mono',
  size: 14,
  lineHeight: 1.4,
  ligatures: true,
};
