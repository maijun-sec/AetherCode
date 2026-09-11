package org.aethercode.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * small helper that converts a raw JSON map (as returned
 * by {@link AgentCardDiscovery}) into a typed {@link AgentCard}.
 * Split off from {@link AgentCardDiscovery} so the test suite
 * can validate parsing without hitting a real HTTP server.
 */
public final class AgentCardMapper {

    private AgentCardMapper() {}

    @SuppressWarnings("unchecked")
    public static AgentCard fromMap(Map<String, Object> body, ObjectMapper m) {
        if (body == null) throw new IllegalArgumentException("card body is null");
        String name = stringOrThrow(body, "name");
        String url  = stringOrThrow(body, "url");
        String description = optionalString(body, "description").orElse("");
        String version     = optionalString(body, "version").orElse("0.1.0");
        AgentCard.Provider provider = body.get("provider") instanceof Map<?,?> pm
                ? parseProvider((Map<String, Object>) pm)
                : null;
        List<AgentCard.Skill> skills = new ArrayList<>();
        if (body.get("skills") instanceof List<?> sl) {
            for (Object o : sl) {
                if (o instanceof Map<?,?> sm) {
                    skills.add(parseSkill((Map<String, Object>) sm));
                }
            }
        }
        AgentCard.Capabilities caps = body.get("capabilities") instanceof Map<?,?> cm
                ? parseCapabilities((Map<String, Object>) cm)
                : AgentCard.Capabilities.defaults();
        AgentCard.Authentication auth = body.get("authentication") instanceof Map<?,?> am
                ? parseAuthentication((Map<String, Object>) am)
                : AgentCard.Authentication.open();
        return new AgentCard(name, description, version, url, provider, skills, caps, auth);
    }

    private static AgentCard.Provider parseProvider(Map<String, Object> pm) {
        String org = optionalString(pm, "organization").orElse("");
        String url = optionalString(pm, "url").orElse("");
        return new AgentCard.Provider(org, url);
    }

    @SuppressWarnings("unchecked")
    private static AgentCard.Skill parseSkill(Map<String, Object> sm) {
        String id   = stringOrThrow(sm, "id");
        String name = stringOrThrow(sm, "name");
        String desc = optionalString(sm, "description").orElse("");
        List<String> in = stringListOr(sm, "inputModes", List.of("text"));
        List<String> out = stringListOr(sm, "outputModes", List.of("text"));
        return new AgentCard.Skill(id, name, desc, in, out);
    }

    private static AgentCard.Capabilities parseCapabilities(Map<String, Object> cm) {
        Boolean stream  = optionalBool(cm, "streaming").orElse(false);
        Boolean push    = optionalBool(cm, "pushNotifications").orElse(false);
        Boolean hist    = optionalBool(cm, "stateTransitionHistory").orElse(true);
        return new AgentCard.Capabilities(stream, push, hist);
    }

    @SuppressWarnings("unchecked")
    private static AgentCard.Authentication parseAuthentication(Map<String, Object> am) {
        List<String> schemes = stringListOr(am, "schemes", List.of());
        return new AgentCard.Authentication(schemes);
    }

    private static String stringOrThrow(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new IllegalArgumentException("missing required field: " + k);
        return v.toString();
    }
    private static Optional<String> optionalString(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }
    private static Optional<Boolean> optionalBool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return Optional.of(b);
        if (v == null) return Optional.empty();
        return Optional.of(Boolean.parseBoolean(v.toString()));
    }
    @SuppressWarnings("unchecked")
    private static List<String> stringListOr(Map<String, Object> m, String k, List<String> fallback) {
        Object v = m.get(k);
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o.toString());
            return out.isEmpty() ? fallback : out;
        }
        return fallback;
    }
}
