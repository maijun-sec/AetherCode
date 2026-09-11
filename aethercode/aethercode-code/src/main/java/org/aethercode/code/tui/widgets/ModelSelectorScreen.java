package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive model selector screen for the {@code /model} command.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.model_selector}. The
 * Python module exposes a {@code ModelSelectorScreen} that lists
 * available models with a fuzzy filter, an optional default toggle
 * ({@code Ctrl+S}), and the ability to install a provider's extra
 * package on demand. The Java port preserves the public surface and
 * dismiss contract.</p>
 */
public class ModelSelectorScreen extends ModalScreen<String> {

    /** Upper bound (in cells) for the model selector list. */
    public static final int MODEL_LIST_MAX_HEIGHT = 16;

    /** Floor (in cells) so the model selector list never collapses to zero. */
    public static final int MODEL_LIST_MIN_HEIGHT = 1;

    /** Header label for the MRU pseudo-provider section. */
    public static final String RECENT_SECTION_LABEL = "Recent";

    /** A model entry shown in the selector. */
    public record ModelEntry(String spec, String label, String provider, boolean recommended,
                             boolean recent, boolean authenticated) {}

    /** Authentication state for the provider. */
    public enum ProviderAuthState { OK, NEEDS_KEY, NEEDS_LOGIN, NEEDS_INSTALL }

    private final List<ModelEntry> models;
    private final String currentModel;
    private final String defaultModel;
    private int selected = 0;
    private String filter = "";
    private boolean showOnlyRecommended;
    private String autoClassifierModel;
    private String defaultAutoClassifierModel;

    public ModelSelectorScreen(List<ModelEntry> models, String currentModel, String defaultModel) {
        super("", "model-selector-screen");
        this.models = models == null ? List.of() : List.copyOf(models);
        this.currentModel = currentModel;
        this.defaultModel = defaultModel;
    }

    public List<ModelEntry> models() { return models; }
    public String currentModel() { return currentModel; }
    public String defaultModel() { return defaultModel; }
    public int selected() { return selected; }
    public String filter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }
    public boolean showOnlyRecommended() { return showOnlyRecommended; }
    public void setShowOnlyRecommended(boolean v) { this.showOnlyRecommended = v; }

    public String autoClassifierModel() { return autoClassifierModel; }
    public void setAutoClassifierModel(String v) { this.autoClassifierModel = v; }
    public String defaultAutoClassifierModel() { return defaultAutoClassifierModel; }
    public void setDefaultAutoClassifierModel(String v) { this.defaultAutoClassifierModel = v; }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static("Select Model",
                WidgetNode.Role.PRIMARY, false, true, false));
        rows.add(new WidgetNode.Input("model-filter", "Filter…", filter, false));
        for (int i = 0; i < models.size(); i++) {
            ModelEntry m = models.get(i);
            String prefix = i == selected ? "▶ " : "  ";
            String suffix = "";
            if (m.spec().equals(currentModel)) suffix += " current";
            if (m.spec().equals(defaultModel)) suffix += " default";
            rows.add(new WidgetNode.Static(prefix + m.label() + suffix,
                    i == selected ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT));
        }
        rows.add(new WidgetNode.Static(
                "↑/↓ navigate · Enter select · Ctrl+S default · Esc cancel",
                WidgetNode.Role.MUTED, true, false, true));
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "model-selector-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.of("escape", "cancel", "Cancel"),
                KeyBinding.priority("up", "move_up", "Up"),
                KeyBinding.priority("k", "move_up", "Up"),
                KeyBinding.priority("down", "move_down", "Down"),
                KeyBinding.priority("j", "move_down", "Down"),
                KeyBinding.priority("enter", "select", "Select"),
                KeyBinding.priority("ctrl+s", "set_default", "Set default"),
                KeyBinding.priority("tab", "cursor_down", "Next"),
                KeyBinding.priority("shift+tab", "cursor_up", "Previous"));
    }

    public void actionCancel() { dismiss(null); }
    public void actionMoveUp() {
        if (models.isEmpty()) return;
        selected = (selected - 1 + models.size()) % models.size();
    }
    public void actionMoveDown() {
        if (models.isEmpty()) return;
        selected = (selected + 1) % models.size();
    }
    public void actionSelect() {
        if (models.isEmpty()) { dismiss(null); return; }
        dismiss(models.get(selected).spec());
    }
    public void actionSetDefault() { /* host persists via deepagents-core */ }
}
