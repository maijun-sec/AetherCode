import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * source-pin tests for the bash-tool streamed-output
 * plumbing. Previously, the BashTool called {@code ctx.emit(...)}
 * per stdout/stderr line but the engine's messageSink was
 * wired to {@code null} in AetherCodeEngine, so the lines
 * were silently dropped. The user described the symptom as
 * "the mvn command takes a bit long to run" — the runtime was fine, the
 * perception was that nothing was happening during a slow
 * bash tool call.
 *
 * <p>The R193 fix:
 * <ol>
 *   <li>QueryEngine forwards each line as a new
 *       {@code StreamEvent.ToolOutputDelta}.</li>
 *   <li>AetherCodeMethods' eventToMap serialises it as
 *       {@code {type: "tool_output_delta", id, text}}.</li>
 *   <li>The desktop store's {@code rpc.on('stream_event', ...)}
 *       handler routes it to {@code appendToolOutput(id, text)}
 *       which stream-appends the text to the matching tool
 *       event's {@code output} field.</li>
 *   <li>MessageList's auto-scroll useEffect depends on the
 *       {@code steps} array reference (not just
 *       {@code steps.length}) so the scroll-to-bottom fires
 *       on every chunk as the tool's output grows.</li>
 *   <li>The .step-card CSS uses {@code width: 100%} (not
 *       {@code fit-content; max-width: 760px}) so the body
 *       fills the parent .subtask-card / .preparing-body
 *       and the user can read the full streamed output.</li>
 *   <li>Each completed step renders a one-line
 *       {@code .step-summary} at the bottom of the body,
 *       showing "🧠 思考 X 次, ⚡ 执行 Y 条命令, ⏱ Ns" (the R193 footer).</li>
 * </ol>
 *
 * <p>These source-pin tests catch any future refactor that
 * drops one of the six pieces. A regression that, say, moves
 * the auto-scroll dep back to {@code steps.length} would
 * fail the corresponding test even if the chat still looks
 * "fine" in a smoke test (the user has to wait for the
 * bash tool to finish, the scroll never follows the
 * streamed output).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R193: tool_output_delta plumbing', () => {
  it('desktop store must handle tool_output_delta events', () => {
    const src = readSrc('src/store/index.ts');
    // The stream_event switch should have a `case 'tool_output_delta'`
    // that calls appendToolOutput with the (id, text) pair. legacy
    // the switch had no such case, so the line fell through and was
    // silently dropped — making `mvn --version` feel like it took
    // forever (the user saw a blank card for the whole tool run).
    expect(
      src,
      'stream_event switch must have a tool_output_delta case — ' +
        'otherwise BashTool\'s streamed output is silently dropped',
    ).toMatch(/case\s+['"]tool_output_delta['"]\s*:/);
    expect(
      src,
      'tool_output_delta handler must route to appendToolOutput',
    ).toMatch(/appendToolOutput\s*\(\s*toolId\s*,\s*text\s*\)/);
  });

  it('desktop store must expose appendToolOutput action', () => {
    const src = readSrc('src/store/index.ts');
    // The AppState type must declare the new action. Without
    // this declaration the TypeScript compiler errors out, but
    // the source-pin catches a future refactor that deletes
    // it without noticing.
    expect(
      src,
      'AppState must declare appendToolOutput',
    ).toMatch(/appendToolOutput\s*:\s*\(id:\s*string,\s*text:\s*string\)\s*=>\s*void/);
    // The implementation must mutate toolEvents with a functional
    // set() that handles the "first chunk" case (output == null)
    // and the "subsequent chunk" case (output != null, append).
    expect(
      src,
      'appendToolOutput must handle the first chunk (output == null) ' +
        'and subsequent chunks (output += text) without dropping data',
    ).toMatch(/te\.output\s*==\s*null/);
    expect(
      src,
      'appendToolOutput must append to existing output (not REPLACE it)',
    ).toMatch(/te\.output\s*\+\s*sep\s*\+\s*text/);
  });

  it('MessageList auto-scroll must follow tool output growth', () => {
    const src = readSrc('src/components/MessageList.tsx');
    // The auto-scroll useEffect deps must include the `steps`
    // array reference, not just `steps.length`. With only
    // `steps.length` in deps, the effect doesn't re-run while
    // a tool is streaming (the array length is unchanged even
    // though the per-tool `output` field grows). Previously the
    // scroll never followed the streamed output and the user
    // had to manually scroll every few seconds.
    const useEffect = src.match(
      /\}\s*,\s*\[messages,\s*isStreaming,\s*steps[\s\S]*?pinned\]\s*\)/,
    );
    expect(
      useEffect,
      'auto-scroll useEffect should depend on the `steps` array reference ' +
        '(not just steps.length) so it fires on every streamed chunk',
    ).toBeTruthy();
    // It must NOT depend on steps.length alone.
    expect(
      src,
      'auto-scroll useEffect must not depend on steps.length alone ' +
        '— that misses the tool_output_delta stream events',
    ).not.toMatch(
      /\}\s*,\s*\[messages,\s*isStreaming,\s*steps\.length[\s\S]*?pinned\]\s*\)/,
    );
  });

  it('MessageList auto-scroll must use direct scrollTop, not smooth scrollIntoView', () => {
    // Prior round-up: scrollIntoView({behavior:'smooth'}) is
    // async — when many streamed chunks arrive in quick
    // succession, each smooth-scroll gets canceled by the next,
    // leaving the viewport stuck partway down. Direct
    // scrollTop assignment is synchronous and idempotent.
    // The user reported "the panel does not scroll down at all" even with the R193
    // step-array-ref fix in place — root cause was the
    // smooth-scroll behavior. This test pins the post-fix
    // shape so a future refactor that switches back to
    // scrollIntoView fails the build.
    const src = readSrc('src/components/MessageList.tsx');
    expect(
      src,
      'auto-scroll should use direct `scrollTop = scrollHeight` assignment, ' +
        'not `bottomRef.scrollIntoView({behavior:"smooth"})`',
    ).toMatch(/listRef\.current\.scrollTop\s*=\s*listRef\.current\.scrollHeight/);
    // We also need a rAF wrap so the browser has laid out the
    // new content before we measure scrollHeight. Without
    // rAF, scrollHeight reads stale and we under-scroll.
    expect(
      src,
      'auto-scroll should run inside requestAnimationFrame so the browser ' +
        'has laid out the new content before we read scrollHeight',
    ).toMatch(/requestAnimationFrame[\s\S]*?scrollHeight/m);
  });

  it('.step-card CSS must fill parent width', () => {
    const css = readSrc('src/components/MessageList.css');
    // The previous rule was `width: fit-content; max-width: 760px;`
    // which forced the card to hug its content and never fill the
    // parent .subtask-card / .preparing-body. The user complained:
    // "I can't see all the content inside, and the frame is a bit narrow — it should fill the available width".
    // We pin the post-fix shape: width: 100% (or 100% via fill
    // shorthand) with no fixed max-width cap that would clip the
    // card to a centred 760px column.
    const stepCard = css.match(/\.step-card\s*\{[\s\S]*?\n\}/);
    expect(stepCard, '.step-card block must exist').toBeTruthy();
    // Strip /* ... */ comments so the regex doesn't match the
    // explanatory comment that quotes the previous shape (the
    // R193 rationale block contains "max-width: 760px" verbatim).
    const body = stepCard![0].replace(/\/\*[\s\S]*?\*\//g, '');
    expect(
      body,
      '.step-card should fill the parent (width: 100% / 100% / fill) ' +
        'instead of hugging its content',
    ).toMatch(/width:\s*100%/);
    expect(
      body,
      '.step-card should not be capped at 760px — that was the root ' +
        'cause of the "narrow box, can\'t see content" complaint',
    ).not.toMatch(/max-width:\s*760px/);
  });

  it('agent text + tool events are rendered as a single markdown document', () => {
    // prior round: the agent's output is a single
    // markdown document. The user's R193 feedback said
    // "every step ends with a summary line" — in the new
    // architecture, the summary is the markdown doc itself
    // (the text + the tool events as code blocks). We pin
    // both:
    //   (a) a `stepsToMarkdown` helper that converts
    //       {text, toolEvents} → markdown, and
    //   (b) an `AgentMarkdownMessage` component that
    //       renders the doc via react-markdown.
    const src = readSrc('src/components/MessageList.tsx');
    expect(
      src,
      'stepsToMarkdown helper must exist',
    ).toMatch(/function\s+stepsToMarkdown/);
    // the renderer no longer flattens
    // think + tool events into a single markdown
    // document. The user complained "the tool calls always
    // end up at the very bottom" — previously, react-markdown rendered the
    // whole concatenated string and tool events landed
    // at the document's tail. We now emit one
    // independently-foldable <details> per block
    // (think / tool / result) and each block is a
    // small react-markdown of its own.
    expect(
      src,
      'AgentMarkdownMessage must split output into per-block details (R193)',
    ).toMatch(/function\s+BlockView/);
    expect(
      src,
      'AgentMarkdownMessage must interleave think + tool blocks in time order (R193)',
    ).toMatch(/buildBlocks\(/);
  });

  it('latest streamed tool output is visible immediately (no click-to-expand)', () => {
    // prior round-up: the user complained that the streamed
    // mvn output wasn't visible during the run. prior round
    // fixed this by inlining tool output as code blocks in
    // the agent's markdown document — no more ToolEventPill
    // to click. The streamed output lands in the tool's
    // `output` field and toolEventToMarkdown renders it as
    // a fenced code block, so the user always sees the
    // latest text without interaction.
    const src = readSrc('src/components/MessageList.tsx');
    // Pin: toolEventToMarkdown function exists and emits
    // fenced code blocks. We use simple substring checks
    // because the regex on backticks gets tangled with
    // the JS escape sequences in the source.
    expect(src).toContain('function toolEventToMarkdown');
    expect(src).toContain('trimmed');
    expect(src).toContain('bash');
    // The streaming cursor is a literal ▍ at the end of
    // the live document. The source file uses the JS
    // escape sequence '\u25cd' (the editor can't reliably
    // save the raw char in Windows PowerShell I/O).
    expect(src).toContain("md += ' \\u25cd';");
  });

  it('.step-card CSS must be a flat trace item, no border or background', () => {
    // prior round-up: the user said "the style doesn't look good" and showed
    // a clean white-background example with flat trace items
    // (no border, no background fill). We strip the heavy
    // bordered-card style from .step-card so the chat reads
    // as a flat list of trace items. The pre-prior style had
    // a `border: 1px solid var(--chat-divider)` + a
    // `background: var(--chat-surface)` — both gone now.
    const css = readSrc('src/components/MessageList.css');
    const stepCard = css.match(/\.step-card\s*\{[\s\S]*?\n\}/);
    expect(stepCard, '.step-card block must exist').toBeTruthy();
    const body = stepCard![0].replace(/\/\*[\s\S]*?\*\//g, '');
    // The afterward-follow-up shape: border: none (or no
    // border at all in the rule). The bordered pill is
    // gone.
    expect(
      body,
      '.step-card must drop its border to match the flat-trace style',
    ).toMatch(/border:\s*none/);
    expect(
      body,
      '.step-card must drop its background fill to match the flat-trace style',
    ).toMatch(/background:\s*transparent/);
  });

  it('.step-tool CSS must be a flat single-line trace item', () => {
    // prior round-up: the .step-tool "terminal pill" had
    // a `background: var(--chat-code-bg)` + `border: 1px
    // solid var(--chat-divider)` + `border-radius: 8px`.
    // The user wanted the flat example style: a single
    // monospace line with an icon and the command inline,
    // no background fill, no border, no padding box.
    const css = readSrc('src/components/MessageList.css');
    const stepTool = css.match(/\.step-tool\s*\{[\s\S]*?\n\}/);
    expect(stepTool, '.step-tool block must exist').toBeTruthy();
    const body = stepTool![0].replace(/\/\*[\s\S]*?\*\//g, '');
    expect(
      body,
      '.step-tool must drop its border — flat trace item, not a card',
    ).toMatch(/border:\s*none/);
    expect(
      body,
      '.step-tool must drop its background — flat trace item, not a card',
    ).toMatch(/background:\s*transparent/);
  });

  it('.preparing-card and .preparing-body must NOT clip long think text', () => {
    // prior round: the user reported "the content inside the
    // tiny box is impossible to read" — the preparing card's think text
    // was being clipped by the viewport. Two root causes:
    //   (a) `.preparing-card` had `overflow: hidden` so
    //       the card itself couldn't grow tall enough to
    //       show the think text below the visible area.
    //   (b) `.preparing-body` had no max-height or
    //       overflow rule, so a 1k-char <think> block
    //       either pushed the chat panel's bottom
    //       off-screen or got clipped silently.
    //
    // Fix: `.preparing-card` becomes `overflow: visible`
    // (so the card can grow with content), and
    // `.preparing-body` gets a `max-height: 50vh` +
    // `overflow-y: auto` so the user can scroll WITHIN
    // the card when the think text is huge.
    const css = readSrc('src/components/MessageList.css');
    const card = css.match(/\.preparing-card\s*\{[\s\S]*?\n\}/);
    expect(card, '.preparing-card block must exist').toBeTruthy();
    const cardBody = card![0].replace(/\/\*[\s\S]*?\*\//g, '');
    expect(
      cardBody,
      '.preparing-card must NOT clip — overflow: hidden on the card ' +
        'silently truncates the think text below the viewport',
    ).toMatch(/overflow:\s*visible/);
    expect(
      cardBody,
      '.preparing-card must NOT have overflow: hidden — that was the ' +
        'root cause of the "tiny box, content unreadable" complaint',
    ).not.toMatch(/overflow:\s*hidden/);

    const body = css.match(/\.preparing-body\s*\{[\s\S]*?\n\}/);
    expect(body, '.preparing-body block must exist').toBeTruthy();
    const bodyText = body![0].replace(/\/\*[\s\S]*?\*\//g, '');
    expect(
      bodyText,
      '.preparing-body must have a max-height so a long think text ' +
        'does not push the entire chat panel off-screen',
    ).toMatch(/max-height:/);
    expect(
      bodyText,
      '.preparing-body must have overflow-y: auto so the user can ' +
        'scroll within the card when the think text overflows',
    ).toMatch(/overflow-y:\s*auto/);
  });
});
