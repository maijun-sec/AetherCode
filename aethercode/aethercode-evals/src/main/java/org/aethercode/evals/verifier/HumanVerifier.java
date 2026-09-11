package org.aethercode.evals.verifier;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Human-in-the-loop verifier. The agent loop uses this to gate high-
 * risk actions on a human confirming them.
 *
 * <p>The verifier is a placeholder for the prompt that asks the human
 * (or, in tests, a stub function). The actual UI integration lives
 * in {@code aethercode-tools/interactive/AskUserQuestionTool} (and the
 * Desktop app). This class only defines the contract so the agent
 * loop can compose it with other verifiers without knowing the UI
 * plumbing.</p>
 *
 * <p>Three modes:</p>
 * <ul>
 *   <li><b>Configured callback</b> — production path; pass a
 *       {@link Function} that asks the human and returns a verdict.
 *       Tests use this to simulate a yes/no click.</li>
 *   <li><b>Auto-deny</b> — if no callback is configured, every
 *       verification is {@code FAIL} at {@code BLOCK} severity. This
 *       is the safe default: a misconfigured loop that tries to use
 *       a Human verifier without wiring it up will not silently let
 *       the action through.</li>
 *   <li><b>Async / future-based</b> — the callback returns a
 *       {@link CompletableFuture} so the agent loop can time out
 *       the human response and fall back to a {@code WARN}.</li>
 * </ul>
 *
 * <p>Timeout: {@link #verify(String)} waits up to {@code timeoutS}
 * seconds for the callback's future to complete; if the wait times
 * out, the verifier returns {@code FAIL} at {@code WARN} severity
 * (the human didn't respond, but a slow human is not the same as a
 * deny — surface the gap, don't block forever).</p>
 */
public class HumanVerifier implements Verifier<String> {

    /** Decision the human made about the candidate action. */
    public enum Decision { APPROVE, REJECT, ABSTAIN }

    /** Hook a UI uses to ask the human and resolve when they answer. */
    @FunctionalInterface
    public interface AskHuman {
        CompletableFuture<Decision> ask(String summary);
    }

    private final String name;
    private final String summaryBuilder;
    private final AskHuman askHuman;
    private final int timeoutS;

    public HumanVerifier(String name, String summaryBuilder) {
        this(name, summaryBuilder, null, 30);
    }

    public HumanVerifier(String name, String summaryBuilder, AskHuman askHuman, int timeoutS) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (summaryBuilder == null) summaryBuilder = "";
        if (timeoutS < 1) throw new IllegalArgumentException("timeoutS must be >= 1");
        this.name = name;
        this.summaryBuilder = summaryBuilder;
        this.askHuman = askHuman;
        this.timeoutS = timeoutS;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() {
        return "Human gate: " + (askHuman == null ? "auto-deny (unwired)" : "wired");
    }

    @Override
    public VerificationResult verify(String input) {
        if (askHuman == null) {
            return VerificationResult.fail(Verifier.Severity.BLOCK,
                    "human gate not wired; defaulting to deny",
                    Map.of("verifier", name, "wired", false));
        }
        String summary = renderSummary(input);
        CompletableFuture<Decision> future;
        try {
            future = askHuman.ask(summary);
        } catch (RuntimeException ex) {
            return VerificationResult.fail(Verifier.Severity.BLOCK,
                    "ask-human callback threw: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    Map.of("verifier", name, "exception", ex.getClass().getName()));
        }
        if (future == null) {
            return VerificationResult.fail(Verifier.Severity.BLOCK, "ask-human returned null future",
                    Map.of("verifier", name));
        }
        Decision decision;
        try {
            decision = future.get(timeoutS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            return VerificationResult.fail(Verifier.Severity.WARN,
                    "human did not respond within " + timeoutS + "s",
                    Map.of("verifier", name, "timed_out", true));
        } catch (java.util.concurrent.ExecutionException ex) {
            return VerificationResult.fail(Verifier.Severity.BLOCK,
                    "ask-human future failed: " + ex.getCause(),
                    Map.of("verifier", name, "exception",
                            ex.getCause() == null ? "?" : ex.getCause().getClass().getName()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return VerificationResult.fail(Verifier.Severity.BLOCK, "interrupted while waiting on human",
                    Map.of("verifier", name, "interrupted", true));
        }
        return switch (decision) {
            case APPROVE -> VerificationResult.pass("human approved", Map.of("verifier", name));
            case REJECT -> VerificationResult.fail(Verifier.Severity.BLOCK, "human rejected",
                    Map.of("verifier", name, "decision", "REJECT"));
            case ABSTAIN -> VerificationResult.fail(Verifier.Severity.WARN, "human abstained",
                    Map.of("verifier", name, "decision", "ABSTAIN"));
        };
    }

    private String renderSummary(String input) {
        if (summaryBuilder.isEmpty()) {
            String preview = input == null ? "" :
                    input.length() > 200 ? input.substring(0, 200) + "..." : input;
            return "Approve action with output:\n\n" + preview;
        }
        return summaryBuilder + "\n\n" + (input == null ? "" : input);
    }

    public boolean isWired() { return askHuman != null; }

    public int timeoutS() { return timeoutS; }
}
