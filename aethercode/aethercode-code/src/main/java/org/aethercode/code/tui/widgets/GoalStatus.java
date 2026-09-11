package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.Optional;

/**
 * Persistent inline display for the current goal.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.goal_status}. The Python
 * module is a small {@code Static} subclass that renders the active goal
 * and its lifecycle state above the input. The Java port preserves the
 * {@link #setGoal(String, String, String)} contract and returns a
 * {@link WidgetNode} tree from {@link #render()}.</p>
 *
 * <p>The Python type alias {@code GoalStatus = Literal["active", "blocked",
 * "complete"]} is modeled here as the {@link State} enum; the
 * {@code setGoal} API accepts the string form so callers can pass through
 * the original state name without conversion.</p>
 */
public class GoalStatus extends Widget {

    /** Lifecycle state of a goal, matching the Python {@code GoalStatus} union. */
    public enum State { ACTIVE, BLOCKED, COMPLETE }

    private String objective;
    private String status;        // raw string from caller; mapped to State
    private String note;
    private boolean display;

    public GoalStatus(String id) {
        super(id, "goal-status-panel");
        this.objective = null;
        this.status = null;
        this.note = null;
        this.display = false;
    }

    /**
     * Render the current goal or hide the panel when no goal exists.
     *
     * <p>Mirrors the Python {@code set_goal} method. The {@code status}
     * argument is a free-form string; the Java port normalizes it to the
     * {@link State} enum for the {@code label} lookup but tolerates
     * arbitrary values so callers can pass the original Python state
     * name without conversion.</p>
     *
     * @param objective Persisted goal objective, or {@code null}/empty to clear.
     * @param status    Lifecycle state ({@code "active"}, {@code "blocked"},
     *                  {@code "complete"}, or {@code null} for default-active).
     * @param note      Blocker or completion note; only rendered when status
     *                  is {@code "blocked"} or {@code "complete"}.
     */
    public void setGoal(String objective, String status, String note) {
        this.objective = objective;
        this.status = status;
        this.note = note;
        this.display = objective != null && !objective.isEmpty();
    }

    /** Whether the panel should currently be shown. */
    public boolean isDisplayed() {
        return display;
    }

    @Override
    public WidgetNode render() {
        if (!display) {
            return new WidgetNode.Static("", WidgetNode.Role.MUTED);
        }
        State resolved = resolveState(status);
        String label = resolved == State.COMPLETE ? "completed" : resolved.name().toLowerCase();
        WidgetNode header = new WidgetNode.Static(
                "Goal · " + label, WidgetNode.Role.PRIMARY, false, true, false);
        WidgetNode body = new WidgetNode.Static(
                objective, WidgetNode.Role.TEXT, false, false, false);
        WidgetNode root = new WidgetNode.Container(
                WidgetNode.Layout.VERTICAL,
                List.of(header, body),
                "goal-status-panel");
        if (note != null && !note.isEmpty() &&
                (resolved == State.BLOCKED || resolved == State.COMPLETE)) {
            WidgetNode noteNode = new WidgetNode.Static(
                    note, WidgetNode.Role.MUTED, true, false, false);
            return new WidgetNode.Container(
                    WidgetNode.Layout.VERTICAL,
                    List.of(header, body, noteNode),
                    "goal-status-panel");
        }
        return root;
    }

    /** The current objective (or {@code null} when no goal is set). */
    public Optional<String> objective() {
        return Optional.ofNullable(objective);
    }

    /** The current note (or {@code null}). */
    public Optional<String> note() {
        return Optional.ofNullable(note);
    }

    private static State resolveState(String status) {
        if (status == null) return State.ACTIVE;
        return switch (status.toLowerCase()) {
            case "complete", "completed" -> State.COMPLETE;
            case "blocked" -> State.BLOCKED;
            default -> State.ACTIVE;
        };
    }
}
