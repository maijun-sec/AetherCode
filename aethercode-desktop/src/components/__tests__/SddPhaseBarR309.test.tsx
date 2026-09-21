// @vitest-environment jsdom
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import { useStore } from '../../store';
import { SddPhaseBar } from '../SddPhaseBar';

/**
 * R309 wire-protocol tests for the desktop SDD phase bar's
 * new `need-content` state. The daemon emits a
 * `phase-need-content` event with the full system/user prompts
 * and the `maxTokens` hint; the bar parks the chip on
 * `need-content`, surfaces a multi-line textarea, and on
 * 📤 发送  (or Ctrl/⌘+Enter) forwards the typed content as a
 * `phase-content` command. The daemon writes the body to disk
 * and emits `phase-draft`, which the existing pending-accept
 * pane then handles.
 *
 * <h2>Source-pin goals</h2>
 * <ol>
 *   <li>When the store pushes a phase with state
 *       {@code 'need-content'}, the bar renders a
 *       {@code 📤 发送内容} button + a multi-line textarea
 *       under the chip strip. None of the other action panes
 *       (pending-accept / clarify-pending / converge-pending)
 *       leak through.</li>
 *   <li>Pressing the button sends {@code {action:"phase-content",
 *       content:"..."}} via {@code sendSsdCommand}. The
 *       {@code content} field equals the textarea text
 *       verbatim — no trimming, no preamble wrapping, no
 *       markdown reformatting (the daemon's
 *       {@code SddRunner.cleanOutput} pass is responsible for
 *       the latter).</li>
 *   <li>Ctrl+Enter (and ⌘+Enter on macOS) also sends the same
 *       command and clears the textarea. This matches the
 *       R300 inline-revise muscle memory the user already has
 *       from the pending-accept flow.</li>
 *   <li>The send button stays disabled while the textarea is
 *       empty (no accidental empty-content submits that
 *       would zero-byte the artefact on disk).</li>
 * </ol>
 */
describe('SddPhaseBar R309 need-content pane', () => {
  beforeEach(() => {
    useStore.setState({
      sddEnabled: true,
      ssdPhases: [],
      ssdActive: true,
      ssdSlug: 'java-maven',
      ssdIntent: 'build a maven project',
    });
  });

  it('renders the need-content action pane (textarea + send button) when a phase is parked on need-content', () => {
    useStore.setState({
      ssdPhases: [
        { id: 'constitution', title: '项目原则', state: 'done' },
        // R309: daemon just emitted phase-need-content for
        // the specify phase — user must paste LLM output.
        { id: 'specify', title: '需求分析', state: 'need-content' },
        { id: 'clarify', title: '需求澄清', state: 'idle', optional: true },
        { id: 'plan', title: '详细设计', state: 'idle' },
      ],
    });
    createFixture().render(<SddPhaseBar />);

    // The textarea is visible and editable.
    const textarea = screen.getByTestId('sdd-need-content-textarea');
    expect(textarea).toBeTruthy();
    // The send button is visible but disabled while empty.
    const sendBtn = screen.getByTestId('sdd-btn-send-need-content');
    expect(sendBtn).toBeTruthy();
    expect((sendBtn as HTMLButtonElement).disabled).toBe(true);
    // No other action pane is rendered — the
    // pending-accept / clarify-pending / converge-pending
    // forms each gate on their own state value.
    expect(screen.queryByTestId('sdd-btn-accept')).toBeNull();
    expect(screen.queryByTestId('sdd-revise-textarea')).toBeNull();
    expect(screen.queryByTestId('sdd-btn-clarify-send')).toBeNull();
    expect(screen.queryByTestId('sdd-btn-converge-accept')).toBeNull();
  });

  it('📤 发送内容  button sends {action:"phase-content", content} verbatim when pressed', () => {
    const sendSsdCommand = vi.fn();
    useStore.setState({
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'need-content' },
      ],
      // inject the spy on top of the store action
      ...{ sendSsdCommand } as any,
    });
    // The store's setState doesn't accept function overrides
    // cleanly; reach into the store directly. The safer path
    // is to spy on the real sendSsdCommand method via
    // Object.defineProperty.
    const original = useStore.getState().sendSsdCommand;
    Object.defineProperty(useStore.getState(), 'sendSsdCommand', {
      value: sendSsdCommand, writable: true, configurable: true,
    });
    createFixture().render(<SddPhaseBar />);

    const textarea = screen.getByTestId('sdd-need-content-textarea') as HTMLTextAreaElement;
    const body = '# Real Spec\n\n## User Stories\n\n- **FR-001**: sort int arrays\n';
    fireEvent.change(textarea, { target: { value: body } });
    const sendBtn = screen.getByTestId('sdd-btn-send-need-content');
    expect((sendBtn as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(sendBtn);

    expect(sendSsdCommand).toHaveBeenCalledTimes(1);
    const arg = sendSsdCommand.mock.calls[0][0];
    expect(arg.action).toBe('phase-content');
    expect(arg.content).toBe(body);
    // Textarea clears after send so the next round starts
    // blank — mirrors the R300 revise / clarify textareas.
    expect(textarea.value).toBe('');

    // Restore the original so afterEach teardown doesn't see
    // a torn-up prototype.
    Object.defineProperty(useStore.getState(), 'sendSsdCommand', {
      value: original, writable: true, configurable: true,
    });
  });

  it('Ctrl+Enter sends the same phase-content command and clears the textarea', () => {
    const sendSsdCommand = vi.fn();
    const original = useStore.getState().sendSsdCommand;
    Object.defineProperty(useStore.getState(), 'sendSsdCommand', {
      value: sendSsdCommand, writable: true, configurable: true,
    });
    useStore.setState({
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'need-content' },
      ],
    });
    createFixture().render(<SddPhaseBar />);

    const textarea = screen.getByTestId('sdd-need-content-textarea') as HTMLTextAreaElement;
    const body = '# Spec body\n\ndetails here';
    fireEvent.change(textarea, { target: { value: body } });
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true });

    expect(sendSsdCommand).toHaveBeenCalledTimes(1);
    expect(sendSsdCommand.mock.calls[0][0].action).toBe('phase-content');
    expect(sendSsdCommand.mock.calls[0][0].content).toBe(body);
    expect(textarea.value).toBe('');

    Object.defineProperty(useStore.getState(), 'sendSsdCommand', {
      value: original, writable: true, configurable: true,
    });
  });

  it('plain Enter (without Ctrl/Meta) does NOT send the command', () => {
    const sendSsdCommand = vi.fn();
    const original = useStore.getState().sendSsdCommand;
    Object.defineProperty(useStore.getState(), 'sendSsdCommand', {
      value: sendSsdCommand, writable: true, configurable: true,
    });
    useStore.setState({
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'need-content' },
      ],
    });
    createFixture().render(<SddPhaseBar />);

    const textarea = screen.getByTestId('sdd-need-content-textarea') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: 'something' } });
    // Plain Enter — must add a newline, NOT fire the command.
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(sendSsdCommand).not.toHaveBeenCalled();
    expect(textarea.value).toBe('something');

    Object.defineProperty(useStore.getState(), 'sendSsdCommand', {
      value: original, writable: true, configurable: true,
    });
  });
});