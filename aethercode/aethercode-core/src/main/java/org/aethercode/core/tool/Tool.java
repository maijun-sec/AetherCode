package org.aethercode.core.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.aethercode.core.message.Message;
import org.aethercode.core.permission.PermissionResult;

/**
 * A single tool the agent can invoke.
 *
 * <p>Mirror of langchain4j's {@code Tool} interface, with extra hooks
 * for permission checking, input validation, concurrency safety, and
 * read-only / destructive classification. The engine ({@code
 * StreamingToolExecutor}) wires each tool through pre-/post-hooks and
 * permission checks before invoking {@link #call}.</p>
 *
 * <p>Tools are immutable. Use {@link Tools#build} to wrap a {@link
 * ToolDef} into a concrete {@code Tool}.</p>
 */
public interface Tool {

    /** Stable identifier the model uses to call this tool. */
    String name();

    /** Human-readable description; surfaced in the model prompt. */
    String description();

    /** Optional search hint for tool discovery; defaults to {@link #description()}. */
    default String searchHint() { return description(); }

    /**
     * JSON schema describing the tool's arguments, or {@code null} when
     * the tool takes no arguments. The model uses this to validate
     * inputs and {@code ToolParamValidator} uses it for per-argument
     * error messages.
     */
    Map<String, Object> inputSchema();

    /** True when concurrent invocations of this tool are safe. */
    default boolean isConcurrencySafe(Map<String, Object> input) { return true; }

    /** True when the tool does not mutate state. */
    default boolean isReadOnly(Map<String, Object> input) { return false; }

    /** True when the tool can destroy data (rm, drop, etc). */
    default boolean isDestructive(Map<String, Object> input) { return false; }

    /**
     * Per-tool input validator hook. Return {@code null} or blank for
     * "ok", or a short error message string that {@code
     * ToolParamValidator} appends to the validation report.
     */
    default String validateInput(Map<String, Object> input) { return null; }

    /**
     * Returns the user-facing name; defaults to {@link #name()}.
     * Tools that expose unstable IDs (e.g. random session keys) can
     * override to expose a stable human label.
     */
    default String userFacingName(Map<String, Object> input) { return name(); }

    /**
     * Permission check before the tool is invoked. Implementations
     * delegate to {@code ProjectPermissionPolicy} or similar.
     */
    CompletableFuture<PermissionResult> checkPermissions(
            Map<String, Object> input, Tool.CallContext ctx);

    /**
     * Invoke the tool with the given (already-validated) arguments.
     * Implementations should respect {@link CallContext#isAborted()}
     * and emit progress via {@link CallContext#emit}.
     */
    CompletableFuture<Tool.ToolResult> call(
            Map<String, Object> input, Tool.CallContext ctx);

    /**
     * Per-invocation context. Carries the session id, an abort flag,
     * a progress sink, and an arbitrary extras map.
     */
    final class CallContext {
        private final String sessionId;
        private final AtomicBoolean aborted;
        private final Consumer<Message> onProgress;
        private final Map<String, Object> extras;

        public CallContext(String sessionId,
                           Consumer<Message> onProgress,
                           Map<String, Object> extras) {
            this.sessionId = sessionId;
            this.aborted = new AtomicBoolean(false);
            this.onProgress = onProgress != null ? onProgress : m -> {};
            // legacy this used `Map.of()` (an
            // immutable empty map) when the caller
            // passed no extras. `CallContext.of("s").setExtra(k, v)`
            // then threw UnsupportedOperationException
            // because `Map.of()` rejects put. The
            // canonical user pattern is to create a
            // CallContext and then setExtra one or
            // more times before passing it to a tool,
            // so the field MUST be a mutable map.
            // `new HashMap<>()` is fine; tool code
            // typically adds <5 entries.
            this.extras = extras != null ? extras : new HashMap<>();
        }

        public static CallContext of(String sessionId) {
            return new CallContext(sessionId, null, null);
        }

        public String sessionId() { return sessionId; }
        public boolean isAborted() { return aborted.get(); }
        public void abort() { aborted.set(true); }
        public void emit(Message msg) { onProgress.accept(msg); }
        public <T> T extra(String key) { @SuppressWarnings("unchecked") T v = (T) extras.get(key); return v; }
        public void setExtra(String key, Object value) { extras.put(key, value); }
        public Map<String, Object> extras() { return extras; }
    }

    /**
     * Tool invocation result. Carries the model-facing output plus an
     * optional list of attachments (text previews, images, diff
     * previews).
     */
    final class ToolResult {
        private final Object output;
        private final List<Attachment> attachments;
        private final boolean isError;

        public ToolResult(Object output) {
            this(output, List.of(), false);
        }
        public ToolResult(Object output, List<Attachment> attachments) {
            this(output, attachments, false);
        }
        public ToolResult(Object output, List<Attachment> attachments, boolean isError) {
            this.output = output;
            this.attachments = attachments != null ? List.copyOf(attachments) : List.of();
            this.isError = isError;
        }
        public static ToolResult of(Object output) {
            return new ToolResult(output);
        }
        public static ToolResult error(String message) {
            return new ToolResult(message, List.of(), true);
        }
        public Object output() { return output; }
        public List<Attachment> attachments() { return attachments; }
        public boolean isError() { return isError; }
    }

    /**
     * Out-of-band payload that travels with a tool result (text
     * preview, image, diff). Tools emit these alongside {@link
     * ToolResult#output()}.
     */
    sealed interface Attachment permits Attachment.TextPreview,
                                        Attachment.ImageAttachment,
                                        Attachment.DiffPreview {
        record TextPreview(String title, String body) implements Attachment {}
        record ImageAttachment(String mimeType, String base64, int byteCount) implements Attachment {}
        record DiffPreview(String path, String before, String after) implements Attachment {}
    }
}
