package org.aethercode.partner.quickjs.ptc;

import org.aethercode.partner.quickjs.prompt.ReplPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Programmatic tool calling (PTC) support for
 * {@code CodeInterpreterMiddleware}.
 *
 * <p>1:1 port of the Python
 * <code>langchain_quickjs._ptc</code> module. PTC exposes the
 * agent's tools inside the JavaScript REPL as
 * <code>tools.&lt;camelCaseName&gt;(input)</code> async functions, so
 * the model can fan out N tool calls inside a single
 * <code>eval</code>.</p>
 *
 * <p>This module handles two pieces:</p>
 * <ul>
 *   <li><strong>filtering</strong> &mdash; turn the live agent toolset
 *       into the subset exposed to PTC;</li>
 *   <li><strong>prompt rendering</strong> &mdash; delegate to
 *       {@link ReplPrompt#renderPtcPrompt} for the API-reference
 *       block describing each exposed tool.</li>
 * </ul>
 *
 * <p>The host-function bridge that actually invokes each tool lives in
 * {@code repl.Repl} next to the rest of the context wiring.</p>
 */
public final class PtcSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(PtcSupport.class);

    /** Reserved subagent task tool name. Cannot appear in {@code ptc}. */
    public static final String RESERVED_SUBAGENT_TASK_NAME = "task";

    private static final String TASK_IN_PTC_MSG =
            "The subagent `task` tool cannot be exposed via `ptc`. It is always "
                    + "available as the top-level `task()` global inside the REPL (with "
                    + "`subagentType`, `label`, and `responseSchema` support); exposing it through "
                    + "the `tools.*` namespace would create a second, conflicting dispatch path "
                    + "that drops `responseSchema`. Remove \"task\" from `ptc`.";

    private PtcSupport() {}

    // -----------------------------------------------------------------
    //  Identifier / case helpers
    // -----------------------------------------------------------------

    /** Convert {@code snake_case} / {@code kebab-case} &rarr; {@code camelCase}. */
    public static String toCamelCase(String name) {
        return ReplPrompt.toCamelCase(name);
    }

    /** Return whether {@code name} is a valid JavaScript identifier. */
    public static boolean isValidJsIdentifier(String name) {
        return ReplPrompt.isValidJsIdentifier(name);
    }

    /** Return whether a tool can be exposed as {@code tools.<camelCaseName>}. */
    public static boolean isValidPtcToolName(String name) {
        return ReplPrompt.isValidPtcToolName(name);
    }

    // -----------------------------------------------------------------
    //  Filtering
    // -----------------------------------------------------------------

    /**
     * Lightweight PTC allowlist entry. Mirrors the Python
     * {@code PTCOption = list[str | BaseTool]}: each entry is either a
     * tool name (matched against the agent's toolset) or a fully
     * formed tool exposed directly.
     */
    public sealed interface PtcEntry permits PtcEntry.ByName, PtcEntry.ByTool {
        record ByName(String name) implements PtcEntry {}
        record ByTool(ReplPrompt.ToolLike tool) implements PtcEntry {}
    }

    /**
     * Return the subset of {@code tools} exposed inside the REPL.
     *
     * <p>{@code selfToolName} is the REPL's own tool name; it is
     * <em>always</em> excluded to prevent the model from recursing
     * <code>tools.eval("tools.eval(...)")</code>. If the model wants a
     * nested eval, it can just write nested code in one call &mdash;
     * that is the whole point of PTC.</p>
     *
     * <p>{@code config} is allowlist-only:</p>
     * <ul>
     *   <li>{@code ByName} entries: expose matching tool names from
     *       {@code tools}.</li>
     *   <li>{@code ByTool} entries: expose those tools directly
     *       (minus {@code selfToolName}).</li>
     * </ul>
     *
     * <p>Mixed lists are supported and merged. Explicit
     * {@code ByTool} entries are included first, then name-matched
     * agent tools are appended. Duplicate tool names are
     * deduplicated.</p>
     *
     * <p>The subagent {@code task} tool is reserved and may not
     * appear in {@code config} (by name or instance) &mdash; it is
     * always available as the {@code task()} global, so a
     * {@code tools.task} PTC variant would be a conflicting,
     * degraded duplicate. A {@code "task"} entry raises
     * {@link IllegalArgumentException}.</p>
     *
     * @param tools         the live agent toolset
     * @param config        the PTC allowlist
     * @param selfToolName  the REPL's own tool name (always excluded)
     * @return the filtered, deduplicated tool list
     */
    public static List<ReplPrompt.ToolLike> filterToolsForPtc(
            List<? extends ReplPrompt.ToolLike> tools,
            List<PtcEntry> config,
            String selfToolName) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "Unsupported `ptc` config type. Use a list of tool names, list of BaseTool instances, or disable PTC.");
        }
        List<ReplPrompt.ToolLike> explicit = new ArrayList<>();
        Set<String> allowNames = new LinkedHashSet<>();
        for (PtcEntry entry : config) {
            if (entry instanceof PtcEntry.ByTool byTool) {
                if (RESERVED_SUBAGENT_TASK_NAME.equals(byTool.tool().name())) {
                    throw new IllegalArgumentException(TASK_IN_PTC_MSG);
                }
                if (!byTool.tool().name().equals(selfToolName)) {
                    explicit.add(byTool.tool());
                }
            } else if (entry instanceof PtcEntry.ByName byName) {
                if (RESERVED_SUBAGENT_TASK_NAME.equals(byName.name())) {
                    throw new IllegalArgumentException(TASK_IN_PTC_MSG);
                }
                allowNames.add(byName.name());
            } else {
                throw new IllegalArgumentException("ptc list entries must be str or BaseTool");
            }
        }
        // Append name-matched agent tools.
        List<ReplPrompt.ToolLike> selected = new ArrayList<>(explicit);
        if (tools != null) {
            for (ReplPrompt.ToolLike t : tools) {
                if (!t.name().equals(selfToolName) && allowNames.contains(t.name())) {
                    selected.add(t);
                }
            }
        }
        // Deduplicate by name.
        List<ReplPrompt.ToolLike> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ReplPrompt.ToolLike tool : selected) {
            if (seen.add(tool.name())) {
                deduped.add(tool);
            }
        }
        raiseOnInvalidPtcTools(deduped);
        return deduped;
    }

    private static void raiseOnInvalidPtcTools(List<? extends ReplPrompt.ToolLike> tools) {
        for (ReplPrompt.ToolLike tool : tools) {
            String camel = toCamelCase(tool.name());
            if (isValidJsIdentifier(camel)) continue;
            throw new IllegalArgumentException(
                    "PTC tool name '" + tool.name() + "' cannot be exposed as JavaScript identifier '"
                            + camel + "'. Tool names must map to /^[A-Za-z_$][A-Za-z0-9_$]*$/.");
        }
    }

    // -----------------------------------------------------------------
    //  Prompt rendering
    // -----------------------------------------------------------------

    /**
     * Build the {@code tools} namespace section of the system
     * prompt. Returns the empty string when {@code tools} is empty so
     * the caller can simply concatenate.
     */
    public static String renderPtcPrompt(List<? extends ReplPrompt.ToolLike> tools, String toolName) {
        if (tools == null || tools.isEmpty()) return "";
        raiseOnInvalidPtcTools(tools);
        return ReplPrompt.renderPtcPrompt(tools, toolName);
    }
}
