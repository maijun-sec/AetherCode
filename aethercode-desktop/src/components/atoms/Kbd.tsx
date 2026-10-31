// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/**
 * `<Kbd>` — keyboard-shortcut chip atom.
 *
 * R350 (UX P1-2): one shared atom for all `<kbd>` markup across the
 * desktop. The previous design had six near-identical local styles
 * (Welcome, MessageInput, CommandPalette, RpcCommandPalette,
 * LoopGuardBanner, SessionPickerModal). They drifted on font size,
 * padding, and background tokens — visible as different "chips" in
 * different surfaces.
 *
 * The atom:
 *  - reads --bg-input / --border / --text from the local theme
 *  - exposes `tone` ("default" | "accent" | "danger") for visual weight
 *  - exposes `size` ("sm" | "md") so dense footers can stay dense
 *  - renders a `<kbd>` so screen-readers and copy-paste still work
 *
 * Don't reach for raw `<kbd>` in new surfaces — use this atom.
 */
import { type ReactNode } from 'react';
import './Kbd.css';

export type KbdTone = 'default' | 'accent' | 'danger';
export type KbdSize = 'sm' | 'md';

export interface KbdProps {
  children: ReactNode;
  /** Visual tone. default = neutral chip, accent = highlight (e.g. primary CTA hint), danger = warning. */
  tone?: KbdTone;
  /** sm = 10px (default), md = 11px. Footers / dense labels pick sm; standalone hints pick md. */
  size?: KbdSize;
  /** Optional platform-specific display. e.g. on macOS we render ⌘ instead of Cmd. */
  platform?: 'auto' | 'mac' | 'win';
  /** Optional aria-label override for screen readers — defaults to children text. */
  'aria-label'?: string;
  className?: string;
}

/**
 * Map a logical key name to its platform-native glyph.
 * Kept small on purpose — only the keys Welcome / input-hint
 * actually need this. New keys can extend the table.
 */
const KEY_GLYPHS: Record<string, { mac: string; win: string }> = {
  Cmd: { mac: '⌘', win: 'Ctrl' },
  Ctrl: { mac: '⌃', win: 'Ctrl' },
  Alt: { mac: '⌥', win: 'Alt' },
  Shift: { mac: '⇧', win: 'Shift' },
  Enter: { mac: '↵', win: 'Enter' },
  Esc: { mac: 'esc', win: 'Esc' },
  Tab: { mac: '⇥', win: 'Tab' },
  Up: { mac: '↑', win: '↑' },
  Down: { mac: '↓', win: '↓' },
  Left: { mac: '←', win: '←' },
  Right: { mac: '→', win: '→' },
};

/**
 * Resolve a logical key to its visible glyph for the active platform.
 * `auto` reads navigator.platform; mac → glyph, others → win text.
 */
function resolveKey(key: string, platform: 'auto' | 'mac' | 'win'): string {
  const entry = KEY_GLYPHS[key];
  if (!entry) return key;
  if (platform === 'mac') return entry.mac;
  if (platform === 'win') return entry.win;
  // auto
  if (typeof navigator !== 'undefined' && /Mac|iPhone|iPad/i.test(navigator.platform)) {
    return entry.mac;
  }
  return entry.win;
}

/**
 * If a child is a plain string that matches a logical key name,
 * substitute the platform-specific glyph. Children that are not
 * strings (numbers, nested elements) pass through unchanged.
 */
function normalizeChildren(children: ReactNode, platform: 'auto' | 'mac' | 'win'): ReactNode {
  if (typeof children === 'string') {
    return resolveKey(children, platform);
  }
  return children;
}

export function Kbd({
  children,
  tone = 'default',
  size = 'sm',
  platform = 'auto',
  className,
  ...rest
}: KbdProps) {
  const resolved = normalizeChildren(children, platform);
  const cls = [
    'kbd-atom',
    `kbd-atom-${tone}`,
    `kbd-atom-${size}`,
    className,
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <kbd className={cls} {...rest}>
      {resolved}
    </kbd>
  );
}

/**
 * `<KbdPlus>` — renders "Kbd + Kbd" with a literal plus sign.
 * Saves a few characters at every call site that needs a chord.
 *
 * Accepts an array of any length so 2-key (`Ctrl+K`) and
 * 3-key (`Ctrl+Shift+H`) chords share the same helper.
 * Returns `<Kbd>` for length 1 (no plus sign).
 */
export function KbdPlus({ children }: { children: ReactNode[] }) {
  const arr = Array.isArray(children) ? children : [children];
  if (arr.length === 0) return null;
  if (arr.length === 1) return <Kbd>{arr[0]}</Kbd>;
  return (
    <span className="kbd-chord">
      {arr.map((child, i) => (
        <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
          {i > 0 && (
            <span className="kbd-plus" aria-hidden="true">+</span>
          )}
          <Kbd>{child}</Kbd>
        </span>
      ))}
    </span>
  );
}