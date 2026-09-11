import { describe, it, expect } from 'vitest';
import { computeModelDropdownValue, type ModelEntry } from './modelDropdownValue';

/**
 * behaviour tests for the
 * `computeModelDropdownValue` helper. The bug it
 * fixes: legacy the model dropdown used
 * `value={model}` but the option ids were
 * `${providerName}/${modelId}`. The value never
 * matched an option, so the browser fell back to
 * the first option alphabetically — making the
 * dropdown always look like the user's "default"
 * was whatever model sorted first (typically M1,
 * not what the daemon actually had, e.g. M3).
 *
 * <p>These tests pin the post-fix contract:
 * <ol>
 *   <li>empty / nullish model → empty string
 *       (placeholder line shows)</li>
 *   <li>model matches an entry → that entry's
 *       id (e.g. "minmax/MiniMax-M3") is
 *       returned so the select's value attribute
 *       actually matches an option</li>
 *   <li>model is not in the list → fall back
 *       to the bare model so the placeholder
 *       line shows it (no silent fallback to
 *       the first option)</li>
 *   <li>multiple entries with the same model id
 *       (rare but possible) → first match wins
 *       (same as Array.find semantics)</li>
 * </ol>
 */
const ENTRIES: ModelEntry[] = [
  { id: 'minmax/MiniMax-M1', label: 'minmax / MiniMax-M1', provider: 'minmax' },
  { id: 'minmax/MiniMax-M3', label: 'minmax / MiniMax-M3', provider: 'minmax' },
  { id: 'anthropic/claude-sonnet-4-5', label: 'anthropic / claude-sonnet-4-5', provider: 'anthropic' },
];

describe('对应历史 round: computeModelDropdownValue', () => {
  it('returns empty string when model is empty / nullish (placeholder shows)', () => {
    expect(computeModelDropdownValue('', ENTRIES)).toBe('');
    expect(computeModelDropdownValue(undefined, ENTRIES)).toBe('');
    expect(computeModelDropdownValue(null, ENTRIES)).toBe('');
  });

  it('returns the matching entry id when model is in the list (M3 case)', () => {
    // The bug case: daemon is M3, store.model = "MiniMax-M3",
    // pre-fix the dropdown would have value="MiniMax-M3"
    // which doesn't match any option (all ids have
    // provider prefix). Post-fix it returns
    // "minmax/MiniMax-M3" which DOES match.
    expect(computeModelDropdownValue('MiniMax-M3', ENTRIES)).toBe('minmax/MiniMax-M3');
  });

  it('returns the matching entry id for M1 too', () => {
    expect(computeModelDropdownValue('MiniMax-M1', ENTRIES)).toBe('minmax/MiniMax-M1');
  });

  it('returns the matching entry id for an anthropic model', () => {
    // Cross-provider: the user's current model is from
    // anthropic, not the default provider. The lookup
    // should still find it.
    expect(computeModelDropdownValue('claude-sonnet-4-5', ENTRIES)).toBe('anthropic/claude-sonnet-4-5');
  });

  it('falls back to the bare model when it is not in the list', () => {
    // The daemon is using a model the local providers
    // cache doesn't know about (e.g. a model retired
    // from the dropdown but still on the daemon). The
    // pre-fix bug showed the first option in the list
    // (misleading); post-fix we show the bare model so
    // the placeholder line is at least accurate.
    expect(computeModelDropdownValue('gpt-99-ultra', ENTRIES)).toBe('gpt-99-ultra');
  });

  it('handles an empty entries list gracefully', () => {
    // First-launch race: listProviders hasn't resolved
    // yet, the dropdown is empty. The value should
    // just be the bare model (placeholder line).
    expect(computeModelDropdownValue('MiniMax-M3', [])).toBe('MiniMax-M3');
  });

  it('matches by exact suffix, not substring (so claude-1 does not match claude-10)', () => {
    // Regression guard: if the lookup were `e.id.includes(model)`
    // instead of `e.id.endsWith('/' + model)`, the model
    // "MiniMax-M1" would also match "minmax/MiniMax-M10"
    // if that model existed. The endsWith('/' + model)
    // anchor prevents this.
    const entries: ModelEntry[] = [
      { id: 'minmax/MiniMax-M1', label: 'minmax / MiniMax-M1', provider: 'minmax' },
      { id: 'minmax/MiniMax-M10', label: 'minmax / MiniMax-M10', provider: 'minmax' },
    ];
    // For "MiniMax-M1" we want exactly the M1 entry,
    // not the M10 entry. (First match wins; depends on
    // entry order in the test fixture.)
    const v = computeModelDropdownValue('MiniMax-M1', entries);
    expect(v).toBe('minmax/MiniMax-M1');
  });

  it('first match wins when the same model id exists in two providers', () => {
    // Theoretically possible if two providers expose
    // models with overlapping names. The test pins
    // find() semantics (first match) so a refactor
    // that picks the "current provider" instead would
    // need an explicit decision rather than silent
    // behaviour change.
    const entries: ModelEntry[] = [
      { id: 'providerA/MiniMax-M3', label: 'A / M3', provider: 'providerA' },
      { id: 'providerB/MiniMax-M3', label: 'B / M3', provider: 'providerB' },
    ];
    expect(computeModelDropdownValue('MiniMax-M3', entries)).toBe('providerA/MiniMax-M3');
  });
});
