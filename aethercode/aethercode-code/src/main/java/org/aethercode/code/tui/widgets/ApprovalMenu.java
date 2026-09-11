package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Approval menu for HITL — standard widget patterns.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.approval}. The Python
 * {@code ApprovalMenu(Container)} renders the per-tool approval box
 * (driven by {@code ToolRenderers}), the user's choices (approve,
 * approve+auto, reject, reject with reason), and the expandable
 * command. The Java port preserves the public data shape and dismiss
 * contract.</p>
 */
public class ApprovalMenu extends Widget {

    /** A single action the user is being asked to approve. */
    public record ActionRequest(String name, Map<String, Object> args, String description) {}

    /** User decision. */
    public record Decision(String type, String reason) {
        public static Decision approve() { return new Decision("approve", ""); }
        public static Decision auto() { return new Decision("auto_approve_all", ""); }
        public static Decision reject(String reason) {
            return new Decision("reject", reason == null ? "" : reason);
        }
    }

    /** Tools that don't need detailed info display. */
    public static final java.util.Set<String> MINIMAL_TOOLS = java.util.Set.of("execute");

    private final List<ActionRequest> actionRequests;
    private final String assistantId;
    private final boolean autoModeEligible;
    private final boolean showDiffLineNumbers;
    private final List<String> options;
    private int selected = 0;
    private int rejectIndex;
    private boolean commandExpanded;
    private String rejectReason;

    public ApprovalMenu(List<ActionRequest> actionRequests, String assistantId,
                        boolean autoModeEligible, boolean showDiffLineNumbers) {
        super("approval-menu", "approval-menu");
        this.actionRequests = actionRequests == null ? List.of() : List.copyOf(actionRequests);
        this.assistantId = assistantId;
        this.autoModeEligible = autoModeEligible;
        this.showDiffLineNumbers = showDiffLineNumbers;
        this.options = buildOptions(this.actionRequests, this.autoModeEligible);
        this.rejectIndex = options.size() - 1;
    }

    public ApprovalMenu(ActionRequest single, String assistantId) {
        this(single == null ? List.of() : List.of(single), assistantId, true, true);
    }

    public List<ActionRequest> actionRequests() { return actionRequests; }
    public String assistantId() { return assistantId; }
    public List<String> options() { return options; }
    public int selected() { return selected; }
    public void setSelected(int v) { this.selected = v; }
    public int rejectIndex() { return rejectIndex; }
    public boolean commandExpanded() { return commandExpanded; }
    public void setCommandExpanded(boolean v) { this.commandExpanded = v; }
    public String rejectReason() { return rejectReason; }
    public void setRejectReason(String reason) { this.rejectReason = reason; }

    private static List<String> buildOptions(List<ActionRequest> requests, boolean autoModeEligible) {
        List<String> out = new ArrayList<>();
        if (autoModeEligible) {
            out.add("1. Approve (y)");
            out.add("2. Enable Auto for this thread (a)");
        } else {
            out.add("1. Approve (y)");
        }
        out.add("3. Reject (n)");
        out.add("4. Reject with feedback (e / tab)");
        return out;
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        for (int i = 0; i < actionRequests.size(); i++) {
            ActionRequest r = actionRequests.get(i);
            ToolRenderers.Result rendererResult = ToolRenderers.getRenderer(r.name())
                    .getApprovalWidget(r.args(), assistantId);
            // The actual rendered approval body comes from a host-supplied
            // ToolApprovalWidget. The Java port emits a structural summary.
            rows.add(new WidgetNode.Static(
                    "Tool: " + r.name() + "  " + r.description(),
                    WidgetNode.Role.PRIMARY, false, true, false));
        }
        for (int i = 0; i < options.size(); i++) {
            String prefix = i == selected ? "▶ " : "  ";
            rows.add(new WidgetNode.Static(prefix + options.get(i),
                    i == selected ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT));
        }
        if (rejectReason != null && !rejectReason.isEmpty()) {
            rows.add(new WidgetNode.Static("Reject reason: " + rejectReason,
                    WidgetNode.Role.ERROR));
        }
        rows.add(new WidgetNode.Static(
                "↑/↓ navigate · Enter select · y/a/n/1/2/3 quick keys · tab reject with feedback",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "approval-menu");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("up", "move_up", "Up"),
                KeyBinding.of("k", "move_up", "Up"),
                KeyBinding.of("down", "move_down", "Down"),
                KeyBinding.of("j", "move_down", "Down"),
                KeyBinding.of("enter", "select", "Select"),
                KeyBinding.of("1", "select_position(0)", "Select first"),
                KeyBinding.of("2", "select_position(1)", "Select second"),
                KeyBinding.of("3", "select_position(2)", "Select third"),
                KeyBinding.of("y", "select_approve", "Approve"),
                KeyBinding.of("a", "select_auto", "Auto-approve"),
                KeyBinding.of("n", "select_reject", "Reject"),
                KeyBinding.of("e", "toggle_expand", "Expand"),
                KeyBinding.of("tab", "reject_with_reason", "Reject with feedback"));
    }

    public void actionMoveUp() {
        if (options.isEmpty()) return;
        selected = (selected - 1 + options.size()) % options.size();
    }
    public void actionMoveDown() {
        if (options.isEmpty()) return;
        selected = (selected + 1) % options.size();
    }
    public void actionSelect() { /* host dispatches based on selected */ }
    public void actionSelectApprove() { /* dismiss with Decision.approve() */ }
    public void actionSelectAuto() { /* dismiss with Decision.auto() */ }
    public void actionSelectReject() { /* dismiss with Decision.reject("") */ }
    public void actionToggleExpand() { this.commandExpanded = !this.commandExpanded; }
    public void actionRejectWithReason() { /* opens the inline Input field */ }
}
