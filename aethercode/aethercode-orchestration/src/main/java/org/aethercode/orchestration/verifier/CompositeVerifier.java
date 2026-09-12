package org.aethercode.orchestration.verifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compose multiple verifiers into a single check with a configurable
 * policy.
 *
 * <p>Three policies, mirroring the paper's "verify-aware planning"
 * guidance (Radar direction #4 in §VII):</p>
 * <ul>
 *   <li><b>ALL_MUST_PASS</b> — every verifier must pass. Used for
 *       safety-critical paths (anything that touches credentials, prod
 *       data, or the network).</li>
 *   <li><b>ANY_MUST_PASS</b> — at least one verifier must pass. Used
 *       for permissive fallbacks (allow if either schema or llm-judge
 *       says ok).</li>
 *   <li><b>MIN_THRESHOLD</b> — at least {@code k} of {@code n} verifiers
 *       must pass. Used for soft ensembles (e.g. 2 of 3 llm-judges
 *       say pass → action proceeds).</li>
 * </ul>
 *
 * <p>Severity handling:</p>
 * <ul>
 *   <li>{@code ALL_MUST_PASS}: any {@code BLOCK} failure → composite fails
 *       with the highest-severity reason surfaced.</li>
 *   <li>{@code ANY_MUST_PASS}: composite passes if any single verifier
 *       passes; the highest-severity pass is surfaced.</li>
 *   <li>{@code MIN_THRESHOLD}: as {@code ALL_MUST_PASS} but the count
 *       of passes is checked against the threshold.</li>
 * </ul>
 *
 * <p>Composite metadata: each composite result carries the per-verifier
 * outcomes in {@code metadata["verifier_results"]} so the agent loop
 * and eval reports can attribute a failure to a specific sub-check.</p>
 */
public class CompositeVerifier<T> implements Verifier<T> {

    public enum Policy { ALL_MUST_PASS, ANY_MUST_PASS, MIN_THRESHOLD }

    private final String name;
    private final String description;
    private final List<Verifier<T>> verifiers;
    private final Policy policy;
    private final int minThreshold;

    public CompositeVerifier(String name, List<Verifier<T>> verifiers, Policy policy) {
        this(name, "", verifiers, policy, 0);
    }

    public CompositeVerifier(String name, String description,
                             List<Verifier<T>> verifiers, Policy policy, int minThreshold) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (verifiers == null || verifiers.isEmpty()) {
            throw new IllegalArgumentException("verifiers must be non-empty");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must be non-null");
        }
        if (policy == Policy.MIN_THRESHOLD && (minThreshold < 1 || minThreshold > verifiers.size())) {
            throw new IllegalArgumentException(
                    "MIN_THRESHOLD requires 1 <= minThreshold <= verifiers.size(), got " + minThreshold);
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.verifiers = List.copyOf(verifiers);
        this.policy = policy;
        this.minThreshold = minThreshold;
    }

    public static <T> CompositeVerifier<T> allMustPass(String name, List<Verifier<T>> verifiers) {
        return new CompositeVerifier<>(name, verifiers, Policy.ALL_MUST_PASS);
    }

    public static <T> CompositeVerifier<T> anyMustPass(String name, List<Verifier<T>> verifiers) {
        return new CompositeVerifier<>(name, verifiers, Policy.ANY_MUST_PASS);
    }

    public static <T> CompositeVerifier<T> minThreshold(String name, List<Verifier<T>> verifiers, int k) {
        return new CompositeVerifier<>(name, "", verifiers, Policy.MIN_THRESHOLD, k);
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return description; }

    @Override
    public VerificationResult verify(T input) {
        List<VerificationResult> results = new ArrayList<>(verifiers.size());
        for (Verifier<T> v : verifiers) {
            VerificationResult r;
            try {
                r = v.verify(input);
            } catch (RuntimeException ex) {
                r = VerificationResult.fail(Verifier.Severity.BLOCK,
                        "verifier crashed: " + v.name() + " (" + ex.getClass().getSimpleName() + ")",
                        Map.of("exception", ex.getClass().getName(), "message", String.valueOf(ex.getMessage())));
            }
            results.add(r);
        }
        return switch (policy) {
            case ALL_MUST_PASS -> allMustPass(results);
            case ANY_MUST_PASS -> anyMustPass(results);
            case MIN_THRESHOLD -> minThreshold(results);
        };
    }

    private VerificationResult allMustPass(List<VerificationResult> results) {
        // Find the highest-severity failure (BLOCK > WARN > INFO).
        VerificationResult worst = null;
        for (VerificationResult r : results) {
            if (r.passed()) continue;
            if (worst == null || r.severity().ordinal() > worst.severity().ordinal()) {
                worst = r;
            }
        }
        if (worst == null) {
            return VerificationResult.pass("all " + results.size() + " verifiers passed",
                    wrapMetadata(results, Map.of("policy", "ALL_MUST_PASS", "passed", results.size())));
        }
        return VerificationResult.fail(worst.severity(),
                "composite(" + name + ") failed at " + worstReason(worst) + ": " + worst.reason(),
                wrapMetadata(results, Map.of(
                        "policy", "ALL_MUST_PASS",
                        "passed", countPassed(results),
                        "total", results.size(),
                        "failed_verifier", worstReason(worst))));
    }

    private VerificationResult anyMustPass(List<VerificationResult> results) {
        VerificationResult best = null;
        for (VerificationResult r : results) {
            if (!r.passed()) continue;
            if (best == null || r.severity().ordinal() < best.severity().ordinal()) {
                best = r;
            }
        }
        if (best != null) {
            return VerificationResult.pass("any-of passed via " + bestReason(best),
                    wrapMetadata(results, Map.of("policy", "ANY_MUST_PASS", "passed", countPassed(results))));
        }
        return VerificationResult.fail(Verifier.Severity.BLOCK,
                "composite(" + name + ") failed: no verifier passed",
                wrapMetadata(results, Map.of("policy", "ANY_MUST_PASS", "passed", 0)));
    }

    private VerificationResult minThreshold(List<VerificationResult> results) {
        int passed = countPassed(results);
        if (passed >= minThreshold) {
            return VerificationResult.pass(
                    passed + " of " + results.size() + " verifiers passed (>= " + minThreshold + ")",
                    wrapMetadata(results, Map.of(
                            "policy", "MIN_THRESHOLD",
                            "threshold", minThreshold,
                            "passed", passed,
                            "total", results.size())));
        }
        return VerificationResult.fail(Verifier.Severity.BLOCK,
                "composite(" + name + ") failed: " + passed + " of " + results.size() +
                        " passed (< " + minThreshold + ")",
                wrapMetadata(results, Map.of(
                        "policy", "MIN_THRESHOLD",
                        "threshold", minThreshold,
                        "passed", passed,
                        "total", results.size())));
    }

    private static int countPassed(List<VerificationResult> results) {
        int n = 0;
        for (VerificationResult r : results) if (r.passed()) n++;
        return n;
    }

    private static String worstReason(VerificationResult r) {
        return r.metadata().getOrDefault("verifier", "?").toString();
    }

    private static String bestReason(VerificationResult r) {
        return r.metadata().getOrDefault("verifier", "?").toString();
    }

    private static Map<String, Object> wrapMetadata(List<VerificationResult> results, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(extra);
        m.put("verifier_results", results);
        return m;
    }

    public List<Verifier<T>> verifiers() { return verifiers; }

    public Policy policy() { return policy; }

    public int minThreshold() { return minThreshold; }
}
