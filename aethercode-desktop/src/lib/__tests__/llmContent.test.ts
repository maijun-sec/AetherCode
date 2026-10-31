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

// R713 (2026-10-18): tests for the centralized LLM content
// defense helpers in lib/llmContent.ts. These are the
// regression tests for the lesson behind R712: a 286 KB
// unfiltered tool_result pinned the WebView2 renderer to
// 100% CPU for 11 hours because nothing capped the body
// before react-markdown / remark-gfm saw it. The helpers
// are now the only entry point for untrusted LLM content;
// these tests pin their behavior so a future refactor
// can't silently re-introduce the cap-free path.

import { describe, it, expect } from 'vitest';
import {
  capForBody,
  capForInline,
  capForTitle,
  capForInput,
  titleAttr,
  HARD_MAX_BODY_CHARS,
  HARD_MAX_INLINE_CHARS,
  HARD_MAX_TITLE_CHARS,
  HARD_MAX_INPUT_CHARS,
} from '../llmContent';

describe('capForBody', () => {
  it('passes through strings under the cap', () => {
    const s = 'short think block';
    expect(capForBody(s)).toBe(s);
  });
  it('passes through strings exactly at the cap', () => {
    const s = 'a'.repeat(HARD_MAX_BODY_CHARS);
    expect(capForBody(s)).toBe(s);
  });
  it('caps strings just over the cap', () => {
    const s = 'a'.repeat(HARD_MAX_BODY_CHARS + 1);
    const out = capForBody(s, 'think');
    expect(out.startsWith('a'.repeat(HARD_MAX_BODY_CHARS))).toBe(true);
    expect(out).toContain('[...R713:');
    expect(out).toContain('think');
    expect(out).toContain('truncated from 50001');
  });
  it('caps 286 KB the same way as any other oversize input', () => {
    // The R712 root cause: a 286 KB tool_result. The test
    // verifies the cap is independent of payload size —
    // the user reported one example, the rule defends
    // against any example.
    const s = 'a'.repeat(286_000);
    const out = capForBody(s);
    expect(out.length).toBeLessThan(286_000);
    expect(out.length).toBeLessThanOrEqual(HARD_MAX_BODY_CHARS + 500);
  });
  it('handles empty string', () => {
    expect(capForBody('')).toBe('');
  });
  it('handles null-ish input safely', () => {
    // The helpers accept `string` only; this is a smoke
    // test that empty / whitespace isn't a footgun.
    expect(capForBody('   ')).toBe('   ');
  });
});

describe('capForInline', () => {
  it('uses the per-tool preview cap by default (4 KB)', () => {
    const s = 'a'.repeat(5_000);
    const out = capForInline(s);
    expect(out).toContain('...(truncated)');
    expect(out.startsWith('a'.repeat(HARD_MAX_INLINE_CHARS))).toBe(true);
  });
  it('honors a custom preview cap', () => {
    const s = 'a'.repeat(500);
    const out = capForInline(s, 100);
    expect(out).toContain('...(truncated)');
    expect(out.startsWith('a'.repeat(100))).toBe(true);
  });
  it('falls back to the hard body cap for > 50 KB', () => {
    const s = 'a'.repeat(HARD_MAX_BODY_CHARS + 10_000);
    const out = capForInline(s, 4_000, 'tool output');
    expect(out.startsWith('a'.repeat(HARD_MAX_BODY_CHARS))).toBe(true);
    expect(out).toContain('[...R713:');
    expect(out).toContain('tool output');
    // The inline preview cap should NOT also trigger
    // (otherwise the user sees two "truncated" markers).
    expect(out.match(/truncated/g)?.length).toBe(1);
  });
});

describe('capForTitle', () => {
  it('passes through short titles', () => {
    expect(capForTitle('ls -la')).toBe('ls -la');
  });
  it('caps at 200 chars by default', () => {
    const s = 'a'.repeat(500);
    const out = capForTitle(s);
    expect(out.length).toBeLessThanOrEqual(HARD_MAX_TITLE_CHARS);
  });
  it('uses ellipsis suffix when truncating', () => {
    const out = capForTitle('a'.repeat(500));
    expect(out.endsWith('…')).toBe(true);
  });
  it('honors a custom cap', () => {
    const out = capForTitle('a'.repeat(500), 50);
    expect(out.length).toBeLessThanOrEqual(50);
    expect(out.endsWith('…')).toBe(true);
  });
});

describe('capForInput', () => {
  it('passes through under 32 KB', () => {
    const s = 'old content\n' + 'a'.repeat(20_000) + '\nnew content';
    expect(capForInput(s)).toBe(s);
  });
  it('caps at 32 KB by default', () => {
    const s = 'a'.repeat(50_000);
    const out = capForInput(s, undefined, 'file_edit old_string');
    expect(out.length).toBeLessThan(50_000);
    expect(out).toContain('file_edit old_string');
  });
});

describe('titleAttr', () => {
  it('collapses whitespace for tooltip readability', () => {
    const out = titleAttr('line 1\nline 2\n  line 3  ');
    expect(out).toBe('line 1 line 2 line 3');
  });
  it('caps the result', () => {
    const s = 'a'.repeat(500);
    const out = titleAttr(s);
    expect(out.length).toBeLessThanOrEqual(HARD_MAX_TITLE_CHARS);
  });
  it('passes through short strings unchanged', () => {
    expect(titleAttr('hello world')).toBe('hello world');
  });
});

describe('cap constants are export-stable', () => {
  // R713: the cap values are part of the defensive contract.
  // Bumping them down should be a deliberate decision
  // (visible in git) so a future refactor that, say, raises
  // HARD_MAX_BODY_CHARS to 1 MB doesn't sneak through.
  it('HARD_MAX_BODY_CHARS is 50 KB', () => {
    expect(HARD_MAX_BODY_CHARS).toBe(50_000);
  });
  it('HARD_MAX_INLINE_CHARS is 4 KB', () => {
    expect(HARD_MAX_INLINE_CHARS).toBe(4_000);
  });
  it('HARD_MAX_TITLE_CHARS is 200', () => {
    expect(HARD_MAX_TITLE_CHARS).toBe(200);
  });
  it('HARD_MAX_INPUT_CHARS is 32 KB', () => {
    expect(HARD_MAX_INPUT_CHARS).toBe(32_000);
  });
});
