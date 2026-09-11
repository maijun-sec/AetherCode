package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.NotificationEvent;
import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestEvent;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseEvent;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureEvent;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactEvent;
import org.aethercode.code.hooks.HookDomainEvents.PreToolUseEvent;
import org.aethercode.code.hooks.HookDomainEvents.SessionEndEvent;
import org.aethercode.code.hooks.HookDomainEvents.SessionStartEvent;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStartEvent;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStopEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable runtime snapshot for Hooks v2 configuration.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.snapshot} module. A snapshot pairs the
 * declaration-ordered handler list with a stable
 * {@code snapshot_id} and the diagnostics that arose while
 * compiling it.</p>
 */
public final class Snapshot {

    private static final Logger LOG = LoggerFactory.getLogger(Snapshot.class);

    /**
     * Claude-compatible exact-match character set (letters, digits,
     * <code>_</code>, <code>-</code>, spaces, <code>,</code>,
     * <code>|</code>).
     */
    private static final Pattern EXACT_MATCHER = Pattern.compile("^[\\w\\s,\\-|]+$");

    private Snapshot() {}

    /** One ordered command handler in a configuration snapshot. */
    public record HookHandler(
            String id,
            HookEvent event,
            String command,
            Double timeout,
            String statusMessage,
            MatcherHolder matcher,
            String matcherText,
            List<String> argv,
            HooksSource source) {

        public HookHandler {
            if (id == null) id = "";
            if (command == null) command = "";
            if (argv == null) argv = List.of();
            else argv = List.copyOf(argv);
        }
    }

    /** Matchers are either a regex, a frozenset of names, or null (=match all). */
    public sealed interface MatcherHolder {
        boolean matches(String target);
    }

    public record RegexMatcher(Pattern pattern) implements MatcherHolder {
        public boolean matches(String target) {
            return target != null && pattern.matcher(target).find();
        }
    }

    public record SetMatcher(Set<String> names) implements MatcherHolder {
        public SetMatcher {
            names = Set.copyOf(names);
        }
        public boolean matches(String target) {
            return target != null && names.contains(target);
        }
    }

    /** Match-all sentinel. */
    public record AnyMatcher() implements MatcherHolder {
        public boolean matches(String target) { return true; }
    }

    /** Matched handlers for one invocation. */
    public record HookMatch(List<HookHandler> handlers, List<HookDiagnostic> diagnostics) {
        public HookMatch {
            handlers = handlers == null ? List.of() : List.copyOf(handlers);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * Immutable, declaration-ordered Hooks v2 runtime configuration.
     */
    public record HooksSnapshot(
            Map<HookEvent, List<HookHandler>> handlers,
            String snapshotId,
            List<HookDiagnostic> diagnostics) {

        public HooksSnapshot {
            handlers = handlers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        public static HooksSnapshot fromConfig(HookConfigTypes.HooksConfig config,
                                               Map<HookEvent, List<SourcedGroup>> groups,
                                               List<HookDiagnostic> diagnostics,
                                               String snapshotId) {
            String canonicalId = Loading.computeSnapshotId(config, groups);
            if (snapshotId != null && !snapshotId.equals(canonicalId)) {
                throw new IllegalArgumentException("Provided snapshot_id does not match canonical configuration");
            }
            Map<HookEvent, List<SourcedGroup>> sourced = groups != null ? groups : defaultSourced(config);
            Map<HookEvent, List<HookHandler>> expanded = new EnumMap<>(HookEvent.class);
            List<HookDiagnostic> compileDiagnostics = new java.util.ArrayList<>(diagnostics == null ? List.of() : diagnostics);
            for (Map.Entry<HookEvent, List<SourcedGroup>> entry : sourced.entrySet()) {
                HookEvent event = entry.getKey();
                List<SourcedGroup> eventGroups = entry.getValue();
                Capabilities.MatcherField matcherField = Capabilities.getEventSpec(event).matcherField();
                List<HookHandler> handlers = new java.util.ArrayList<>();
                for (int groupIndex = 0; groupIndex < eventGroups.size(); groupIndex++) {
                    SourcedGroup sg = eventGroups.get(groupIndex);
                    HooksSource source = sg.source();
                    HookConfigTypes.MatcherGroup group = sg.group();
                    if (matcherField == null && group.matcher() != null
                            && !group.matcher().isEmpty() && !"*".equals(group.matcher())) {
                        String message = "Rejected hook group " + event.wireName() + ":" + groupIndex
                                + ": " + event.wireName() + " does not support matchers";
                        LOG.warn(message);
                        compileDiagnostics.add(new HookDiagnostic(
                                "unsupported_matcher", HookDiagnostic.Severity.WARNING,
                                message, null, "matcher"));
                        continue;
                    }
                    MatcherHolder compiled = compileMatcher(group.matcher());
                    if (compiled == null) {
                        String message = "Rejected hook group " + event.wireName() + ":" + groupIndex
                                + ": invalid matcher " + group.matcher();
                        LOG.warn(message);
                        compileDiagnostics.add(new HookDiagnostic(
                                "invalid_matcher", HookDiagnostic.Severity.WARNING,
                                message, null, "matcher"));
                        continue;
                    }
                    for (int handlerIndex = 0; handlerIndex < group.hooks().size(); handlerIndex++) {
                        HookConfigTypes.CommandHandlerSpec spec = group.hooks().get(handlerIndex);
                        List<String> argv = spec.argv() == null ? null : spec.argv().stream()
                                .map(p -> source.resolveVariables(p, false))
                                .toList();
                        String resolvedCommand = source.resolveVariables(spec.command(), true);
                        handlers.add(new HookHandler(
                                event.wireName() + ":" + groupIndex + ":" + handlerIndex,
                                event,
                                resolvedCommand,
                                spec.timeout(),
                                spec.statusMessage(),
                                compiled,
                                group.matcher(),
                                argv,
                                source));
                    }
                }
                expanded.put(event, List.copyOf(handlers));
            }
            return new HooksSnapshot(expanded, canonicalId, compileDiagnostics);
        }

        private static Map<HookEvent, List<SourcedGroup>> defaultSourced(HookConfigTypes.HooksConfig config) {
            Map<HookEvent, List<SourcedGroup>> out = new EnumMap<>(HookEvent.class);
            for (Map.Entry<HookEvent, List<HookConfigTypes.MatcherGroup>> e : config.hooks().entrySet()) {
                List<SourcedGroup> list = e.getValue().stream()
                        .map(g -> new SourcedGroup(Loading.UNSOURCED, g))
                        .toList();
                out.put(e.getKey(), list);
            }
            return out;
        }

        /** Return handlers matching an invocation in declaration order. */
        public HookMatch match(HookInvocation invocation) {
            HookEvent event = invocation.event().event();
            Capabilities.MatcherField matcherField = Capabilities.getEventSpec(event).matcherField();
            String target = matchTarget(invocation, matcherField);
            List<HookHandler> all = handlers.getOrDefault(event, List.of());
            List<HookHandler> matched = all.stream()
                    .filter(h -> handlerMatches(h, matcherField, target))
                    .toList();
            return new HookMatch(matched, List.of());
        }

        /** Return events that have at least one compiled handler. */
        public Set<HookEvent> configuredEvents() {
            Set<HookEvent> out = new LinkedHashSet<>();
            for (Map.Entry<HookEvent, List<HookHandler>> e : handlers.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    out.add(e.getKey());
                }
            }
            return out;
        }

        /** Return server-owned events with at least one compiled handler. */
        public Set<HookEvent> configuredServerEvents() {
            Set<HookEvent> out = new LinkedHashSet<>();
            for (HookEvent event : configuredEvents()) {
                if (Capabilities.getEventSpec(event).owner() == HookOwner.SERVER) {
                    out.add(event);
                }
            }
            return out;
        }
    }

    /** Pair of {@link HooksSource} and {@link HookConfigTypes.MatcherGroup}. */
    public record SourcedGroup(HooksSource source, HookConfigTypes.MatcherGroup group) {}

    /**
     * Compile a Claude-compatible matcher pattern. {@code null}, empty,
     * or {@code "*"} matches all; an exact-character matcher is stored
     * as a set; any other character switches to an unanchored regex.
     */
    public static MatcherHolder compileMatcher(String value) {
        if (value == null || value.isEmpty() || "*".equals(value)) {
            return new AnyMatcher();
        }
        if (EXACT_MATCHER.matcher(value).matches()) {
            String[] parts = value.split("[|,]");
            Set<String> names = new LinkedHashSet<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
            return new SetMatcher(names);
        }
        try {
            return new RegexMatcher(Pattern.compile(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean handlerMatches(HookHandler handler, Capabilities.MatcherField matcherField,
                                          String target) {
        if (matcherField == null || handler.matcher() instanceof AnyMatcher) {
            return true;
        }
        if (target == null) return false;
        return handler.matcher().matches(target);
    }

    private static String matchTarget(HookInvocation invocation, Capabilities.MatcherField matcherField) {
        if (matcherField == null) return null;
        var event = invocation.event();
        if (matcherField == Capabilities.MatcherField.TOOL_NAME) {
            if (event instanceof PermissionRequestEvent p) {
                return Tools.toWireToolName(p.call().name(), p.call().mcpServer());
            }
            if (event instanceof PreToolUseEvent p) {
                return Tools.toWireToolName(p.call().name(), p.call().mcpServer());
            }
            if (event instanceof PostToolUseEvent p) {
                return Tools.toWireToolName(p.call().name(), p.call().mcpServer());
            }
            if (event instanceof PostToolUseFailureEvent p) {
                return Tools.toWireToolName(p.call().name(), p.call().mcpServer());
            }
        }
        if (matcherField == Capabilities.MatcherField.NOTIFICATION_TYPE
                && event instanceof NotificationEvent n) {
            return Projection.toWireNotificationType(n.notification().type()).wireName().toLowerCase();
        }
        if (matcherField == Capabilities.MatcherField.CAUSE
                && (event instanceof SessionStartEvent s)) {
            return s.cause().name().toLowerCase();
        }
        if (matcherField == Capabilities.MatcherField.CAUSE
                && event instanceof SessionEndEvent se) {
            return se.cause().name().toLowerCase();
        }
        if (matcherField == Capabilities.MatcherField.TRIGGER
                && event instanceof PreCompactEvent pc) {
            return pc.trigger().name().toLowerCase();
        }
        if (matcherField == Capabilities.MatcherField.AGENT_NAME
                && event instanceof SubagentStartEvent sa) {
            return sa.agent().name();
        }
        if (matcherField == Capabilities.MatcherField.AGENT_NAME
                && event instanceof SubagentStopEvent ss) {
            return ss.agent().name();
        }
        return null;
    }

    /** Type-narrowing helper to spot a {@link Pattern}. */
    static boolean isRegex(MatcherHolder m) {
        return m instanceof RegexMatcher;
    }

    /** Return a compiled Pattern if {@code m} is a regex matcher. */
    public static Pattern regexOf(MatcherHolder m) {
        return m instanceof RegexMatcher r ? r.pattern() : null;
    }

    /** Return the matcher frozenset if {@code m} is a set matcher. */
    public static Set<String> setOf(MatcherHolder m) {
        return m instanceof SetMatcher s ? s.names() : null;
    }
}
