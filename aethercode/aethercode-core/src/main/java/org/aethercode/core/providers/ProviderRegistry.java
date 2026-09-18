package org.aethercode.core.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * in-memory index of every
 * {@link ProviderSpec} AetherCode can talk to.
 *
 * <p>Loaded from {@code <userHome>/.aethercode/providers.yaml}
 * (or any explicit path passed to the constructor).
 * When the file is missing, the registry falls back
 * to a sensible default set: {@code minmax}
 * (the original target), plus {@code glm}, {@code qwen},
 * {@code deepseek} — these are the Chinese brands the
 * user has on their shortlist. Foreign brands
 * ({@code anthropic}, {@code openai}, {@code gemini})
 * are listed but documented as
 * "unverified — bring your own tests".
 *
 * <p>The registry is the source of truth for the
 * desktop's Settings provider picker; the daemon
 * exposes the list via the {@code listProviders} RPC.
 *
 * <p>For tests, construct an instance directly with
 * {@link #ProviderRegistry(List)} (in-memory).
 */
public final class ProviderRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, ProviderSpec> byName;

    private ProviderRegistry(Map<String, ProviderSpec> byName) {
        this.byName = Map.copyOf(byName);
    }

    /** In-memory registry from a list of specs. The
     *  first spec whose name matches a later one
     *  wins (so a user override in providers.yaml
     *  beats a bundled default). */
    public ProviderRegistry(List<ProviderSpec> specs) {
        this.byName = index(specs);
    }

    /** Load from a YAML file. Missing file → bundled
     *  defaults (the four Chinese brands the user
     *  signed off on, plus the foreign brands as
     *  "untested"). Malformed YAML → empty list +
     *  a warning log; the renderer falls back to a
     *  "no providers" empty state. */
    public static ProviderRegistry loadFrom(Path yamlFile) {
        if (yamlFile == null || !Files.exists(yamlFile)) {
            LOG.info("providers.yaml not found at {} — using bundled defaults", yamlFile);
            return new ProviderRegistry(bundledDefaults());
        }
        try {
            String raw = Files.readString(yamlFile);
            return parse(raw);
        } catch (IOException e) {
            LOG.warn("failed to read providers.yaml at {}: {} — using empty registry",
                    yamlFile, e.getMessage());
            return new ProviderRegistry(List.of());
        }
    }

    /** Parse from a raw YAML string. Public for
     *  tests; the file-based loadFrom is the
     *  normal path. */
    public static ProviderRegistry parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new ProviderRegistry(List.of());
        }
        try {
            ObjectMapper om = new ObjectMapper(new YAMLFactory());
            YamlShape shape = om.readValue(yaml, YamlShape.class);
            List<ProviderSpec> specs = new ArrayList<>();
            if (shape != null && shape.providers != null) {
                for (ProviderYaml py : shape.providers) {
                    specs.add(py.toSpec());
                }
            }
            return new ProviderRegistry(specs);
        } catch (Exception e) {
            LOG.warn("failed to parse providers.yaml: {}", e.getMessage());
            return new ProviderRegistry(List.of());
        }
    }

    public List<ProviderSpec> list() {
        return List.copyOf(byName.values());
    }

    public Optional<ProviderSpec> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    /** The first provider in the list — used when
     *  no explicit provider was chosen. The bundled
     *  default is {@code minmax} (the project's
     *  original target); a custom providers.yaml
     *  can override by listing their preferred
     *  provider first. */
    public Optional<ProviderSpec> defaultProvider() {
        if (byName.isEmpty()) return Optional.empty();
        return Optional.of(byName.values().iterator().next());
    }

    private static Map<String, ProviderSpec> index(List<ProviderSpec> specs) {
        // LinkedHashMap to preserve insertion order
        // (matters for defaultProvider).
        Map<String, ProviderSpec> out = new LinkedHashMap<>();
        for (ProviderSpec p : specs) {
            // Later wins on collision (so a user
            // override in providers.yaml beats the
            // bundled default of the same name).
            out.put(p.name(), p);
        }
        return out;
    }

    // ---- YAML shape ----

    /** Internal type for YAML deserialisation. The
     *  on-the-wire shape is one level deep:
     *  {@code providers: [{name, type, baseUrl, ...}]}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class YamlShape {
        public List<ProviderYaml> providers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProviderYaml {
        public String name;
        public String type;
        public String baseUrl;
        public String apiKeyEnv;
        public String defaultModel;
        public List<ModelYaml> models;
        /** R283: provider-level compaction defaults. Each
         *  field is optional; absent fields fall back to
         *  the per-model block, then to
         *  {@link org.aethercode.core.compact.CompactConfig#DEFAULT}. */
        public CompactYaml compact;
        /** R285: provider-level variant defaults. Applied
         *  to every sibling model that doesn't declare
         *  its own variant block. A provider that wants
         *  to share presets across its whole model
         *  family (e.g. "GLM uses temperature 0.3 for
         *  low by default") can declare it once at
         *  the provider level. */
        public List<VariantYaml> variants;

        ProviderSpec toSpec() {
            List<ModelSpec> ms = new ArrayList<>();
            if (models != null) {
                for (ModelYaml my : models) {
                    int out = my.maxOutput != null && my.maxOutput > 0
                            ? my.maxOutput
                            : my.context;  // conservative default
                    // R283: convert the per-model compact block.
                    // The provider's compact block serves as a
                    // fallback for fields missing from the
                    // model block — the merge happens in
                    // {@link ModelSpec#compact()} / CompactSpec.toConfig().
                    CompactSpec merged = CompactYaml.merge(my.compact, compact);
                    // R285: same merge idea for variants. The
                    // model-level list wins when non-empty;
                    // otherwise we inherit the provider-level
                    // block (and ModelSpec's own constructor
                    // falls back to Variant.BUILTIN if BOTH
                    // are missing).
                    List<Variant> mergedVariants = VariantYaml.mergeList(my.variants, variants);
                    ms.add(new ModelSpec(
                            my.id,
                            my.inputPer1k,
                            my.outputPer1k,
                            my.context,
                            out,
                            Boolean.TRUE.equals(my.isDefault),
                            merged,
                            mergedVariants));
                }
            }
            // The provider-level compact uses its own contextWindow
            // (or falls back to the LARGEST model's context if
            // absent). The individual model's CompactSpec inherits
            // from this.
            CompactSpec providerCompact = compact != null
                    ? compact.toSpec()
                    : null;
            // R285: provider-level variant block to the
            // same model — sibling models that lack
            // their own variant list pick this up.
            List<Variant> providerVariants = variants != null
                    ? VariantYaml.toVariantList(variants)
                    : null;
            return new ProviderSpec(
                    name, type, baseUrl,
                    apiKeyEnv, defaultModel, ms, providerCompact, providerVariants);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ModelYaml {
        public String id;
        public double inputPer1k;
        public double outputPer1k;
        public int context;
        /** R136.4: max output tokens the model supports.
         *  Optional in YAML; if absent, defaults to
         *  {@code context} (the conservative ceiling). */
        public Integer maxOutput;
        /** YAML key "default" is a Java reserved word;
         *  {@link JsonProperty} maps the wire name
         *  to this field. The field name itself is
         *  the Java-idiomatic {@code isDefault}. */
        @JsonProperty("default")
        public Boolean isDefault;
        /** R283: per-model compaction override. */
        public CompactYaml compact;
        /** R285: per-model variants. Empty list means
         *  "inherit the provider's variant block"; a
         *  non-empty list replaces the provider
         *  block (so a model owner can pick exactly
         *  which presets apply). */
        public List<VariantYaml> variants;
    }

    /** R285: provider-level OR model-level variant
     *  block. Inner class so the YAML deserialiser
     *  can parse the same shape at both positions
     *  (mirrors {@link CompactYaml}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class VariantYaml {
        public String name;
        public String description;
        public Double temperature;
        public Integer maxTokens;
        public Integer reasoningBudget;
        public Boolean extendedThinking;

        VariantSpec toSpec() {
            return new VariantSpec(
                    name, description, temperature, maxTokens,
                    reasoningBudget, extendedThinking);
        }

        static List<Variant> toVariantList(List<VariantYaml> ys) {
            if (ys == null || ys.isEmpty()) return null;
            List<Variant> out = new ArrayList<>(ys.size());
            for (VariantYaml y : ys) {
                if (y == null || y.name == null || y.name.isBlank()) continue;
                out.add(y.toSpec().toVariant());
            }
            return out.isEmpty() ? null : out;
        }

        /** R285: resolve a model-level variants list
         *  against the provider's list. The model
         *  wins when non-empty; otherwise we fall
         *  back to the provider's block. A
         *  {@code null} or empty model-list means
         *  "inherit"; the resulting list is what the
         *  ModelSpec constructor receives. */
        static List<Variant> mergeList(List<VariantYaml> model,
                                       List<VariantYaml> provider) {
            List<Variant> modelResolved = model == null
                    ? null
                    : toVariantList(model);
            if (modelResolved != null) return modelResolved;
            return toVariantList(provider);
        }
    }

    /** R283: provider-level OR model-level compact block.
     *  Inner class so the YAML deserialiser can parse
     *  the same shape at both positions. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CompactYaml {
        public Integer contextWindow;
        public Integer compactAt;
        public Integer preserveTail;
        public String strategy;

        CompactSpec toSpec() {
            if (contextWindow == null) {
                throw new IllegalArgumentException(
                        "compact block requires contextWindow");
            }
            return new CompactSpec(contextWindow, compactAt, preserveTail, strategy);
        }

        /** merge a model-level block on top of a
         *  provider-level block. {@code provider} is the
         *  fallback (its values fill any nulls in
         *  {@code model}); {@code model} wins when both
         *  declare the same field. The result is
         *  always non-null when the provider block is
         *  present (we copy contextWindow from there
         *  when the model block omits it). */
        static CompactSpec merge(CompactYaml model, CompactYaml provider) {
            CompactYaml winner = model != null ? model : provider;
            if (winner == null) return null;
            CompactYaml parent = model != null ? provider : null;
            CompactYaml m = new CompactYaml();
            m.contextWindow = firstNonNull(model != null ? model.contextWindow : null,
                    parent != null ? parent.contextWindow : null);
            m.compactAt = firstNonNull(model != null ? model.compactAt : null,
                    parent != null ? parent.compactAt : null);
            m.preserveTail = firstNonNull(model != null ? model.preserveTail : null,
                    parent != null ? parent.preserveTail : null);
            m.strategy = firstNonNull(model != null ? model.strategy : null,
                    parent != null ? parent.strategy : null);
            return m.toSpec();
        }

        private static <T> T firstNonNull(T a, T b) {
            return a != null ? a : b;
        }
    }

    // ---- Bundled defaults ----

    /** Sensible default provider set. The user can
     *  override any of these by writing
     *  {@code ~/.aethercode/providers.yaml}. Foreign
     *  brands are listed but their pricing is a
     *  best-effort estimate — AetherCode doesn't run
     *  a real test against them, the user is on
     *  their own. */
    public static List<ProviderSpec> bundledDefaults() {
        List<ProviderSpec> out = new ArrayList<>();
        // minmax — the project's original target.
        // M3 is the bundled default. M3 is
        // the only model in this set that actually
        // drives tool calls reliably — the previous
        // default MiniMax-Text-01 hallucinates tool
        // calls in markdown (describes `file_write`
        // payloads inside ```json blocks instead of
        // issuing the wire-format tool_use message),
        // which surfaces to the user as "the task
        // failed silently". Text-01 is still listed
        // for users who want it, just not as default.
        out.add(new ProviderSpec(
                "minmax", "openai-compat",
                "https://api.minimaxi.com/v1",
                "MINIMAX_API_KEY",
                "MiniMax-M3",
                List.of(
                        // R136.4: MiniMax M3 family advertises 1M context
                        // and 512K output (API guarantees at least 512K
                        // available, max output 512K). Pin both so the
                        // chat-completion `max_tokens` param doesn't cap
                        // the model at 1024 like the R15 hardcoded default.
                        new ModelSpec("MiniMax-M3",       0.001, 0.008, 1_000_000, 512_000, true),
                        new ModelSpec("MiniMax-Text-01",  0.001, 0.008, 1_000_000, 512_000, false),
                        new ModelSpec("MiniMax-M1",       0.001, 0.008, 1_000_000, 512_000, false)
                )));
        // glm (智谱)
        out.add(new ProviderSpec(
                "glm", "openai-compat",
                "https://open.bigmodel.cn/api/paas/v4",
                "GLM_API_KEY",
                "glm-4-plus",
                List.of(
                        new ModelSpec("glm-4-plus",  0.0007, 0.0007, 128_000, 128_000, true),
                        new ModelSpec("glm-4-air",  0.0001, 0.0001, 128_000, 128_000, false),
                        new ModelSpec("glm-4-flash", 0.0001, 0.0001, 128_000, 128_000, false)
                )));
        // qwen (通义千问, DashScope OpenAI-compat)
        out.add(new ProviderSpec(
                "qwen", "openai-compat",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "DASHSCOPE_API_KEY",
                "qwen-plus",
                List.of(
                        new ModelSpec("qwen-plus",      0.0008, 0.002, 128_000, 128_000, true),
                        new ModelSpec("qwen-turbo",     0.0003, 0.0006, 1_000_000, 1_000_000, false),
                        new ModelSpec("qwen-max",       0.0002, 0.0006, 128_000, 128_000, false),
                        new ModelSpec("qwen-coder-plus", 0.0008, 0.002, 128_000, 128_000, false)
                )));
        // deepseek
        out.add(new ProviderSpec(
                "deepseek", "openai-compat",
                "https://api.deepseek.com",
                "DEEPSEEK_API_KEY",
                "deepseek-chat",
                List.of(
                        new ModelSpec("deepseek-chat",     0.00027, 0.0011, 64_000, 64_000, true),
                        new ModelSpec("deepseek-reasoner", 0.00055, 0.00219, 64_000, 64_000, false)
                )));
        // Foreign brands — listed, untested.
        // AetherCode doesn't run a real test against
        // these. The user provides the API key; if
        // the upstream API changes shape, things
        // break silently. Output ceilings use the
        // published vendor numbers.
        out.add(new ProviderSpec(
                "anthropic", "openai-compat",
                "https://api.anthropic.com/v1",
                "ANTHROPIC_API_KEY",
                "claude-sonnet-4-5",
                List.of(
                        ModelSpec.free("claude-sonnet-4-5", 200_000, 64_000),
                        ModelSpec.free("claude-opus-4-1",   200_000, 64_000),
                        ModelSpec.free("claude-haiku-4-5",  200_000, 64_000)
                )));
        out.add(new ProviderSpec(
                "openai", "openai-compat",
                "https://api.openai.com/v1",
                "OPENAI_API_KEY",
                "gpt-4o",
                List.of(
                        ModelSpec.free("gpt-4o",      128_000, 16_384),
                        ModelSpec.free("gpt-4o-mini", 128_000, 16_384),
                        ModelSpec.free("o1",         200_000, 100_000),
                        ModelSpec.free("o3-mini",    200_000, 100_000)
                )));
        out.add(new ProviderSpec(
                "gemini", "openai-compat",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "GEMINI_API_KEY",
                "gemini-2.5-pro",
                List.of(
                        ModelSpec.free("gemini-2.5-pro",   1_000_000, 64_000),
                        ModelSpec.free("gemini-2.5-flash", 1_000_000, 64_000)
                )));
        return Collections.unmodifiableList(out);
    }
}
