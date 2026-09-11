package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * an output produced by an A2A task. An artifact is the
 * durable result the caller came for — a document, a JSON blob,
 * a generated image. Composed of {@link Part}s, just like a
 * {@link Message}, but persisted in the {@link Task} so a
 * later {@code tasks/get} can fetch it without re-running the
 * task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Artifact(
        @JsonProperty("artifactId") String artifactId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("parts") List<Part> parts) {

    public Artifact {
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("artifact must have at least one part");
        }
        if (name == null) name = "artifact";
        if (description == null) description = "";
        if (artifactId == null || artifactId.isEmpty()) {
            artifactId = UUID.randomUUID().toString();
        }
    }

    public static Artifact of(String name, Part... parts) {
        return new Artifact(null, name, "", List.of(parts));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("artifactId", artifactId);
        m.put("name", name);
        if (!description.isEmpty()) m.put("description", description);
        m.put("parts", parts.stream().map(Part::toMap).toList());
        return m;
    }
}
