package org.aethercode.a2a;

import org.aethercode.a2a.schema.AgentCard;

import java.util.List;
import java.util.Map;

/**
 * minimal entry point that runs an {@link A2AServer}
 * against an in-process stdin/stdout line loop. Mirrors the
 * shape of {@code aethercode-acp}'s default {@code Main} —
 * a thin glue layer so the protocol is runnable without
 * pulling in a transport binding.
 *
 * <p>Real HTTP / SSE / gRPC transports are out of scope for
 * the first cut; the host application (aethercode-cli, an IDE,
 * a microservice) is expected to wire {@link A2AServer#handleLine}
 * into its own I/O loop.</p>
 *
 * <p>R250d: when {@code --http <port>} is passed on the
 * command line, the {@link A2AHttpTransport} is started
 * instead of the stdin loop. Default card's
 * {@code url} is rewritten to point at the bound port so
 * a remote client can read {@code /.well-known/agent.json}
 * and follow the URL. The two modes are mutually exclusive
 * — pick one at startup.</p>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Parse a tiny subset of flags. We keep it
        // hand-rolled (no args4j) to avoid pulling in
        // a dep just for two flags.
        Integer httpPort = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--http".equals(args[i])) {
                try {
                    httpPort = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException nfe) {
                    System.err.println("aethercode-a2a: --http expects a numeric port, got '"
                            + args[i + 1] + "'");
                    System.exit(2);
                }
            }
        }
        if (httpPort != null) {
            runHttp(httpPort);
            return;
        }
        runStdio();
    }

    private static void runStdio() {
        AgentCard card = defaultCard();
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        // Line-by-line read from stdin. The transport is the
        // caller's job; this main is just a developer-friendly
        // smoke-test for the protocol shape.
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
             var writer = new java.io.PrintWriter(System.out, true)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String reply = server.handleLine(line);
                if (reply != null) {
                    writer.println(reply);
                }
            }
        } catch (Exception e) {
            System.err.println("aethercode-a2a: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /** HTTP mode. Block forever (server.stop is wired
     *  to a JVM shutdown hook so Ctrl-C / SIGTERM still tears
     *  it down cleanly). */
    private static void runHttp(int port) {
        AgentCard base = defaultCard();
        // Patch the card's URL so a client reading
        // /.well-known/agent.json sees the actual bound port.
        // We re-create the card with a port-correct url;
        // the original is a record-like so we go through
        // a toMap/fromMap round-trip.
        AgentCard card;
        try {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) m.convertValue(base, java.util.Map.class);
            map.put("url", "http://localhost:" + port + "/a2a");
            card = m.convertValue(map, AgentCard.class);
        } catch (Exception e) {
            System.err.println("aethercode-a2a: failed to patch card url: " + e.getMessage());
            card = base;
        }
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        A2AHttpTransport transport = new A2AHttpTransport(server);
        try {
            transport.start(port);
        } catch (Exception e) {
            System.err.println("aethercode-a2a: failed to start HTTP transport on port "
                    + port + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
        // Wire a shutdown hook so Ctrl-C / SIGTERM still tears
        // the server down cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("aethercode-a2a: shutting down");
            transport.stop();
        }, "aethercode-a2a-shutdown"));
        // Block forever. The transport's executor runs
        // request threads; this main thread just waits.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** A small example Agent Card so the default main advertises something sensible. */
    public static AgentCard defaultCard() {
        return new AgentCard(
                "AetherCode A2A Demo Agent",
                "Echoes the user message back as a single text artifact.",
                "0.1.0",
                "http://localhost:0/a2a",
                new AgentCard.Provider("AetherCode", "https://aethercode.org"),
                List.of(new AgentCard.Skill(
                        "echo",
                        "echo",
                        "Returns the user's text verbatim inside a text artifact.",
                        List.of("text"),
                        List.of("text"))),
                new AgentCard.Capabilities(false, false, true),
                AgentCard.Authentication.open());
    }
}
