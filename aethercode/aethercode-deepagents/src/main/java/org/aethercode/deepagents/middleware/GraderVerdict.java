package org.aethercode.deepagents.middleware;

/**
 * Verdict the grader sub-agent emits via structured output.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.rubric.GraderVerdict} literal type.
 * Three values: {@code satisfied} (every criterion passes),
 * {@code needs_revision} (at least one criterion fails; loop
 * continues), {@code failed} (the rubric itself is malformed).</p>
 */
public enum GraderVerdict {
    SATISFIED("satisfied"),
    NEEDS_REVISION("needs_revision"),
    FAILED("failed");

    private final String json;
    GraderVerdict(String json) { this.json = json; }
    public String jsonValue() { return json; }
    public static GraderVerdict fromJson(String s) {
        if (s == null) return null;
        for (GraderVerdict v : values()) if (v.json.equalsIgnoreCase(s)) return v;
        return null;
    }
}
