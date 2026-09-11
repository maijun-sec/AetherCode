package org.aethercode.code.tui.widgets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup tip widget shown above the chat input.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.startup_tip}. The
 * Python module exposes a registry of weighted tips, a {@code _pick_tip}
 * helper that selects one randomly, and a {@code StartupTip(Static)}
 * widget that renders the chosen tip above the chat input.</p>
 *
 * <p>The Java port preserves the registry and pick semantics, replacing
 * {@code random.choices} with a deterministic
 * {@link java.util.random.RandomGenerator}-style implementation, and
 * renders the tip as a {@link WidgetNode.Static} that the host projects
 * above the input.</p>
 */
public final class StartupTip {

    /** Generic editor tip replaced at construction when an editor is configured. */
    public static final String TIP_EXTERNAL_EDITOR = "Press ctrl+x to compose prompts in your external editor";

    /** Tip used when {@code startup.yolo_switcher} keeps YOLO in the approval cycle. */
    public static final String TIP_SHIFT_TAB_WITH_YOLO =
            "Press Shift+Tab to cycle Manual, Auto, and YOLO modes";

    /** Tip used when orgs/users disable YOLO entry via the approval switcher. */
    public static final String TIP_SHIFT_TAB_WITHOUT_YOLO =
            "Press Shift+Tab to toggle Manual and Auto modes";

    /** Tips shown above the chat input, with relative selection weights. */
    private static final Map<String, Integer> TIPS = new LinkedHashMap<>();
    static {
        TIPS.put("Use @ to reference files and / for commands", 3);
        TIPS.put("Try /threads to resume a previous conversation", 2);
        TIPS.put("Use /offload to summarize older messages and free up the context window", 2);
        TIPS.put("Use /context to see context window usage and remaining space", 1);
        TIPS.put("Use /copy to copy the latest message", 3);
        TIPS.put("Use /cost to see a breakdown of estimated spend", 1);
        TIPS.put("Use /tools to list the tools available to the agent", 1);
        TIPS.put("Open /mcp and press Enter on a remote server to sign in again", 1);
        TIPS.put("Use /remember to save learnings from this conversation", 1);
        TIPS.put("Use /model to switch models mid-conversation", 2);
        TIPS.put("Use /effort to change the current model's reasoning effort", 1);
        TIPS.put(TIP_EXTERNAL_EDITOR, 1);
        TIPS.put("Use /skill:<name> to invoke a skill directly", 1);
        TIPS.put("Use /theme to customize the TUI's colors", 1);
        TIPS.put("Use /skill-creator to build reusable agent skills", 1);
        TIPS.put("Ask for a workflow to fan work out to subagents in parallel", 3);
        TIPS.put("Use /timestamps to show or hide message timestamp footers", 1);
        TIPS.put("Click a collapsed message or press Ctrl+O to expand it", 1);
        TIPS.put("Drag the chat input's top border to resize it", 1);
        TIPS.put("Use /agents to browse and switch between your available agents", 2);
        TIPS.put("Use /auto model to review Auto actions with a faster, cheaper model", 1);
        TIPS.put(TIP_SHIFT_TAB_WITH_YOLO, 2);
        TIPS.put("Use !! for incognito shell commands that stay out of model context", 1);
        TIPS.put("Deep Agents can explain its own features and look up its docs. Ask it how to use.", 3);
    }

    /** Returns the weighted registry, optionally swapping the YOLO tip and editor tip. */
    public static Map<String, Integer> activeTips(String editorDisplayName, boolean yoloSwitcherEnabled) {
        Map<String, Integer> tips = new LinkedHashMap<>(TIPS);
        if (editorDisplayName != null && !editorDisplayName.isEmpty()) {
            Integer weight = tips.remove(TIP_EXTERNAL_EDITOR);
            if (weight != null) {
                tips.put("Press ctrl+x to compose prompts in " + editorDisplayName, weight);
            }
        }
        if (!yoloSwitcherEnabled) {
            Integer weight = tips.remove(TIP_SHIFT_TAB_WITH_YOLO);
            if (weight != null) {
                tips.put(TIP_SHIFT_TAB_WITHOUT_YOLO, weight);
            }
        }
        return tips;
    }

    /** Pick a random tip from the active registry. */
    public static String pickTip(String editorDisplayName, boolean yoloSwitcherEnabled, Random rng) {
        Map<String, Integer> tips = activeTips(editorDisplayName, yoloSwitcherEnabled);
        int total = tips.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) throw new IllegalStateException("no tips registered");
        int target = rng.nextInt(total);
        int cursor = 0;
        for (Map.Entry<String, Integer> e : tips.entrySet()) {
            cursor += e.getValue();
            if (target < cursor) return e.getKey();
        }
        // Defensive: the cumulative sum above guarantees we returned already.
        return tips.keySet().iterator().next();
    }

    /** Convenience: pick a tip using the default {@link ThreadLocalRandom}. */
    public static String pickTip(String editorDisplayName, boolean yoloSwitcherEnabled) {
        return pickTip(editorDisplayName, yoloSwitcherEnabled, ThreadLocalRandom.current());
    }

    /** One tip rendered above the chat input. */
    public static final class Widget extends org.aethercode.code.tui.widgets.Widget {
        private final String tip;

        public Widget(String tip) {
            super("", "startup-tip");
            this.tip = tip == null ? pickTip(null, true) : tip;
        }

        public String tip() { return tip; }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Static("Tip: " + tip, WidgetNode.Role.MUTED,
                    true, false, true);
        }
    }

    private StartupTip() {}
}
