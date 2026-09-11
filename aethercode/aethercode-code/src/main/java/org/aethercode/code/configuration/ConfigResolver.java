package org.aethercode.code.configuration;

import org.aethercode.code.configuration.ConfigProviders.RankedProviderValue;
import org.aethercode.code.configuration.ConfigTypes.Found;
import org.aethercode.code.configuration.ConfigTypes.ProviderHealth;
import org.aethercode.code.configuration.ConfigTypes.ProviderResult;
import org.aethercode.code.configuration.ConfigTypes.ProviderStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure ranked resolution and deep-merge logic for layered configuration.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.resolver} module. The ranked
 * engine is intentionally unaware of the manifest, UI, model, theme,
 * environment, or filesystem.</p>
 */
public final class ConfigResolver {
    private static final Logger LOGGER = Logger.getLogger(ConfigResolver.class.getName());

    /** Managed policy rank; lower numeric ranks have stronger precedence. */
    public static final int MANAGED_RANK = 200;
    /** Reserved seam for a future CLI provider; no CLI provider ships today. */
    public static final int CLI_RANK = 300;
    /** Process-environment rank. */
    public static final int ENVIRONMENT_RANK = 400;
    /** User {@code config.toml} rank. */
    public static final int USER_RANK = 500;
    /** Typed manifest-default rank. */
    public static final int DEFAULT_RANK = 1000;

    private final List<Provider> providers;
    private final ReentrantLock lock = new ReentrantLock();

    public ConfigResolver(List<Provider> providers) {
        java.util.Objects.requireNonNull(providers, "providers");
        List<Provider> ordered = new ArrayList<>(providers);
        ordered.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Provider p : ordered) {
            if (!seen.add(p.rank())) {
                throw new IllegalArgumentException("config providers must have unique ranks");
            }
        }
        this.providers = List.copyOf(ordered);
    }

    public List<Provider> providers() { return providers; }

    /**
     * Resolve one option through every provider.
     */
    public ResolvedValue get(Provider.ManifestOption option) {
        lock.lock();
        try {
            return resolve(option, providers);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolve one option against a lock-held provider generation.
     */
    static ResolvedValue resolve(Provider.ManifestOption option, List<Provider> providers) {
        List<RankedProviderValue> values = new ArrayList<>();
        for (Provider p : providers) {
            ProviderResult<Object> result = p.get(option);
            ProviderStatus status = p.status();
            boolean durable = p.durable();
            values.add(new RankedProviderValue(p.rank(), durable, status, result, List.of()));
        }
        String strategy = option.mergeStrategyName() == null
                ? "replace" : option.mergeStrategyName();
        List<RankedProviderValue> effectiveValues = values;
        if ("union".equals(strategy) || "deep_merge".equals(strategy)) {
            effectiveValues = new ArrayList<>();
            for (RankedProviderValue v : values) {
                if (v.rank() != DEFAULT_RANK) effectiveValues.add(v);
            }
        }
        ResolvedValue resolved = resolveRanked(effectiveValues, strategy);
        if (resolved == null) {
            RankedProviderValue fallback = new RankedProviderValue(DEFAULT_RANK, true,
                    new ProviderStatus("default", null, ProviderHealth.OK),
                    new Found<>(option.defaultValue()),
                    List.of());
            List<RankedProviderValue> withoutDefault = new ArrayList<>();
            for (RankedProviderValue v : values) {
                if (v.rank() != DEFAULT_RANK) withoutDefault.add(v);
            }
            List<RankedProviderValue> augmented = new ArrayList<>(withoutDefault);
            augmented.add(fallback);
            resolved = resolveRanked(augmented, strategy);
        }
        if (resolved == null) {
            throw new IllegalStateException("fallback provider was unset for " + option.key());
        }
        return resolved;
    }

    /** Reload every provider. */
    public void reload() {
        lock.lock();
        try {
            for (Provider p : providers) p.reload();
        } finally {
            lock.unlock();
        }
    }

    /** Return provider health keyed by precedence rank. */
    public Map<Integer, ProviderStatus> providerStatuses() {
        lock.lock();
        try {
            Map<Integer, ProviderStatus> statuses = new LinkedHashMap<>();
            for (Provider p : providers) statuses.put(p.rank(), p.status());
            return Collections.unmodifiableMap(statuses);
        } finally {
            lock.unlock();
        }
    }

    // ---- Static resolution helpers ------------------------------------------

    /**
     * One provider's already-coerced result for an option.
     */
    public record RankedProviderValue(int rank, boolean durable, ProviderStatus status,
                                       ProviderResult<Object> result, List<String> diagnostics) {
        public RankedProviderValue {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    /**
     * Resolved value with rank-keyed provenance and provider health.
     */
    public record ResolvedValue(Object value,
                                 Map<Integer, java.util.Set<List<String>>> provenance,
                                 Map<Integer, ProviderResult<Object>> tierHealth,
                                 Map<Integer, ProviderStatus> providerStatus,
                                 java.util.Set<Integer> maskedRanks,
                                 List<Integer> selectedRanks,
                                 Map<Integer, List<String>> tierDiagnostics) {
        public ResolvedValue {
            provenance = Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
            tierHealth = Collections.unmodifiableMap(new LinkedHashMap<>(tierHealth));
            providerStatus = Collections.unmodifiableMap(new LinkedHashMap<>(providerStatus));
            maskedRanks = maskedRanks == null ? java.util.Set.of() : java.util.Set.copyOf(maskedRanks);
            selectedRanks = selectedRanks == null ? List.of() : List.copyOf(selectedRanks);
            tierDiagnostics = tierDiagnostics == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(tierDiagnostics));
            // Selected ranks must all be present in providerStatus.
            for (Integer r : selectedRanks) {
                if (!providerStatus.containsKey(r)) {
                    throw new IllegalArgumentException(
                            "selected ranks " + r + " have no provider status; "
                                    + "rendering provenance would raise KeyError");
                }
            }
            for (Integer r : maskedRanks) {
                if (selectedRanks.contains(r)) {
                    throw new IllegalArgumentException(
                            "ranks " + r + " cannot be both selected and masked");
                }
            }
        }

        /** Contributing ranks in precedence order. */
        public List<Integer> ranks() {
            return selectedRanks.isEmpty()
                    ? provenance.keySet().stream().sorted().toList()
                    : selectedRanks;
        }
    }

    /**
     * Resolve provider results by numeric rank and merge strategy.
     */
    public static ResolvedValue resolveRanked(List<RankedProviderValue> providers, String strategy) {
        if (strategy == null) strategy = "replace";
        if (!List.of("replace", "union", "deep_merge").contains(strategy)) {
            throw new IllegalArgumentException("unknown config merge strategy: " + strategy);
        }
        List<RankedProviderValue> ordered = new ArrayList<>(providers);
        ordered.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        java.util.Set<Integer> ranks = new java.util.HashSet<>();
        for (RankedProviderValue p : ordered) {
            if (!ranks.add(p.rank())) {
                throw new IllegalArgumentException("ranked config providers must have unique ranks");
            }
        }
        Map<Integer, ProviderResult<Object>> tierHealth = new LinkedHashMap<>();
        Map<Integer, ProviderStatus> providerStatus = new LinkedHashMap<>();
        Map<Integer, List<String>> tierDiagnostics = new LinkedHashMap<>();
        for (RankedProviderValue p : ordered) {
            tierHealth.put(p.rank(), p.result());
            providerStatus.put(p.rank(), p.status());
            tierDiagnostics.put(p.rank(), p.diagnostics());
        }
        List<RankedProviderValue> found = new ArrayList<>();
        for (RankedProviderValue p : ordered) {
            if (p.result() instanceof Found) found.add(p);
        }
        if (found.isEmpty()) return null;
        if ("union".equals(strategy)) {
            return resolveRankedUnion(found, tierHealth, providerStatus, tierDiagnostics);
        }
        if ("deep_merge".equals(strategy)) {
            return resolveRankedDeepMerge(found, tierHealth, providerStatus, tierDiagnostics);
        }
        // replace
        List<Integer> durableRanks = new ArrayList<>();
        for (RankedProviderValue p : found) {
            if (p.durable()) durableRanks.add(p.rank());
        }
        java.util.Set<Integer> masked = new java.util.LinkedHashSet<>();
        for (RankedProviderValue p : found) {
            if (!p.durable()) {
                for (Integer dr : durableRanks) {
                    if (dr < p.rank()) {
                        masked.add(p.rank());
                        break;
                    }
                }
            }
        }
        RankedProviderValue winner = null;
        for (RankedProviderValue p : found) {
            if (!masked.contains(p.rank())) { winner = p; break; }
        }
        if (winner == null) winner = found.get(0);
        Map<Integer, java.util.Set<List<String>>> provenance = new LinkedHashMap<>();
        provenance.put(winner.rank(), java.util.Set.of(List.of()));
        return new ResolvedValue(
                ((Found<?>) winner.result()).value(),
                provenance, tierHealth, providerStatus, masked,
                List.of(winner.rank()), tierDiagnostics);
    }

    private static ResolvedValue resolveRankedUnion(
            List<RankedProviderValue> found,
            Map<Integer, ProviderResult<Object>> tierHealth,
            Map<Integer, ProviderStatus> providerStatus,
            Map<Integer, List<String>> tierDiagnostics) {
        // Best-effort union of list-like values.
        List<Object> union = new ArrayList<>();
        for (int i = found.size() - 1; i >= 0; i--) {
            Object v = ((Found<?>) found.get(i).result()).value();
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (!union.contains(o)) union.add(deepCopy(o));
                }
            } else {
                // Non-list: fall back to replace.
                return replaceWithStrongest(found, tierHealth, providerStatus, tierDiagnostics);
            }
        }
        Map<Integer, java.util.Set<List<String>>> provenance = new LinkedHashMap<>();
        List<Integer> selected = new ArrayList<>();
        for (RankedProviderValue p : found) {
            provenance.put(p.rank(), java.util.Set.of(List.of()));
            selected.add(p.rank());
        }
        return new ResolvedValue(union, provenance, tierHealth, providerStatus,
                java.util.Set.of(), selected, tierDiagnostics);
    }

    private static ResolvedValue resolveRankedDeepMerge(
            List<RankedProviderValue> found,
            Map<Integer, ProviderResult<Object>> tierHealth,
            Map<Integer, ProviderStatus> providerStatus,
            Map<Integer, List<String>> tierDiagnostics) {
        RankedProviderValue weakest = found.get(found.size() - 1);
        Object value = ((Found<?>) weakest.result()).value();
        if (!(value instanceof Map<?, ?>)) {
            return replaceWithStrongest(found, tierHealth, providerStatus, tierDiagnostics);
        }
        Map<String, Object> merged = deepCopyMap(castStringKeyed(value));
        for (int i = found.size() - 2; i >= 0; i--) {
            Object higher = ((Found<?>) found.get(i).result()).value();
            if (!(higher instanceof Map<?, ?>)) {
                return replaceWithStrongest(found, tierHealth, providerStatus, tierDiagnostics);
            }
            merged = deepMergeMaps(merged, castStringKeyed(higher));
        }
        Map<Integer, java.util.Set<List<String>>> provenance = new LinkedHashMap<>();
        List<Integer> selected = new ArrayList<>();
        for (RankedProviderValue p : found) {
            provenance.put(p.rank(), java.util.Set.of(List.of()));
            selected.add(p.rank());
        }
        return new ResolvedValue(merged, provenance, tierHealth, providerStatus,
                java.util.Set.of(), selected, tierDiagnostics);
    }

    private static ResolvedValue replaceWithStrongest(
            List<RankedProviderValue> found,
            Map<Integer, ProviderResult<Object>> tierHealth,
            Map<Integer, ProviderStatus> providerStatus,
            Map<Integer, List<String>> tierDiagnostics) {
        RankedProviderValue winner = found.get(0);
        Map<Integer, java.util.Set<List<String>>> provenance = new LinkedHashMap<>();
        provenance.put(winner.rank(), java.util.Set.of(List.of()));
        return new ResolvedValue(deepCopy(((Found<?>) winner.result()).value()),
                provenance, tierHealth, providerStatus, java.util.Set.of(),
                List.of(winner.rank()), tierDiagnostics);
    }

    // ---- Helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyed(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String s) out.put(s, e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) out.put(e.getKey(), deepCopy(e.getValue()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> m) {
            return deepCopyMap(castStringKeyed(m));
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(deepCopy(o));
            return out;
        }
        return value;
    }

    private static Map<String, Object> deepMergeMaps(Map<String, Object> lower,
                                                     Map<String, Object> higher) {
        Map<String, Object> out = new LinkedHashMap<>(lower);
        for (var e : higher.entrySet()) {
            String key = e.getKey();
            Object existing = out.get(key);
            Object incoming = e.getValue();
            if (existing instanceof Map<?, ?> && incoming instanceof Map<?, ?>) {
                out.put(key, deepMergeMaps(castStringKeyed(existing), castStringKeyed(incoming)));
            } else {
                out.put(key, deepCopy(incoming));
            }
        }
        return out;
    }

    // ---- Object utilities ----------------------------------------------------
}
