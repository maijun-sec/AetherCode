package org.aethercode.core.runtime.llm.profiles;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-process registry for third-party profile plugins.
 *
 * <p>Java-native port of the
 * {@code deepagents.profiles._builtin_profiles} entry-point
 * discovery. Third-party code can register a zero-arg
 * {@link Runnable} (the registration hook) under one of the two
 * group names:
 *
 * <ul>
 *   <li>{@link BuiltinProfiles#PROVIDER_PROFILE_GROUP}</li>
 *   <li>{@link BuiltinProfiles#HARNESS_PROFILE_GROUP}</li>
 * </ul>
 *
 * <p>On bootstrap, every registered hook in each group is invoked
 * exactly once. A failing plugin is logged and skipped so a single
 * misbehaving distribution cannot prevent the rest from loading.</p>
 */
public final class ProfilePluginRegistry {
    private static final Logger LOGGER = Logger.getLogger(ProfilePluginRegistry.class.getName());

    private static final List<Runnable> PROVIDER_HOOKS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> HARNESS_HOOKS = new CopyOnWriteArrayList<>();

    private ProfilePluginRegistry() {}

    /** Register a third-party hook for a profile group. */
    public static void register(String group, Runnable hook) {
        if (group == null || hook == null) {
            throw new IllegalArgumentException("group and hook are required");
        }
        switch (group) {
            case BuiltinProfiles.PROVIDER_PROFILE_GROUP -> PROVIDER_HOOKS.add(hook);
            case BuiltinProfiles.HARNESS_PROFILE_GROUP -> HARNESS_HOOKS.add(hook);
            default -> throw new IllegalArgumentException("Unknown group: " + group);
        }
    }

    /** Invoke every hook in a group, isolating failures. */
    public static void invokeGroup(String group) {
        List<Runnable> hooks = hooksFor(group);
        for (Runnable hook : hooks) {
            invokeSafely(group, hook);
        }
    }

    private static List<Runnable> hooksFor(String group) {
        if (BuiltinProfiles.PROVIDER_PROFILE_GROUP.equals(group)) return PROVIDER_HOOKS;
        if (BuiltinProfiles.HARNESS_PROFILE_GROUP.equals(group)) return HARNESS_HOOKS;
        LOGGER.log(Level.WARNING, "Unknown plugin group {0}; no hooks invoked.", group);
        return List.of();
    }

    private static void invokeSafely(String group, Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE,
                    "Plugin in " + group + " raised; registrations from this "
                            + "plugin are absent: " + e.getMessage(), e);
        }
    }

    /** Test-only: clear all hooks. */
    public static void clear() {
        PROVIDER_HOOKS.clear();
        HARNESS_HOOKS.clear();
    }

    /** Test-only: count registered hooks for a group. */
    public static int count(String group) {
        return hooksFor(group).size();
    }
}
