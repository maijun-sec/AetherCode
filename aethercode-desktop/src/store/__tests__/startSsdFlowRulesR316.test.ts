// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R317 — startSsdFlow + sendSsdCommand build a per-phase prompt
 * that the agent loads the sdd skill from. The prompt must:
 *   1. Tag the chat message as `[sdd-task: <slug>, phase: <N>, action: <run|modify|skip>]`
 *      so the agent knows which phase to run.
 *   2. Reference the per-phase reference file (e.g.
 *      `phase-1-constitution.md`) so the agent loads the right
 *      instructions.
 *   3. List the phase's input files explicitly — the agent
 *      must read these via read_file, not infer from memory.
 *   4. Name the single output file the phase must write,
 *      under `<cwd>/.aethercode/sdd/<slug>/` (lowercase, kebab).
 *   5. Forbid writing SPEC.md / DESIGN.md / pom.xml in cwd root.
 *   6. Mandate HARD PAUSE after each phase.
 *   7. Embed the user's original intent verbatim.
 *
 * This test reads the desktop store source and asserts the
 * buildPhasePrompt() helper satisfies (1)-(7). If a future
 * round relaxes the prompt (e.g. lets the agent self-pick
 * the next phase), this test fails immediately.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const STORE_TS = join(process.cwd(), 'src', 'store', 'index.ts');
const source = readFileSync(STORE_TS, 'utf8');

describe('startSsdFlow — R317 per-phase prompt contract', () => {
  it('tags the prompt with [sdd-task: <slug>, phase: <N>, action: <run|modify|skip>]', () => {
    expect(source).toMatch(/\[sdd-task:\s*\$\{[^}]+\},\s*phase:\s*\$\{[^}]+\},\s*action:\s*\$\{[^}]+\}\]/);
  });

  it('instructs the agent to load the per-phase reference file', () => {
    expect(source).toMatch(/references\/phase-\$\{[^}]+\}-\$\{[^}]+\}\.md/);
  });

  it('lists the phase input files explicitly', () => {
    // inputFilesForPhase builds the list; buildPhasePrompt
    // formats it as `${i + 1}. \`${f}\`` — the prompt source
    // has to call inputFilesForPhase and embed it.
    expect(source).toMatch(/inputFilesForPhase/);
    expect(source).toMatch(/inputsBlock/);
  });

  it('mandates a single output file under .aethercode/sdd/<slug>/', () => {
    expect(source).toMatch(/\$\{dir\}\/\$\{outputFile\}/);
  });

  it('pins the correct lowercase output filenames + strict path', () => {
    // The buildPhasePrompt helper produces the canonical
    // lowercase filename list. If a future refactor adds
    // uppercase variants (e.g. SPEC.md) the helpers would
    // catch it; this source-pin catches it earlier.
    expect(source).toMatch(/case 'specify':\s+return 'spec\.md';/);
    expect(source).toMatch(/case 'plan':\s+return 'design\.md';/);
    expect(source).toMatch(/constitution\.md/);
    expect(source).toMatch(/dev\.log/);
    expect(source).toMatch(/convergence\.json/);
    // The strict-path wording in the prompt.
    expect(source).toMatch(/绝对禁止写到其他路径/);
    expect(source).toMatch(/\.aethercode\/sdd\/<slug>\//);
  });

  it('mandates HARD PAUSE — agent must not advance on its own', () => {
    expect(source).toMatch(/HARD PAUSE/);
    expect(source).toMatch(/Do NOT advance/i);
  });

  it('embeds the original user intent', () => {
    expect(source).toMatch(/Original user intent/);
    expect(source).toMatch(/\$\{[^}]+\.intent\}/);
  });

  it('phase 1 has no input files (entry phase)', () => {
    expect(source).toMatch(/no input files — this is the entry phase/);
  });

  it('phase N reads prior phase outputs', () => {
    // For each phase i in 1..N, the prompt references
    // `${dir}/${fname}` for prior outputs.
    expect(source).toMatch(/for \(let i = 1; i < n; i\+\+\)/);
  });
});