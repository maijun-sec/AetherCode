/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-18 acceptance test for {@link SessionTokensCommand}.
 * The RPC is a stub from Java-3 — the test verifies the
 * command's "not yet implemented" fallback path. Once
 * session/tokens lands, the success path is exercised by
 * the same code shape.
 */
class SessionTokensCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream oldOut, oldErr;

    @BeforeEach
    void setUp() {
        oldOut = System.out;
        oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(oldOut);
        System.setErr(oldErr);
        SupervisorRpc.resetToDefault();
    }

    @Test
    void tokensPrintsFormattedOutputOnSuccess() throws Exception {
        SupervisorRpc.installForTesting((method, params) -> Map.of(
                "tokensIn", 12345L,
                "tokensOut", 6789L,
                "cachedIn", 4000L,
                "costUsd", 0.1234));
        SessionTokensCommand cmd = new SessionTokensCommand();
        cmd.id = "c-123";
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("session: c-123")
                .contains("tokensIn:    12345")
                .contains("tokensOut:   6789")
                .contains("cachedIn:    4000")
                .contains("costUsd:     $0.1234");
    }
}
