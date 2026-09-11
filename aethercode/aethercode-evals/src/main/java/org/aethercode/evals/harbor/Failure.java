package org.aethercode.evals.harbor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Failure classification for eval trial results.
 *
 * <p>Categorizes failures as infrastructure (OOM, timeout, sandbox) vs. model
 * capability using exit codes and text pattern matching. Java 21 port of
 * {@code deepagents_harbor.failure}.</p>
 */
public final class Failure {

    private static final Logger LOGGER = LoggerFactory.getLogger(Failure.class);

    /** Classification of trial failures. */
    public enum FailureCategory {
        /** Model produced wrong answer, incomplete solution, or logic error. */
        CAPABILITY,
        /** Out-of-memory kill (exit code 137 / signal 9). */
        INFRA_OOM,
        /** Command or task exceeded time limit (exit code 124). */
        INFRA_TIMEOUT,
        /** Sandbox crash, network failure, or other environment error. */
        INFRA_SANDBOX,
        /** Could not determine failure category. */
        UNKNOWN;

        /**
         * Whether this failure is caused by infrastructure rather than
         * model capability.
         */
        public boolean isInfrastructure() {
            return this == INFRA_OOM
                    || this == INFRA_TIMEOUT
                    || this == INFRA_SANDBOX;
        }
    }

    /** Exit codes indicating the process was killed due to out-of-memory. */
    private static final Set<Integer> OOM_EXIT_CODES = Set.of(137);

    /** Exit codes indicating the process exceeded a time limit. */
    private static final Set<Integer> TIMEOUT_EXIT_CODES = Set.of(124);

    /** Case-insensitive substrings in exception text that signal an OOM kill. */
    private static final List<String> OOM_PATTERNS = List.of(
            "oomkilled",
            "out of memory",
            "cannot allocate memory",
            "memory allocation failed",
            "signal 9",
            "sigkill",
            "exit code 137");

    /** Case-insensitive substrings in exception text that signal a timeout. */
    private static final List<String> TIMEOUT_PATTERNS = List.of(
            "timed out",
            "deadline exceeded",
            "exit code 124");

    /** Case-insensitive substrings in exception text that signal sandbox / network failures. */
    private static final List<String> SANDBOX_PATTERNS = List.of(
            "sandbox crashed",
            "sandbox exited unexpectedly",
            "sandbox error",
            "sandbox failure",
            "connection refused",
            "connection reset",
            "broken pipe",
            "network unreachable",
            "no route to host",
            "exec failed");

    /** Match exit_code / exit code / exit-code variants (dot is a wildcard). */
    private static final Pattern EXIT_CODE_RE = Pattern.compile(
            "(?:exit.code[\"\\s:]+)(\\d+)", Pattern.CASE_INSENSITIVE);

    private Failure() {}

    /**
     * Extract non-zero exit codes from ATIF trajectory observation results.
     *
     * <p>Parses the trajectory JSON structurally and only searches
     * observation content (tool output) for exit code patterns, avoiding
     * false positives from model-generated text that discusses exit
     * codes.</p>
     *
     * @param trajectoryJson raw JSON text of the ATIF trajectory
     * @return the list of non-zero exit codes found in observation results
     */
    public static List<Integer> extractExitCodes(String trajectoryJson) {
        if (trajectoryJson == null) {
            return List.of();
        }
        List<String> observationTexts = extractObservationTexts(trajectoryJson);
        if (observationTexts == null) {
            // Fall back to regex on raw text if parsing fails (e.g. non-ATIF input)
            return extractExitCodesRaw(trajectoryJson);
        }
        if (observationTexts.isEmpty()) {
            return List.of();
        }
        List<Integer> codes = new ArrayList<>();
        for (String text : observationTexts) {
            codes.addAll(extractExitCodesRaw(text));
        }
        return codes;
    }

    /**
     * Classify a trial failure as infrastructure or capability.
     *
     * <p>Uses exit codes and exception text to determine whether a failure
     * was caused by infrastructure issues (OOM, timeout, sandbox crash) or
     * by the model's capability. Pattern matching is restricted to
     * {@code exceptionText} only (structured, controlled output) to avoid
     * false positives from model-generated content in trajectories.</p>
     *
     * @param exceptionText content of {@code exception.txt} if present
     * @param exitCodes     list of non-zero exit codes observed during the trial
     * @return the determined failure category
     */
    public static FailureCategory classifyFailure(String exceptionText, List<Integer> exitCodes) {
        // Check exit codes first (most reliable signal)
        if (exitCodes != null) {
            for (int code : exitCodes) {
                if (OOM_EXIT_CODES.contains(code)) {
                    return FailureCategory.INFRA_OOM;
                }
                if (TIMEOUT_EXIT_CODES.contains(code)) {
                    return FailureCategory.INFRA_TIMEOUT;
                }
            }
        }

        // Pattern match only against exception text (not trajectory)
        if (exceptionText != null && !exceptionText.isEmpty()) {
            String lower = exceptionText.toLowerCase();

            for (String p : OOM_PATTERNS) {
                if (lower.contains(p)) {
                    return FailureCategory.INFRA_OOM;
                }
            }
            for (String p : TIMEOUT_PATTERNS) {
                if (lower.contains(p)) {
                    return FailureCategory.INFRA_TIMEOUT;
                }
            }
            for (String p : SANDBOX_PATTERNS) {
                if (lower.contains(p)) {
                    return FailureCategory.INFRA_SANDBOX;
                }
            }

            // Exception present but no infra signals -- ambiguous
            return FailureCategory.UNKNOWN;
        }

        // No exception, no infra exit codes -- capability failure
        return FailureCategory.CAPABILITY;
    }

    /** Convenience overload with no exit codes. */
    public static FailureCategory classifyFailure(String exceptionText) {
        return classifyFailure(exceptionText, null);
    }

    /**
     * Extract observation result content from parsed ATIF trajectory JSON.
     *
     * <p>Only returns text from observation results (tool outputs).</p>
     *
     * @param trajectoryJson raw JSON text of the trajectory
     * @return the list of observation content strings, or {@code null} if
     *         the JSON could not be parsed as a valid ATIF trajectory
     *         (triggers raw fallback)
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractObservationTexts(String trajectoryJson) {
        Object parsed;
        try {
            parsed = JsonLoaders.parseJsonLenient(trajectoryJson);
        } catch (Exception ex) {
            LOGGER.debug("Failed to parse trajectory JSON for observation extraction");
            return null;
        }
        if (!(parsed instanceof Map<?, ?> data)) {
            return null;
        }
        if (!data.containsKey("steps")) {
            return null;
        }
        Object stepsObj = data.get("steps");
        if (!(stepsObj instanceof List<?> steps)) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Object stepObj : steps) {
            if (!(stepObj instanceof Map<?, ?> step)) {
                continue;
            }
            Object obs = step.get("observation");
            if (!(obs instanceof Map<?, ?> obsMap)) {
                continue;
            }
            Object resultsObj = obsMap.get("results");
            if (!(resultsObj instanceof List<?> results)) {
                continue;
            }
            for (Object resultObj : results) {
                if (!(resultObj instanceof Map<?, ?> result)) {
                    continue;
                }
                Object content = result.get("content");
                if (content instanceof String s) {
                    texts.add(s);
                } else if (content instanceof List<?> parts) {
                    // ContentPart list (ATIF v1.6+)
                    for (Object partObj : parts) {
                        if (partObj instanceof Map<?, ?> part
                                && part.get("text") instanceof String text) {
                            texts.add(text);
                        }
                    }
                }
            }
        }
        return texts;
    }

    /** Extract non-zero exit codes from a text string using regex. */
    private static List<Integer> extractExitCodesRaw(String text) {
        if (text == null) {
            return List.of();
        }
        List<Integer> codes = new ArrayList<>();
        Matcher matcher = EXIT_CODE_RE.matcher(text);
        while (matcher.find()) {
            int code = Integer.parseInt(matcher.group(1));
            if (code != 0) {
                codes.add(code);
            }
        }
        return codes;
    }
}
