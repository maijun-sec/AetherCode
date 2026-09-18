// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { parseModelSwitchCommand } from '../MessageInput';

/**
 * R285: parser unit tests for the `/model
 * provider/model[:variant]` slash that MessageInput
 * recognises. The full UI flow (clicking Quality
 * pills → store.switchVariant → MockRpcServer) is
 * covered indirectly by the R285 daemon-side
 * AetherCodeMethodsR285Test + the R284 fixture
 * harness contract: the store action calls the
 * production `rpc` singleton, which goes through
 * Tauri invoke and has no in-process path under
 * jsdom. We test the parser in isolation so the
 * regex contract stays pinned without taking on
 * the production-singleton limitation.
 */
describe('parseModelSwitchCommand R285', () => {
  it('parses /model provider/model:variant', () => {
    const r = parseModelSwitchCommand('model glm/glm-4-plus:high');
    expect(r).toEqual({ provider: 'glm', model: 'glm-4-plus', variant: 'high' });
  });

  it('parses /model provider/model without variant', () => {
    const r = parseModelSwitchCommand('model glm/glm-4-flash');
    expect(r).toEqual({ provider: 'glm', model: 'glm-4-flash' });
  });

  it('is case-insensitive on the leading slash', () => {
    const r = parseModelSwitchCommand('MODEL glm/glm-4-flash:xhigh');
    expect(r).toEqual({ provider: 'glm', model: 'glm-4-flash', variant: 'xhigh' });
  });

  it('returns null for non-model inputs', () => {
    expect(parseModelSwitchCommand('hello world')).toBeNull();
    expect(parseModelSwitchCommand('/workflow create')).toBeNull();
    expect(parseModelSwitchCommand('model glm/glm-4-flash more args')).toBeNull();
    expect(parseModelSwitchCommand('')).toBeNull();
  });

  it('returns null when the model is missing', () => {
    // missing model — parser rejects
    expect(parseModelSwitchCommand('model glm/')).toBeNull();
  });

  it('opencode-style aliases resolve to the right preset', () => {
    // the parser is name-only — the daemon side
    // does the alias resolution (default/medium/med →
    // MEDIUM, low/fast → LOW, high/deep → HIGH, xhigh
    // → XHIGH). The parser just returns the raw name.
    expect(parseModelSwitchCommand('model glm/glm-4-flash:fast'))
      .toEqual({ provider: 'glm', model: 'glm-4-flash', variant: 'fast' });
    expect(parseModelSwitchCommand('model glm/glm-4-flash:deep'))
      .toEqual({ provider: 'glm', model: 'glm-4-flash', variant: 'deep' });
  });
});