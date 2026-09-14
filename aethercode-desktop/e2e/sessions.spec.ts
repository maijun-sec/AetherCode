/**
 * Session list E2E.
 *
 * <p>Pins the structural behaviour the user asked about in
 * 2026-09-14:
 *
 *  - R266g: the inner "SESSIONS" header is gone when the
 *    list is rendered inside a project group (the project
 *    <summary> already provides the project name + count).
 *  - R267: clicking a historical session loads its
 *    transcript into the chat.
 *
 * <p>Uses the mocked Tauri surface — the test installs
 * per-spec handlers for {@code listSessions} /
 * {@code getTranscript} so the renderer sees a fixed
 * scenario.
 */
import { test, expect } from './_fixtures';

const SAMPLE_SESSIONS = [
  {
    id: 'sess-2026-09-13-001',
    title: '在当前目录下实现一个',
    preview: '在当前目录下实现一个 maven 项目',
    cwd: 'D:\\tmp\\abc_1',
    lastUsedAt: Date.now() - 12 * 3600_000,
    messageCount: 85,
  },
  {
    id: 'sess-2026-09-14-002',
    title: '新会话',
    preview: '',
    cwd: 'D:\\tmp\\abc_1',
    lastUsedAt: Date.now() - 60_000,
    messageCount: 1,
  },
];

test.describe('session list (R266g + R267)', () => {
  test('left rail shows project groups, no top-level SESSIONS header', async ({ mockedPage }) => {
    // install the per-spec handler before the initial
    // mount so the renderer's first paint already sees
    // the seeded session list.
    await mockedPage.addInitScript((sessions) => {
      const w = window as any;
      w.__E2E_MOCK__.setInvokeHandler('listSessions', () => ({
        sessions: sessions.map((s: any) => ({ ...s, sizeBytes: 1024 })),
        total: sessions.length,
      }));
    }, SAMPLE_SESSIONS);

    await mockedPage.goto('/');
    await expect(mockedPage.locator('.project-group-name', { hasText: 'abc_1' }))
      .toBeVisible({ timeout: 10_000 });

    // R266g: zero .section-header elements inside the
    // .session-list sub-tree (the inner SessionList
    // component). The project's <summary> uses
    // .project-group-summary, not .section-header, so
    // it doesn't count. Pre-R266g we'd see 1 (the inner
    // "SESSIONS" bar); post-R266g we see 0. We scope to
    // .session-list because other components in the left
    // rail (TaskSummary, etc.) also use .section-header
    // for their own labels — we want the SESSIONS bar
    // specifically to be gone.
    const sessionListHeaders = mockedPage.locator('.session-list .section-header');
    await expect(sessionListHeaders).toHaveCount(0);

    // the two sample sessions render as rows. Use the
    // title text rather than the id (the user sees
    // titles, not UUIDs).
    await expect(mockedPage.getByText('在当前目录下实现一个').first()).toBeVisible();
    // the "新会话" fallback label renders for the
    // session with empty preview.
    await expect(mockedPage.getByText('新会话').first()).toBeVisible();
  });

  test('clicking a historical session loads its transcript (R267)', async ({ mockedPage }) => {
    // install the per-spec handlers BEFORE the initial
    // mount so the renderer's first paint already sees
    // the seeded session list.
    await mockedPage.addInitScript((sessions) => {
      const w = window as any;
      w.__E2E_MOCK__.setInvokeHandler('listSessions', () => ({
        sessions: sessions.map((s: any) => ({ ...s, sizeBytes: 1024 })),
        total: sessions.length,
      }));
      // The store's hydrateTranscript() reads sessionId
      // from getTranscript's response. The mock returns
      // the historical session's transcript regardless
      // of what sessionId the store requested — that
      // mirrors the real daemon's behaviour where
      // getTranscript returns the engine's current
      // session, and the store's guard `t.sessionId
      // !== sessionId` already bails on mismatches. For
      // this test we just need the mock to return a
      // transcript with the matching sessionId.
      w.__E2E_MOCK__.setInvokeHandler('getTranscript', () => ({
        sessionId: 'sess-2026-09-13-001',
        messages: [
          {
            id: 'm-user-1',
            role: 'user',
            content: [{ type: 'text', text: '在当前目录下实现一个 maven 项目' }],
            timestamp: '2026-09-13T15:11:14.000Z',
          },
          {
            id: 'm-asst-1',
            role: 'assistant',
            content: [
              {
                type: 'text',
                text: '好的，我先在当前目录看一下现状，然后写一个 pom.xml。',
              },
            ],
            timestamp: '2026-09-13T15:11:20.000Z',
          },
        ],
      }));
    }, SAMPLE_SESSIONS);

    await mockedPage.goto('/');
    await expect(mockedPage.locator('.project-group-name', { hasText: 'abc_1' }))
      .toBeVisible({ timeout: 10_000 });

    // the historical session is the first row in the
    // project group. Click it.
    const histRow = mockedPage.getByText('在当前目录下实现一个').first();
    await histRow.click();

    // R267: the historical user message lands in the
    // chat log within a few hundred ms (sync commit +
    // async hydrate + render).
    await expect(mockedPage.getByText('在当前目录下实现一个 maven 项目'))
      .toBeVisible({ timeout: 5_000 });
  });
});
