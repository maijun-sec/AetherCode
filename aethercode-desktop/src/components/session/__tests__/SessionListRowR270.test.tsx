// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SessionListRow } from '../SessionListRow';
import type { SessionListItem } from '../../../rpc/types';

/**
 * R270 (2026-09-15) — SessionListRow behaviour test for the
 * new lastAgentEvent line + the conditional preview line.
 * The daemon-side companion is in
 * AetherCodeMethodsR270Test. Together they ensure:
 *   - When the daemon populates `lastAgentEvent`, the row
 *     renders a `→ file_write HeapSortTest.java` line.
 *   - When the row has both an explicit title AND a
 *     different preview, BOTH the lastAgentEvent line and
 *     a "preview" line are shown (preview is the original
 *     first user prompt).
 *   - When title === preview (no rename), the verbatim
 *     preview line is hidden (redundant with title).
 *   - Empty / whitespace lastAgentEvent is hidden.
 */

function makeSession(overrides: Partial<SessionListItem> = {}): SessionListItem {
  return {
    id: 'abc-123',
    cwd: '/Users/me/proj/fathom-dfa',
    title: 'fathom-dfa ... R437 验证 取消',
    model: 'MiniMax-M3',
    state: 'completed',
    startedAt: Date.now() - 7_200_000,
    lastActiveAt: Date.now() - 3_600_000,
    tokensIn: 1000,
    tokensOut: 500,
    parentId: null,
    preview: 'fathom-dfa ... R437 验证 取消',
    lastAgentEvent: 'file_write HeapSortTest.java',
    ...overrides,
  };
}

describe('R270: SessionListRow lastAgentEvent line', () => {
  it('renders the arrow + lastAgentEvent when populated', () => {
    render(
      <SessionListRow
        session={makeSession()}
        isCurrent={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByText('file_write HeapSortTest.java')).toBeTruthy();
    expect(screen.getByText('→')).toBeTruthy();
  });

  it('hides the lastAgentEvent line when empty', () => {
    render(
      <SessionListRow
        session={makeSession({ lastAgentEvent: '' })}
        isCurrent={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.queryByText(/file_write/)).toBeNull();
  });

  it('hides the lastAgentEvent line when whitespace only', () => {
    render(
      <SessionListRow
        session={makeSession({ lastAgentEvent: '   ' })}
        isCurrent={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.queryByText(/file_write/)).toBeNull();
  });
});

describe('R270: SessionListRow preview line conditional', () => {
  it('hides the verbatim preview when title === preview (default)', () => {
    // No explicit title — title falls back to the truncated
    // preview. Showing the verbatim preview in quotes would
    // be redundant with the title.
    render(
      <SessionListRow
        session={makeSession({
          title: undefined,
          preview: 'fathom-dfa ... R437 验证 取消',
        })}
        isCurrent={false}
        onSelect={() => {}}
      />,
    );
    // Title shows the preview as-is. The verbatim preview
    // in quotes should NOT render.
    expect(screen.queryByText(/"fathom-dfa \.\.\. R437 验证 取消"/)).toBeNull();
  });

  it('shows the verbatim preview when title differs from preview', () => {
    // User renamed the session. Keep the original first
    // prompt visible below the title.
    render(
      <SessionListRow
        session={makeSession({
          title: 'HeapSort Investigation',
          preview: '请在当前目录下生成 java maven 项目',
        })}
        isCurrent={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByText('HeapSort Investigation')).toBeTruthy();
    expect(screen.getByText('"请在当前目录下生成 java maven 项目"')).toBeTruthy();
  });

  it('shows BOTH the lastAgentEvent line and the verbatim preview line when renamed', () => {
    render(
      <SessionListRow
        session={makeSession({
          title: 'HeapSort Investigation',
          preview: '请在当前目录下生成 java maven 项目',
          lastAgentEvent: 'file_write HeapSortTest.java',
        })}
        isCurrent={false}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByText('file_write HeapSortTest.java')).toBeTruthy();
    expect(screen.getByText('"请在当前目录下生成 java maven 项目"')).toBeTruthy();
  });
});