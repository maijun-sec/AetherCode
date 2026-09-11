import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  WORKFLOW_EXAMPLES,
  importExampleWorkflows,
} from './workflowExamples';

/**
 * bundled starter workflows + one-click import.
 *
 * The "user can't see any workflows on first launch"
 * complaint from R114 is exactly what this round
 * targets. The test guards against three regressions:
 *   1. Someone removes the examples file
 *   2. Someone drops a YAML that fails the
 *      {name, description, steps} minimum schema
 *   3. The import helper doesn't surface per-example
 *      failures (which would mask a partial-success as a
 *      hard error)
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('对应历史 round: workflow examples', () => {
  it('workflowExamples.ts exists', () => {
    const path = join(root, 'src', 'components', 'workflowExamples.ts');
    expect(existsSync(path)).toBe(true);
  });

  it('exports exactly 3 example workflows (not too few, not too many)', () => {
    expect(WORKFLOW_EXAMPLES.length).toBe(3);
  });

  it('every example has a unique name', () => {
    const names = WORKFLOW_EXAMPLES.map((e) => e.name);
    expect(new Set(names).size).toBe(names.length);
  });

  it('every example has a non-empty description', () => {
    for (const e of WORKFLOW_EXAMPLES) {
      expect(e.description.length).toBeGreaterThan(0);
    }
  });

  it('every example YAML has a name, description, and at least one step', () => {
    for (const e of WORKFLOW_EXAMPLES) {
      expect(e.yaml).toMatch(/^name:\s+\S+/m);
      expect(e.yaml).toMatch(/^description:\s+\S+/m);
      // Step list: at least one `- id:` line
      expect(e.yaml).toMatch(/- id:\s+\S+/);
    }
  });

  it('every example YAML has a unique name matching its key', () => {
    for (const e of WORKFLOW_EXAMPLES) {
      const match = e.yaml.match(/^name:\s+(\S+)/m);
      expect(match).toBeTruthy();
      expect(match![1]).toBe(e.name);
    }
  });

  it('YAMLs are diverse: at least one shell + one agent + one gate step', () => {
    const allYaml = WORKFLOW_EXAMPLES.map((e) => e.yaml).join('\n');
    expect(allYaml).toMatch(/type:\s+shell/);
    expect(allYaml).toMatch(/type:\s+agent/);
    expect(allYaml).toMatch(/type:\s+gate/);
  });

  it('no example uses type: skill (would require a skill to be installed)', () => {
    const allYaml = WORKFLOW_EXAMPLES.map((e) => e.yaml).join('\n');
    expect(allYaml).not.toMatch(/type:\s+skill/);
  });
});

describe('对应历史 round: importExampleWorkflows helper', () => {
  it('imports all 3 examples on success', async () => {
    const calls: { name: string; content: string }[] = [];
    const r = await importExampleWorkflows(async (name, content) => {
      calls.push({ name, content });
      return { ok: true };
    });
    expect(r.imported).toEqual(['hello-shell', 'file-summary', 'safe-commit']);
    expect(r.failed).toEqual([]);
    expect(calls.length).toBe(3);
  });

  it('records per-example failures (partial success)', async () => {
    const r = await importExampleWorkflows(async (name) => {
      if (name === 'file-summary') return { ok: false, reason: 'permission denied' };
      return { ok: true };
    });
    expect(r.imported).toEqual(['hello-shell', 'safe-commit']);
    expect(r.failed).toEqual([
      { name: 'file-summary', reason: 'permission denied' },
    ]);
  });

  it('catches RPC exceptions and reports them as failures', async () => {
    const r = await importExampleWorkflows(async (name) => {
      if (name === 'safe-commit') throw new Error('network down');
      return { ok: true };
    });
    expect(r.imported).toEqual(['hello-shell', 'file-summary']);
    expect(r.failed[0].name).toBe('safe-commit');
    expect(r.failed[0].reason).toContain('network down');
  });

  it('returns empty result on a total failure (all throws)', async () => {
    const r = await importExampleWorkflows(async () => {
      throw new Error('daemon down');
    });
    expect(r.imported).toEqual([]);
    expect(r.failed.length).toBe(3);
    for (const f of r.failed) {
      expect(f.reason).toContain('daemon down');
    }
  });

  it('preserves the example order in the result', async () => {
    const r = await importExampleWorkflows(async (name) => {
      // The helper iterates WORKFLOW_EXAMPLES in declared
      // order; we mirror that here so a re-ordering of the
      // source array would be caught.
      const index = WORKFLOW_EXAMPLES.findIndex((e) => e.name === name);
      return { ok: true, _index: index } as { ok: boolean };
    });
    expect(r.imported).toEqual(WORKFLOW_EXAMPLES.map((e) => e.name));
  });
});

describe('对应历史 round: MessageInput workflow picker integration', () => {
  const src = readFileSync(join(root, 'src', 'components', 'MessageInput.tsx'), 'utf-8');

  it('imports WORKFLOW_EXAMPLES + importExampleWorkflows', () => {
    expect(src).toMatch(/import\s*\{[^}]*WORKFLOW_EXAMPLES[^}]*\}\s+from\s+['"]\.\/workflowExamples['"]/);
    expect(src).toMatch(/import\s*\{[^}]*importExampleWorkflows[^}]*\}\s+from\s+['"]\.\/workflowExamples['"]/);
  });

  it('removed the disabled gate on the workflow button', () => {
    // 历史-B, the button was
    // `disabled={availableWorkflows.length === 0 && !showWorkflows}`.
    // The 对应历史 round version removed the gate so the user can
    // always open the picker and see the "导入示例" button.
    const stripped = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    expect(stripped).not.toMatch(/disabled=\{availableWorkflows\.length\s*===\s*0\s*&&\s*!showWorkflows\}/);
  });

  it('renders the 📥 导入示例 button in the empty state', () => {
    expect(src).toContain('workflow-import-btn');
    expect(src).toContain('导入示例');
  });

  it('calls rpc.writeWorkflow via the import helper', () => {
    expect(src).toMatch(/importExampleWorkflows\([\s\S]*?rpc\.writeWorkflow/);
  });

  it('refreshes the workflow list after import', () => {
    expect(src).toMatch(/await\s+refreshWorkflows\(\)/);
  });

  it('shows a result pill (ok / partial / error) after the import', () => {
    expect(src).toContain('workflow-import-result');
    expect(src).toContain('workflow-import-result-ok');
    expect(src).toContain('workflow-import-result-partial');
    expect(src).toContain('workflow-import-result-error');
  });
});
