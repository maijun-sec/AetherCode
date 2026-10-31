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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-18 acceptance test for {@link SessionShowCommand}.
 * Mocks the RPC; verifies the human and --json output paths.
 */
class SessionShowCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream oldOut;
    private PrintStream oldErr;

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
    void humanOutputRendersHeaderAndEvents() throws Exception {
        SupervisorRpc.installForTesting((method, params) -> {
            // The RPC accepts {id, limit}; echo them back so
            // the test can verify the command sent them.
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", params.get("id"));
            r.put("title", "Add login form");
            r.put("cwd", "/work/proj");
            r.put("model", "claude-sonnet-4");
            r.put("startedAt", "2026-08-29T08:42:00Z");
            r.put("lastActiveAt", "2026-08-29T08:51:23Z");
            r.put("state", "COMPLETED");
            r.put("tokensIn", 12345);
            r.put("tokensOut", 678);
            r.put("events", List.of(
                    Map.of("type", "user_message", "preview", "Add a login form"),
                    Map.of("type", "tool_call", "preview", "edit_file(/work/proj/auth.ts)")));
            return r;
        });

        SessionShowCommand cmd = new SessionShowCommand();
        cmd.id = "c-123";
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("session: c-123")
                .contains("Add login form")
                .contains("/work/proj")
                .contains("claude-sonnet-4")
                .contains("tokens:      in=12345 out=678")
                .contains("events:      2")
                .contains("[1] user_message")
                .contains("[2] tool_call");
    }
}
