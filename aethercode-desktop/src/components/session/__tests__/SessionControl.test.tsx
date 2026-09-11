// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { createFixture, flush, autoCleanup } from '../../../test/testUtils';
import { SessionControl } from '../SessionControl';

autoCleanup();

describe('Phase 4.1 / T-4-05: SessionControl', () => {
  let fixture = createFixture({});
  beforeEach(() => {
    fixture = createFixture({});
  });

  it('all three buttons are disabled when there is no active task', () => {
    fixture.render(<SessionControl taskId={null} taskState="unknown" />);
    const c = screen.getByTestId('session-control-continue');
    const p = screen.getByTestId('session-control-pause');
    const s = screen.getByTestId('session-control-stop');
    expect(c.hasAttribute('disabled')).toBe(true);
    expect(p.hasAttribute('disabled')).toBe(true);
    expect(s.hasAttribute('disabled')).toBe(true);
  });

  it('Continue is enabled when the task is paused', () => {
    fixture.render(<SessionControl taskId="task-1" taskState="paused" />);
    expect(screen.getByTestId('session-control-continue').hasAttribute('disabled')).toBe(false);
    expect(screen.getByTestId('session-control-pause').hasAttribute('disabled')).toBe(true);
  });

  it('Pause is enabled when the task is running', () => {
    fixture.render(<SessionControl taskId="task-1" taskState="running" />);
    expect(screen.getByTestId('session-control-pause').hasAttribute('disabled')).toBe(false);
    expect(screen.getByTestId('session-control-continue').hasAttribute('disabled')).toBe(true);
  });

  it('Stop is enabled when running or paused', () => {
    const { rerender } = fixture.render(<SessionControl taskId="task-1" taskState="running" />);
    expect(screen.getByTestId('session-control-stop').hasAttribute('disabled')).toBe(false);
    rerender(<SessionControl taskId="task-1" taskState="paused" />);
    expect(screen.getByTestId('session-control-stop').hasAttribute('disabled')).toBe(false);
  });

  it('all three buttons are disabled once the task is completed', () => {
    // After completion only Continue is enabled (per spec).
    fixture.render(<SessionControl taskId="task-1" taskState="completed" />);
    expect(screen.getByTestId('session-control-continue').hasAttribute('disabled')).toBe(false);
    expect(screen.getByTestId('session-control-pause').hasAttribute('disabled')).toBe(true);
    expect(screen.getByTestId('session-control-stop').hasAttribute('disabled')).toBe(true);
  });

  it('clicking Pause dispatches task/pause with the active task id', async () => {
    fixture.render(<SessionControl taskId="task-1" taskState="running" />);
    fireEvent.click(screen.getByTestId('session-control-pause'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'task/pause');
    expect(called).toBeDefined();
    expect((called?.params as { id: string }).id).toBe('task-1');
  });

  it('clicking Continue dispatches task/resume', async () => {
    fixture.render(<SessionControl taskId="task-1" taskState="paused" />);
    fireEvent.click(screen.getByTestId('session-control-continue'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'task/resume');
    expect(called).toBeDefined();
  });

  it('clicking Stop dispatches task/kill', async () => {
    fixture.render(<SessionControl taskId="task-1" taskState="paused" />);
    fireEvent.click(screen.getByTestId('session-control-stop'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'task/kill');
    expect(called).toBeDefined();
  });

  it('onAfterControl fires with the chosen op after a successful control', async () => {
    const calls: ('resume' | 'pause' | 'kill')[] = [];
    fixture.render(
      <SessionControl
        taskId="task-1"
        taskState="running"
        onAfterControl={(op) => calls.push(op)}
      />,
    );
    fireEvent.click(screen.getByTestId('session-control-pause'));
    await waitFor(() => {
      expect(calls).toEqual(['pause']);
    });
  });
});

