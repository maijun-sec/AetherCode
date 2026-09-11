package org.aethercode.models;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase 2.2 / T-2-14 (design.md §3.7): low-level YAML
 * parser for {@code providers.yaml}. Pure I/O — no caching,
 * no layering, no filesystem-walk. Callers (the registry)
 * decide where to read from and how to merge.
 *
 * <p>Expected YAML shape:
 * <pre>
 * providers:
 *   - name: anthropic
 *     models:
 *       - name: claude-opus-4-1
 *         contextWindow: 200000
 *         maxOutput: 32000
 *         capabilities: [vision, tools, json-mode]
 *         pricing:
 *           inputPerMTokensUsd: 15.0
 *           outputPerMTokensUsd: 75.0
 *           cachedPerMTokensUsd: 1.5
 * </pre>
 *
 * <p>The loader is permissive: it ignores unknown fields, drops
 * entries with missing required fields, and surfaces bad YAML
 * as a {@link ModelRegistryException} (Kind.BAD_YAML). The intent
 * is that a hand-edited file with one bad row doesn't take down
 * the daemon — the rest of the file is still loaded.
 */
public final class ModelRegistryLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ModelRegistryLoader.class);

    /** One provider block in the YAML — just enough to build
     *  {@link ModelProfile}s. The {@code active} flag is read
     *  separately; see {@link #loadActiveNames}. */
    public record ProviderBlock(
            String name,
            List<ModelProfile> models
    ) {}

    private final ObjectMapper yaml;

    public ModelRegistryLoader() {
        this.yaml = new ObjectMapper(new YAMLFactory());
    }

    /** Load providers + models from a single YAML file. The file
     *  does not need to exist; an empty list is returned. */
    public List<ModelProfile> loadFromFile(Path file) {
        if (!Files.exists(file)) {
            LOG.debug("providers.yaml not found at {}, returning empty list", file);
            return List.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return loadFromStream(in);
        } catch (IOException e) {
            throw ModelRegistryException.ioError("cannot read " + file, e);
        }
    }

    /** Load providers + models from an open stream. Used by tests
     *  that want to feed inline YAML. */
    @SuppressWarnings("unchecked")
    public List<ModelProfile> loadFromStream(InputStream in) {
        try {
            Map<String, Object> root = yaml.readValue(in, new TypeReference<>() {});
            Object providersNode = root.get("providers");
            if (!(providersNode instanceof List<?> providersList)) {
                return List.of();
            }
            List<ModelProfile> out = new ArrayList<>();
            for (Object p : providersList) {
                if (!(p instanceof Map<?, ?> providerMap)) continue;
                // A missing provider name falls back to "unknown" —
                // a model under a nameless provider block is still
                // a useful model. The contract is "missing rows are
                // skipped", not "missing provider metadata is fatal".
                String pname = stringOf(providerMap.get("name"));
                if (pname == null || pname.isBlank()) pname = "unknown";
                Object modelsNode = providerMap.get("models");
                if (!(modelsNode instanceof List<?> modelsList)) continue;
                for (Object m : modelsList) {
                    if (!(m instanceof Map<?, ?> modelMap)) continue;
                    ModelProfile profile = parseModel(pname, (Map<String, Object>) modelMap);
                    if (profile != null) out.add(profile);
                }
            }
            return out;
        } catch (IOException e) {
            throw ModelRegistryException.badYaml("invalid providers.yaml: " + e.getMessage(), e);
        }
    }

    /** Read the top-level {@code active} field — a list of model
     *  names that should be marked as the user's default for the
     *  containing layer. Both a bare string and a list are
     *  supported (the list form is for projects that ship a
     *  per-language default). */
    @SuppressWarnings("unchecked")
    public List<String> loadActiveNames(Path file) {
        if (!Files.exists(file)) return List.of();
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = yaml.readValue(in, new TypeReference<>() {});
            Object active = root.get("active");
            if (active == null) return List.of();
            if (active instanceof String s) {
                return s.isBlank() ? List.of() : List.of(s);
            }
            if (active instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object e : list) {
                    if (e != null) out.add(e.toString());
                }
                return out;
            }
            return List.of();
        } catch (IOException e) {
            LOG.warn("cannot read active field from {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /** Parse a single model entry. Returns null for entries that
     *  are missing required fields — the loader is permissive. */
    @SuppressWarnings("unchecked")
    private ModelProfile parseModel(String providerName, Map<String, Object> modelMap) {
        String name = stringOf(modelMap.get("name"));
        if (name == null || name.isBlank()) {
            LOG.warn("model entry missing 'name' under provider {}", providerName);
            return null;
        }
        int contextWindow = intOf(modelMap.get("contextWindow"), 0);
        int maxOutput = intOf(modelMap.get("maxOutput"), 0);
        String displayName = stringOf(modelMap.get("displayName"));
        Set<String> capabilities = new TreeSet<>();
        Object caps = modelMap.get("capabilities");
        if (caps instanceof List<?> capList) {
            for (Object c : capList) {
                if (c != null) capabilities.add(c.toString());
            }
        }
        Pricing pricing = Pricing.free();
        Object pricingNode = modelMap.get("pricing");
        if (pricingNode instanceof Map<?, ?> pricingMap) {
            pricing = parsePricing((Map<String, Object>) pricingMap);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        Object metaNode = modelMap.get("metadata");
        if (metaNode instanceof Map<?, ?> metaMap) {
            for (Map.Entry<?, ?> e : metaMap.entrySet()) {
                meta.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return new ModelProfile(
                name,
                providerName,
                displayName != null ? displayName : name,
                contextWindow,
                maxOutput,
                capabilities,
                pricing,
                meta
        );
    }

    private Pricing parsePricing(Map<String, Object> pricingMap) {
        Double in = doubleOf(pricingMap.get("inputPerMTokensUsd"));
        Double out = doubleOf(pricingMap.get("outputPerMTokensUsd"));
        Double cached = doubleOf(pricingMap.get("cachedPerMTokensUsd"));
        try {
            return new Pricing(in, out, cached);
        } catch (IllegalArgumentException iae) {
            LOG.warn("ignoring invalid pricing block: {}", iae.getMessage());
            return Pricing.free();
        }
    }

    private static String stringOf(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static int intOf(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    private static Double doubleOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /** Sort helper: by provider, then by name. Used by the registry
     *  to keep the listed order stable across reloads. */
    public static List<ModelProfile> sorted(List<ModelProfile> in) {
        TreeMap<String, TreeSet<ModelProfile>> grouped = new TreeMap<>();
        for (ModelProfile p : in) {
            grouped.computeIfAbsent(p.provider(), k -> new TreeSet<>((a, b) -> a.name().compareTo(b.name())))
                    .add(p);
        }
        List<ModelProfile> out = new ArrayList<>(in.size());
        for (var e : grouped.entrySet()) {
            out.addAll(e.getValue());
        }
        return out;
    }
}
