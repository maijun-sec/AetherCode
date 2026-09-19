// @vitest-environment jsdom
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import { useStore } from '../../store';
import { SddPhaseBar } from '../SddPhaseBar';

describe('SddPhaseBar R288/R289', () => {
  beforeEach(() => {
    // reset the SDD flag between tests so each test starts clean
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

  it('renders the four phases when sddEnabled is true (even with empty ssdPhases)', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    expect(screen.getByTestId('sdd-phase-bar')).toBeTruthy();
    expect(screen.getByTestId('sdd-phase-spec')).toBeTruthy();
    expect(screen.getByTestId('sdd-phase-design')).toBeTruthy();
    expect(screen.getByTestId('sdd-phase-tasks')).toBeTruthy();
    expect(screen.getByTestId('sdd-phase-dev')).toBeTruthy();
  });

  it('shows Chinese phase titles (需求分析 / 详细设计 / 任务分析 / 开发实现)', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    expect(screen.getByText('需求分析')).toBeTruthy();
    expect(screen.getByText('详细设计')).toBeTruthy();
    expect(screen.getByText('任务分析')).toBeTruthy();
    expect(screen.getByText('开发实现')).toBeTruthy();
  });

  it('the close button calls setSddEnabled(false) when no run is active', () => {
    useStore.setState({ sddEnabled: true, ssdActive: false });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-phase-bar-close'));
    // The store action is the real impl: `set({sddEnabled: on})` +
    // `if (!on) get().stopSsdFlow()`. stopSsdFlow no-ops when no
    // driver is wired up.
    expect(useStore.getState().sddEnabled).toBe(false);
  });

  it('all phases start in the idle state when ssdPhases is empty', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    const items = ['spec', 'design', 'tasks', 'dev'] as const;
    for (const id of items) {
      const node = screen.getByTestId(`sdd-phase-${id}`);
      expect(node.className).toContain('sdd-phase-idle');
    }
  });

  it('R289: reflects ssdPhases state from the store', () => {
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: 'sort-java',
      ssdPhases: [
        { id: 'spec',   title: '需求分析', state: 'done' },
        { id: 'design', title: '详细设计', state: 'running' },
        { id: 'tasks',  title: '任务分析', state: 'pending-accept', preview: 'todo content', path: '/tmp/spec.md' },
        { id: 'dev',    title: '开发实现', state: 'idle' },
      ],
    });
    renderBar();
    expect(screen.getByTestId('sdd-phase-spec').className).toContain('sdd-phase-done');
    expect(screen.getByTestId('sdd-phase-design').className).toContain('sdd-phase-running');
    expect(screen.getByTestId('sdd-phase-tasks').className).toContain('sdd-phase-pending-accept');
    expect(screen.getByTestId('sdd-phase-dev').className).toContain('sdd-phase-idle');
    // the pending-accept phase surfaces its path
    expect(screen.getByTestId('sdd-phase-path-tasks')).toBeTruthy();
  });

  it('R289: the close button sends {action:quit} when a run is active', () => {
    const sendSsdCommand = vi.fn();
    useStore.setState({
      sddEnabled: true,
      ssdActive: true,
      ssdSlug: 'sort-java',
      ssdPhases: [
        { id: 'spec',   title: '需求分析', state: 'running' },
      ],
      sendSsdCommand,
    });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-phase-bar-close'));
    expect(sendSsdCommand).toHaveBeenCalledWith({ action: 'quit' });
  });
});