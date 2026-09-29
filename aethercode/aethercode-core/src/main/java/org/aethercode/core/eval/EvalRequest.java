package org.aethercode.core.eval;

import java.util.List;
import java.util.Map;

/**
 * One test case definition (matches harness/test_case.schema.json).
 *
 * <p>This is the Java representation of the JSON files under
 * {@code tests/Axx_xx/}. The harness (Python) reads these and sends them
 * over JSON-RPC; on the AetherCode side they arrive as
 * {@code agent.run} request params.
 *
 * <p>Field mapping (JSON -> Java):
 * <ul>
 *   <li>{@code id} -> {@link #id}</li>
 *   <li>{@code category} -> {@link #category}</li>
 *   <li>{@code name} -> {@link #name}</li>
 *   <li>{@code description} -> {@link #description}</li>
 *   <li>{@code input.user_message} -> {@link #userMessage}</li>
 *   <li>{@code input} (full map) -> {@link #input} (preserved for fixtures)</li>
 *   <li>{@code expected_behavior} -> {@link #expectedBehavior}</li>
 *   <li>{@code pass_criteria} -> {@link #passCriteria}</li>
 *   <li>{@code scoring_method} -> {@link #scoringMethod} ("deterministic" | "llm-judge" | "human")</li>
 *   <li>{@code tags} -> {@link #tags}</li>
 * </ul>
 *
 * <p>See {@code evaluation-framework.md} for the full test item taxonomy.
 */
public final class EvalRequest {

    public final String id;
    public final String category;
    public final String name;
    public final String description;
    public final String userMessage;
    public final Map<String, Object> input;
    public final List<String> expectedBehavior;
    public final Map<String, Object> passCriteria;
    public final String scoringMethod;  // "deterministic" | "llm-judge" | "human"
    public final List<String> tags;
    public final int version;

    public EvalRequest(
            String id,
            String category,
            String name,
            String description,
            String userMessage,
            Map<String, Object> input,
            List<String> expectedBehavior,
            Map<String, Object> passCriteria,
            String scoringMethod,
            List<String> tags,
            int version) {
        this.id = id;
        this.category = category;
        this.name = name;
        this.description = description;
        this.userMessage = userMessage;
        this.input = input;
        this.expectedBehavior = expectedBehavior;
        this.passCriteria = passCriteria;
        this.scoringMethod = scoringMethod;
        this.tags = tags;
        this.version = version;
    }

    public boolean isScoringMethod(String method) {
        return scoringMethod != null && scoringMethod.equals(method);
    }

    public boolean hasTag(String tag) {
        return tags != null && tags.contains(tag);
    }

    public boolean isSafetyTest() {
        if ("A8".equals(category)) return true;
        if (tags == null) return false;
        for (String t : tags) {
            if (t == null) continue;
            String low = t.toLowerCase();
            if (low.equals("safety") || low.startsWith("safety-") || low.startsWith("safety_")) {
                return true;
            }
        }
        return false;
    }

    public boolean isMemoryTest() {
        if ("A3".equals(category)) return true;
        if (tags == null) return false;
        for (String t : tags) {
            if (t == null) continue;
            String low = t.toLowerCase();
            if (low.equals("memory") || low.startsWith("memory-") || low.startsWith("memory_")) {
                return true;
            }
        }
        return false;
    }
}