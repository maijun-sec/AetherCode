package org.aethercode.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.A2AClient;
import org.aethercode.a2a.A2AHttpTransport;
import org.aethercode.a2a.schema.AgentCard;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Parent command for {@code aethercode a2a ...} subcommands. Talks to a remote
 * A2A agent from the CLI — fetch its card, send messages, subscribe to streaming
 * tasks, look tasks up by id, and cancel them.
 *
 * <p>Usage:</p>
 * <pre>
 *   aethercode a2a host:port card                # print the Agent Card
 *   aethercode a2a host:port send "..."           # message/send
 *   aethercode a2a host:port stream "..."         # message/sendSubscribe, events as they arrive
 *   aethercode a2a host:port get    <taskId>      # tasks/get
 *   aethercode a2a host:port cancel <taskId>      # tasks/cancel
 * </pre>
 *
 * <p>{@code host} accepts either {@code host:port} (treated as {@code http://host:port})
 * or a full URL. The actual RPC path is fixed to {@code /a2a} as declared in the
 * agent card; the CLI only normalises the base URI, leaving the rest of the path
 * resolution to the A2A client.</p>
 *
 * <p>The first RPC triggers a card fetch (via {@code GET /.well-known/agent.json})
 * so the CLI can read the agent's self-declared URL and capabilities without
 * hardcoding any path.</p>
 */
@Command(
        name = "a2a",
        mixinStandardHelpOptions = true,
        description = "Talk to a remote A2A (Agent2Agent) server.",
        subcommands = {
                A2aCardCommand.class,
                A2aSendCommand.class,
                A2aStreamCommand.class,
                A2aGetCommand.class,
                A2aCancelCommand.class
        })
public class A2aCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "HOST",
            description = "Agent host (host:port or full URL). Default scheme: http.")
    String host;

    @Option(names = {"--scheme"},
            description = "Default scheme when host has no scheme (default: http).")
    String scheme = "http";

    @Option(names = {"--timeout"},
            description = "HTTP connect timeout in seconds (default 5).")
    int timeoutSeconds = 5;

    @Override
    public Integer call() throws Exception {
        // With no subcommand, fetch and print the card so that a bare
        // `aethercode a2a host` doubles as a smoke test.
        A2aCommandHelper helper = new A2aCommandHelper(host, scheme, timeoutSeconds);
        AgentCard card = helper.card();
        System.out.println("name:    " + card.name());
        System.out.println("version: " + card.version());
        System.out.println("url:     " + card.url());
        if (card.description() != null) {
            System.out.println("desc:    " + card.description());
        }
        return 0;
    }

    // Shared helper: fetch the card + build a client.

    /**
     * Resolves the base URI from the user-supplied host, fetches the agent card,
     * and constructs an {@link A2AClient} pointed at the agent's self-declared
     * RPC path.
     *
     * <p>Shared by every subcommand so the resolution rules stay consistent —
     * the CLI never touches a path the card did not advertise.</p>
     */
    public static final class A2aCommandHelper {
        private final URI baseUri;
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpClient http;
        private final AgentCard card;

        public A2aCommandHelper(String host, String scheme, int timeoutSeconds) throws Exception {
            this.baseUri = normaliseHost(host, scheme);
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            this.card = fetchCard0();
        }

        public AgentCard card() { return card; }

        public A2AClient newClient() {
            // Prefer the agent's self-declared URL. When that URL is a
            // placeholder or unparseable, fall back to the user-supplied
            // baseUri + the default RPC path.
            // Many test scenarios (and agents that didn't fill in their
            // card URL at bind time) leave the URL as a placeholder; in those
            // cases we should trust the user's input over the placeholder.
            URI rpcUri;
            try {
                String url = card.url();
                if (url != null && !url.isBlank() && !url.contains("placeholder")) {
                    rpcUri = URI.create(url);
                } else {
                    // baseUri is scheme://host[:port]; the synchronous call
                    // lands at /a2a (the RpcHandler context).
                    rpcUri = URI.create(baseUri.toString()
                            + A2AHttpTransport.DEFAULT_RPC_PATH);
                }
            } catch (Exception e) {
                rpcUri = URI.create(baseUri.toString()
                        + A2AHttpTransport.DEFAULT_RPC_PATH);
            }
            return new A2AClient(rpcUri, card, http, mapper);
        }

        public ObjectMapper mapper() { return mapper; }
        public HttpClient http() { return http; }

        private AgentCard fetchCard0() throws Exception {
            // The well-known path is fixed by the A2A spec (it matches the
            // constant on A2AHttpTransport).
            URI cardUri = baseUri.resolve(A2AHttpTransport.WELL_KNOWN_AGENT_CARD);
            HttpRequest req = HttpRequest.newBuilder(cardUri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("A2A card HTTP " + resp.statusCode()
                        + " from " + cardUri + ": " + resp.body());
            }
            return mapper.readValue(resp.body(), AgentCard.class);
        }

        private static URI normaliseHost(String host, String scheme) {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host argument is required");
            }
            if (host.contains("://")) {
                return URI.create(host);
            }
            // Bare host[:port]; prepend the default scheme.
            return URI.create(scheme + "://" + host);
        }
    }
}
