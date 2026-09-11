/**
 * R245.2: tests for the cross-process self-eval audit helper.
 *
 * <p>Two layers of testing:</p>
 * <ol>
 *   <li>{@code aggregateReport} is a pure function over
 *       {@code (BankStats, BankUnit[])}; tests pass synthetic
 *       payloads and assert the math.</li>
 *   <li>{@code auditSelfEval} is the TUI-facing wrapper that
 *       makes two BankClient round trips; tests mock the
 *       whole {@code bank-client.js} module (same pattern as
 *       R245.1 {@code bank-recall.test.ts}).</li>
 * </ol>
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

const { mockStats, mockRecallAllKinds } = vi.hoisted(() => ({
  mockStats: vi.fn(),
  mockRecallAllKinds: vi.fn(),
}));

vi.mock('../bank-client.js', () => {
  class FakeBankClient {
    stats = mockStats;
    recallAllKinds = mockRecallAllKinds;
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
  unitConfidence,
  aggregateReport,
  formatSelfEvalReport,
  auditSelfEval,
} from '../self-eval-audit.js';
import type { BankStats, BankUnit } from '../bank-client.js';

// --- helpers ---

function makeUnit(overrides: Partial<BankUnit>): BankUnit {
  return {
    id: 'u',
    taskKind: 'file_edit',
    errorPattern: 'e',
    fixStrategy: 'f',
    example: '',
    utility: 0.5,
    uses: 0,
    okCount: 0,
    notOkCount: 0,
    createdAt: '2026-09-10T00:00:00Z',
    ...overrides,
  };
}

function makeStats(overrides: Partial<BankStats>): BankStats {
  return {
    size: 0, kinds: [], perKind: {}, totalOk: 0, totalNotOk: 0,
    ...overrides,
  };
}

// --- unitConfidence ---

describe('unitConfidence', () => {
  it('returns 0.0 for a zero-observation unit (Laplace-smoothed)', () => {
    expect(unitConfidence(0, 0)).toBe(0);
  });
  it('returns 0.0 for 0 ok / 1 notOk (k=1 smoothing pulls down)', () => {
    // 0 / (0+1+1) = 0/2 = 0 — Laplace makes a single
    // failure look much less damning than 0%; the k=1
    // constant ensures a unit with no signal ranks below
    // any unit with at least one positive observation.
    expect(unitConfidence(0, 1)).toBe(0);
  });
  it('returns ~0.33 for 1 ok / 1 notOk', () => {
    expect(unitConfidence(1, 1)).toBeCloseTo(1 / 3, 5);
  });
  it('returns 0.5 for 1 ok / 0 notOk (k=1 smoothing)', () => {
    expect(unitConfidence(1, 0)).toBeCloseTo(0.5, 5);
  });
  it('approaches 1.0 as okCount dominates', () => {
    expect(unitConfidence(99, 1)).toBeCloseTo(99 / 101, 5);
  });
  it('coerces non-numeric to 0', () => {
    expect(unitConfidence(NaN, NaN)).toBe(0);
    expect(unitConfidence(-1, 5)).toBe(0);
  });
});

// --- aggregateReport ---

describe('aggregateReport', () => {
  it('returns all-zeros for an empty bank', () => {
    const r = aggregateReport(makeStats({ size: 0, kinds: [], perKind: {} }), []);
    expect(r.totalUnits).toBe(0);
    expect(r.totalOk).toBe(0);
    expect(r.totalNotOk).toBe(0);
    expect(r.successRate).toBe(0);
    expect(r.avgConfidence).toBe(0);
    expect(r.observed).toBe(0);
    expect(r.weakestKind).toBeNull();
    expect(r.topKind).toBeNull();
    expect(r.perKind).toEqual([]);
  });

  it('aggregates per-kind ok / notOk / rate', () => {
    const units: BankUnit[] = [
      makeUnit({ id: 'a', taskKind: 'file_edit', okCount: 3, notOkCount: 1 }),
      makeUnit({ id: 'b', taskKind: 'file_edit', okCount: 2, notOkCount: 2 }),
      makeUnit({ id: 'c', taskKind: 'build',    okCount: 5, notOkCount: 0 }),
    ];
    const stats = makeStats({
      size: 3, kinds: ['file_edit', 'build'],
      perKind: { file_edit: 2, build: 1 },
      totalOk: 10, totalNotOk: 3,
    });
    const r = aggregateReport(stats, units);
    expect(r.totalUnits).toBe(3);
    expect(r.totalOk).toBe(10);
    expect(r.totalNotOk).toBe(3);
    expect(r.observed).toBe(13);
    expect(r.successRate).toBeCloseTo(10 / 13, 5);
    expect(r.perKind).toHaveLength(2);

    // file_edit: 5/8 = 0.625; build: 5/5 = 1.0
    const fe = r.perKind.find((k) => k.kind === 'file_edit')!;
    const bd = r.perKind.find((k) => k.kind === 'build')!;
    expect(fe.units).toBe(2);
    expect(fe.ok).toBe(5);
    expect(fe.notOk).toBe(3);
    expect(fe.rate).toBeCloseTo(5 / 8, 5);
    expect(bd.rate).toBe(1.0);
    expect(bd.observationCount).toBe(5);
  });

  it('picks weakest as lowest-rate kind with observations', () => {
    const units: BankUnit[] = [
      makeUnit({ id: 'a', taskKind: 'a', okCount: 1, notOkCount: 5 }),
      makeUnit({ id: 'b', taskKind: 'b', okCount: 5, notOkCount: 1 }),
      makeUnit({ id: 'c', taskKind: 'c', okCount: 0, notOkCount: 0 }),  // 0 obs, ignored
    ];
    const r = aggregateReport(makeStats({ size: 3, kinds: ['a', 'b', 'c'], perKind: { a: 1, b: 1, c: 1 }, totalOk: 6, totalNotOk: 6 }), units);
    expect(r.weakestKind).toBe('a');
    expect(r.topKind).toBe('b');
  });

  it('returns null weakest/top when no observations', () => {
    const units = [makeUnit({ id: 'a', taskKind: 'a', okCount: 0, notOkCount: 0 })];
    const r = aggregateReport(makeStats({ size: 1, kinds: ['a'], perKind: { a: 1 }, totalOk: 0, totalNotOk: 0 }), units);
    expect(r.weakestKind).toBeNull();
    expect(r.topKind).toBeNull();
    expect(r.avgConfidence).toBe(0);
  });

  it('avgConfidence is the mean of per-kind confidences', () => {
    // All three units have okCount=0, so all confidences are 0
    // (Laplace: 0/(0+0+1)=0 for unit a, 0 for unit b,
    // 0/(0+1+1)=0 for unit c). Mean = 0.
    const units = [
      makeUnit({ id: 'a', taskKind: 'x', okCount: 0, notOkCount: 0 }),
      makeUnit({ id: 'b', taskKind: 'x', okCount: 0, notOkCount: 0 }),
      makeUnit({ id: 'c', taskKind: 'x', okCount: 0, notOkCount: 1 }),
    ];
    const r = aggregateReport(makeStats({ size: 3, kinds: ['x'], perKind: { x: 3 }, totalOk: 0, totalNotOk: 1 }), units);
    expect(r.avgConfidence).toBe(0);
  });

  it('avgConfidence with mixed observations matches the per-unit mean', () => {
    // unit a (1,0): 1/(1+0+1) = 0.5
    // unit b (0,1): 0/(0+1+1) = 0
    // unit c (2,1): 2/(2+1+1) = 0.5
    // Mean = (0.5 + 0 + 0.5) / 3 = 1/3
    const units = [
      makeUnit({ id: 'a', taskKind: 'x', okCount: 1, notOkCount: 0 }),
      makeUnit({ id: 'b', taskKind: 'x', okCount: 0, notOkCount: 1 }),
      makeUnit({ id: 'c', taskKind: 'x', okCount: 2, notOkCount: 1 }),
    ];
    const r = aggregateReport(makeStats({ size: 3, kinds: ['x'], perKind: { x: 3 }, totalOk: 3, totalNotOk: 2 }), units);
    expect(r.avgConfidence).toBeCloseTo(1 / 3, 5);
  });

  it('coerces non-numeric okCount/notOkCount to 0', () => {
    const units = [
      makeUnit({ id: 'a', taskKind: 'x', okCount: NaN as any, notOkCount: 2 }),
      makeUnit({ id: 'b', taskKind: 'x', okCount: 3, notOkCount: 'x' as any }),
    ];
    const r = aggregateReport(makeStats({ size: 2, kinds: ['x'], perKind: { x: 2 }, totalOk: 3, totalNotOk: 2 }), units);
    expect(r.perKind[0].ok).toBe(3);
    expect(r.perKind[0].notOk).toBe(2);
  });
});

// --- formatSelfEvalReport ---

describe('formatSelfEvalReport', () => {
  it('renders a one-line summary when there are no observations', () => {
    const text = formatSelfEvalReport({
      totalUnits: 5, totalOk: 0, totalNotOk: 0,
      successRate: 0, avgConfidence: 0, perKind: [],
      weakestKind: null, topKind: null, observed: 0,
    });
    expect(text).toBe('self-eval audit: 5 units, success no outcomes yet, avg confidence 0.00');
  });

  it('renders success rate + per-kind extremes when observations exist', () => {
    const text = formatSelfEvalReport({
      totalUnits: 10, totalOk: 8, totalNotOk: 2,
      successRate: 0.8, avgConfidence: 0.55, perKind: [
        { kind: 'build', units: 1, ok: 1, notOk: 2, rate: 1 / 3, avgConfidence: 0.5, observationCount: 3 },
        { kind: 'file_edit', units: 2, ok: 7, notOk: 0, rate: 1.0, avgConfidence: 0.875, observationCount: 7 },
      ],
      weakestKind: 'build', topKind: 'file_edit', observed: 10,
    });
    const lines = text.split('\n');
    expect(lines[0]).toBe('self-eval audit: 10 units, success 80% (8/10), avg confidence 0.55');
    expect(lines[1]).toBe('  weakest: build (33% over 3 obs)');
    expect(lines[2]).toBe('  top:     file_edit (100% over 7 obs)');
  });

  it('omits weakest/top lines when no observations', () => {
    const text = formatSelfEvalReport({
      totalUnits: 3, totalOk: 0, totalNotOk: 0,
      successRate: 0, avgConfidence: 0, perKind: [
        { kind: 'a', units: 1, ok: 0, notOk: 0, rate: 0, avgConfidence: 0, observationCount: 0 },
      ],
      weakestKind: null, topKind: null, observed: 0,
    });
    expect(text.split('\n')).toHaveLength(1);
  });
});

// --- auditSelfEval (with mocked BankClient) ---

describe('auditSelfEval', () => {
  beforeEach(() => {
    mockStats.mockReset();
    mockRecallAllKinds.mockReset();
  });

  it('returns ok summary with text + report on success', async () => {
    // auditSelfEval accepts a BankClient as its first arg
    // (test/DI mode). We pass a fake whose stats + recallAllKinds
    // are the vi.fn() mocks. Production callers omit it
    // and the helper builds the client from env.
    const fakeClient = { stats: mockStats, recallAllKinds: mockRecallAllKinds };
    mockStats.mockResolvedValueOnce({
      size: 2, kinds: ['file_edit'], perKind: { file_edit: 2 },
      totalOk: 4, totalNotOk: 1,
    });
    mockRecallAllKinds.mockResolvedValueOnce([
      makeUnit({ id: 'a', taskKind: 'file_edit', okCount: 3, notOkCount: 0 }),
      makeUnit({ id: 'b', taskKind: 'file_edit', okCount: 1, notOkCount: 1 }),
    ]);
    const out = await auditSelfEval(fakeClient as any);
    expect(out.ok).toBe(true);
    if (out.ok) {
      expect(out.text).toContain('self-eval audit: 2 units');
      expect(out.text).toContain('80%');
      expect(out.report.weakestKind).toBe('file_edit');
    }
  });

  it('returns down summary on transport error (status 0)', async () => {
    const { BankClientError } = await import('../bank-client.js');
    const fakeClient = { stats: mockStats, recallAllKinds: mockRecallAllKinds };
    mockStats.mockRejectedValueOnce(new (BankClientError as any)(0, 'ECONNREFUSED'));
    const out = await auditSelfEval(fakeClient as any);
    expect(out.ok).toBe(false);
    if (!out.ok) {
      expect(out.text).toBe('self-eval audit: down (transport error)');
    }
  });

  it('returns down summary on HTTP 503', async () => {
    const { BankClientError } = await import('../bank-client.js');
    const fakeClient = { stats: mockStats, recallAllKinds: mockRecallAllKinds };
    mockStats.mockRejectedValueOnce(new (BankClientError as any)(503, 'daemon busy'));
    const out = await auditSelfEval(fakeClient as any);
    expect(out.ok).toBe(false);
    if (!out.ok) {
      expect(out.text).toBe('self-eval audit: down (HTTP 503)');
    }
  });
});
