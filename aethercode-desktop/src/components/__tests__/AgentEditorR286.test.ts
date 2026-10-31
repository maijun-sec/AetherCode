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

// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R286: AgentEditor variant dropdown.
 *
 * <p>The editor's frontmatter form now has a
 * fourth field — Quality preset — alongside
 * description / displayName / model. Tests
 * pin:
 * <ol>
 *   <li>the dropdown lists the four bundled
 *       presets (low / medium / high / xhigh)
 *       plus an "(inherit)" empty option;</li>
 *   <li>the editor sends the variant through to
 *       the createAgent / updateAgent RPCs;</li>
 *   <li>the AgentsPanel prefills the variant
 *       from getAgentBody's response so edit-mode
 *       shows the agent's current quality.</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();
const editorSrc = readFileSync(join(root, 'components', 'AgentEditor.tsx'), 'utf-8');
const agentsPanelSrc = readFileSync(join(root, 'components', 'AgentsPanel.tsx'), 'utf-8');
const methodsSrc = readFileSync(join(root, 'lib', 'methods.ts'), 'utf-8');

describe('R286: AgentEditor variant dropdown', () => {
  it('declares a variant local state', () => {
    expect(editorSrc).toMatch(/useState\(initial\?\.variant\s*\?\?\s*['"]['"]\)/);
  });

  it('lists the four bundled presets + inherit', () => {
    expect(editorSrc).toContain("value: ''");          // inherit
    expect(editorSrc).toContain("value: 'low'");
    expect(editorSrc).toContain("value: 'medium'");
    expect(editorSrc).toContain("value: 'high'");
    expect(editorSrc).toContain("value: 'xhigh'");
  });

  it('renders a data-testid for the variant dropdown', () => {
    expect(editorSrc).toMatch(/data-testid="agent-editor-variant"/);
  });

  it('sends the variant in the createAgent / updateAgent payload', () => {
    // The onSave handler builds an `opts`
    // object that the store passes
    // through to rpc.createAgent /
    // rpc.updateAgent. The variant field
    // must travel alongside model +
    // description.
    expect(editorSrc).toMatch(/variant:\s*variant\.trim\(\)/);
  });
});

describe('R286: AgentEditor initial props include variant', () => {
  it('declares variant on the initial prop shape', () => {
    expect(editorSrc).toMatch(/initial\?\s*:\s*\{[\s\S]*?variant:\s*string/);
  });
});

describe('R286: AgentsPanel prefills variant on edit', () => {
  it('passes r.variant to the initial prop', () => {
    // The AgentEditorLazy wrapper fetches
    // the body on edit and passes
    // description / displayName / model
    // / variant. Without the variant
    // here the editor would always start
    // on "(inherit)" and silently drop
    // the agent's quality binding.
    expect(agentsPanelSrc).toMatch(/variant:\s*r\.variant\s*\?\?\s*['"]['"]/);
  });

  it('seeds the create-mode initial with an empty variant', () => {
    expect(agentsPanelSrc).toMatch(/setInitial\(\{[\s\S]*?variant:\s*['"]['"]/);
  });
});

describe('R286: lib/methods.ts createAgent / updateAgent accept variant', () => {
  it('createAgent declares variant on its opts', () => {
    expect(methodsSrc).toMatch(/createAgent\(opts:\s*\{[\s\S]*?variant\?:\s*string/);
  });

  it('updateAgent declares variant on its opts', () => {
    expect(methodsSrc).toMatch(/updateAgent\(opts:\s*\{[\s\S]*?variant\?:\s*string/);
  });

  it('getAgentBody response shape includes variant', () => {
    expect(methodsSrc).toMatch(/getAgentBody\(name:\s*string\)\s*:\s*Promise<\{[\s\S]*?variant\?:\s*string/);
  });
});