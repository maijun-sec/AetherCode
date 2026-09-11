/**
 * Tests for the auto-compact circuit breaker (T-140 → T-145).
 *
 * Covers:
 *  - per-session `consecutiveCompactFailures` counter
 *  - trips after 3 failures in the same session
 *  - success resets the counter and clears the disabled flag
 *  - `autoCompactDisabled` flag is independent per session
 *  - `reset()` clears everything (aethercode compact reset)
 *  - `warningMessage()` returns a TUI-friendly string only when tripped
 *  - configurable threshold
 *  - default options match design.md §2.6
 */
import { describe, expect, it } from "vitest";

import {
  CircuitBreaker,
  DEFAULT_FAILURE_THRESHOLD,
  defaultCircuitBreakerOptions,
} from "../circuit-breaker.js";

const FIXED_NOW = () => 1_700_000_000_000;

describe("defaultCircuitBreakerOptions", () => {
  it("exposes threshold = 3 (per design.md §2.6)", () => {
    expect(DEFAULT_FAILURE_THRESHOLD).toBe(3);
    expect(defaultCircuitBreakerOptions().threshold).toBe(3);
  });
});

describe("CircuitBreaker — counter (T-140)", () => {
  it("starts at zero for an unseen session", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    const s = cb.status("s1");
    expect(s.consecutiveCompactFailures).toBe(0);
    expect(s.autoCompactDisabled).toBe(false);
    expect(s.lastFailureTs).toBeNull();
    expect(cb.isTripped("s1")).toBe(false);
  });

  it("increments the per-session counter on every failure", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1", "llm_error");
    expect(cb.status("s1").consecutiveCompactFailures).toBe(1);
    cb.recordFailure("s1", "timeout");
    expect(cb.status("s1").consecutiveCompactFailures).toBe(2);
    cb.recordFailure("s1", "validation_error");
    expect(cb.status("s1").consecutiveCompactFailures).toBe(3);
  });

  it("tracks lastFailureTs", () => {
    let now = 1000;
    const cb = new CircuitBreaker({ now: () => now });
    cb.recordFailure("s1");
    expect(cb.status("s1").lastFailureTs).toBe(1000);
    now = 2000;
    cb.recordFailure("s1");
    expect(cb.status("s1").lastFailureTs).toBe(2000);
  });

  it("keeps counters per-session (sessions are independent)", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s2");
    expect(cb.status("s1").consecutiveCompactFailures).toBe(2);
    expect(cb.status("s2").consecutiveCompactFailures).toBe(1);
  });
});

describe("CircuitBreaker — trip (T-141) + disabled flag (T-142)", () => {
  it("sets autoCompactDisabled=true after 3 consecutive failures in the same session", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(false);
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
    expect(cb.status("s1").autoCompactDisabled).toBe(true);
  });

  it("does not trip on a single failure or two failures", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(false);
    expect(cb.status("s1").autoCompactDisabled).toBe(false);
  });

  it("a success in between resets the counter and the trip", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
    cb.recordSuccess("s1");
    expect(cb.isTripped("s1")).toBe(false);
    expect(cb.status("s1").consecutiveCompactFailures).toBe(0);
    expect(cb.status("s1").autoCompactDisabled).toBe(false);
  });

  it("a success alone clears a previously-set disabled flag", () => {
    const cb = new CircuitBreaker({ threshold: 2, now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
    cb.recordSuccess("s1");
    expect(cb.isTripped("s1")).toBe(false);
  });

  it("trips one session without affecting others", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
    expect(cb.isTripped("s2")).toBe(false);
  });
});

describe("CircuitBreaker — threshold customization", () => {
  it("honours a custom threshold", () => {
    const cb = new CircuitBreaker({ threshold: 5, now: FIXED_NOW });
    for (let i = 0; i < 4; i++) {
      cb.recordFailure("s1");
    }
    expect(cb.isTripped("s1")).toBe(false);
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
  });

  it("clamps threshold to at least 1", () => {
    const cb = new CircuitBreaker({ threshold: 0, now: FIXED_NOW });
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
  });
});

describe("CircuitBreaker — TUI warning (T-143)", () => {
  it("returns null when the breaker is not tripped", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    expect(cb.warningMessage("s1")).toBeNull();
    cb.recordFailure("s1");
    expect(cb.warningMessage("s1")).toBeNull();
  });

  it("returns a TUI-friendly warning when tripped", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    const msg = cb.warningMessage("s1");
    expect(msg).not.toBeNull();
    expect(msg).toMatch(/auto-?compact/i);
    expect(msg).toMatch(/aethercode compact reset/);
  });

  it("includes the failure count in the message", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    const msg = cb.warningMessage("s1") ?? "";
    expect(msg).toContain("3");
  });
});

describe("CircuitBreaker — reset (T-144)", () => {
  it("clears the disabled flag and counter for the session", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
    cb.reset("s1");
    expect(cb.isTripped("s1")).toBe(false);
    expect(cb.status("s1").consecutiveCompactFailures).toBe(0);
    expect(cb.status("s1").autoCompactDisabled).toBe(false);
  });

  it("reset on an unseen session is a no-op", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    expect(() => cb.reset("nope")).not.toThrow();
    expect(cb.status("nope").consecutiveCompactFailures).toBe(0);
  });

  it("after reset, the session must rebuild 3 failures to trip again", () => {
    const cb = new CircuitBreaker({ now: FIXED_NOW });
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
    cb.reset("s1");
    cb.recordFailure("s1");
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(false);
    cb.recordFailure("s1");
    expect(cb.isTripped("s1")).toBe(true);
  });
});

describe("CircuitBreaker — failure history ring buffer", () => {
  it("records the reason and ts of every failure", () => {
    let now = 100;
    const cb = new CircuitBreaker({ now: () => now });
    cb.recordFailure("s1", "llm_error");
    now = 200;
    cb.recordFailure("s1", "timeout");
    const hist = cb.failureHistory("s1");
    expect(hist).toHaveLength(2);
    expect(hist[0]?.reason).toBe("llm_error");
    expect(hist[0]?.ts).toBe(100);
    expect(hist[1]?.reason).toBe("timeout");
    expect(hist[1]?.ts).toBe(200);
  });
});
