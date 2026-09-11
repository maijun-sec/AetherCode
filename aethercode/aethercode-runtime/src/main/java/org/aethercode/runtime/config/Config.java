package org.aethercode.runtime.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-invocation agent configuration.
 *
 * <p>Java-native equivalent of langchain's <code>RunnableConfig</code> +
 * langgraph's <code>CONFIG_KEY_*</code>. Holds the run id, thread id,
 * recursion limit, callbacks, and a free-form metadata bag.</p>
 */
public final class Config {

    /** Sentinel used for unset values. */
    public static final Object UNSET = new Object();

    private final String runId;
    private final String threadId;
    private final String userId;
    private final int recursionLimit;
    private final Map<String, Object> metadata;
    private final Map<String, Object> tags;
    private final Map<String, Object> configurable;

    private Config(Builder b) {
        this.runId          = b.runId;
        this.threadId       = b.threadId;
        this.userId         = b.userId;
        this.recursionLimit = b.recursionLimit;
        this.metadata       = Map.copyOf(b.metadata);
        this.tags           = Map.copyOf(b.tags);
        this.configurable   = Map.copyOf(b.configurable);
    }

    public String runId()                                  { return runId; }
    public String threadId()                               { return threadId; }
    public String userId()                                 { return userId; }
    public int recursionLimit()                            { return recursionLimit; }
    public Map<String, Object> metadata()                  { return metadata; }
    public Map<String, Object> tags()                      { return tags; }
    public Map<String, Object> configurable()              { return configurable; }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> configurable(String key) {
        return Optional.ofNullable((T) configurable.get(key));
    }

    public Config withConfigurable(String key, Object value) {
        Map<String, Object> merged = new HashMap<>(configurable);
        merged.put(key, value);
        return toBuilder().configurable(merged).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .runId(runId)
                .threadId(threadId)
                .userId(userId)
                .recursionLimit(recursionLimit)
                .metadata(metadata)
                .tags(tags)
                .configurable(configurable);
    }

    public static final class Builder {
        private String runId = UUID.randomUUID().toString();
        private String threadId;
        private String userId;
        private int recursionLimit = 25;
        private Map<String, Object> metadata = new HashMap<>();
        private Map<String, Object> tags = new HashMap<>();
        private Map<String, Object> configurable = new HashMap<>();

        public Builder runId(String v)                    { this.runId = v; return this; }
        public Builder threadId(String v)                 { this.threadId = v; return this; }
        public Builder userId(String v)                   { this.userId = v; return this; }
        public Builder recursionLimit(int v)              { this.recursionLimit = v; return this; }
        public Builder metadata(Map<String, Object> v)    { this.metadata = v; return this; }
        public Builder tags(Map<String, Object> v)        { this.tags = v; return this; }
        public Builder configurable(Map<String, Object> v) { this.configurable = v; return this; }
        public Builder configurable(String key, Object v) {
            this.configurable.put(key, v);
            return this;
        }
        public Config build() { return new Config(this); }
    }
}
