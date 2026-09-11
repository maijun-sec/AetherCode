package org.aethercode.acp.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * ACP content block records.
 *
 * <p>Java-native port of the Python {@code acp.schema} content block
 * types. The ACP wire format distinguishes between text, image, audio,
 * resource, and embedded-resource blocks; Java carries each as its
 * own record with a fixed {@code type} discriminant.</p>
 */
public final class ContentBlocks {
    private ContentBlocks() {}

    /**
     * Plain text content. Mirrors
     * {@code acp.schema.TextContentBlock}.
     */
    public record TextContentBlock(String text) implements ContentBlock {
        public TextContentBlock {
            Objects.requireNonNull(text, "text");
        }
        @Override public String type() { return "text"; }
    }

    /**
     * Image content. The {@code data} field carries the base64
     * payload; {@code mimeType} is the IANA media type; {@code uri}
     * is an optional source URL. Mirrors
     * {@code acp.schema.ImageContentBlock}.
     */
    public record ImageContentBlock(
            String data,
            String mimeType,
            Optional<String> uri,
            Optional<String> description) implements ContentBlock {
        public ImageContentBlock {
            Objects.requireNonNull(mimeType, "mimeType");
            data = data == null ? "" : data;
            uri = uri == null ? Optional.empty() : uri;
            description = description == null ? Optional.empty() : description;
        }
        @Override public String type() { return "image"; }
    }

    /**
     * Audio content. Mirrors
     * {@code acp.schema.AudioContentBlock}.
     */
    public record AudioContentBlock(
            String data,
            String mimeType,
            Optional<String> description) implements ContentBlock {
        public AudioContentBlock {
            Objects.requireNonNull(mimeType, "mimeType");
            data = data == null ? "" : data;
            description = description == null ? Optional.empty() : description;
        }
        @Override public String type() { return "audio"; }
    }

    /**
     * Resource reference (a pointer to a file the client knows
     * about). Mirrors {@code acp.schema.ResourceContentBlock}.
     */
    public record ResourceContentBlock(
            String uri,
            String name,
            Optional<String> mimeType,
            Optional<String> description) implements ContentBlock {
        public ResourceContentBlock {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(name, "name");
            mimeType = mimeType == null ? Optional.empty() : mimeType;
            description = description == null ? Optional.empty() : description;
        }
        @Override public String type() { return "resource"; }
    }

    /**
     * Embedded resource payload (the client has inlined the
     * resource). Mirrors
     * {@code acp.schema.EmbeddedResourceContentBlock}.
     */
    public record EmbeddedResourceContentBlock(
            EmbeddedResource resource) implements ContentBlock {
        public EmbeddedResourceContentBlock {
            Objects.requireNonNull(resource, "resource");
        }
        @Override public String type() { return "resource_link"; }
    }

    /**
     * The inlined resource: a text blob or a base64 binary blob.
     * Mirrors {@code acp.schema.EmbeddedResource}.
     */
    public sealed interface EmbeddedResource permits TextResource, BlobResource {
        String mimeType();
    }
    public record TextResource(String mimeType, String text) implements EmbeddedResource {
        public TextResource {
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(text, "text");
        }
    }
    public record BlobResource(String mimeType, String blob) implements EmbeddedResource {
        public BlobResource {
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(blob, "blob");
        }
    }
}
