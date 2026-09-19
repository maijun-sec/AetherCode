// @vitest-environment jsdom
import { describe, expect, it, beforeEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import { useStore } from '../../store';
import { SddPhaseBar } from '../SddPhaseBar';

describe('SddPhaseBar R288', () => {
  beforeEach(() => {
    // reset the SDD flag between tests so each test starts clean
    useStore.setState({ sddEnabled: false, setSddEnabled: vi_setSddEnabled });
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

  it('renders the four phases when sddEnabled is true', () => {
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

  it('the close button calls setSddEnabled(false)', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    fireEvent.click(screen.getByTestId('sdd-phase-bar-close'));
    // The store action is the real impl: `set({ sddEnabled: on })`
    expect(useStore.getState().sddEnabled).toBe(false);
  });

  it('all phases start in the idle state', () => {
    useStore.setState({ sddEnabled: true });
    renderBar();
    const items = ['spec', 'design', 'tasks', 'dev'] as const;
    for (const id of items) {
      const node = screen.getByTestId(`sdd-phase-${id}`);
      expect(node.className).toContain('sdd-phase-idle');
    }
  });
});

// Helper: keep a stable function reference on the store so
// useStore.setState calls don't overwrite our action binding.
function vi_setSddEnabled(on: boolean) {
  useStore.setState({ sddEnabled: on });
}
