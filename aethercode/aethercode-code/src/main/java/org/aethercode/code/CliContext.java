package org.aethercode.code;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight runtime context types for the CLI agent graph.
 *
 * <p>Carries per-run overrides (model swap/params, approval mode) passed via
 * {@code context=}. Java-native port of the Python
 * {@code deepagents_code._cli_context} module.</p>
 */
public final class CliContext {
    private CliContext() {}

    /**
     * Per-run {@code classifier_model} value meaning "review with the main
     * agent model". An absent (or {@code null}) {@code classifier_model} only
     * says the run carries no preference, so the classifier keeps whatever
     * the server resolved at startup.
     */
    public static final String INHERIT_CLASSIFIER_MODEL = "__dcode_inherit_classifier__";

    /**
     * Declared {@code context_schema} for the agent graph. Registered via
     * {@code context_schema=} when the graph is built, so LangGraph coerces
     * each run's {@code context=} payload into this record.
     */
    public record CliContextSchema(
            String model,
            Map<String, Object> modelParams,
            Map<String, Object> profileOverrides,
            Integer modelContextLimit,
            String classifierModel,
            String approvalMode,
            Boolean autoApprove,
            String approvalModeKey,
            String threadId,
            String turnId,
            String offloadToolCallId,
            String hooksSnapshotId,
            List<String> hooksServerEvents,
            String promptId) {

        public CliContextSchema {
            modelParams = modelParams == null ? Map.of() : Map.copyOf(modelParams);
            profileOverrides = profileOverrides == null ? Map.of() : Map.copyOf(profileOverrides);
            hooksServerEvents = hooksServerEvents == null ? List.of() : List.copyOf(hooksServerEvents);
            if (approvalMode == null) {
                approvalMode = "manual";
            }
            if (autoApprove == null) {
                autoApprove = Boolean.FALSE;
            }
        }

        /** Builder for the schema. */
        public static Builder builder() { return new Builder(); }

        /** Builder mirror of the {@link CliContextSchema} record. */
        public static final class Builder {
            private String model;
            private Map<String, Object> modelParams = new HashMap<>();
            private Map<String, Object> profileOverrides = new HashMap<>();
            private Integer modelContextLimit;
            private String classifierModel;
            private String approvalMode = "manual";
            private Boolean autoApprove = false;
            private String approvalModeKey;
            private String threadId;
            private String turnId;
            private String offloadToolCallId;
            private String hooksSnapshotId;
            private List<String> hooksServerEvents = List.of();
            private String promptId;

            public Builder model(String v) { this.model = v; return this; }
            public Builder modelParams(Map<String, Object> v) { this.modelParams = v; return this; }
            public Builder profileOverrides(Map<String, Object> v) { this.profileOverrides = v; return this; }
            public Builder modelContextLimit(Integer v) { this.modelContextLimit = v; return this; }
            public Builder classifierModel(String v) { this.classifierModel = v; return this; }
            public Builder approvalMode(String v) { this.approvalMode = v; return this; }
            public Builder autoApprove(Boolean v) { this.autoApprove = v; return this; }
            public Builder approvalModeKey(String v) { this.approvalModeKey = v; return this; }
            public Builder threadId(String v) { this.threadId = v; return this; }
            public Builder turnId(String v) { this.turnId = v; return this; }
            public Builder offloadToolCallId(String v) { this.offloadToolCallId = v; return this; }
            public Builder hooksSnapshotId(String v) { this.hooksSnapshotId = v; return this; }
            public Builder hooksServerEvents(List<String> v) { this.hooksServerEvents = v; return this; }
            public Builder promptId(String v) { this.promptId = v; return this; }

            public CliContextSchema build() {
                return new CliContextSchema(model, modelParams, profileOverrides,
                        modelContextLimit, classifierModel, approvalMode, autoApprove,
                        approvalModeKey, threadId, turnId, offloadToolCallId,
                        hooksSnapshotId, hooksServerEvents, promptId);
            }
        }
    }

    /**
     * Client-facing builder for the per-run graph context payload. Callers
     * populate this and pass it via {@code context=} to
     * {@code astream}/{@code ainvoke}.
     */
    public static Map<String, Object> build(Map<String, Object> overrides) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (overrides != null) {
            out.putAll(overrides);
        }
        out.putIfAbsent("approval_mode", "manual");
        out.putIfAbsent("auto_approve", false);
        return out;
    }
}
