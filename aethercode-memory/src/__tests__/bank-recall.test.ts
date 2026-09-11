/**
 * R245.1: tests for the TUI/desktop-side bank wrapper.
 *
 * <p>These tests stub the {@link BankClient} by replacing
 * the {@code aethercode-memory} module's export with a
 * hand-rolled fake (the same pattern as
 * {@code bank-client.test.ts} from R244.3, but with a fake
 * client instead of a fake fetch — the wrapper only calls
 * the high-level methods, not {@code fetch} directly).</p>
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// R245.1: vi.mock factories are evaluated before imports, so
// the mock fns must be hoisted via vi.hoisted; otherwise the
// factory captures `undefined` and the real client gets
// constructed (which then tries to actually fetch the bank).
const { mockStats, mockRecallFor, mockPing } = vi.hoisted(() => ({
  mockStats: vi.fn(),
  mockRecallFor: vi.fn(),
  mockPing: vi.fn(),
}));

vi.mock('../bank-client.js', () => {
  // Stub the underlying client; only the methods bank-recall
  // actually calls are surfaced. Tests reset the mocks in
  // beforeEach.
  class FakeBankClient {
    stats = mockStats;
    recallFor = mockRecallFor;
    ping = mockPing;
  }
  return {
    BankClient: FakeBankClient,
    BankClientError: class BankClientError extends Error {
      status: number;
      constructor(status: number, message: string) {
        super(`bank client (status=${status}): ${message}`);
        this.name = 'BankClientError';
        this.status = status;
      }
    },
  };
});

import {
  resolveBankUrl,
  formatBankStats,
  formatRecall,
  readBankStats,
  readBankRecall,
  DEFAULT_BANK_URL,
} from '../bank-recall.js';

describe('resolveBankUrl', () => {
  it('returns DEFAULT_BANK_URL when env is empty', () => {
    expect(resolveBankUrl({})).toBe(DEFAULT_BANK_URL);
  });
  it('honors AETHERCODE_BANK_URL', () => {
    expect(resolveBankUrl({ AETHERCODE_BANK_URL: 'http://daemon.lan:9000' }))
      .toBe('http://daemon.lan:9000');
  });
  it('strips a single trailing slash', () => {
    expect(resolveBankUrl({ AETHERCODE_BANK_URL: 'http://x:7777/' }))
      .toBe('http://x:7777');
  });
});

describe('formatBankStats', () => {
  it('renders size + kinds + ok/notOk ratio', () => {
    const text = formatBankStats({
      size: 12, kinds: ['a', 'b', 'c'], perKind: { a: 5, b: 4, c: 3 },
      totalOk: 8, totalNotOk: 1,
    });
    expect(text).toBe('bank: 12 units, 3 kinds, 8 ok / 1 notOk');
  });
  it('handles zero outcomes gracefully', () => {
    const text = formatBankStats({
      size: 0, kinds: [], perKind: {}, totalOk: 0, totalNotOk: 0,
    });
    expect(text).toBe('bank: 0 units, 0 kinds, no outcomes yet');
  });
  it('handles a single kind', () => {
    const text = formatBankStats({
      size: 5, kinds: ['file_edit'], perKind: { file_edit: 5 },
      totalOk: 3, totalNotOk: 2,
    });
    expect(text).toBe('bank: 5 units, 1 kinds, 3 ok / 2 notOk');
  });
});

describe('formatRecall', () => {
  it('renders the top unit strategy + example', () => {
    const text = formatRecall('file_edit', [{
      id: 'u1', taskKind: 'file_edit', errorPattern: 'perm',
      fixStrategy: 'ensure dir exists first', example: 'mkdir -p /x',
      utility: 0.85, uses: 3, okCount: 2, notOkCount: 0,
      createdAt: '2026-09-10T00:00:00Z',
    }]);
    expect(text).toBe('bank[file_edit]: 1 units (top: ensure dir exists first: mkdir -p /x)');
  });
  it('handles an empty recall', () => {
    expect(formatRecall('file_edit', [])).toBe('bank[file_edit]: 0 units');
  });
  it('omits the example when empty', () => {
    const text = formatRecall('build', [{
      id: 'u1', taskKind: 'build', errorPattern: 'x', fixStrategy: 'use --no-daemon',
      example: '', utility: 0.5, uses: 0, okCount: 0, notOkCount: 0,
      createdAt: '2026-09-10T00:00:00Z',
    }]);
    expect(text).toBe('bank[build]: 1 units (top: use --no-daemon)');
  });
});

describe('readBankStats', () => {
  beforeEach(() => {
    mockStats.mockReset();
  });
  it('returns ok summary on success', async () => {
    mockStats.mockResolvedValueOnce({
      size: 5, kinds: ['a'], perKind: { a: 5 }, totalOk: 3, totalNotOk: 1,
    });
    const out = await readBankStats({});
    expect(out.ok).toBe(true);
    if (out.ok) {
      expect(out.text).toBe('bank: 5 units, 1 kinds, 3 ok / 1 notOk');
      expect(out.stats.size).toBe(5);
    }
  });
  it('returns down summary on transport error (status 0)', async () => {
    const { BankClientError } = await import('../bank-client.js');
    mockStats.mockRejectedValueOnce(new (BankClientError as any)(0, 'ECONNREFUSED'));
    const out = await readBankStats({});
    expect(out.ok).toBe(false);
    if (!out.ok) {
      expect(out.text).toBe('bank: down (transport error)');
      expect(out.reason).toContain('ECONNREFUSED');
    }
  });
  it('returns down summary on HTTP error (status != 0)', async () => {
    const { BankClientError } = await import('../bank-client.js');
    mockStats.mockRejectedValueOnce(new (BankClientError as any)(503, 'daemon busy'));
    const out = await readBankStats({});
    expect(out.ok).toBe(false);
    if (!out.ok) {
      expect(out.text).toBe('bank: down (HTTP 503)');
    }
  });
});

describe('readBankRecall', () => {
  beforeEach(() => {
    mockRecallFor.mockReset();
  });
  it('rejects empty kind without hitting the wire', async () => {
    const out = await readBankRecall('', 3, {});
    expect(out.ok).toBe(false);
    if (!out.ok) {
      expect(out.reason).toBe('empty-kind');
    }
    expect(mockRecallFor).not.toHaveBeenCalled();
  });
  it('returns formatted recall line on success', async () => {
    mockRecallFor.mockResolvedValueOnce([{
      id: 'u1', taskKind: 'file_edit', errorPattern: 'perm',
      fixStrategy: 'ensure dir exists first', example: 'mkdir -p /x',
      utility: 0.85, uses: 3, okCount: 2, notOkCount: 0,
      createdAt: '2026-09-10T00:00:00Z',
    }]);
    const out = await readBankRecall('file_edit', 3, {});
    expect(out.ok).toBe(true);
    if (out.ok) {
      expect(out.text).toContain('bank[file_edit]');
      expect(out.text).toContain('ensure dir exists first');
      expect(out.units).toHaveLength(1);
    }
  });
  it('returns empty-list summary on success with no units', async () => {
    mockRecallFor.mockResolvedValueOnce([]);
    const out = await readBankRecall('unknown_kind', 3, {});
    expect(out.ok).toBe(true);
    if (out.ok) {
      expect(out.text).toBe('bank[unknown_kind]: 0 units');
      expect(out.units).toEqual([]);
    }
  });
  it('returns down summary on transport error', async () => {
    const { BankClientError } = await import('../bank-client.js');
    mockRecallFor.mockRejectedValueOnce(new (BankClientError as any)(0, 'connect ETIMEDOUT'));
    const out = await readBankRecall('file_edit', 3, {});
    expect(out.ok).toBe(false);
    if (!out.ok) {
      expect(out.text).toBe('bank: down (transport error)');
    }
  });
});
