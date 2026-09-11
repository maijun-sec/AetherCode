/**
 * T-406: ThemeEventBus — the live-switch event primitive.
 *  - on() returns an unsubscribe function
 *  - multiple listeners are all called
 *  - off() detaches a specific listener
 *  - removeAllListeners() clears everything (for tests)
 */

import { describe, it, expect } from 'vitest';
import { ThemeEventBus } from '../index.js';
import { dark, light } from '../index.js';

describe('ThemeEventBus (T-405)', () => {
  it('emits a typed change event to all listeners', () => {
    const bus = new ThemeEventBus();
    const a: string[] = [];
    const b: string[] = [];
    bus.on('change', (e) => a.push(e.current.name));
    bus.on('change', (e) => b.push(e.previous?.name ?? 'null'));
    bus.emitChange({ previous: null, current: light });
    bus.emitChange({ previous: light, current: dark });
    expect(a).toEqual(['light', 'dark']);
    expect(b).toEqual(['null', 'light']);
  });

  it('unsubscribe returned by on() detaches the listener', () => {
    const bus = new ThemeEventBus();
    const calls: number[] = [];
    const handler = () => calls.push(1);
    const unsub = bus.on('change', handler);
    bus.emitChange({ previous: null, current: light });
    unsub();
    bus.emitChange({ previous: light, current: dark });
    expect(calls).toEqual([1]);
  });

  it('off() detaches a specific listener', () => {
    const bus = new ThemeEventBus();
    const calls: number[] = [];
    const h1 = () => calls.push(1);
    const h2 = () => calls.push(2);
    bus.on('change', h1);
    bus.on('change', h2);
    bus.off('change', h1);
    bus.emitChange({ previous: null, current: light });
    expect(calls).toEqual([2]);
  });

  it('once() fires only once', () => {
    const bus = new ThemeEventBus();
    const calls: number[] = [];
    bus.once('change', () => calls.push(1));
    bus.emitChange({ previous: null, current: light });
    bus.emitChange({ previous: light, current: dark });
    expect(calls).toEqual([1]);
  });

  it('removeAllListeners() drops every listener', () => {
    const bus = new ThemeEventBus();
    const calls: number[] = [];
    bus.on('change', () => calls.push(1));
    bus.on('change', () => calls.push(2));
    bus.removeAllListeners();
    bus.emitChange({ previous: null, current: light });
    expect(calls).toEqual([]);
  });
});
