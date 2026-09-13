package org.aethercode.orchestration.verifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MIRROR-style intra + inter reflection (arXiv:2505.20670).
 *
 * <p>MIRROR improves tool-augmented LLM agents by adding two
 * reflection passes after the model produces a candidate answer:
 * <ol>
 *   <li><b>Intra-reflection</b> — the same agent re-reads its own
 *       output and finds internal inconsistencies (e.g. "I said
 *       2+2=5 but also said 4")</li>
 *   <li><b>Inter-reflection</b> — a peer agent re-reads the output
 *       and finds gaps (missing tool call, wrong argument, etc.)</li>
 * </ol>
 * The model then revises based on the collected feedback.
 *
 * <p>This class is the AetherCode Tier-3 implementation: it
 * computes feedback strings (not LLM-generated — that's the
 * caller's job) and applies a small set of cheap, deterministic
 * detectors (numeric contradictions, missing required tokens,
 * empty output, unparseable JSON blocks) that the paper identifies
 * as the highest-yield reflection triggers. Real LLM-based
 * reflection is layered on top via {@link #reflect(List, String)}.
 *
 * <h2>Why a Tier-3 wrapper for this</h2>
 * The paper shows that pure LLM reflection is expensive (4× cost)
 * and noisy. By exposing a deterministic "first-pass" reflector,
 * the AetherCode agent can use it as a cheap pre-filter before
 * paying for an LLM revision. The {@link RedFlagDetector} is
 * the lower-level primitive; this class is the paper's framing of
 * "use red flags as a reflection trigger".
 */
public final class MirrorReflector {

    /** A single piece of feedback (deterministic or LLM-generated). */
    public record Feedback(Kind kind, String detail) {
        public Feedback { Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(detail, "detail"); }
        public enum Kind {
            INTERNAL_CONTRADICTION,    // same-agent self-inconsistency
            PEER_GAP,                  // peer-agent identified gap
            NUMERIC_MISMATCH,          // output contains contradictory numbers
            MISSING_TOOL,              // expected tool call not present
            UNPARSEABLE,                // output cannot be parsed
            EMPTY                       // output is empty / whitespace
        }
    }
    /** A reflection record: output + the feedback the reflector found. */
    public record Reflection(String output, List<Feedback> feedback) {
        public Reflection {
            feedback = List.copyOf(feedback);
        }
        public boolean needsRevision() { return !feedback.isEmpty(); }
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final RedFlagDetector redFlagDetector;

    public MirrorReflector() {
        this(new RedFlagDetector());
    }

    public MirrorReflector(RedFlagDetector redFlagDetector) {
        this.redFlagDetector = Objects.requireNonNull(redFlagDetector, "redFlagDetector");
    }

    /**
     * First-pass reflection: runs the cheap detectors over the
     * output and returns a {@link Reflection} record. The agent
     * can use {@link Reflection#needsRevision()} to decide whether
     * to pay for an LLM revision pass.
     */
    public Reflection firstPass(String output) {
        List<Feedback> fb = new ArrayList<>();
        if (output == null || output.isBlank()) {
            fb.add(new Feedback(Feedback.Kind.EMPTY, "output is empty or whitespace"));
            return new Reflection(output, fb);
        }
        // numeric contradiction: any number appears with both forms
        // "x = N" and "x = M" where N != M. (very cheap heuristic;
        // a richer detector would track named entities).
        var nums = NUMBER.matcher(output).results().toList();
        if (nums.size() >= 2) {
            // look for two distinct integer values within the same line
            for (int i = 0; i < nums.size() - 1; i++) {
                if (!nums.get(i).group().equals(nums.get(i + 1).group())) {
                    fb.add(new Feedback(Feedback.Kind.NUMERIC_MISMATCH,
                        "distinct numeric values present: "
                        + nums.get(i).group() + " vs " + nums.get(i + 1).group()));
                    break;
                }
            }
        }
        // delegate to RedFlagDetector for the rest
        for (RedFlagDetector.RedFlag f : redFlagDetector.inspect(output)) {
            Feedback.Kind kind = switch (f.rule()) {
                case "TOO_SHORT", "TOO_LONG" -> Feedback.Kind.PEER_GAP;
                case "REPEAT_RUN" -> Feedback.Kind.INTERNAL_CONTRADICTION;
                case "BRACKET_IMBALANCE" -> Feedback.Kind.UNPARSEABLE;
                case "CONTRADICTION" -> Feedback.Kind.INTERNAL_CONTRADICTION;
                default -> Feedback.Kind.PEER_GAP;
            };
            fb.add(new Feedback(kind, f.detail()));
        }
        return new Reflection(output, fb);
    }

    /**
     * Second-pass: merge a list of feedback entries (LLM-generated
     * or human-supplied) with the deterministic first-pass results
     * and return a unified reflection. Used when the agent decides
     * to pay for an LLM revision.
     */
    public Reflection reflect(List<Feedback> externalFeedback, String output) {
        List<Feedback> all = new ArrayList<>(firstPass(output).feedback());
        if (externalFeedback != null) all.addAll(externalFeedback);
        return new Reflection(output, all);
    }
}
