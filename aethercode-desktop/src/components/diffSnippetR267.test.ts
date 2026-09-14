import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R267 desktop polish (2026-09-14): snippet-style diff
 * rendering for file_edit tool calls. The user wanted
 * `git diff -U2`-style: only show actually-changed lines
 * (`+` / `-`), collapse long runs of unchanged context (` `)
 * into `··· N unchanged lines ···`, cap total visible lines.
 *
 * <p>Previously the renderer dumped the tool's
 * "edited /path/to/file (1 replacement)" output into a
 * fenced ```diff block, which the user correctly flagged
 * as a fake diff.
 *
 * <p>Source-pin tests for the four pieces:
 * <ol>
 *   <li>DiffSegment type exported from MessageList</li>
 *   <li>summarizeDiff() helper exported from MessageList</li>
 *   <li>buildEditSnippetFromInput() builds a unified diff
 *       from file_edit's raw input</li>
 *   <li>BlockView's file_edit branch calls DiffSnippetView
 *       (a real React component, not a markdown fence)</li>
 * </ol>
 * A regression in any of these would re-introduce the
 * "fake diff" bug even if the chat still looks fine in a
 * smoke test.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R267: snippet-style diff rendering for file_edit', () => {
  it('MessageList exports the DiffSegment type', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // The discriminated union — meta / add / del / ctx / elided
    // — is what the renderer keys off to pick colours. Without
    // the type the whole feature collapses to a single
    // un-coloured blob.
    expect(
      src,
      'DiffSegment type must be exported from MessageList.tsx',
    ).toMatch(/export\s+type\s+DiffSegment/);
    expect(
      src,
      'DiffSegment must include the elided variant for collapsed ctx',
    ).toMatch(/kind:\s*['"]elided['"][\s\S]*?count:\s*number/);
  });

  it('summarizeDiff helper collapses long ctx runs into elided segments', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // summariseDiff must (a) collapse >4 consecutive ctx
    // lines to a single elided segment and (b) cap the
    // total to ~50 segments. A regression that drops
    // either check would re-introduce the user complaint
    // ("I see the whole file dumped in the chat").
    expect(
      src,
      'summarizeDiff must export from MessageList',
    ).toMatch(/export\s+function\s+summarizeDiff\s*\(/);
    expect(
      src,
      'summarizeDiff must have a ctx-collapse threshold constant',
    ).toMatch(/DIFF_CTX_COLLAPSE_THRESHOLD\s*=\s*4/);
    expect(
      src,
      'summarizeDiff must have a max-lines cap constant',
    ).toMatch(/DIFF_MAX_LINES\s*=\s*50/);
    expect(
      src,
      'summarizeDiff must push elided segments for long ctx runs',
    ).toMatch(/kind:\s*['"]elided['"][\s\S]*?count:\s*runLength\s*-\s*2\s*\*\s*keep/);
  });

  it('buildEditSnippetFromInput derives a unified diff from raw tool input', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // The renderer reads ev.input (the raw tool_use_start
    // input) and reconstructs the diff client-side. The
    // protocol does not forward attachments through
    // tool_result, so this is the only place the desktop
    // can see the before/after text.
    expect(
      src,
      'buildEditSnippetFromInput must be exported from MessageList',
    ).toMatch(/export\s+function\s+buildEditSnippetFromInput\s*\(/);
    expect(
      src,
      'buildEditSnippetFromInput must read old_string + new_string + file_path',
    ).toMatch(/obj\.old_string/);
    expect(
      src,
      'buildEditSnippetFromInput must produce unified-diff @@ hunk header',
    ).toMatch(/@@\s*-1,.*\+1,/);
    expect(
      src,
      'buildEditSnippetFromInput must prefix old lines with -',
    ).toMatch(/oldLines\.map/);
    expect(
      src,
      'buildEditSnippetFromInput must use - template literal',
    ).toMatch(/`-\$\{l\}`/);
    expect(
      src,
      'buildEditSnippetFromInput must prefix new lines with +',
    ).toMatch(/newLines\.map/);
    expect(
      src,
      'buildEditSnippetFromInput must use + template literal',
    ).toMatch(/`\+\$\{l\}`/);
  });

  it('BlockView uses DiffSnippetView for file_edit (not a markdown fence)', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // The file_edit branch in BlockView must call
    // buildEditSnippetFromInput and render via
    // DiffSnippetView — a real React component with
    // +/- colour coding. The historical code path was:
    //   body = trimmed ? `\`\`\`diff\n${trimmed}\n\`\`\`` : '';
    // which produced a fake diff (the tool's output is
    // literally "edited /path (1 replacement)"). The
    // source-pin catches any regression that re-introduces
    // that markdown fence.
    expect(
      src,
      'BlockView file_edit branch must call buildEditSnippetFromInput',
    ).toMatch(/buildEditSnippetFromInput\s*\(\s*ev\.input\s*\)/);
    expect(
      src,
      'BlockView file_edit branch must render the snippet via DiffSnippetView',
    ).toMatch(/diffNode\s*=\s*<DiffSnippetView/);
    // The OLD markdown fence for file_edit must be gone.
    expect(
      src,
      'BlockView must NOT wrap file_edit output in a ```diff markdown fence',
    ).not.toMatch(/file_edit['"][\s\S]{0,200}```diff/);
  });

  it('MessageList.css ships the diff-snippet styling', () => {
    const css = readSrc('src/components/MessageList.css');
    // The CSS rules needed by DiffSnippetView:
    //  - .diff-snippet (container)
    //  - .diff-snippet-line (per-row)
    //  - .diff-snippet-add / -del / -ctx / -meta / -elided
    // Without these the rendered React tree has no colour
    // and the snippet looks like raw monospace text — the
    // user would say "the diff is invisible".
    expect(css, 'CSS must define .diff-snippet container').toMatch(/\.diff-snippet\s*\{/);
    expect(css, 'CSS must define .diff-snippet-add for + lines').toMatch(/\.diff-snippet-add/);
    expect(css, 'CSS must define .diff-snippet-del for - lines').toMatch(/\.diff-snippet-del/);
    expect(css, 'CSS must define .diff-snippet-ctx for context').toMatch(/\.diff-snippet-ctx/);
    expect(css, 'CSS must define .diff-snippet-elided for collapsed rows').toMatch(/\.diff-snippet-elided/);
  });
});