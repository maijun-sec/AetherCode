// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { safeParseSsdEvent } from '../ssd/tauriSsdDriver';

/**
 * R309 wire-protocol tests for the new
 * {@code phase-need-content} outbound event. Mirrors the
 * daemon's InteractiveRepl.requestContent shape so the
 * desktop ↔ daemon contract can't silently regress to the
 * pre-R309 "phase-draft only" model where the daemon
 * fabricated its own LLM reply.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@code parses_phase_need_content_with_all_four_fields}
 *       — the parser surfaces systemPrompt + userPrompt +
 *       maxTokens + phase verbatim so the renderer can
 *       forward them to an attached LLM.</li>
 *   <li>{@code normalises_event_field_to_kind} — daemon emits
 *       {@code {"event":"phase-need-content",...}}; the
 *       renderer must remap to {@code kind} just like the
 *       R297 fix did for phase-draft / phase-accepted.</li>
 *   <li>{@code empty_prompt_string_is_still_a_valid_request}
 *       — defensive: an empty userPrompt must still parse
 *       (so the daemon can request a trivial template-only
 *       generation without crashing the wire).</li>
 * </ol>
 */
describe('tauriSsdDriver R309 phase-need-content', () => {
  it('parses phase-need-content with all four fields (phase, systemPrompt, userPrompt, maxTokens)', () => {
    const line = JSON.stringify({
      event: 'phase-need-content',
      phase: 'specify',
      systemPrompt: 'You write software specs.',
      userPrompt: '# Feature Spec: java-maven\n\nPlease fill in:',
      maxTokens: 4096,
    });
    const ev = safeParseSsdEvent(line);
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-need-content');
    if (ev!.kind === 'phase-need-content') {
      expect(ev.phase).toBe('specify');
      expect(ev.systemPrompt).toBe('You write software specs.');
      expect(ev.userPrompt).toBe('# Feature Spec: java-maven\n\nPlease fill in:');
      expect(ev.maxTokens).toBe(4096);
    }
  });

  it('normalises daemon `event` field to renderer `kind` (R297 compat)', () => {
    const line = JSON.stringify({
      event: 'phase-need-content',
      phase: 'plan',
      systemPrompt: 'sys',
      userPrompt: 'usr',
      maxTokens: 2048,
    });
    const ev = safeParseSsdEvent(line);
    expect(ev).not.toBeNull();
    // Daemon emits `event`, renderer reads `kind` — without
    // this remap the safeParseSsdEvent null check would
    // drop every phase-need-content line on the floor and
    // the chip strip would stay idle (the same symptom as
    // the pre-R297 R297 fix that motivated the
    // `event` → `kind` normaliser).
    expect(ev!.kind).toBe('phase-need-content');
    // The renderer accepts the `event` field on input
    // (carried through unchanged); the `kind` field is the
    // canonical discriminator the rest of the renderer
    // switches on.
    expect((ev as unknown as { event?: string }).event).toBe('phase-need-content');
  });

  it('empty userPrompt still parses (daemon template-only request)', () => {
    const line = JSON.stringify({
      event: 'phase-need-content',
      phase: 'constitution',
      systemPrompt: '',
      userPrompt: '',
      maxTokens: 512,
    });
    const ev = safeParseSsdEvent(line);
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-need-content');
    if (ev!.kind === 'phase-need-content') {
      expect(ev.systemPrompt).toBe('');
      expect(ev.userPrompt).toBe('');
      expect(ev.maxTokens).toBe(512);
    }
  });
});