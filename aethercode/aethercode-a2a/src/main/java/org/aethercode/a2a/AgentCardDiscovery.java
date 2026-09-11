package org.aethercode.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * well-known Agent Card discovery. Fetches the JSON
 * document at {@code https://{domain}/.well-known/agent-card.json}
 * (or the legacy {@code /.well-known/agent.json} path the v0.1
 * spec used). Pure I/O, no in-process state.
 *
 * <p>Used by {@link A2AClient} to learn the remote agent's
 * endpoint, skills, and supported authentication schemes before
 * sending a task.
 */
public final class AgentCardDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(AgentCardDiscovery.class);

    public static final String WELL_KNOWN_V03 = "/.well-known/agent-card.json";
    public static final String WELL_KNOWN_V01 = "/.well-known/agent.json";

    private final HttpClient http;
    private final ObjectMapper mapper;

    public AgentCardDiscovery() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                new ObjectMapper());
    }

    public AgentCardDiscovery(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    /**
     * Fetch the Agent Card for a remote agent identified by its
     * root URL (e.g. {@code https://specialist.example.com}).
     * Tries v0.3 first, falls back to v0.1.
     */
    public Optional<AgentCard> fetch(String rootUrl) {
        if (rootUrl == null || rootUrl.isBlank()) return Optional.empty();
        String base = rootUrl.endsWith("/") ? rootUrl.substring(0, rootUrl.length() - 1) : rootUrl;
        for (String path : new String[]{WELL_KNOWN_V03, WELL_KNOWN_V01}) {
            try {
                AgentCard card = tryFetch(base + path);
                if (card != null) {
                    LOG.info("fetched A2A Agent Card from {} ({} skills)",
                            base + path, card.skills().size());
                    return Optional.of(card);
                }
            } catch (Exception e) {
                LOG.debug("no Agent Card at {}{}: {}", base, path, e.getMessage());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private AgentCard tryFetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) return null;
        Map<String, Object> body = mapper.readValue(resp.body(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return AgentCardMapper.fromMap(body, mapper);
    }
}
