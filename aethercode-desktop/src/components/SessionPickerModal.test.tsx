import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * keyboard-driven session picker modal.
 *
 * Source-only assertions, same pattern as ToolsPanel.test.ts
 * (prior round). The desktop test harness doesn't have
 * @testing-library/react installed, so we verify the
 * wiring (file presence, imports, App.tsx mount, Header
 * button, store calls) so any future cleanup that removes
 * the picker is caught.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R111: Session picker modal', () => {
  it('SessionPickerModal.tsx exists', () => {
    const path = join(root, 'src', 'components', 'SessionPickerModal.tsx');
    expect(existsSync(path)).toBe(true);
  });

  it('SessionPickerModal.css exists', () => {
    const path = join(root, 'src', 'components', 'SessionPickerModal.css');
    expect(existsSync(path)).toBe(true);
  });

  it('imports the SessionPickerModal into App.tsx', () => {
    const app = read('src/App.tsx');
    expect(app).toMatch(/import\s*\{\s*SessionPickerModal\s*\}\s*from\s*['"]\.\/components\/SessionPickerModal['"]/);
  });

  it('wires the picker state (showSessionPicker / setShowSessionPicker)', () => {
    const app = read('src/App.tsx');
    expect(app).toMatch(/showSessionPicker/);
    expect(app).toMatch(/setShowSessionPicker/);
  });

  it('renders SessionPickerModal when showSessionPicker is true', () => {
    const app = read('src/App.tsx');
    // The conditional render: {showSessionPicker && (<SessionPickerModal ... />)}.
    // The component may be on the next line — match a more permissive
    // pattern.
    expect(app).toMatch(/showSessionPicker\s*&&/);
    expect(app).toMatch(/<SessionPickerModal\b/);
  });

  it('binds Ctrl/Cmd+Shift+P to open the picker', () => {
    const app = read('src/App.tsx');
    // The shortcut should be wired in the keydown handler.
    // Match the "Shift + p|P" branch.
    expect(app).toMatch(/shiftKey/);
    expect(app).toMatch(/setShowSessionPicker/);
  });

  it('Header accepts onSessionPickerClick prop', () => {
    const header = read('src/components/Header.tsx');
    expect(header).toMatch(/onSessionPickerClick/);
  });

  it('Header renders the session-picker button with Ctrl/Cmd+Shift+P hint', () => {
    const header = read('src/components/Header.tsx');
    expect(header).toMatch(/onSessionPickerClick/);
    expect(header).toMatch(/Ctrl\/Cmd\+Shift\+P/);
  });

  it('App.tsx passes onSessionPickerClick to Header', () => {
    const app = read('src/App.tsx');
    expect(app).toMatch(/onSessionPickerClick=\{[^}]+\}/);
  });

  it('SessionPickerModal uses the store for sessions / currentSessionId / switchSession / createNewSession', () => {
    const src = read('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/useStore/);
    expect(src).toMatch(/sessions/);
    expect(src).toMatch(/currentSessionId/);
    expect(src).toMatch(/switchSession/);
    expect(src).toMatch(/createNewSession/);
  });

  it('SessionPickerModal handles keyboard navigation (ArrowDown / ArrowUp / Enter / Escape)', () => {
    const src = read('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/ArrowDown/);
    expect(src).toMatch(/ArrowUp/);
    expect(src).toMatch(/'Enter'/);
    expect(src).toMatch(/'Escape'/);
  });

  it('SessionPickerModal filters by query (label + id + name)', () => {
    const src = read('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/labelFor\(s\)\.toLowerCase\(\)\.includes\(q\)/);
    expect(src).toMatch(/s\.id\.toLowerCase\(\)\.includes\(q\)/);
    expect(src).toMatch(/s\.name && s\.name\.toLowerCase\(\)\.includes\(q\)/);
  });

  it('SessionPickerModal always shows the + new session row', () => {
    const src = read('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/__new/);
    expect(src).toMatch(/new session/);
  });

  it('SessionPickerModal closes on overlay click and on Escape', () => {
    const src = read('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/onClick=\{onClose\}/);
    expect(src).toMatch(/e\.stopPropagation\(\)/);
  });

  it('SessionPickerModal renders nothing when closed (returns null)', () => {
    const src = read('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/if\s*\(\s*!open\s*\)\s*return\s*null/);
  });
});
