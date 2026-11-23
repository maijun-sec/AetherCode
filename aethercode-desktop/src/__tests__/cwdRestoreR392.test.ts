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

// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// R392 (2026-11-23) — "how do I create a session?" came back with a
// screenshot of a desktop that showed:
//
//   CURRENT SESSION
//   📂 —            ← no project
//   Active
//   0 sessions
//
// …and, crucially, NO "new session" button and no project group.
//
// WHY. The renderer's `cwd` was only ever written in three places:
// the `cwd_changed` notification (which fires on a CHANGE — never at
// start-up), `bindSessionCwd`, and `setCwd`. `initialize()` never
// restored it. So after a restart the store's `cwd` was null even
// though the daemon was perfectly healthy and bound to a project.
//
// The daemon KNOWS which project it is on: `listProjects` returns
// `{ current, projects }`. The renderer fetched that payload and threw
// the `current` half away — the type didn't even declare it.
//
// The consequence was not cosmetic:
//   • the header showed an empty cwd,
//   • "new session" had no cwd to bind to, and
//   • a session created that way landed under "未关联项目".
//
// These pins cover the contract on BOTH sides of the wire.

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R392: the wire shape carries the daemon\'s current project', () => {
  it('listProjects declares `current`', () => {
    const src = readSrc('src/lib/methods.ts');
    expect(
      src,
      "listProjects' declared return type must include `current` — the daemon returns the " +
        "cwd it is bound to and the renderer needs it to restore its own cwd",
    ).toMatch(/listProjects\(\)\s*:\s*Promise<\{[^}]*current\?/);
  });

  it('the daemon really does send it (the Java side)', () => {
    const java = readSrc('../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java');
    expect(
      java,
      "the daemon's listProjects must put the current cwd on the wire — otherwise the " +
        "renderer has nothing to restore from",
    ).toMatch(/"current"/);
  });
});

describe('R392: initialize() restores the renderer cwd', () => {
  it('the store writes cwd from the daemon\'s current project', () => {
    const src = readSrc('src/store/index.ts');
    expect(
      src,
      'initialize() must restore the renderer cwd from listProjects.current — ' +
        'without it the desktop starts with no project, no "new session" button, and ' +
        'sessions that land under "未关联项目"',
    ).toMatch(/cwd:\s*projects\.current\s*\?\?\s*null/);
  });

  it('the listProjects fallback keeps the same shape', () => {
    const src = readSrc('src/store/index.ts');
    // The `.catch()` fallback must carry `current` too, or the
    // union collapses and `projects.current` is a type error (and,
    // worse, a silent undefined at runtime).
    expect(src).toMatch(/rpc\.listProjects\(\)\.catch\(\(\)\s*=>\s*\(\{\s*projects:\s*\[\],\s*current:\s*null\s*\}\)\)/);
  });
});

describe('R392: the session library does not follow the project', () => {
  it('the Rust side pins the sessions dir instead of deriving it from the cwd', () => {
    const rust = readSrc('src-tauri/src/lib.rs');
    // The `--sessions-dir` argument must NOT be derived from the cwd.
    expect(
      rust,
      'the daemon --sessions-dir must come from the PINNED location, not from ' +
        '`<cwd>/.aethercode/sessions` — otherwise switching projects moves the library',
    ).not.toMatch(/\.arg\("--sessions-dir"\)\s*\n\s*\.arg\(cwd\.join/);
    expect(rust).toMatch(/\.arg\("--sessions-dir"\)\s*\n\s*\.arg\(sessions_dir\.to_string_lossy\(\)/);
  });

  it('the pinned location is persisted and the cwd rewrite cannot wipe it', () => {
    const rust = readSrc('src-tauri/src/lib.rs');
    expect(rust).toMatch(/sessionsDir/);
    expect(
      rust,
      'save_persisted_cwd_to must MERGE into the existing JSON, not replace it — ' +
        'a replace would delete `sessionsDir` (the R390 lesson on the Java side)',
    ).toMatch(/body\["lastCwd"\]\s*=/);
  });

  it('get_cwd reports the daemon\'s project, not the process\'s launch folder', () => {
    const rust = readSrc('src-tauri/src/lib.rs');
    const body = rust.slice(
      rust.indexOf('async fn get_cwd('),
      rust.indexOf('async fn get_cwd(') + 1200,
    );
    expect(body.length, 'get_cwd must exist').toBeGreaterThan(0);
    expect(
      body,
      'get_cwd must prefer the PERSISTED last-project over `current_dir` — the daemon ' +
        'is spawned with the persisted cwd, so the launch folder is not the project',
    ).toMatch(/persisted_cwd/);
  });
});

describe('R392: the "new session" button must be reachable', () => {
  it('the left rail hands the current project to the group list', () => {
    const src = readSrc('src/components/LeftPanel.tsx');
    // The behavioural proof lives in projectGroupR392.test.tsx (it
    // renders the component and clicks the button). This pin covers
    // the WIRE between the store and the component: if the rail stops
    // passing `currentCwd`, the current project silently loses its
    // group and the button disappears again — with every behavioural
    // test still green, because they pass `currentCwd` by hand.
    expect(
      src,
      'LeftPanel must pass the store cwd to ProjectGroupList as `currentCwd` — ' +
        'without it the current project has no group, so there is no "＋" button ' +
        'to create a session with',
    ).toMatch(/<ProjectGroupList[\s\S]{0,400}currentCwd=\{useStore\(\(s\) => s\.cwd\)\}/);
  });

  it('the group list always renders a group for the current project', () => {
    const src = readSrc('src/components/ProjectGroup.tsx');
    expect(
      src,
      'ProjectGroupList must ADD the current project to the group map even when it ' +
        'holds no sessions — the groups were derived only from existing sessions, so ' +
        'zero sessions meant zero groups and therefore no way to create one',
    ).toMatch(/groups\.set\(currentKey,\s*\[\]\)/);
  });
});
