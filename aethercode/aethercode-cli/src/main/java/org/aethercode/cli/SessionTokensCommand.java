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
