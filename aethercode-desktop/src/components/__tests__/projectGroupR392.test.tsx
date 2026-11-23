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
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import { ProjectGroupList } from '../ProjectGroup';
import type { SessionInfo } from '../../lib/methods';

/**
 * R392 (2026-11-23) — "现在我怎么创建个 session，或者一个任务？"
 *
 * The desktop's left rail had NO way to create a session:
 *
 *   CURRENT SESSION
 *   📂 —
 *   Active
 *   0 sessions
 *
 * The "create a session" affordance ("＋") lived ONLY inside a project
 * group's header, and the groups were derived purely from the sessions
 * that already existed:
 *
 *     for (const s of sessions) { groups.set(s.cwd ?? null, …) }
 *
 * So the very first session of a fresh install had no button to click:
 *
 *     no session → no group → no "＋" → still no session
 *
 * A chicken-and-egg that only breaks once the user happens to have
 * one session somewhere. The user had none (they had moved theirs to
 * the trash), so the button was simply gone.
 *
 * THE FIX: the group list always renders a group for the CURRENT
 * project (`currentCwd`) — the project the user is standing in — even
 * when that project holds no sessions yet. The current project also
 * sorts FIRST, so the button is where the eye already is.
 *
 * These are BEHAVIOUR tests, not source pins: they render the real
 * component and click the real button.
 */

function sess(cwd: string | null, id: string, title = 't'): SessionInfo {
  return {
    id,
    title,
    cwd,
    createdAt: 1,
    lastUsedAt: 1,
  } as unknown as SessionInfo;
}

/** The "＋" button of the group whose header shows `name`. */
function newSessionButton(container: HTMLElement, name: string): HTMLButtonElement | null {
  const groups = Array.from(container.querySelectorAll('.project-group'));
  const group = groups.find((g) => {
    const label = g.querySelector('.project-group-name')?.textContent ?? '';
    return label === name;
  });
  if (!group) return null;
  return group.querySelector('button.project-group-new');
}

function groupNames(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('.project-group-name')).map(
    (n) => n.textContent ?? '',
  );
}

describe('R392 ProjectGroupList: the current project always has a group', () => {
  afterEach(() => cleanup());

  it('renders the current project group even when there are NO sessions at all', () => {
    // This is the user's exact screen: 0 sessions.
    const { container } = render(
      <ProjectGroupList
        sessions={[]}
        currentSessionId={null}
        currentCwd="D:/tmp/abc_1"
        onNewSession={() => {}}
      />,
    );
    expect(
      groupNames(container),
      'the current project must be listed even with zero sessions — otherwise the ' +
        '"＋" button (the only in-panel way to create a session) does not exist',
    ).toContain('abc_1');
    expect(newSessionButton(container, 'abc_1')).not.toBeNull();
  });

  it('the "＋" button of the current project creates a session IN that project', () => {
    const onNewSession = vi.fn();
    const { container } = render(
      <ProjectGroupList
        sessions={[]}
        currentSessionId={null}
        currentCwd="D:/tmp/abc_1"
        onNewSession={onNewSession}
      />,
    );
    const btn = newSessionButton(container, 'abc_1');
    expect(btn).not.toBeNull();
    fireEvent.click(btn!);
    // The cwd must be the project's — a session created without a cwd
    // lands under "未关联项目" (the R392 bug).
    expect(onNewSession).toHaveBeenCalledTimes(1);
    expect(onNewSession).toHaveBeenCalledWith('D:/tmp/abc_1');
  });

  it('the current project group comes FIRST, ahead of other projects', () => {
    const { container } = render(
      <ProjectGroupList
        sessions={[sess('D:/tmp/other', 's1'), sess('D:/tmp/third', 's2')]}
        currentSessionId={null}
        currentCwd="D:/tmp/abc_1"
        onNewSession={() => {}}
      />,
    );
    const names = groupNames(container);
    expect(names[0], 'the project the user is standing in must be on top').toBe('abc_1');
    // the other projects are still there (regression: we ADD a group,
    // we do not replace the list).
    expect(names).toContain('other');
    expect(names).toContain('third');
  });

  it('an empty current project does not duplicate an existing group', () => {
    const { container } = render(
      <ProjectGroupList
        sessions={[sess('D:/tmp/abc_1', 's1')]}
        currentSessionId={null}
        currentCwd="D:/tmp/abc_1"
        onNewSession={() => {}}
      />,
    );
    const names = groupNames(container);
    expect(names.filter((n) => n === 'abc_1').length).toBe(1);
  });

  it('without a current project, the groups are still derived from the sessions', () => {
    // The "no project yet" case: the daemon cannot start without a cwd,
    // so the app is in the awaiting-cwd state (folder picker) — the
    // rail must not invent a group.
    const { container } = render(
      <ProjectGroupList
        sessions={[sess('D:/tmp/abc_1', 's1')]}
        currentSessionId={null}
        currentCwd={null}
        onNewSession={() => {}}
      />,
    );
    expect(groupNames(container)).toEqual(['abc_1']);
  });
});
