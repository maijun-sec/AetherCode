// @vitest-environment jsdom
/**
 * R281 — SSD panel tests.
 *
 * Drives the panel with a {@link MockSsdDriver} so we can exercise the
 * event-driven UI without spawning the JVM subprocess. The MockSsdDriver
 * is the same component the panel uses in production, so behaviour here
 * matches the real wire protocol exactly.
 */
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { SsdPanel } from '../SsdPanel';
import { MockSsdDriver, type SsdDriverEvent } from '../driver';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();

/** a canned 4-phase event stream that exercises every chip state once. */
function fullRunEvents(): SsdDriverEvent[] {
  return [
    {
      kind: 'phase-list',
      feature: 'invoice-export',
      phases: [
        { id: 'spec', order: 1, title: 'Spec' },
        { id: 'design', order: 2, title: 'Design' },
        { id: 'tasks', order: 3, title: 'Tasks' },
        { id: 'dev', order: 4, title: 'Implement' },
      ],
    },
    { kind: 'phase-start', phase: 'spec', order: 1, title: 'Spec' },
    {
      kind: 'phase-draft',
      phase: 'spec',
      path: '/tmp/invoice-export/spec.md',
      bytes: 4321,
      preview: '# Spec\n\nInitial requirements ...',
    },
    { kind: 'phase-accepted', phase: 'spec', revisionCount: 0 },
    { kind: 'phase-start', phase: 'design', order: 2, title: 'Design' },
    {
      kind: 'phase-draft',
      phase: 'design',
      path: '/tmp/invoice-export/design.md',
      bytes: 5400,
      preview: '# Design\n\nArchitecture overview ...',
    },
    { kind: 'phase-revising', phase: 'design', revision: 'add NFR-3 about audit logging' },
    {
      kind: 'phase-draft',
      phase: 'design',
      path: '/tmp/invoice-export/design.md',
      bytes: 6100,
      preview: '# Design\n\nArchitecture overview ...\n\n## NFR-3 Audit logging',
    },
    { kind: 'phase-accepted', phase: 'design', revisionCount: 1 },
    {
      kind: 'complete',
      feature: 'invoice-export',
      results: [
        { phaseId: 'spec', path: '/tmp/invoice-export/spec.md', revisions: 0 },
        { phaseId: 'design', path: '/tmp/invoice-export/design.md', revisions: 1 },
      ],
    },
  ];
}

describe('R281: source-pin invariants', () => {
  it('SsdPanel.tsx + driver.ts + SsdPanel.css all exist', () => {
    expect(existsSync(join(root, 'src/components/ssd/SsdPanel.tsx'))).toBe(true);
    expect(existsSync(join(root, 'src/components/ssd/driver.ts'))).toBe(true);
    expect(existsSync(join(root, 'src/components/ssd/SsdPanel.css'))).toBe(true);
  });

  it('driver.ts defines the 11 event kinds from InteractiveRepl.java', () => {
    const src = readFileSync(join(root, 'src/components/ssd/driver.ts'), 'utf-8');
    for (const k of [
      'phase-list',
      'phase-start',
      'phase-draft',
      'phase-revising',
      'phase-accepted',
      'phase-skipped',
      'phase-error',
      'complete',
      'abort',
      'error',
      'log',
    ]) {
      expect(src).toMatch(new RegExp(`kind:\\s*['"]${k}['"]`));
    }
  });

  it('driver.ts defines the 4 inbound commands', () => {
    const src = readFileSync(join(root, 'src/components/ssd/driver.ts'), 'utf-8');
    expect(src).toMatch(/action:\s*['"]accept['"]/);
    expect(src).toMatch(/action:\s*['"]revise['"]/);
    expect(src).toMatch(/action:\s*['"]skip['"]/);
    expect(src).toMatch(/action:\s*['"]quit['"]/);
  });

  it('SsdPanel.tsx imports the CSS file (so vite bundles it)', () => {
    const src = readFileSync(join(root, 'src/components/ssd/SsdPanel.tsx'), 'utf-8');
    expect(src).toMatch(/import\s+['"]\.\/SsdPanel\.css['"]/);
  });
});

describe('R281: SsdPanel UI behaviour with MockSsdDriver', () => {
  it('renders empty-state when no phase-list has arrived yet', () => {
    const driver = new MockSsdDriver([]);
    render(<SsdPanel driver={driver} />);
    expect(screen.getByTestId('ssd-panel')).toBeDefined();
    // Empty state visible until the daemon emits the first event.
    expect(screen.getByText(/waiting for daemon to announce/i)).toBeDefined();
  });

  it('renders one TODO chip per phase once phase-list arrives', async () => {
    const driver = new MockSsdDriver(fullRunEvents());
    render(<SsdPanel driver={driver} />);
    // Wait for the chips to mount (driver.start() is async via setTimeout).
    await waitFor(() => {
      expect(screen.getByTestId('ssd-chip-spec')).toBeDefined();
    });
    expect(screen.getByTestId('ssd-chip-design')).toBeDefined();
    expect(screen.getByTestId('ssd-chip-tasks')).toBeDefined();
    expect(screen.getByTestId('ssd-chip-dev')).toBeDefined();
  });

  it('Accept button sends an "accept" command via the driver', async () => {
    const driver = new MockSsdDriver([
      { kind: 'phase-list', feature: 'x', phases: [{ id: 'spec', order: 1, title: 'Spec' }] },
      { kind: 'phase-start', phase: 'spec', order: 1, title: 'Spec' },
      {
        kind: 'phase-draft',
        phase: 'spec',
        path: '/tmp/x/spec.md',
        bytes: 100,
        preview: '# spec',
      },
    ]);
    render(<SsdPanel driver={driver} />);
    await waitFor(() => {
      expect(screen.getByTestId('ssd-confirm-pane')).toBeDefined();
    });
    fireEvent.click(screen.getByTestId('ssd-accept'));
    expect(driver.getCommands()).toEqual([{ action: 'accept' }]);
  });

  it('Apply Revision sends "revise" with the textarea text', async () => {
    const driver = new MockSsdDriver([
      { kind: 'phase-list', feature: 'x', phases: [{ id: 'spec', order: 1, title: 'Spec' }] },
      { kind: 'phase-start', phase: 'spec', order: 1, title: 'Spec' },
      {
        kind: 'phase-draft',
        phase: 'spec',
        path: '/tmp/x/spec.md',
        bytes: 100,
        preview: '# spec',
      },
    ]);
    render(<SsdPanel driver={driver} />);
    await waitFor(() => {
      expect(screen.getByTestId('ssd-confirm-pane')).toBeDefined();
    });
    fireEvent.change(screen.getByTestId('ssd-revision'), {
      target: { value: 'add an NFR about audit logging' },
    });
    fireEvent.click(screen.getByTestId('ssd-apply-revision'));
    expect(driver.getCommands()).toEqual([
      { action: 'revise', text: 'add an NFR about audit logging' },
    ]);
  });

  it('Apply Revision button is disabled when textarea is empty', async () => {
    const driver = new MockSsdDriver([
      { kind: 'phase-list', feature: 'x', phases: [{ id: 'spec', order: 1, title: 'Spec' }] },
      { kind: 'phase-start', phase: 'spec', order: 1, title: 'Spec' },
      {
        kind: 'phase-draft',
        phase: 'spec',
        path: '/tmp/x/spec.md',
        bytes: 100,
        preview: '# spec',
      },
    ]);
    render(<SsdPanel driver={driver} />);
    await waitFor(() => {
      expect(screen.getByTestId('ssd-confirm-pane')).toBeDefined();
    });
    const btn = screen.getByTestId('ssd-apply-revision') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });

  it('Quit button sends "quit" command when running', async () => {
    const driver = new MockSsdDriver([
      { kind: 'phase-list', feature: 'x', phases: [{ id: 'spec', order: 1, title: 'Spec' }] },
      { kind: 'phase-start', phase: 'spec', order: 1, title: 'Spec' },
    ]);
    render(<SsdPanel driver={driver} />);
    await waitFor(() => {
      expect(screen.getByTestId('ssd-quit')).toBeDefined();
    });
    fireEvent.click(screen.getByTestId('ssd-quit'));
    expect(driver.getCommands()).toEqual([{ action: 'quit' }]);
  });

  it('completion pane shows results when complete event arrives', async () => {
    const driver = new MockSsdDriver([
      { kind: 'phase-list', feature: 'x', phases: [{ id: 'spec', order: 1, title: 'Spec' }] },
      { kind: 'phase-start', phase: 'spec', order: 1, title: 'Spec' },
      {
        kind: 'phase-draft',
        phase: 'spec',
        path: '/tmp/x/spec.md',
        bytes: 100,
        preview: '# spec',
      },
      { kind: 'phase-accepted', phase: 'spec', revisionCount: 0 },
      {
        kind: 'complete',
        feature: 'x',
        results: [{ phaseId: 'spec', path: '/tmp/x/spec.md', revisions: 0 }],
      },
    ]);
    render(<SsdPanel driver={driver} />);
    await waitFor(() => {
      expect(screen.getByTestId('ssd-complete-pane')).toBeDefined();
    });
    expect(screen.getByText(/SSD complete/i)).toBeDefined();
  });
});

describe('R281: MockSsdDriver behaviour', () => {
  it('registers and returns a custom draft body via fetchDraft', async () => {
    const driver = new MockSsdDriver([], { '/tmp/spec.md': '# custom full body' });
    const body = await driver.fetchDraft('/tmp/spec.md');
    expect(body).toBe('# custom full body');
  });

  it('falls back to the preview field when no draft is registered', async () => {
    const driver = new MockSsdDriver([
      {
        kind: 'phase-draft',
        phase: 'spec',
        path: '/tmp/spec.md',
        bytes: 50,
        preview: '# preview',
      },
    ]);
    const body = await driver.fetchDraft('/tmp/spec.md');
    expect(body).toBe('# preview');
  });

  it('sendCommand after stop is a no-op', () => {
    const driver = new MockSsdDriver([]);
    driver.start();
    driver.stop();
    driver.sendCommand({ action: 'accept' });
    expect(driver.getCommands()).toEqual([]);
  });
});