package org.aethercode.core.agent;

import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * subagent framework. Modelled on the TS
 * {@code services/agentSummary/subagent.ts} — the parent engine can delegate
 * a focused task to a child engine with a restricted tool pool, then wait
 * (or stream) the result.
 *
 * <p>A {@link Subagent} is a named toolset + system-prompt prefix. The
 * orchestrator spawns a fresh child engine for each delegation, runs the
 * task, and returns a {@link Result} with the captured text + counts.
 *
 * <p>The {@link SubagentEngine} interface is the duck-typed contract a
 * child engine must satisfy — AetherCodeEngine implements it. Tests can
 * implement the interface with a stub that returns canned events.
 */
public final class Subagent {

    private final String name;
    private final String description;
    private final String systemPromptPrefix;
    private final List<Tool> tools;
    private final String model;

    public Subagent(String name, String description, String systemPromptPrefix,
                    List<Tool> tools, String model) {
        this.name = name;
        this.description = description;
        this.systemPromptPrefix = systemPromptPrefix == null ? "" : systemPromptPrefix;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.model = model;
    }

    public String name() { return name; }
    public String description() { return description; }
    public String systemPromptPrefix() { return systemPromptPrefix; }
    public List<Tool> tools() { return tools; }
    public String model() { return model; }

    /** simple in-memory registry. */
    public static final class Registry {
        private final Map<String, Subagent> map = new ConcurrentHashMap<>();
        public Registry register(Subagent s) { map.put(s.name(), s); return this; }
        public Subagent get(String name) { return map.get(name); }
        public boolean contains(String name) { return map.containsKey(name); }
        public java.util.Set<String> names() { return map.keySet(); }
    }

    public record Result(String agentName, String text, int toolCalls, long elapsedMs, boolean ok, String error) {
        public static Result ok(String name, String text, int toolCalls, long ms) {
            return new Result(name, text, toolCalls, ms, true, null);
        }
        public static Result error(String name, String error, long ms) {
            return new Result(name, "", 0, ms, false, error);
        }
    }

    /** duck-typed child engine — AetherCodeEngine implements this implicitly. */
    public interface SubagentEngine {
        /** Default 1-arg query. Delegates to the 3-arg form
         *  with no overrides (uses the engine's default
         *  chat client and full tool pool). */
        default Stream<StreamEvent> query(String task) {
            return query(task, null, null);
        }
        /** query with an optional per-call tool pool
         *  override (used by AgentTool to scope a subagent's
         *  tools to a role preset). The 1-arg overload
         *  delegates here with both overrides null. */
        Stream<StreamEvent> query(String task,
                                  org.aethercode.core.llm.ChatClient chatClientOverride,
                                  List<Tool> toolPoolOverride);
        String sessionId();
        List<Tool> tools();
    }

    /** factory for child engines — injected so tests can stub. */
    @FunctionalInterface
    public interface EngineFactory {
        SubagentEngine create(Subagent spec, Object parentCtx);
    }
}
