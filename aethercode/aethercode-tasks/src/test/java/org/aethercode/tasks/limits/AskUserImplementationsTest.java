package org.aethercode.tasks.limits;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R240 (O-5): tests for {@link AskUserImplementations}. The headless
 * prompter is the interesting one — we feed it a fake stdin and
 * verify the {@code c}/{@code r}/anything-else → decision mapping,
 * plus the timeout → cancel contract.
 */
class AskUserImplementationsTest {

    private static LimitsPausePolicy.PausePrompt prompt(Limits limits, int actual) {
        return new LimitsPausePolicy.PausePrompt(
                "child-1",
                List.of(new LimitsEnforcer.LimitHit("tokens", limits.tokens(), actual)),
                limits,
                "/tmp",
                "do the thing");
    }

    private static Limits limited() {
        return Limits.builder().tokens(100L).wallClockMs(10_000L).build();
    }

    @Test
    void autoRaiseAlwaysRaises() {
        LimitsPausePolicy.AskUser u = AskUserImplementations.autoRaise();
        LimitsPausePolicy.Decision d = u.ask(prompt(limited(), 250));
        assertEquals(LimitsPausePolicy.Decision.RAISE, d);
    }

    @Test
    void autoCancelAlwaysCancels() {
        LimitsPausePolicy.AskUser u = AskUserImplementations.autoCancel();
        LimitsPausePolicy.Decision d = u.ask(prompt(limited(), 250));
        assertEquals(LimitsPausePolicy.Decision.CANCEL, d);
    }

    @Test
    void headlessContinue() {
        InputStream in = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        LimitsPausePolicy.AskUser u = AskUserImplementations.headless(in, 2_000L);
        assertEquals(LimitsPausePolicy.Decision.CONTINUE, u.ask(prompt(limited(), 250)));
    }

    @Test
    void headlessRaise() {
        InputStream in = new ByteArrayInputStream("r\n".getBytes(StandardCharsets.UTF_8));
        LimitsPausePolicy.AskUser u = AskUserImplementations.headless(in, 2_000L);
        assertEquals(LimitsPausePolicy.Decision.RAISE, u.ask(prompt(limited(), 250)));
    }

    @Test
    void headlessFullWordContinue() {
        InputStream in = new ByteArrayInputStream("continue\n".getBytes(StandardCharsets.UTF_8));
        LimitsPausePolicy.AskUser u = AskUserImplementations.headless(in, 2_000L);
        assertEquals(LimitsPausePolicy.Decision.CONTINUE, u.ask(prompt(limited(), 250)));
    }

    @Test
    void headlessUnknownIsCancel() {
        InputStream in = new ByteArrayInputStream("zzz\n".getBytes(StandardCharsets.UTF_8));
        LimitsPausePolicy.AskUser u = AskUserImplementations.headless(in, 2_000L);
        assertEquals(LimitsPausePolicy.Decision.CANCEL, u.ask(prompt(limited(), 250)));
    }

    @Test
    void headlessTimeoutIsCancel() {
        // 0-ms timeout returns null from the reader, which the
        // prompter maps to CANCEL.
        InputStream in = new ByteArrayInputStream("ignored\n".getBytes(StandardCharsets.UTF_8));
        LimitsPausePolicy.AskUser u = AskUserImplementations.headless(in, 0L);
        assertEquals(LimitsPausePolicy.Decision.CANCEL, u.ask(prompt(limited(), 250)));
    }

    @Test
    void headlessEofIsCancel() {
        // Empty stream — readLine returns null immediately.
        InputStream in = new ByteArrayInputStream(new byte[0]);
        LimitsPausePolicy.AskUser u = AskUserImplementations.headless(in, 2_000L);
        assertEquals(LimitsPausePolicy.Decision.CANCEL, u.ask(prompt(limited(), 250)));
    }

    @Test
    void renderSummaryIsNonEmpty() {
        String s = AskUserImplementations.renderSummary(prompt(limited(), 250));
        assertNotNull(s);
        // Expect the limit name + actual + limit to appear.
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("tokens"));
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("250"));
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("100"));
    }

    @Test
    void parseTimeoutMsUsesFallbackForBadValue() {
        assertEquals(1234L, AskUserImplementations.parseTimeoutMs("1234", 5000L));
        assertEquals(5000L, AskUserImplementations.parseTimeoutMs("not-a-number", 5000L));
        assertEquals(5000L, AskUserImplementations.parseTimeoutMs(null, 5000L));
        assertEquals(5000L, AskUserImplementations.parseTimeoutMs("", 5000L));
        assertEquals(5000L, AskUserImplementations.parseTimeoutMs("-5", 5000L));
    }
}
