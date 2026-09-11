package org.aethercode.core.runtime.llm.profiles.provider;

import org.aethercode.core.runtime.llm.ProviderProfile;
import org.aethercode.core.runtime.llm.ProviderProfileRegistry;

import java.util.Map;

/**
 * Built-in NVIDIA provider profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.provider._nvidia}. Injects Deep Agents
 * app-origin attribution into NVIDIA NIM requests via the
 * {@code X-BILLING-INVOKE-ORIGIN} header supported by
 * {@code langchain-nvidia-ai-endpoints}. Users may layer
 * additional kwargs on top via
 * {@link ProviderProfile#registerProviderProfile(String, ProviderProfile)}.</p>
 */
public final class NvidiaProviderProfile {
    private NvidiaProviderProfile() {}

    /** NVIDIA NIM header used to attribute requests to an originating app. */
    public static final String NVIDIA_BILLING_ORIGIN_HEADER = "X-BILLING-INVOKE-ORIGIN";

    /** Deep Agents identity reported to NVIDIA NIM. */
    public static final String NVIDIA_APP_ORIGIN = "DeepAgents";

    /**
     * Build default NVIDIA NIM app-attribution kwargs. Returning a
     * new nested mapping on every call keeps profile resolution
     * isolated between model instances.
     */
    public static Map<String, Object> nvidiaAttributionKwargs() {
        return Map.of(
                "default_headers",
                Map.of(NVIDIA_BILLING_ORIGIN_HEADER, NVIDIA_APP_ORIGIN));
    }

    /**
     * Register the built-in NVIDIA provider profile with both the
     * high-level {@link ProviderProfile} registry and the low-level
     * {@link ProviderProfileRegistry} so the resolver
     * {@code applyProviderProfile} path can find the factory kwargs.
     */
    public static void register() {
        ProviderProfile.registerProviderProfile("nvidia",
                new ProviderProfile(
                        null, null,
                        NvidiaProviderProfile::nvidiaAttributionKwargs));
        ProviderProfileRegistry.register("nvidia", spec -> {
            ProviderProfile p = ProviderProfile.getProviderProfile(spec);
            if (p != null && p.preInit() != null) {
                p.preInit().accept(spec);
            }
            if (p != null && p.initKwargsFactory() != null) {
                return p.initKwargsFactory().get();
            }
            return nvidiaAttributionKwargs();
        });
    }
}
