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

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R390: the desktop reads the session's TITLE — the field the daemon
 * actually sends — and re-reads the session list when a run ends.
 *
 * ★ THE DEFECT ★
 *
 * The daemon has always sent `title` (and the TUI has always read
 * `title`). The desktop's `SessionInfo` declared `name?: string` — a
 * field the daemon NEVER sent. Every reader of `s.name` therefore read
 * `undefined`, always:
 *
 *   • the LeftPanel's session rows fell through to the preview;
 *   • the "Current Session" card's name pill NEVER rendered;
 *   • the command palette and the welcome screen's recent-session
 *     chips showed `Session <id-tail>` for every session.
 *
 * And nothing re-read the list at the end of a turn, so a long task
 * finished with the sidebar still showing its pre-task state.
 *
 * These pins fail the build if either half comes back.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

/**
 * The body of one `case '<name>':` arm of the store's stream-event
 * switch, up to the next top-level `case '`.
 *
 * ★ The BRACED form is what identifies the switch arm ★ — the file
 * also has a `case 'run_end':` in the event-LABEL helper (a one-liner
 * that returns "turn end: …"), and a naive search finds THAT one
 * first. The switch arm opens a block; the label helper does not.
 */
function caseArm(src: string, name: string): string {
  const braced = `case '${name}': {`;
  const start = src.indexOf(braced);
  if (start === -1) return '';
  const rest = src.slice(start + 1);
  const next = rest.search(/\n\s{6}case '/);
  return next === -1 ? rest : rest.slice(0, next);
}

describe('R390: the session title field', () => {
  it('SessionInfo declares `title`, not `name`', () => {
    const src = readSrc('src/lib/methods.ts');
    // the SessionInfo interface — the block between "interface
    // SessionInfo {" and the next "export interface"
    const from = src.indexOf('export interface SessionInfo {');
    const to = src.indexOf('export interface', from + 10);
    const block = src.slice(from, to === -1 ? undefined : to);
    expect(block, 'SessionInfo must declare the title field').toMatch(/\btitle\?\s*:\s*string/);
    expect(block, 'SessionInfo must NOT declare the never-sent `name` field')
      .not.toMatch(/^\s*name\?\s*:/m);
  });

  it('SessionList.sessionLabel reads `s.title`', () => {
    const src = readSrc('src/components/SessionList.tsx');
    expect(src).toMatch(/if\s*\(\s*s\.title\s*&&/);
    expect(src).not.toMatch(/if\s*\(\s*s\.name\s*&&/);
  });

  it('TaskSummary reads `currentSession?.title`', () => {
    const src = readSrc('src/components/TaskSummary.tsx');
    expect(src).toMatch(/currentSession\?\.title/);
    expect(src).not.toMatch(/currentSession\?\.name/);
  });

  it('the command palette labels a session by its title', () => {
    const src = readSrc('src/components/CommandPalette.tsx');
    expect(src).toMatch(/s\.title\?\.trim\(\)/);
  });

  it('the session picker labels + searches by title', () => {
    const src = readSrc('src/components/SessionPickerModal.tsx');
    expect(src).toMatch(/s\.title && s\.title\.trim\(\)/);
    expect(src).toMatch(/s\.title && s\.title\.toLowerCase\(\)\.includes\(q\)/);
  });

  it('the welcome screen labels a recent session by its title', () => {
    const src = readSrc('src/components/Welcome.tsx');
    expect(src).toMatch(/s\.title\?\.trim\(\)/);
    expect(src).not.toMatch(/s\.name\?\.trim\(\)/);
  });
});

describe('R390: the session list is re-read when a run ends', () => {
  it('the run_end arm refreshes the session list', () => {
    const src = readSrc('src/store/index.ts');
    const arm = caseArm(src, 'run_end');
    expect(arm, 'the run_end arm must exist').not.toBe('');
    expect(arm, 'the run_end arm must refresh the session list')
      .toMatch(/refreshSessions\s*\(\s*\)/);
  });

  it('sendMessage goes through refreshSessions, not a wholesale replace', () => {
    const src = readSrc('src/store/index.ts');
    // The old tail did `const { sessions } = await rpc.listSessions();
    // set({ sessions: list })` — a full replace that dropped the
    // sessions belonging to another project's daemon.
    expect(src).toMatch(/await\s+get\(\)\.refreshSessions\(\)/);
    expect(src).not.toMatch(/const\s*\{\s*sessions\s*\}\s*=\s*await\s+rpc\.listSessions\(\)/);
  });
});
