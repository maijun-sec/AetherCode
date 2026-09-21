// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { cleanup, screen } from '@testing-library/react';
import { afterEach } from 'vitest';
import { createFixture } from '../../test/testUtils';
import { useStore } from '../../store';
import { MessageList } from '../MessageList';

/**
 * R308: SDD system messages render as markdown.
 *
 * <p>Before R308 the desktop pushed {@code phase-draft}
 * (and {@code phase-start} / {@code phase-accepted} / etc.)
 * content into the chat stream as a {@code role: 'system'}
 * message whose body was a literal markdown source
 * string — e.g. {@code 📐 specify 阶段草案\n\n文件:
 * `path`\n\n``` \n# Feature Spec...```}. MessageList
 * rendered the body verbatim in a plain-text pill, so
 * the user saw the raw markdown source (literal `**`,
 * `##`, `[NEEDS CLARIFICATION: ...]`) instead of the
 * formatted page.
 *
 * <p>R308 teaches MessageList to detect the SDD
 * {@code metadata.kind} prefix ({@code sdd-}) and route
 * those messages through ReactMarkdown + remark-gfm so
 * headers / lists / code blocks / tables / blockquotes
 * get their normal styling. Non-SDD system messages
 * (reconnect / error / workflow / compaction / busy /
 * disconnected) keep the plain-text pill — escaping the
 * brackets in `[Error] xxx` would be more confusing than
 * helpful.
 *
 * <p>Source-pin tests below assert:
 * <ol>
 *   <li>{@code ssd-draft} system message renders
 *       {@code <h1>} / {@code <h2>} / {@code <ul>} /
 *       {@code <code>} (not literal markdown text);</li>
 *   <li>{@code sdd-phase-start} / {@code sdd-phase-accepted}
 *       system messages render the {@code **bold**} via
 *       {@code <strong>};</li>
 *   <li>plain {@code [Error] xxx} system message stays
 *       plain text (no {@code ReactMarkdown}
 *       involvement);</li>
 *   <li>the SDD pill class {@code message-system-sdd}
 *       is applied so the CSS shape override kicks
 *       in.</li>
 * </ol>
 */
describe('MessageList R308 markdown rendering of SDD system messages', () => {
  afterEach(() => cleanup());

  it('renders ssd-draft system message with ReactMarkdown (headings / lists / code rendered, not literal markdown)', () => {
    const fixture = createFixture({ seed: { sessionId: 'sess-r308' } });
    useStore.setState({
      currentSessionId: 'sess-r308',
      messages: [{
        id: 'draft-1',
        role: 'system',
        // The exact content shape SddRunner / InteractiveRepl
        // builds in store/index.ts phase-draft case: header
        // (📐 + **bold**) + path (in backticks) + preview
        // body (markdown source) + footer (`等待确认`).
        content:
          '📐 **specify** 阶段草案\n\n' +
          '文件: `.aethercode/sdd/java-maven/spec.md`\n\n' +
          '# Feature Specification: java-maven\n\n' +
          '## User Scenarios & Testing *(mandatory)*\n\n' +
          '- **FR-001**: System MUST sort int arrays\n' +
          '- **FR-002**: System MUST sort long arrays\n\n' +
          '_(等待确认 → 进入下一阶段)_',
        timestamp: 1000,
        metadata: { kind: 'sdd-draft', phase: 'specify', path: '.aethercode/sdd/java-maven/spec.md' },
      }],
      steps: [], subTasks: [],
    });
    fixture.render(<MessageList />);

    // The SDD pill class is applied so the wider,
    // non-italic CSS surface kicks in.
    const sddRow = document.querySelector('.message-system-sdd');
    expect(sddRow).toBeTruthy();

    // ReactMarkdown renders # as <h1>, ## as <h2>, - as
    // <ul><li>, ** as <strong>. None of those appear in
    // the literal content string, so finding them
    // proves ReactMarkdown was applied (not plain text).
    expect(document.querySelector('.message-system-sdd h1')?.textContent).toContain('Feature Specification: java-maven');
    expect(document.querySelector('.message-system-sdd h2')?.textContent).toContain('User Scenarios & Testing');
    expect(document.querySelectorAll('.message-system-sdd li').length).toBe(2);
    // The first <strong> is 'specify' from the header
    // (`📐 **specify** 阶段草案`). The list items wrap
    // their text in ** too, so we get 'FR-001' /
    // 'FR-002' further down. Iterate all <strong>
    // elements to find both header + body matches.
    const strongs = Array.from(document.querySelectorAll('.message-system-sdd strong'))
      .map(s => s?.textContent ?? '');
    expect(strongs).toContain('specify');     // header
    expect(strongs).toContain('FR-001');      // list item 1
    expect(strongs).toContain('FR-002');      // list item 2
    // The path is wrapped in backticks → rendered as
    // <code>. We don't assert exact text because the
    // <code> may also appear inside the body, but at
    // least one <code> element exists.
    expect(document.querySelectorAll('.message-system-sdd code').length).toBeGreaterThan(0);
    // The literal markdown source characters should NOT
    // leak through. The body contains `##` and `**`; if
    // ReactMarkdown is wired, they become elements, not
    // raw text. Spot-check by ensuring the row's
    // textContent does not contain the literal "## User".
    expect(sddRow?.textContent).not.toContain('## User');
  });

  it('renders sdd-phase-start / sdd-phase-accepted system messages with ReactMarkdown (bold via <strong>)', () => {
    const fixture = createFixture({ seed: { sessionId: 'sess-r308' } });
    useStore.setState({
      currentSessionId: 'sess-r308',
      messages: [
        {
          id: 'start-1', role: 'system',
          content: '▶️ **specify**：启动',
          timestamp: 1000,
          metadata: { kind: 'sdd-phase-start', phase: 'specify' },
        },
        {
          id: 'accept-1', role: 'system',
          content: '✓ **specify**：已确认（12s）',
          timestamp: 2000,
          metadata: { kind: 'sdd-phase-accepted', phase: 'specify', durationMs: 12000 },
        },
      ],
      steps: [], subTasks: [],
    });
    fixture.render(<MessageList />);

    // Both rows use the SDD class.
    expect(document.querySelectorAll('.message-system-sdd').length).toBe(2);
    // **specify** rendered as <strong>specify</strong> in
    // both rows. If plain text was used, the literal
    // "**specify**" would appear.
    const strongs = document.querySelectorAll('.message-system-sdd strong');
    expect(strongs.length).toBe(2);
    expect(strongs[0]?.textContent).toBe('specify');
    expect(strongs[1]?.textContent).toBe('specify');
    // The literal markdown source must NOT appear.
    expect(document.body.textContent).not.toContain('**specify**');
  });

  it('keeps non-SDD system messages as plain text (no ReactMarkdown, no .message-system-sdd class)', () => {
    const fixture = createFixture({ seed: { sessionId: 'sess-r308' } });
    useStore.setState({
      currentSessionId: 'sess-r308',
      messages: [
        {
          id: 'err-1', role: 'system', isError: true,
          content: '[Error] spawn failed: ACL denied',
          timestamp: 1000,
          // No metadata.kind → not SDD, stays plain text.
        },
        {
          id: 'busy-1', role: 'system', isError: true,
          content: '[busy] daemon already running',
          timestamp: 2000,
          metadata: { kind: 'workflow-error' },
        },
      ],
      steps: [], subTasks: [],
    });
    fixture.render(<MessageList />);

    // Neither row uses the SDD class — they keep the
    // legacy 12 px italic pill.
    expect(document.querySelector('.message-system-sdd')).toBeNull();
    // The literal brackets stay as literal text (no
    // <a> link injection from ReactMarkdown interpreting
    // `[busy] daemon` as a markdown reference link).
    expect(screen.getByText('[Error] spawn failed: ACL denied')).toBeTruthy();
    expect(screen.getByText('[busy] daemon already running')).toBeTruthy();
    // No <h1> / <strong> should appear inside the
    // system message area — those would prove
    // ReactMarkdown was incorrectly applied.
    expect(document.querySelector('.message-system h1')).toBeNull();
    expect(document.querySelector('.message-system strong')).toBeNull();
  });
});