package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;
import org.aethercode.core.runtime.llm.profiles.AnthropicHaiku45Profile;
import org.aethercode.core.runtime.llm.profiles.AnthropicOpus47Profile;
import org.aethercode.core.runtime.llm.profiles.AnthropicSonnet46Profile;
import org.aethercode.core.runtime.llm.profiles.NvidiaNemotron3UltraProfile;
import org.aethercode.core.runtime.llm.profiles.OpenAiCodexProfile;
import org.aethercode.core.runtime.llm.profiles.provider.NvidiaProviderProfile;
import org.aethercode.core.runtime.llm.profiles.provider.OpenAiProviderProfile;
import org.aethercode.core.runtime.llm.profiles.provider.OpenRouterProviderProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bootstrap for built-in and third-party profile plugins.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles._builtin_profiles}. Built-in provider
 * and harness profiles are registered via explicit module imports
 * &mdash; not entry points &mdash; so a malformed or missing
 * {@code dist-info} in the environment cannot silently disable the
 * SDK's own defaults. Third parties plug in via a service-loader-style
 * registry (see {@link ProfilePluginRegistry}).</p>
 *
 * <p>Bootstrap is lazy: importing
 * {@code org.aethercode.core.runtime.llm.profiles} does not trigger it.
 * The first call to a registry lookup
 * ({@link HarnessProfile#getHarnessProfile(String)} or
 * {@link org.aethercode.core.runtime.llm.ProviderProfile#getProviderProfile(String)})
 * runs the bootstrap exactly once per JVM, with cross-thread
 * coordination so a second thread never observes a partially
 * populated registry.</p>
 */
public final class BuiltinProfiles {
    private static final Logger LOGGER = Logger.getLogger(BuiltinProfiles.class.getName());

    /** Service-loader-style group name for third-party {@code ProviderProfile} plugins. */
    public static final String PROVIDER_PROFILE_GROUP = "deepagents.provider_profiles";

    /** Service-loader-style group name for third-party {@code HarnessProfile} plugins. */
    public static final String HARNESS_PROFILE_GROUP = "deepagents.harness_profiles";

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Condition DONE = LOCK.newCondition();
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> RE_ENTRY = new ThreadLocal<>();

    /** Snapshot of harness-profile keys registered during bootstrap. */
    private static Set<String> BOOTSTRAP_HARNESS_KEYS = Set.of();

    private BuiltinProfiles() {}

    /**
     * Ensure the lazy built-in/plugin bootstrap has completed.
     *
     * <p>Runs the built-in provider {@code register} functions, then
     * iterates the registered third-party plugin groups. Built-ins
     * run first so third-party plugins registering under the same
     * key layer on top via additive merge semantics.</p>
     *
     * <p>Idempotent: re-entry from the same thread short-circuits;
     * concurrent threads block until the first thread completes.</p>
     */
    public static void ensureBuiltinProfilesLoaded() {
        if (LOADED.get()) return;
        if (Boolean.TRUE.equals(RE_ENTRY.get())) return;

        LOCK.lock();
        try {
            if (LOADED.get()) return;
            if (Boolean.TRUE.equals(RE_ENTRY.get())) return;
            RE_ENTRY.set(true);
            try {
                Map<String, HarnessProfile> savedHarness = new LinkedHashMap<>(
                        HarnessProfile.snapshot());
                org.aethercode.core.runtime.llm.ProviderProfile.KeyedSnapshot savedProvider =
                        org.aethercode.core.runtime.llm.ProviderProfile.snapshotKeyed();

                Set<String> bootstrapKeys;
                try {
                    NvidiaProviderProfile.register();
                    OpenAiProviderProfile.register();
                    OpenRouterProviderProfile.register();
                    AnthropicOpus47Profile.register();
                    AnthropicSonnet46Profile.register();
                    AnthropicHaiku45Profile.register();
                    NvidiaNemotron3UltraProfile.register();
                    OpenAiCodexProfile.register();
                    ProfilePluginRegistry.invokeGroup(PROVIDER_PROFILE_GROUP);
                    ProfilePluginRegistry.invokeGroup(HARNESS_PROFILE_GROUP);
                    bootstrapKeys = Set.copyOf(HarnessProfile.registeredKeys());
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE,
                            "Built-in profile bootstrap failed; restoring "
                                    + "pre-bootstrap registry state.", e);
                    HarnessProfile.restoreSnapshot(savedHarness);
                    org.aethercode.core.runtime.llm.ProviderProfile.restoreKeyedSnapshot(savedProvider);
                    RE_ENTRY.remove();
                    DONE.signalAll();
                    throw e;
                }
                BOOTSTRAP_HARNESS_KEYS = bootstrapKeys;
                LOADED.set(true);
                RE_ENTRY.remove();
                DONE.signalAll();
            } catch (Throwable t) {
                // Defensive: ensure RE_ENTRY is cleared even if a checked
                // exception (none expected here) escapes.
                RE_ENTRY.remove();
                throw t;
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** Whether the bootstrap has completed. */
    public static boolean isLoaded() {
        return LOADED.get();
    }

    /** Snapshot of harness-profile keys registered during bootstrap. */
    public static Set<String> bootstrapHarnessKeys() {
        return BOOTSTRAP_HARNESS_KEYS;
    }

    /** Force-clear the loaded state and registry contents. Test-only. */
    public static void resetForTest() {
        LOCK.lock();
        try {
            LOADED.set(false);
            BOOTSTRAP_HARNESS_KEYS = Set.of();
        } finally {
            LOCK.unlock();
        }
    }
}
