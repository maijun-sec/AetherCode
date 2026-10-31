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

/**
 * R377: the reasoning-effort capability, under the name the DAEMON uses.
 *
 * ## The three names for one thing
 *
 * The same capability has three names in this repository, and that is
 * how the TUI ended up refusing a feature that works:
 *
 * | where          | name                                              |
 * |----------------|---------------------------------------------------|
 * | the user       | "effort" (`/effort high`)                          |
 * | the engine     | "variant" (`Variant` — a "per-model effort / quality preset") |
 * | the CLI        | `--effort` (a hint that R747 records but does not map) |
 *
 * The TUI asked for `effort/set`. No such method exists. The daemon
 * serves the capability as `switchVariant { variant: "<name>" }`, and
 * has since R285 — the DESKTOP has driven it from its Quality
 * dropdown the whole time. The TUI looked at the method registry,
 * searched for "effort", found nothing, and told the user the feature
 * was impossible.
 *
 * **Lesson 1193 — a refusal is a claim about ANOTHER system's
 * vocabulary, and such a claim cannot verify itself.** The registry
 * does not contain the word you are looking for; it contains a
 * SYNONYM. So "I checked and it is not there" has to be "I checked
 * and here is the daemon's own name for it" — otherwise a naming gap
 * becomes a missing feature.
 *
 * ## What the daemon answers
 *
 * `switchVariant` answers with the RESOLVED preset, not the name that
 * was asked for:
 *
 *     { ok: true, variant: "<name>", activeVariant: {
 *         name, description, temperature, maxTokens,
 *         reasoningBudget, extendedThinking } }
 *
 * `Variant.byName` resolves `default`/`medium`/`med` → the balanced
 * preset, `low`/`fast`, `high`/`deep`, `xhigh`; anything else falls
 * back through the model/provider chain to the bundled default. So the
 * caller must report `activeVariant`, never the name it sent — the
 * whole point of R377 is that the TUI stops claiming things it did
 * not verify.
 */

/** The daemon's `activeVariant` payload (`switchVariant` / `listAvailableModels`). */
export interface VariantInfo {
  /** The preset's canonical name ("low" / "medium" / "high" / "xhigh"). */
  name: string;
  /** The preset's one-line description, when the daemon published one. */
  description?: string | null;
  /** Sampling temperature, when the preset sets one. */
  temperature?: number | null;
  /** Output-token ceiling, when the preset sets one. */
  maxTokens?: number | null;
  /** Reasoning budget, when the preset sets one. */
  reasoningBudget?: number | null;
  /** Whether the preset turns extended thinking on. */
  extendedThinking?: boolean | null;
}

/**
 * The canonical levels, matching `Variant`'s bundled presets
 * (`LOW` / `MEDIUM` / `HIGH` / `XHIGH`).
 *
 * <p>The daemon also accepts the aliases `default` / `med` / `fast` /
 * `deep`, but the TUI offers the four canonical names so that what the
 * user picks is what the daemon reports back.
 */
export const EFFORT_LEVELS = ["low", "medium", "high", "xhigh"] as const;

/** One line per level, shared by both renderers. */
export const EFFORT_EFFECT: Record<string, string> = {
  low: "fastest and cheapest; mechanical edits, lookups",
  medium: "balanced — the default for most work",
  high: "thorough; multi-file reasoning and architecture",
  xhigh: "slowest, priciest; only for genuinely hard problems",
};

/**
 * Format the daemon's answer as one line, e.g. `high — 1.0 / 48k`.
 *
 * <p>Shared by both renderers so the two cannot describe the same
 * preset differently — the rule R375 learned the hard way.
 *
 * @param v - the daemon's `activeVariant`, or nothing
 * @returns the one-line description
 */
export function variantLine(v: VariantInfo | null | undefined): string {
  if (!v || !v.name) return "unknown";
  const knobs: string[] = [];
  if (typeof v.temperature === "number") knobs.push(v.temperature.toFixed(1));
  if (typeof v.maxTokens === "number") knobs.push(`${Math.round(v.maxTokens / 1000)}k`);
  if (knobs.length === 0) return v.name;
  const think = v.extendedThinking ? " + think" : "";
  return `${v.name} — ${knobs.join(" / ")}${think}`;
}
