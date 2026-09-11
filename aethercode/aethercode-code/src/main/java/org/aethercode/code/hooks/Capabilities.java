package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.aethercode.code.hooks.HookDomainEvents.Event;
import org.aethercode.code.hooks.HookDomainEvents.NotificationDecision;
import org.aethercode.code.hooks.HookDomainEvents.NotificationEvent;
import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestDecision;
import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestEvent;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseDecision;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseEvent;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureDecision;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureEvent;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactEvent;
import org.aethercode.code.hooks.HookDomainEvents.PreToolUseDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreToolUseEvent;
import org.aethercode.code.hooks.HookDomainEvents.SessionEndDecision;
import org.aethercode.code.hooks.HookDomainEvents.SessionEndEvent;
import org.aethercode.code.hooks.HookDomainEvents.SessionStartDecision;
import org.aethercode.code.hooks.HookDomainEvents.SessionStartEvent;
import org.aethercode.code.hooks.HookDomainEvents.StopDecision;
import org.aethercode.code.hooks.HookDomainEvents.StopEvent;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStartDecision;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStartEvent;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStopDecision;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStopEvent;
import org.aethercode.code.hooks.HookDomainEvents.UserPromptSubmitDecision;
import org.aethercode.code.hooks.HookDomainEvents.UserPromptSubmitEvent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Capability registry for Hooks v2 events.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.capabilities} module. Each event has a
 * capability spec describing its owner, the matcher field it accepts,
 * default timeout, and how exit code 2 / plain stdout are interpreted.
 * The reducer and engine consult {@link #getEventSpec(HookEvent)} to
 * drive that behavior.</p>
 */
public final class Capabilities {

    private Capabilities() {}

    /** Supported hook handler executor kinds. */
    public enum HandlerType {
        COMMAND
    }

    /** How non-JSON stdout is treated on a successful exit. */
    public enum PlainOutputPolicy {
        IGNORE,
        CONTEXT
    }

    /** How exit code 2 is interpreted for an event. */
    public enum ExitCodePolicy {
        BLOCK,
        CONTEXT,
        DENY,
        FEEDBACK,
        CONTINUE_LOOP,
        DIAGNOSE,
        IGNORE
    }

    /** How matching handler effects are combined. */
    public enum AggregationPolicy {
        CONTEXT,
        PERMISSION,
        FEEDBACK_AND_CONTEXT,
        STOP_LOOP,
        SIDE_EFFECT
    }

    /** Matcher field name; the matcher that matches the named field. */
    public enum MatcherField {
        CAUSE("cause"),
        TOOL_NAME("tool_name"),
        NOTIFICATION_TYPE("notification_type"),
        AGENT_NAME("agent_name"),
        TRIGGER("trigger");

        private final String wireName;
        MatcherField(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
    }

    /** Default command handler timeout (seconds). */
    public static final double DEFAULT_COMMAND_TIMEOUT_SECONDS = 600.0;

    /** Immutable capability description for one hook event. */
    public record HookEventSpec(
            HookEvent event,
            HookOwner owner,
            Class<? extends Event> eventModel,
            Class<? extends Decision> decisionModel,
            MatcherField matcherField,
            double defaultTimeoutSeconds,
            ExitCodePolicy exitCodePolicy,
            PlainOutputPolicy plainOutputPolicy,
            AggregationPolicy aggregationPolicy,
            Set<HandlerType> supportedHandlerTypes) {

        public HookEventSpec {
            supportedHandlerTypes = supportedHandlerTypes == null
                    ? Set.of() : Set.copyOf(supportedHandlerTypes);
        }
    }

    private static final Map<HookEvent, HookEventSpec> SPECS;

    static {
        Map<HookEvent, HookEventSpec> specs = new EnumMap<>(HookEvent.class);
        specs.put(HookEvent.SESSION_START, new HookEventSpec(
                HookEvent.SESSION_START, HookOwner.CLIENT,
                SessionStartEvent.class, SessionStartDecision.class,
                MatcherField.CAUSE, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.DIAGNOSE, PlainOutputPolicy.CONTEXT,
                AggregationPolicy.CONTEXT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.USER_PROMPT_SUBMIT, new HookEventSpec(
                HookEvent.USER_PROMPT_SUBMIT, HookOwner.CLIENT,
                UserPromptSubmitEvent.class, UserPromptSubmitDecision.class,
                null, 30.0,
                ExitCodePolicy.BLOCK, PlainOutputPolicy.CONTEXT,
                AggregationPolicy.CONTEXT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.SESSION_END, new HookEventSpec(
                HookEvent.SESSION_END, HookOwner.CLIENT,
                SessionEndEvent.class, SessionEndDecision.class,
                MatcherField.CAUSE, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.DIAGNOSE, PlainOutputPolicy.IGNORE,
                AggregationPolicy.SIDE_EFFECT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.PERMISSION_REQUEST, new HookEventSpec(
                HookEvent.PERMISSION_REQUEST, HookOwner.CLIENT,
                PermissionRequestEvent.class, PermissionRequestDecision.class,
                MatcherField.TOOL_NAME, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.DENY, PlainOutputPolicy.IGNORE,
                AggregationPolicy.PERMISSION, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.NOTIFICATION, new HookEventSpec(
                HookEvent.NOTIFICATION, HookOwner.CLIENT,
                NotificationEvent.class, NotificationDecision.class,
                MatcherField.NOTIFICATION_TYPE, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.DIAGNOSE, PlainOutputPolicy.IGNORE,
                AggregationPolicy.SIDE_EFFECT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.PRE_TOOL_USE, new HookEventSpec(
                HookEvent.PRE_TOOL_USE, HookOwner.SERVER,
                PreToolUseEvent.class, PreToolUseDecision.class,
                MatcherField.TOOL_NAME, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.DENY, PlainOutputPolicy.IGNORE,
                AggregationPolicy.PERMISSION, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.POST_TOOL_USE, new HookEventSpec(
                HookEvent.POST_TOOL_USE, HookOwner.SERVER,
                PostToolUseEvent.class, PostToolUseDecision.class,
                MatcherField.TOOL_NAME, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.FEEDBACK, PlainOutputPolicy.IGNORE,
                AggregationPolicy.FEEDBACK_AND_CONTEXT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.POST_TOOL_USE_FAILURE, new HookEventSpec(
                HookEvent.POST_TOOL_USE_FAILURE, HookOwner.SERVER,
                PostToolUseFailureEvent.class, PostToolUseFailureDecision.class,
                MatcherField.TOOL_NAME, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.FEEDBACK, PlainOutputPolicy.IGNORE,
                AggregationPolicy.FEEDBACK_AND_CONTEXT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.PRE_COMPACT, new HookEventSpec(
                HookEvent.PRE_COMPACT, HookOwner.SERVER,
                PreCompactEvent.class, PreCompactDecision.class,
                MatcherField.TRIGGER, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.BLOCK, PlainOutputPolicy.IGNORE,
                AggregationPolicy.SIDE_EFFECT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.STOP, new HookEventSpec(
                HookEvent.STOP, HookOwner.SERVER,
                StopEvent.class, StopDecision.class,
                null, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.CONTINUE_LOOP, PlainOutputPolicy.IGNORE,
                AggregationPolicy.STOP_LOOP, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.SUBAGENT_START, new HookEventSpec(
                HookEvent.SUBAGENT_START, HookOwner.SERVER,
                SubagentStartEvent.class, SubagentStartDecision.class,
                MatcherField.AGENT_NAME, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.DIAGNOSE, PlainOutputPolicy.IGNORE,
                AggregationPolicy.CONTEXT, Set.of(HandlerType.COMMAND)));
        specs.put(HookEvent.SUBAGENT_STOP, new HookEventSpec(
                HookEvent.SUBAGENT_STOP, HookOwner.SERVER,
                SubagentStopEvent.class, SubagentStopDecision.class,
                MatcherField.AGENT_NAME, DEFAULT_COMMAND_TIMEOUT_SECONDS,
                ExitCodePolicy.CONTEXT, PlainOutputPolicy.IGNORE,
                AggregationPolicy.CONTEXT, Set.of(HandlerType.COMMAND)));
        SPECS = Collections.unmodifiableMap(specs);
    }

    /** Return the capability entry for {@code event}. */
    public static HookEventSpec getEventSpec(HookEvent event) {
        HookEventSpec spec = SPECS.get(event);
        if (spec == null) {
            throw new IllegalStateException("No spec registered for " + event);
        }
        return spec;
    }
}
