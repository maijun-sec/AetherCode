// @vitest-environment jsdom
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import { useStore } from '../../store';
import { SddPhaseBar } from '../SddPhaseBar';

/**
 * R292 rewrite of the R288 SddPhaseBar tests. The bar now
 * covers the Spec Kit 8-phase pipeline (constitution /
 * specify / clarify / plan / analyze / tasks / implement /
 * converge) with optional quality gates (clarify / analyze /
 * converge) rendering half-opacity. Tests use the kebab-case
 * phase ids + 中文 titles the daemon now emits.
 */
describe('SddPhaseBar R292 (Spec Kit 8 phases)', () => {
  beforeEach(() => {
    useStore.setState({
      sddEnabled: false,
      ssdPhases: [],
      ssdActive: false,
      ssdSlug: '',
      ssdIntent: '',
    });
  });

  function renderBar() {
    const fixture = createFixture();
    return fixture.render(<SddPhaseBar />);
  }

  it('returns null when sddEnabled is false', () => {
    useStore.setState({ sddEnabled: false });
    const { container } = renderBar();
    expect(container.firstChild).toBeNull();
  });

  it('renders the eight Spec Kit phases when sddEnabled is true', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    expect(screen.getByTestId('sdd-phase-bar')).toBeTruthy();
    for (const id of ['constitution', 'specify', 'clarify', 'plan', 'analyze', 'tasks', 'implement', 'converge']) {
      expect(screen.getByTestId(`sdd-phase-${id}`)).toBeTruthy();
    }
  });

  it('shows Chinese phase titles (项目原则 / 需求分析 / 需求澄清 / 详细设计 / 一致性分析 / 任务分析 / 执行实现 / 收敛验证)', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    expect(screen.getByText('项目原则')).toBeTruthy();
    expect(screen.getByText('需求分析')).toBeTruthy();
    expect(screen.getByText('需求澄清')).toBeTruthy();
    expect(screen.getByText('详细设计')).toBeTruthy();
    expect(screen.getByText('一致性分析')).toBeTruthy();
    expect(screen.getByText('任务分析')).toBeTruthy();
    expect(screen.getByText('执行实现')).toBeTruthy();
    expect(screen.getByText('收敛验证')).toBeTruthy();
  });

  it('marks the optional quality gates (clarify / analyze / converge) as optional', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    expect(screen.getByTestId('sdd-phase-clarify').className).toContain('sdd-phase-optional');
    expect(screen.getByTestId('sdd-phase-analyze').className).toContain('sdd-phase-optional');
    expect(screen.getByTestId('sdd-phase-converge').className).toContain('sdd-phase-optional');
    // required phases are NOT optional
    expect(screen.getByTestId('sdd-phase-constitution').className).not.toContain('sdd-phase-optional');
    expect(screen.getByTestId('sdd-phase-specify').className).not.toContain('sdd-phase-optional');
    expect(screen.getByTestId('sdd-phase-plan').className).not.toContain('sdd-phase-optional');
    expect(screen.getByTestId('sdd-phase-tasks').className).not.toContain('sdd-phase-optional');
    expect(screen.getByTestId('sdd-phase-implement').className).not.toContain('sdd-phase-optional');
  });

  it('the close button calls setSddEnabled(false) when no run is active', () => {
    useStore.setState({ sddEnabled: true, ssdActive: false });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-phase-bar-close'));
    expect(useStore.getState().sddEnabled).toBe(false);
  });

  it('all phases start in the idle state when ssdPhases is empty', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    for (const id of ['constitution', 'specify', 'clarify', 'plan', 'analyze', 'tasks', 'implement', 'converge']) {
      const node = screen.getByTestId(`sdd-phase-${id}`);
      expect(node.className).toContain('sdd-phase-idle');
    }
  });

  it('reflects ssdPhases state from the store (Spec Kit advanced)', () => {
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-albums',
      ssdPhases: [
        { id: 'constitution', title: '项目原则',   state: 'done' },
        { id: 'specify',      title: '需求分析',   state: 'done' },
        { id: 'clarify',      title: '需求澄清',   state: 'done', optional: true },
        { id: 'plan',         title: '详细设计',   state: 'running' },
        { id: 'analyze',      title: '一致性分析', state: 'done', optional: true },
        { id: 'tasks',        title: '任务分析',   state: 'pending-accept', preview: 'todo content', path: '/tmp/.specify/specs/001-photo-albums/tasks.md' },
        { id: 'implement',    title: '执行实现',   state: 'idle' },
        { id: 'converge',     title: '收敛验证',   state: 'idle', optional: true },
      ],
    });
    renderBar();
    expect(screen.getByTestId('sdd-phase-constitution').className).toContain('sdd-phase-done');
    expect(screen.getByTestId('sdd-phase-plan').className).toContain('sdd-phase-running');
    expect(screen.getByTestId('sdd-phase-tasks').className).toContain('sdd-phase-pending-accept');
    expect(screen.getByTestId('sdd-phase-implement').className).toContain('sdd-phase-idle');
    expect(screen.getByTestId('sdd-phase-path-tasks')).toBeTruthy();
  });

  it('the close button sends {action:quit} when a run is active', () => {
    const sendSsdCommand = vi.fn();
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-albums',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'running' },
      ],
      sendSsdCommand,
    });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-phase-bar-close'));
    expect(sendSsdCommand).toHaveBeenCalledWith({ action: 'quit' });
  });

  // ---------- R293: per-phase accept/revise/skip action bar ----------

  it('R293: renders accept / revise / skip buttons when a phase is pending-accept', () => {
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'pending-accept', path: '.specify/specs/001-photo-organizer/spec.md' },
        { id: 'plan',    title: '详细设计', state: 'idle' },
      ],
      sendSsdCommand: vi.fn(),
    });
    renderBar();
    expect(screen.getByTestId('sdd-phase-actions').getAttribute('data-waiting-phase')).toBe('specify');
    expect(screen.getByTestId('sdd-phase-actions').getAttribute('data-waiting-state')).toBe('pending-accept');
    expect(screen.getByTestId('sdd-btn-accept')).toBeTruthy();
    expect(screen.getByTestId('sdd-btn-revise')).toBeTruthy();
    expect(screen.getByTestId('sdd-btn-skip')).toBeTruthy();
  });

  it('R293: clicking the accept button sends {action:"accept"} via sendSsdCommand', () => {
    const sendSsdCommand = vi.fn();
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'pending-accept' },
      ],
      sendSsdCommand,
    });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-btn-accept'));
    expect(sendSsdCommand).toHaveBeenCalledWith({ action: 'accept' });
  });

  it('R293: clicking the skip button sends {action:"skip"} via sendSsdCommand', () => {
    const sendSsdCommand = vi.fn();
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'pending-accept' },
      ],
      sendSsdCommand,
    });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-btn-skip'));
    expect(sendSsdCommand).toHaveBeenCalledWith({ action: 'skip' });
  });

  it('R293: the action bar collapses when no phase is waiting on user input', () => {
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'done' },
        { id: 'plan',    title: '详细设计', state: 'running' },
      ],
      sendSsdCommand: vi.fn(),
    });
    renderBar();
    expect(screen.queryByTestId('sdd-phase-actions')).toBeNull();
  });

  it('R293: renders a text input + send button when a phase is clarify-pending', () => {
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'done' },
        { id: 'clarify', title: '需求澄清', state: 'clarify-pending', preview: 'Auth method\n\nWhich auth method?' },
      ],
      sendSsdCommand: vi.fn(),
    });
    renderBar();
    expect(screen.getByTestId('sdd-clarify-input')).toBeTruthy();
    expect(screen.getByTestId('sdd-btn-clarify-send')).toBeTruthy();
    expect(screen.getByTestId('sdd-phase-actions').getAttribute('data-waiting-phase')).toBe('clarify');
  });

  it('R293: renders end / iterate buttons when a phase is converge-pending', () => {
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'converge', title: '收敛验证', state: 'converge-pending', optional: true },
      ],
      sendSsdCommand: vi.fn(),
    });
    renderBar();
    expect(screen.getByTestId('sdd-btn-converge-accept')).toBeTruthy();
    expect(screen.getByTestId('sdd-btn-converge-iterate')).toBeTruthy();
  });

  it('R293: chips are rendered as compact pills (no per-chip preview / description body)', () => {
    // The previous R292 layout rendered a multi-line body
    // per chip (preview + path + description). R293
    // collapses it to a single-line pill with a glyph +
    // number + title — verify the body class is gone.
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: '001-photo-organizer',
      ssdPhases: [
        { id: 'specify', title: '需求分析', state: 'pending-accept', preview: 'long content...', path: '/tmp/spec.md' },
      ],
      sendSsdCommand: vi.fn(),
    });
    renderBar();
    const chip = screen.getByTestId('sdd-phase-specify');
    // The old layout had .sdd-phase-body and .sdd-phase-preview
    // children; the new layout only has glyph/num/title.
    expect(chip.querySelector('.sdd-phase-body')).toBeNull();
    expect(chip.querySelector('.sdd-phase-preview')).toBeNull();
    // The waiting phase should still expose its path via
    // the action bar (not the chip itself).
    expect(chip.querySelector('[data-testid="sdd-phase-path-specify"]')).toBeNull();
    expect(screen.getByTestId('sdd-phase-path-specify')).toBeTruthy();
  });
});