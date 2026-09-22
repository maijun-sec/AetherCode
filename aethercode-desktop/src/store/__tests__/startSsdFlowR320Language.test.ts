// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R320 — detectIntentLanguage() helper classifies the
 * dominant script of an intent string and picks a
 * language hint for the agent. The store/buildPhasePrompt
 * uses this to inject a "请用中文..." block so the agent
 * writes artifacts (constitution.md / spec.md / etc.) in
 * the user's prompt language instead of defaulting to
 * English.
 *
 * Source-pin style: read the store and assert the helper
 * branches + the language hint block.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const STORE_TS = join(process.cwd(), 'src', 'store', 'index.ts');
const source = readFileSync(STORE_TS, 'utf8');

describe('buildPhasePrompt — R320 language detection', () => {
  it('defines detectIntentLanguage helper', () => {
    expect(source).toMatch(/function detectIntentLanguage/);
  });

  it('recognizes Chinese (CJK Unified Ideographs) as zh', () => {
    expect(source).toMatch(/\[\\u4E00-\\u9FFF\]/);
  });

  it('recognizes Japanese (Hiragana / Katakana) as ja', () => {
    expect(source).toMatch(/\[\\u3040-\\u30FF\]/);
  });

  it('recognizes Korean (Hangul) as ko', () => {
    expect(source).toMatch(/\[\\uAC00-\\uD7AF\]/);
  });

  it('recognizes Cyrillic as ru', () => {
    expect(source).toMatch(/\[\\u0400-\\u04FF\]/);
  });

  it('falls back to English for ASCII intents', () => {
    // The helper returns 'en' for non-CJK / non-Cyrillic / etc.
    expect(source).toMatch(/return 'en';/);
  });

  it('injects a languageHintBlock into the hidden prompt', () => {
    expect(source).toMatch(/function languageHintBlock/);
    expect(source).toMatch(/languageHintBlock\(lang\)/);
  });

  it('zh hint says "请用中文撰写所有 spec / design"', () => {
    expect(source).toMatch(/请用\*\*中文\*\*撰写所有 spec \/ design/);
  });

  it('en hint says "Please write all artifacts in English"', () => {
    expect(source).toMatch(/Please write all artifacts[\s\S]*English/);
  });

  it('visibleBlock shows 产物语言 label for the user', () => {
    expect(source).toMatch(/产物语言/);
  });
});