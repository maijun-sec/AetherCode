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

import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 5 (T-5-12 + T-5-13): WorkflowEditorModal + WorkflowPicker (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 5 / T-5-12: WorkflowEditorModal (live preview)', () => {
  it('WorkflowEditorModal.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/WorkflowEditorModal.tsx'))).toBe(true);
  });

  it('exports the editor component', () => {
    const src = read('src/components/WorkflowEditorModal.tsx');
    expect(src).toMatch(/export\s+function\s+WorkflowEditorModal\b/);
  });

  it('renders a live-preview pane (workflow-editor-field-preview)', () => {
    const src = read('src/components/WorkflowEditorModal.tsx');
    expect(src).toMatch(/workflow-editor-field-preview/);
    expect(src).toMatch(/workflow-editor-preview/);
  });

  it('live preview surfaces name + description + version + step count', () => {
    const src = read('src/components/WorkflowEditorModal.tsx');
    expect(src).toMatch(/workflow-editor-preview-key/);
    expect(src).toMatch(/workflow-editor-preview-val/);
    expect(src).toMatch(/extracted\.name/);
    expect(src).toMatch(/extracted\.description/);
    expect(src).toMatch(/extracted\.version/);
    expect(src).toMatch(/extracted\.steps\.length/);
  });

  it('saves via workflow/upsert RPC', () => {
    const src = read('src/components/WorkflowEditorModal.tsx');
    expect(src).toMatch(/rpc\.writeWorkflow/);
  });
});

describe('Phase 5 / T-5-13: WorkflowPicker', () => {
  it('WorkflowPicker.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/workflows/WorkflowPicker.tsx'))).toBe(true);
  });

  it('exports WorkflowPicker component', () => {
    const src = read('src/components/workflows/WorkflowPicker.tsx');
    expect(src).toMatch(/export\s+function\s+WorkflowPicker\b/);
  });

  it('uses useWorkflowList for the source list', () => {
    const src = read('src/components/workflows/WorkflowPicker.tsx');
    expect(src).toMatch(/useWorkflowList/);
  });

  it('calls workflow/run on Run button click', () => {
    const src = read('src/components/workflows/WorkflowPicker.tsx');
    expect(src).toMatch(/workflow\/run/);
  });

  it('renders a search box + builtins-only filter', () => {
    const src = read('src/components/workflows/WorkflowPicker.tsx');
    expect(src).toMatch(/workflow-picker-search/);
    expect(src).toMatch(/builtinsOnly/);
  });

  it('surfaces the inputs for the selected workflow', () => {
    const src = read('src/components/workflows/WorkflowPicker.tsx');
    expect(src).toMatch(/workflow-picker-inputs/);
    expect(src).toMatch(/selected\.inputs/);
  });

  it('renders nothing when closed', () => {
    const src = read('src/components/workflows/WorkflowPicker.tsx');
    expect(src).toMatch(/if\s*\(\s*!open\s*\)\s*return\s*null/);
  });
});
