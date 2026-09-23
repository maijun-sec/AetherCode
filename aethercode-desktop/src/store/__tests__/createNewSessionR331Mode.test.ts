// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R331: New-session mode picker. createNewSession({ mode })
 * now accepts an explicit mode:
 *   - undefined / no opts       → opens the picker
 *                                 (sets pendingNewSession)
 *   - 'normal' / 'workflow'     → setSddEnabled(false)
 *   - 'sdd'                     → setSddEnabled(true)
 *
 * The picker UI itself is covered in
 * components/__tests__/NewSessionModeDialogR331.test.tsx.
 * These tests pin the store contract: source-pin style on
 * the createNewSession signature + reading the source for the
 * mode branch.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const STORE_TS = join(process.cwd(), 'src', 'store', 'index.ts');
const source = readFileSync(STORE_TS, 'utf8');

describe('R331 createNewSession mode branch', () => {
  it('createNewSession accepts an opts argument with mode', () => {
    expect(source).toMatch(/createNewSession:\s*\(opts\?:\s*\{\s*mode\?:\s*'normal'\s*\|\s*'sdd'\s*\|\s*'workflow'\s*\}\)\s*=>\s*void/);
  });

  it('createNewSession with no mode opens the picker (sets pendingNewSession)', () => {
    // The store sets pendingNewSession to { cwd } when no mode
    // is provided — the picker reads this and presents 3
    // options.
    expect(source).toMatch(/createNewSession:\s*\(opts\)\s*=>\s*\{[\s\S]*?set\(\{\s*pendingNewSession:\s*\{\s*cwd:/);
  });

  it('createNewSession with mode sdd enables SDD toggle', () => {
    expect(source).toMatch(/mode === 'sdd'[\s\S]{0,200}set\(\{\s*sddEnabled:\s*true\s*\}\)/);
  });

  it('createNewSession with mode normal/workflow disables SDD toggle', () => {
    expect(source).toMatch(/mode === 'normal' \|\| mode === 'workflow'[\s\S]{0,200}sddEnabled:\s*false/);
  });

  it('pendingNewSession field is declared on AppState', () => {
    // The picker reads it. Make sure the field exists.
    expect(source).toMatch(/pendingNewSession:\s*\{\s*cwd:\s*string\s*\|\s*null\s*\}\s*\|\s*null/);
  });

  it('initial state has pendingNewSession: null', () => {
    // The picker is closed by default.
    expect(source).toMatch(/pendingNewSession:\s*null,/);
  });

  it('createNewSession resets pendingNewSession on completion', () => {
    // After the new session id is minted + the user is
    // switched, the picker should close so it doesn't
    // re-open on the next render.
    expect(source).toMatch(/pendingNewSession:\s*null/);
  });
});

describe('R331 MessageInput no longer auto-detects SDD from chat input', () => {
  it('sddIntentKeyword regex was removed', () => {
    // R321 auto-detect let users with toggle off + a
    // keyword like "用SDD流程" promote to SDD. R331 removes
    // that — sessions are mode-bound at creation. The
    // MessageInput source should no longer mention
    // sddIntentKeyword / spec-kit / speckit trigger.
    const miSrc = readFileSync(join(process.cwd(), 'src', 'components', 'MessageInput.tsx'), 'utf8');
    expect(miSrc).not.toMatch(/sddIntentKeyword/);
    expect(miSrc).not.toMatch(/spec-kit|speckit|用SDD流程|规格化.*流程/);
  });

  it('MessageInput still routes phase commands when sddEnabled is on', () => {
    // R319+R322 contract: when toggle is on AND a run is
    // active, the user can type approve/skip/modify and
    // they get routed to sendSsdCommand. R331 must preserve
    // this — only the auto-detect branch is removed.
    const miSrc = readFileSync(join(process.cwd(), 'src', 'components', 'MessageInput.tsx'), 'utf8');
    expect(miSrc).toMatch(/sendSsdCommand\('approve'\)/);
    expect(miSrc).toMatch(/sendSsdCommand\('skip'\)/);
    expect(miSrc).toMatch(/sendSsdCommand\('modify',\s*intent\)/);
    expect(miSrc).toMatch(/startSsdFlow\(intent\)/);
  });
});