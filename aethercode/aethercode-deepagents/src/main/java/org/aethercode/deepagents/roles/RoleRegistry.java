package org.aethercode.deepagents.roles;

import org.aethercode.deepagents.middleware.SubAgent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * prior round.2 (O-9): a registry of {@link Role}s the agent can
 * dispatch work to. The registry is the single source of truth
 * for "what specialists are available in this workflow".
 *
 * <h2>Standard role presets</h2>
 *
 * <p>Five role presets ship with the registry (mirroring the
 * CrewAI / AutoGen / LangGraph common pattern):
 *
 * <ul>
 *   <li>{@code planner} — decomposes a high-level goal into a
 *       sequenced plan.</li>
 *   <li>{@code researcher} — gathers external context (web,
 *       files, prior runs).</li>
 *   <li>{@code coder} — implements the plan as code / files.</li>
 *   <li>{@code reviewer} — audits the change for bugs, style,
 *       regressions.</li>
 *   <li>{@code executor} — runs the changes (tests, scripts,
 *       deploys) and reports back.</li>
 * </ul>
 *
 * <p>Callers can either:
 *
 * <ol>
 *   <li>use {@link #standardRoles()} for the off-the-shelf
 *       five-role team,</li>
 *   <li>register custom roles with {@link #register(Role)} or
 *       {@link #registerAll(Collection)},</li>
 *   <li>or override individual presets with
 *       {@link #replace(Role)} (e.g. swap the default
 *       {@code reviewer} prompt for a security-focused
 *       variant).</li>
 * </ol>
 *
 * <h2>Compile to {@link SubAgent}s</h2>
 *
 * <p>{@link #asSubAgents()} returns the lower-level
 * representation the {@code CreateDeepAgent} builder
 * understands. The registry stays out of the way; the deep
 * agent loop sees the same {@code SubAgent[]} it always did.
 */
public final class RoleRegistry {

    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final List<RoleRegistryListener> listeners = new CopyOnWriteArrayList<>();

    public RoleRegistry() {
        for (Role r : StandardRoles.all()) {
            roles.put(r.name(), r);
        }
    }

    public RoleRegistry register(Role role) {
        Objects.requireNonNull(role, "role");
        Role prev = roles.put(role.name(), role);
        fireAdded(role, prev);
        return this;
    }

    public RoleRegistry registerAll(Collection<Role> roles) {
        for (Role r : roles) register(r);
        return this;
    }

    /**
     * Replace a role with the same name, regardless of whether
     * it was registered before. Returns the previous role
     * (empty if it was new).
     */
    public Optional<Role> replace(Role role) {
        Objects.requireNonNull(role, "role");
        Role prev = roles.put(role.name(), role);
        fireAdded(role, prev);
        return Optional.ofNullable(prev);
    }

    public boolean unregister(String name) {
        Role removed = roles.remove(name);
        if (removed != null) {
            fireRemoved(removed);
            return true;
        }
        return false;
    }

    public Optional<Role> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(roles.get(name));
    }

    public boolean contains(String name) {
        return name != null && roles.containsKey(name);
    }

    public int size() {
        return roles.size();
    }

    public Set<String> names() {
        return Set.copyOf(roles.keySet());
    }

    /** Snapshot of the registered roles, in registration order. */
    public List<Role> all() {
        return List.copyOf(roles.values());
    }

    /** The default 5-role team. Equivalent to a fresh
     *  {@code RoleRegistry}'s contents, but provided as a
     *  static helper for callers that want to inspect the
     *  presets without instantiating the registry. */
    public static List<Role> standardRoles() {
        return StandardRoles.all();
    }

    /**
     * Compile every registered role to a {@link SubAgent} so
     * the {@code CreateDeepAgent} builder can pick them up
     * via its {@code subagents} argument. Useful one-liner:
     * <pre>{@code
     *   CreateDeepAgent.create(..., registry.asSubAgents(), ...);
     * }</pre>
     *
     * <p>The output order is deterministic and follows the
     * standard preset order (planner → researcher → coder →
     * reviewer → executor) for any role name in that
     * sequence, with custom roles appended at the end. This
     * keeps the system prompt the model sees stable across
     * runs even though the underlying {@link java.util.Map}
     * iteration order is not.
     */
    public List<SubAgent> asSubAgents() {
        List<SubAgent> out = new ArrayList<>(roles.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Role preset : StandardRoles.all()) {
            Role r = roles.get(preset.name());
            if (r != null) {
                out.add(r.toSubAgent());
                seen.add(preset.name());
            }
        }
        for (Role r : roles.values()) {
            if (!seen.contains(r.name())) {
                out.add(r.toSubAgent());
                seen.add(r.name());
            }
        }
        return Collections.unmodifiableList(out);
    }

    // -----------------------------------------------------------------
    //  Listeners — small observer hook for UIs / metrics / tests.
    // -----------------------------------------------------------------

    public interface RoleRegistryListener {
        default void onAdded(Role role, Role replaced) {}
        default void onRemoved(Role role) {}
    }

    public void addListener(RoleRegistryListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeListener(RoleRegistryListener listener) {
        return listeners.remove(listener);
    }

    private void fireAdded(Role r, Role replaced) {
        for (RoleRegistryListener l : listeners) {
            try { l.onAdded(r, replaced); }
            catch (RuntimeException re) {
                // listener bugs must not corrupt the registry
            }
        }
    }

    private void fireRemoved(Role r) {
        for (RoleRegistryListener l : listeners) {
            try { l.onRemoved(r); }
            catch (RuntimeException re) {
                // same as above
            }
        }
    }

    /** Visible for tests: render a compact map of the registry. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Role r : roles.values()) {
            String desc = r.description();
            if (desc.length() > 80) desc = desc.substring(0, 77) + "...";
            out.put(r.name(), desc);
        }
        return out;
    }
}
