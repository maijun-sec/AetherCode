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
 * T-2-18 acceptance test for {@link SessionDeleteCommand}.
 * Verifies the soft-delete envelope and the "Trash" hint in
 * the success message.
 */
class SessionDeleteCommandTest {

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
    void deleteSendsIdAndPrintsSuccessHint() throws Exception {
        Map<String, Object> seen = new java.util.HashMap<>();
        SupervisorRpc.installForTesting((method, params) -> {
            seen.putAll(params);
            return Map.of("ok", true);
        });
        SessionDeleteCommand cmd = new SessionDeleteCommand();
        cmd.id = "c-123";
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        assertThat(seen).containsEntry("id", "c-123");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("deleted c-123")
                .contains("Trash")
                .contains("ac session restore c-123");
    }
}
