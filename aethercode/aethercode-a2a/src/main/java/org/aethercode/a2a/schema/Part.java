package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * the smallest content unit inside a {@link Message} or
 * {@link Artifact}. Three concrete variants per the v0.3 spec:
 *
 * <ul>
 *   <li>{@link TextPart} — plain or markdown text</li>
 *   <li>{@link FilePart} — a binary blob (referenced by URI) or inline base64</li>
 *   <li>{@link DataPart} — structured JSON (form data, parameters)</li>
 * </ul>
 *
 * <p>Each variant carries a {@code kind} discriminator so Jackson
 * can round-trip through the polymorphic {@code Part} type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Part.TextPart.class, name = "text"),
        @JsonSubTypes.Type(value = Part.FilePart.class, name = "file"),
        @JsonSubTypes.Type(value = Part.DataPart.class, name = "data")
})
public sealed interface Part
        permits Part.TextPart, Part.FilePart, Part.DataPart {

    String kind();

    Map<String, Object> toMap();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TextPart(
            @JsonProperty("kind") String kind,
            @JsonProperty("text") String text) implements Part {
        public TextPart {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            if (!"text".equals(kind)) throw new IllegalArgumentException("kind must be 'text'");
        }
        public static TextPart of(String text) { return new TextPart("text", text); }
        @Override public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "text");
            m.put("text", text);
            return m;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FilePart(
            @JsonProperty("kind") String kind,
            @JsonProperty("name") String name,
            @JsonProperty("uri") String uri,
            @JsonProperty("mimeType") String mimeType) implements Part {
        public FilePart {
            Objects.requireNonNull(kind, "kind");
            if (!"file".equals(kind)) throw new IllegalArgumentException("kind must be 'file'");
            if ((uri == null || uri.isEmpty()) && mimeType == null) {
                throw new IllegalArgumentException("FilePart needs uri or mimeType");
            }
        }
        public static FilePart uri(String uri, String name, String mimeType) {
            return new FilePart("file", name, uri, mimeType);
        }
        @Override public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "file");
            if (name != null) m.put("name", name);
            if (uri != null) m.put("uri", uri);
            if (mimeType != null) m.put("mimeType", mimeType);
            return m;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DataPart(
            @JsonProperty("kind") String kind,
            @JsonProperty("data") Map<String, Object> data) implements Part {
        public DataPart {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(data, "data");
            if (!"data".equals(kind)) throw new IllegalArgumentException("kind must be 'data'");
        }
        public static DataPart of(Map<String, Object> data) { return new DataPart("data", data); }
        @Override public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "data");
            m.put("data", data);
            return m;
        }
    }
}
