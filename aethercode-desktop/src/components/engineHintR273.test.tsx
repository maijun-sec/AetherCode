// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R273 (2026-09-16) — the daemon's loop-guard bumps the chat
 * transcript with a `role: user` message whose content begins
 * with the literal `[Engine]` prefix. The LLM API only accepts
 * user/assistant roles, so the daemon can't tag it as `system`
 * without breaking the LLM round-trip; in the transcript, the
 * message looks exactly like a user prompt.
 *
 * Before R273, the desktop rendered these in the same blue
 * right-aligned user bubble as a real "YOU" message (and the
 * user reported "我没有发过，请确认这个消息怎么来的 — 倒数第二个
 * 的 todo item 我已经见过好几次了"). R273 detects the prefix at
 * render time and renders a small muted engine-hint pill
 * instead — no YOU label, no user-bubble styling, summarisable
 * via <details>.
 *
 * Source-pin tests (no DOM):
 *   - MessageList.LegacyMessage contains an isEnginePromptUserMessage check
 *   - MessageList LACKS the "you" role label for engine-authored prompts
 *   - CSS class .message-engine-hint exists
 *
 * Behaviour tests: covered indirectly by LiveMessageList
 * integration (smoke). This file's behaviour surface is "the
 * prefix check is correct" — pure logic, easy to unit test.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

// Pure helper extracted to mirror the LiveMessageList body so
// behaviour tests don't need the full DOM.
function isEnginePromptUserMessage(content: string): boolean {
  if (!content) return false;
  const trimmed = content.trimStart();
  return trimmed.startsWith('[Engine]');
}

describe('R273: engine-authored pseudo-user detection', () => {
  describe('source-pin', () => {
    it('MessageList defines an isEnginePromptUserMessage helper', () => {
      const src = readSrc('src/components/MessageList.tsx');
      expect(src).toMatch(/function\s+isEnginePromptUserMessage\s*\(\s*content\s*:\s*string\s*\)/);
      expect(src).toMatch(/startsWith\(['"]\[Engine\]['"]\)/);
    });

    it('MessageList CSS class .message-engine-hint exists with engine styling', () => {
      const css = readSrc('src/components/MessageList.css');
      expect(css).toMatch(/\.message-engine-hint\b/);
      expect(css).toMatch(/\.message-engine-hint-label\b/);
      expect(css).toMatch(/b48ead/);   // purple/lavender accent
    });

    it('LegacyMessage engine branch shows [引擎] label, NOT "you"', () => {
      const src = readSrc('src/components/MessageList.tsx');
      // The engine branch must include the [引擎] label and must
      // NOT emit the .message-user class with a "you" role tag.
      const engineBlock = src.match(/isEnginePromptUserMessage[\s\S]{0,800}?<\/details>/);
      expect(engineBlock, 'LegacyMessage engine-branch <details> block must exist').not.toBeNull();
      expect(engineBlock![0]).toMatch(/引擎/);
      expect(engineBlock![0]).not.toMatch(/message-user/);
    });
  });

  describe('behaviour', () => {
    it('detects the loop-guard bump style: "[Engine] The current in-progress todo item..."', () => {
      const bump = '[Engine] The current in-progress todo item\n\n  > Run mvn test to verify everything passes\n\nhas now taken 16 tool-call / turn steps\n(previous soft threshold was 15, bumped to 30).\n\nDecide what to do — pick ONE and act on it this turn:\n(A) Mark this todo complete (or move on) if it\'s actually done.';
      expect(isEnginePromptUserMessage(bump)).toBe(true);
    });

    it('does NOT flag a normal user prompt as engine-authored', () => {
      const userPrompt = '在当前目录下，生成一个 java maven 项目，至少支持5种排序算法，支持 int、short、long 三类数组，UT完整';
      expect(isEnginePromptUserMessage(userPrompt)).toBe(false);
    });

    it('does NOT flag a tiny "ping" prompt as engine-authored', () => {
      expect(isEnginePromptUserMessage('ping')).toBe(false);
    });

    it('tolerates leading whitespace before the [Engine] marker (rare but possible)', () => {
      expect(isEnginePromptUserMessage('   [Engine] something')).toBe(true);
      expect(isEnginePromptUserMessage('\n[Engine] something')).toBe(true);
    });

    it('returns false for empty / whitespace-only content', () => {
      expect(isEnginePromptUserMessage('')).toBe(false);
      expect(isEnginePromptUserMessage('   ')).toBe(false);
      expect(isEnginePromptUserMessage('\n\n')).toBe(false);
    });

    it('is case-sensitive ([engine] lowercase should NOT match)', () => {
      // intentional: the daemon uses the literal `[Engine]`
      // prefix; we don't want to match unrelated engine tooling
      // words. If the daemon ever changes its marker, this test
      // will fail and force a synchronous update.
      expect(isEnginePromptUserMessage('[engine] something')).toBe(false);
    });
  });
});
