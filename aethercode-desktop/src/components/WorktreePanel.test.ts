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
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R388c: the worktree panel — wiring and the isolation
 * VERDICT contract.
 *
 * Source-only assertions, the same pattern as
 * ToolsPanel.test.ts / SessionPickerModal.test.tsx: the
 * desktop harness has no @testing-library/react, so we
 * verify the wiring (file presence, imports, the
 * RightPanel mount, the store calls).
 *
 * The assertions that MATTER here are the negative ones:
 * the renderer must DISPLAY the daemon's `isolated`
 * flag, and must never derive it itself. A renderer
 * that inferred isolation from "no branch" would be
 * wrong in both directions — a detached-HEAD worktree
 * has no branch but IS isolated, and a stub worktree
 * has no branch and is NOT (R383's lesson).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R388c: worktree panel', () => {
  it('WorktreePanel.tsx exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'WorktreePanel.tsx'))).toBe(true);
  });

  it('WorktreePanel.css exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'WorktreePanel.css'))).toBe(true);
  });

  it('mounts the panel in the RightPanel', () => {
    const rp = read('src/components/RightPanel.tsx');
    expect(rp).toMatch(/import\s*\{\s*WorktreePanel\s*\}\s*from\s*['"]\.\/WorktreePanel['"]/);
    expect(rp).toMatch(/<WorktreePanel\s*\/>/);
    // the tab must exist too, or the panel is unreachable
    expect(rp).toMatch(/setTab\('worktrees'\)/);
  });

  it('the panel displays the daemon verdict, it does not derive it', () => {
    const p = read('src/components/WorktreePanel.tsx');
    // it READS `isolated` …
    expect(p).toMatch(/\.isolated/);
    // … and does NOT infer isolation from a missing branch
    expect(p).not.toMatch(/!w\.branch\s*===\s*null\s*\?/);
    expect(p).not.toMatch(/isolated\s*=\s*!!?\s*w\.branch/);
    expect(p).not.toMatch(/isolated\s*:\s*!!?\s*w\.branch/);
  });

  it('the panel shows the degradation reason', () => {
    const p = read('src/components/WorktreePanel.tsx');
    // a stub worktree must show WHY it is a stub
    expect(p).toMatch(/w\.reason/);
    // and the panel must warn when the daemon cannot do
    // real isolation at all
    expect(p).toMatch(/isolationUnavailable/);
  });

  it('the methods layer binds every worktree RPC', () => {
    const m = read('src/lib/methods.ts');
    for (const rpc of ['worktreeStatus', 'listWorktrees', 'addWorktree', 'removeWorktree', 'mergeWorktree']) {
      expect(m).toMatch(new RegExp(`this\\.call\\('${rpc}'`));
    }
  });

  it('the store carries the worktree state and actions', () => {
    const s = read('src/store/index.ts');
    expect(s).toMatch(/refreshWorktrees:/);
    expect(s).toMatch(/createWorktree:/);
    expect(s).toMatch(/deleteWorktree:/);
    expect(s).toMatch(/mergeWorktree:/);
    expect(s).toMatch(/selectedWorktree/);
  });

  it('the store passes the selected worktree to createEngine', () => {
    // The panel is useless if the session does not
    // actually bind to the worktree.
    const s = read('src/store/index.ts');
    expect(s).toMatch(/worktree:\s*selectedWorktree/);
  });

  it('a failed merge surfaces the daemon reason', () => {
    // The daemon refuses a dirty worktree with a
    // REASON. The store must carry it through — a
    // generic "merge failed" would be useless.
    const s = read('src/store/index.ts');
    expect(s).toMatch(/mergeWorktree[\s\S]{0,1200}r\?\.message/);
  });
});
