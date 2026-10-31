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
