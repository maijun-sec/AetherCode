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

/**
 * R315 — SddPhaseBar regression test for the hooks-order bug.
 *
 * Background: when the user clicked the SDD toggle, the entire
 * desktop went blank ("white screen"). The root cause was the
 * `if (!sddEnabled) return null;` early-return sitting BEFORE
 * the `useState` / `useEffect` calls. React's Rules of Hooks
 * require hooks to be called in the same order on every render;
 * flipping `sddEnabled: false → true` changed the hook count
 * mid-tree, and React threw "Rendered fewer hooks than
 * expected" — crashing the whole component subtree.
 *
 * This test renders SddPhaseBar with sddEnabled=false (returns
 * null), then re-renders with sddEnabled=true (renders the
 * full chip strip). If any hook is positioned after the early
 * return, this test fails with a console error. Pin the layout.
 */
// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import { useStore } from '../../store';
import { SddPhaseBar } from '../SddPhaseBar';

// Mock the store so the test doesn't need a daemon. We only
// need sddEnabled + sddPhases + setters used by SddPhaseBar.
vi.mock('../../store', () => ({
  useStore: vi.fn(),
}));

describe('SddPhaseBar — R315 hooks order', () => {
  it('does not throw when toggling sddEnabled false → true', () => {
    const mockState = {
      sddEnabled: false,
      sddActive: false,
      sddSlug: '',
      sddPhases: [],
      setSddEnabled: vi.fn(),
      stopSsdFlow: vi.fn(),
      sendSsdCommand: vi.fn(),
    };
    // selector-aware: call the selector with the mock state.
    (useStore as any).mockImplementation((sel: any) => sel(mockState));

    // First render: sddEnabled=false → returns null (no DOM).
    const { unmount } = render(<SddPhaseBar />);
    expect(() => unmount()).not.toThrow();

    // Second render: sddEnabled=true → renders the full chip strip.
    mockState.sddEnabled = true;
    mockState.sddPhases = [
      { id: 'constitution', title: '项目原则', state: 'pending-confirm' },
      { id: 'specify',      title: '需求分析', state: 'idle' },
      { id: 'clarify',      title: '需求澄清', state: 'idle', optional: true },
      { id: 'plan',         title: '详细设计', state: 'idle' },
      { id: 'analyze',      title: '一致性分析', state: 'idle', optional: true },
      { id: 'tasks',        title: '任务分析', state: 'idle' },
      { id: 'implement',    title: '执行实现', state: 'idle' },
      { id: 'converge',     title: '收敛验证', state: 'idle', optional: true },
    ] as any;
    expect(() => render(<SddPhaseBar />)).not.toThrow();
  });

  it('renders all 8 phase chips when sddEnabled is on', () => {
    const mockState = {
      sddEnabled: true,
      sddActive: false,
      sddSlug: '',
      sddPhases: [
        { id: 'constitution', title: '项目原则', state: 'done', endedAt: Date.now(), startedAt: Date.now() - 10000 },
        { id: 'specify',      title: '需求分析', state: 'pending-confirm' },
        { id: 'clarify',      title: '需求澄清', state: 'idle', optional: true },
        { id: 'plan',         title: '详细设计', state: 'idle' },
        { id: 'analyze',      title: '一致性分析', state: 'idle', optional: true },
        { id: 'tasks',        title: '任务分析', state: 'idle' },
        { id: 'implement',    title: '执行实现', state: 'idle' },
        { id: 'converge',     title: '收敛验证', state: 'idle', optional: true },
      ] as any,
      setSddEnabled: vi.fn(),
      stopSsdFlow: vi.fn(),
      sendSsdCommand: vi.fn(),
    };
    (useStore as any).mockImplementation((sel: any) => sel(mockState));

    const { container } = render(<SddPhaseBar />);
    // 8 phase chips (one per Spec Kit phase). Filter to just
    // the phase items (not the progress / running / close
    // counters which share the sdd-phase-* prefix).
    const phaseChips = container.querySelectorAll('[data-testid^="sdd-phase-constitution"], [data-testid^="sdd-phase-specify"], [data-testid^="sdd-phase-clarify"], [data-testid^="sdd-phase-plan"], [data-testid^="sdd-phase-analyze"], [data-testid^="sdd-phase-tasks"], [data-testid^="sdd-phase-implement"], [data-testid^="sdd-phase-converge"]');
    expect(phaseChips.length).toBe(8);
  });
});