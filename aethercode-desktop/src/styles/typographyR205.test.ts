import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * afterward user feedback — the global font sizes
 * were IDE-scale (13px body / 12px button / 12px input)
 * and the line-heights (1.5 / 1.6 / 1.65) gave the prose
 * no vertical breathing room. The user said it was
 * "抠抠搜搜" (stingy / cramped) and asked for the body
 * to be a comfortable "naked-eye" reading size.
 *
 * <p>R205 ships the scale-up at three layers:
 * <ol>
 *   <li>Global body / button / input (styles/global.css)
 *       — bumped to 15 / 14 / 14 px with line-height 1.7</li>
 *   <li>Main chat reading surfaces
 *       (MessageList.css .message-content / .step-text /
 *       .message-assistant .md-body) — 16 / 15.5 / 16 px
 *       with line-height 1.75 / 1.7 / 1.8</li>
 *   <li>Source-pin tests (this file) — catch a future
 *       refactor that accidentally shrinks the scale back
 *       to the IDE-style 13/12 px</li>
 * </ol>
 *
 * <p>The "metadata" sizes (timestamps, tokens, monospace
 * tool inputs) are intentionally left at their smaller
 * scale — those are not the primary reading surface.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R205: global.css — body / button / input scale-up', () => {
  const globalSrc = readFileSync(join(root, 'src', 'styles', 'global.css'), 'utf-8');

  it('body font-size is 15px (was 13px 历史)', () => {
    // 15px is the "naked-eye" reading size. Anything
    // below 14px at full-window reading distance
    // forces the user to lean in. 16px is the
    // formal-typography pick for prose but 15px
    // matches the panel's chrome (badges, tabs,
    // dropdown labels) so the page reads as one
    // scale.
    expect(globalSrc).toMatch(/body\s*\{[\s\S]*?font-size:\s*15px/);
  });

  it('body line-height is 1.7 (was 1.5 历史)', () => {
    // 1.5 was IDE-density. 1.7 is the prose-text
    // sweet spot (same as Substack, Medium, GitHub
    // markdown body). 1.8 would feel too airy for
    // a chat panel; 1.6 still runs long paragraphs
    // together. 1.7 is the middle.
    expect(globalSrc).toMatch(/body\s*\{[\s\S]*?line-height:\s*1\.7/);
  });

  it('button font-size is 14px (was 12px 历史)', () => {
    // Buttons are the most-clicked chrome. 12px
    // forced the user to squint; 14px matches the
    // input controls and keeps the chrome at one
    // scale.
    expect(globalSrc).toMatch(/button\s*\{[\s\S]*?font-size:\s*14px/);
  });

  it('button padding is 8px 16px (was 6px 12px 历史)', () => {
    // Click-target grew in lockstep with the font
    // so a button stays comfortable to hit. The
    // 32px-touch-target guideline needs the
    // padding + the line-height — combined, this
    // puts buttons well over 40px tall.
    expect(globalSrc).toMatch(/button\s*\{[\s\S]*?padding:\s*8px 16px/);
  });

  it('input/textarea/select font-size is 14px (was 12px 历史)', () => {
    // The cwd picker, perm mode, model picker, and
    // the message input box are the user's most
    // frequent input surface. 12px was barely
    // readable on a 4K monitor; 14px matches the
    // button and keeps the panel at one scale.
    expect(globalSrc).toMatch(/input,\s*textarea,\s*select\s*\{[\s\S]*?font-size:\s*14px/);
  });

  it('input/textarea/select padding is 8px 12px (was 6px 10px 历史)', () => {
    // Same rationale as button padding: the click
    // target grew proportionally.
    expect(globalSrc).toMatch(/input,\s*textarea,\s*select\s*\{[\s\S]*?padding:\s*8px 12px/);
  });
});

describe('R205: MessageList.css — main chat reading surfaces', () => {
  const mlSrc = readFileSync(join(root, 'src', 'components', 'MessageList.css'), 'utf-8');

  it('.message-content font-size is 16px (was 14px 历史)', () => {
    // The main chat body — the user's primary
    // reading surface. 16px matches the formal
    // prose-text size and gives long markdown
    // answers room to breathe.
    expect(mlSrc).toMatch(/\.message-content\s*\{[\s\S]*?font-size:\s*16px/);
  });

  it('.message-content line-height is 1.75 (was 1.65 历史)', () => {
    // 1.75 keeps long paragraphs from running
    // together without feeling like a children's
    // book.
    expect(mlSrc).toMatch(/\.message-content\s*\{[\s\S]*?line-height:\s*1\.75/);
  });

  it('.step-text font-size is 15.5px (was 13.5px 历史)', () => {
    // .step-text is the model's "thinking" stream.
    // The user reads it more often than the final
    // answer, so it gets near-prose size.
    expect(mlSrc).toMatch(/\.step-text\s*\{[\s\S]*?font-size:\s*15\.5px/);
  });

  it('.step-text line-height is 1.7 (was 1.6 历史)', () => {
    expect(mlSrc).toMatch(/\.step-text\s*\{[\s\S]*?line-height:\s*1\.7/);
  });

  it('.message-assistant .md-body font-size is 16px (was 14.5px 历史)', () => {
    // The legacy assistant message bubble. Same
    // scale as .message-content — the two
    // surfaces are functionally equivalent from
    // a reading standpoint.
    expect(mlSrc).toMatch(/\.message-assistant \.md-body\s*\{[\s\S]*?font-size:\s*16px/);
  });

  it('.message-assistant .md-body line-height is 1.8 (was 1.7 历史)', () => {
    // The legacy bubble gets 1.8 instead of 1.75
    // because the markdown body can carry long
    // bullet lists and code blocks where the
    // extra space reads as intentional.
    expect(mlSrc).toMatch(/\.message-assistant \.md-body\s*\{[\s\S]*?line-height:\s*1\.8/);
  });
});

describe('R205: the scale-up is documented at each touchpoint (R205 comment)', () => {
  // Pin the R205 attribution comment so a future
  // refactor that strips the comment is caught. The
  // comment is the audit trail for "why is the font
  // 15px" — the next person to touch this file
  // should know the size is intentional, not a
  // typo. A "R205" mention in the comment is the
  // source-pin.
  const globalSrc = readFileSync(join(root, 'src', 'styles', 'global.css'), 'utf-8');
  const mlSrc = readFileSync(join(root, 'src', 'components', 'MessageList.css'), 'utf-8');

  it('global.css carries an R205 attribution comment for the body scale-up', () => {
    expect(globalSrc).toContain('R205');
  });

  it('MessageList.css carries an R205 attribution comment for the chat scale-up', () => {
    expect(mlSrc).toContain('R205');
  });
});
