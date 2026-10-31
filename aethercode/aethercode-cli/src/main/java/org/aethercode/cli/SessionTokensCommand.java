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

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §7.2, design.md §3.5): the
 * {@code ac session tokens <id>} subcommand. Prints a
 * session's input / output / cached token counts and the
 * estimated cost in USD (computed from the active model's
 * {@code ModelProfile.pricing}).
 *
 * <p>The RPC is a thin stub for now; the actual
 * {@code session/tokens} implementation is owned by the
 * session side (Java-3). Until that lands, this command
 * returns a clear "not implemented" message and exit 1.
 */
@Command(
        name = "tokens",
        description = "Show input/output tokens + cost for a session (T-2-18).")
public class SessionTokensCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Session id (e.g. c-1234).")
    String id;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/tokens", Map.of("id", id));
        if (r.containsKey("error") || !r.containsKey("tokensIn")) {
            // The RPC is a stub at this stage. Print a clean
            // message + exit 1 so the user knows the
            // command is wired but the upstream RPC isn't
            // ready yet.
            Object err = r.get("error");
            if (err != null) {
                System.err.println("session.tokens: " + err);
            } else {
                System.err.println("session.tokens: session/tokens RPC is not yet "
                        + "implemented (stub from Java-3) — the CLI command is wired and "
                        + "will work as soon as the supervisor exposes session/tokens");
            }
            return 1;
        }
        System.out.println("session: " + id);
        System.out.println("  tokensIn:    " + r.getOrDefault("tokensIn", 0));
        System.out.println("  tokensOut:   " + r.getOrDefault("tokensOut", 0));
        System.out.println("  cachedIn:    " + r.getOrDefault("cachedIn", 0));
        System.out.println("  costUsd:     $" + r.getOrDefault("costUsd", 0.0));
        return 0;
    }
}
