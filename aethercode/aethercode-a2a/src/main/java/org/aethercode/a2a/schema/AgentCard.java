package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent Card — the A2A discovery document an agent publishes at
 * {@code https://{domain}/.well-known/agent-card.json}.
 *
 * <p>Field shape follows the v0.3 / v1.0 spec, subset that the
 * Java port actually emits today. The card is the contract a
 * client reads to decide whether (and how) to call the agent.
 *
 * <p>The class is a record to keep the wire shape stable; the
 * Jackson {@code @JsonProperty} annotations pin field names so
 * a renamimg in code does not silently break interoperability.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("version") String version,
        @JsonProperty("url") String url,
        @JsonProperty("provider") Provider provider,
        @JsonProperty("skills") List<Skill> skills,
        @JsonProperty("capabilities") Capabilities capabilities,
        @JsonProperty("authentication") Authentication authentication) {

    public AgentCard {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        if (description == null) description = "";
        if (version == null) version = "0.1.0";
        if (skills == null) skills = List.of();
        if (capabilities == null) capabilities = Capabilities.defaults();
        if (authentication == null) authentication = Authentication.open();
    }

    /** Render the card to a JSON-friendly map. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("version", version);
        m.put("url", url);
        if (provider != null) m.put("provider", provider.toMap());
        m.put("skills", skills.stream().map(Skill::toMap).toList());
        m.put("capabilities", capabilities.toMap());
        m.put("authentication", authentication.toMap());
        return m;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Provider(
            @JsonProperty("organization") String organization,
            @JsonProperty("url") String url) {
        public Provider {
            if (organization == null) organization = "";
            if (url == null) url = "";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organization", organization);
            if (!url.isEmpty()) m.put("url", url);
            return m;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skill(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("inputModes") List<String> inputModes,
            @JsonProperty("outputModes") List<String> outputModes) {
        public Skill {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (description == null) description = "";
            if (inputModes == null) inputModes = List.of("text");
            if (outputModes == null) outputModes = List.of("text");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("inputModes", inputModes);
            m.put("outputModes", outputModes);
            return m;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capabilities(
            @JsonProperty("streaming") Boolean streaming,
            @JsonProperty("pushNotifications") Boolean pushNotifications,
            @JsonProperty("stateTransitionHistory") Boolean stateTransitionHistory) {
        public Capabilities {
            if (streaming == null) streaming = false;
            if (pushNotifications == null) pushNotifications = false;
            if (stateTransitionHistory == null) stateTransitionHistory = true;
        }

        public static Capabilities defaults() {
            return new Capabilities(false, false, true);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("streaming", streaming);
            m.put("pushNotifications", pushNotifications);
            m.put("stateTransitionHistory", stateTransitionHistory);
            return m;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Authentication(
            @JsonProperty("schemes") List<String> schemes) {
        public Authentication {
            if (schemes == null) schemes = List.of();
        }

        /** Open / no auth (e.g. localhost dev). */
        public static Authentication open() {
            return new Authentication(List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemes", schemes);
            return m;
        }
    }
}
