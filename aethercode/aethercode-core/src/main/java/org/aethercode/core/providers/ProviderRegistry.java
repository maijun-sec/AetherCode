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
 * <p>R343 — config architecture is two-tier:
 *
 * <ol>
 *   <li><b>Global</b>: {@code <install-dir>/providers.yaml}.
 *       This is the canonical model catalog (operator-owned).
 *       Add new providers, change prices, retire old models —
 *       IT controls the master catalogue. Loaded once at
 *       daemon boot.</li>
 *   <li><b>Per-project</b>: {@code <cwd>/.aethercode/providers.yaml}.
 *       Developers can override individual provider fields
 *       (enabled flag, headers, timeouts, inline apiKey) and
 *       pick a project-scoped default provider. Per-project
 *       yamls CANNOT add new provider names or new model ids —
 *       unknown ids are dropped with a startup warning. This
 *       keeps the model surface area under operator control
 *       (no surprise "the dev added an unsanctioned model"
 *       situation).</li>
 * </ol>
 *
 * <p>The {@link #loadCascade(Path, Path)} helper resolves the
 * two files, merges them with the validator, and falls back
 * to the bundled classpath yaml → {@link #bundledDefaults()}
 * when both files are missing. Tests use {@link #parse(String)}
 * for inline YAML fixtures.
 *
 * <p>The legacy single-file path
 * {@code <userHome>/.aethercode/providers.yaml} is still
 * recognised by {@link #loadFrom(Path)} — kept for backward
 * compat with pre-R343 user installations.
 *
 * <p>The registry is the source of truth for the desktop's
 * Settings provider picker; the daemon exposes the list via
 * the {@code listProviders} RPC.
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
     *  "untested"). Malformed YAML → bundled yaml
     *  (a defensive net — never an empty list).
     *
     *  <p>R343: this single-file path is retained for
     *  backward compat with pre-R343 deployments that
     *  shipped a flat {@code <userHome>/.aethercode/providers.yaml}.
     *  New code should prefer {@link #loadCascade(Path, Path)}. */
    public static ProviderRegistry loadFrom(Path yamlFile) {
        if (yamlFile == null || !Files.exists(yamlFile)) {
            LOG.info("providers.yaml not found at {} — using bundled YAML", yamlFile);
            return loadBundled();
        }
        try {
            String raw = Files.readString(yamlFile);
            return parse(raw);
        } catch (IOException e) {
            LOG.warn("failed to read providers.yaml at {}: {} — falling back to bundled",
                    yamlFile, e.getMessage());
            return loadBundled();
        }
    }

    /**
     * R343: canonical config loader. Resolves a two-tier
     * provider catalogue:
     *
     * <ol>
     *   <li>{@code <installDir>/providers.yaml} — operator-owned
     *       catalogue. When the file is missing, falls back
     *       to {@link #loadBundled()} (the bundled
     *       classpath yaml).</li>
     *   <li>{@code <cwd>/.aethercode/providers.yaml} — per-project
     *       overrides. Optional; missing file is OK and means
     *       "use the global catalogue unchanged".</li>
     * </ol>
     *
     * <p>The per-project file is validated against the global:
     * any provider name or model id not present in the global
     * (or bundled) catalogue is dropped with a startup warning.
     * This prevents developers from quietly adding new models
     * to a project — the model surface area stays under
     * operator control.
     *
     * <p>If both files are missing AND the bundled resource
     * is unavailable, falls back to {@link #bundledDefaults()}
     * (the Java legacy path). The legacy list always contains
     * at least minmax so the daemon never starts empty.
     *
     * @param installDir  the install path (typically the
     *     daemon jar's parent directory). May be {@code null}
     *     or non-existent in dev/test contexts — the loader
     *     treats that as "skip the global file".
     * @param cwd  the working directory (per-project file
     *     lives at {@code <cwd>/.aethercode/providers.yaml}).
     *     May be {@code null} in tests; missing file is OK.
     * @return the merged registry. Always non-null; never empty
     *     (the bundledDefaults() fallback guarantees at least
     *     one provider).
     */
    public static ProviderRegistry loadCascade(Path installDir, Path cwd) {
        // Step 1 — global. installDir/providers.yaml OR bundled.
        ProviderRegistry global;
        if (installDir != null) {
            Path globalFile = installDir.resolve("providers.yaml");
            if (Files.exists(globalFile)) {
                try {
                    String raw = Files.readString(globalFile);
                    global = parse(raw);
                    LOG.info("R343: loaded global providers.yaml from {} ({} providers)",
                            globalFile, global.byName.size());
                } catch (IOException e) {
                    LOG.warn("R343: failed to read global providers.yaml at {}: {} — falling back to bundled",
                            globalFile, e.getMessage());
                    global = loadBundled();
                }
            } else {
                LOG.info("R343: no global providers.yaml at {} — using bundled YAML", globalFile);
                global = loadBundled();
            }
        } else {
            // dev / test context (no install dir). Use bundled
            // yaml directly so the registry isn't empty.
            global = loadBundled();
        }
        // If loadBundled() couldn't find a resource AND
        // bundledDefaults() returned empty (shouldn't happen —
        // bundledDefaults always has at least minmax), fall
        // back to bundledDefaults() once more so the daemon
        // never boots with an empty provider list.
        if (global.byName.isEmpty()) {
            LOG.warn("R343: global catalogue is empty after loadBundled() — falling back to Java bundledDefaults()");
            global = new ProviderRegistry(bundledDefaults());
        }

        // Step 2 — per-project. cwd/.aethercode/providers.yaml.
        // Missing file → no-op (project keeps the global).
        if (cwd == null) {
            return global;
        }
        Path cwdFile = cwd.resolve(".aethercode").resolve("providers.yaml");
        if (!Files.exists(cwdFile)) {
            // Optional file. Many projects won't have one.
            LOG.debug("R343: no per-project providers.yaml at {} — using global catalogue unchanged", cwdFile);
            return global;
        }

        // Step 3 — parse cwd yaml (without model-id enforcement
        // so the validator can read every field), then merge
        // onto global with the unknown-id filter.
        String cwdYamlRaw;
        try {
            cwdYamlRaw = Files.readString(cwdFile);
        } catch (IOException e) {
            LOG.warn("R343: failed to read per-project providers.yaml at {}: {} — using global catalogue unchanged",
                    cwdFile, e.getMessage());
            return global;
        }
        return mergeCwd(global, cwdYamlRaw, cwdFile);
    }

    /**
     * R343: merge a per-project yaml on top of a global
     * registry, dropping unknown provider names and unknown
     * model ids. Each unknown entry logs a warning at startup
     * so the developer / operator sees exactly what was
     * filtered out. The merge keeps the global provider's
     * full model list intact; cwd entries can only edit
     * existing model fields (price, maxOutput, headers, etc.).
     *
     * <p>The cwd raw YAML is parsed via
     * {@link #parseProviderYamlList(String)} so partial
     * entries (e.g. {@code defaultModel: foo} with no
     * {@code baseUrl} / {@code models: []}) don't trip
     * ProviderSpec's required-field checks — the merge
     * logic reads every field directly off the
     * {@link ProviderYaml} entries.
     *
     * <p>For tests use the {@link #mergeCwdFromYamls(ProviderRegistry, java.util.List, java.nio.file.Path)}
     * overload with pre-built {@link ProviderYaml} lists. For
     * the normal file-based flow use
     * {@link #loadCascade(Path, Path)}.
     *
     * @param global the canonical catalogue (operator-owned).
     *     Caller is responsible for ensuring this is non-null
     *     and non-empty; {@link #loadCascade} guarantees this.
     * @param cwdYamlRaw the raw per-project YAML text. May
     *     be {@code null} or malformed — the merge is a
     *     no-op in those cases.
     * @param cwdSourceForLogging the path the YAML was read
     *     from. Used only for warning messages so the
     *     developer / operator can find the offending file.
     * @return a new ProviderRegistry with the merge applied.
     *     Never null; never empty (the global catalog is
     *     guaranteed non-empty by {@link #loadCascade}).
     */
    public static ProviderRegistry mergeCwd(ProviderRegistry global,
                                            String cwdYamlRaw,
                                            Path cwdSourceForLogging) {
        if (global == null || global.byName.isEmpty()) {
            return new ProviderRegistry(List.of());
        }
        if (cwdYamlRaw == null || cwdYamlRaw.isBlank()) {
            return global;
        }
        List<ProviderYaml> cwdYamls = parseProviderYamlList(cwdYamlRaw);
        if (cwdYamls.isEmpty()) {
            return global;
        }
        return mergeCwdFromYamls(global, cwdYamls, cwdSourceForLogging);
    }

    /**
     * R343: in-memory merge helper for tests + the file-based
     * loader. Takes pre-parsed {@link ProviderYaml} entries
     * and merges them onto {@code global}. See
     * {@link #mergeCwd(ProviderRegistry, String, java.nio.file.Path)}
     * for the field-by-field rules.
     */
    public static ProviderRegistry mergeCwdFromYamls(ProviderRegistry global,
                                                    List<ProviderYaml> cwdYamls,
                                                    Path cwdSourceForLogging) {
        if (global == null || global.byName.isEmpty()) {
            return new ProviderRegistry(List.of());
        }
        if (cwdYamls == null || cwdYamls.isEmpty()) {
            return global;
        }
        // Build the merged list. Start from the global specs
        // (full model list intact), then for each cwd entry:
        //   - if the provider name is unknown to global → log + skip
        //   - if the provider name is known → apply field overrides,
        //     and for each model id in cwd, only keep it if it
        //     exists in the global model list; drop + warn otherwise.
        List<ProviderSpec> merged = new ArrayList<>(global.byName.values());
        // Map name → index in `merged` so we can mutate in place.
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        for (int i = 0; i < merged.size(); i++) {
            indexByName.put(merged.get(i).name(), i);
        }

        for (ProviderYaml cwd : cwdYamls) {
            String cwdName = cwd.name;
            Integer idx = cwdName == null ? null : indexByName.get(cwdName);
            if (idx == null) {
                LOG.warn("R343: per-project providers.yaml at {} declared unknown provider '{}' — dropped. " +
                        "Add it to the global catalogue (operator-owned) before referencing from a project.",
                        cwdSourceForLogging, cwdName);
                continue;
            }
            ProviderSpec globalSpec = merged.get(idx);
            ProviderSpec effective = applyFieldOverrides(globalSpec, cwd, cwdSourceForLogging);
            merged.set(idx, effective);
        }
        return new ProviderRegistry(merged);
    }

    /**
     * R343: apply cwd field overrides onto a global provider.
     * cwd fields replace global fields when non-null; null
     * cwd fields fall through to global. The cwd yaml is
     * intentionally partial — baseUrl, models, name, etc.
     * are NOT cwd's to override (those are operator-owned).
     */
    private static ProviderSpec applyFieldOverrides(ProviderSpec globalSpec,
                                                   ProviderYaml cwd,
                                                   Path cwdSourceForLogging) {
        // type / apiKeyEnv / defaultModel / apiKey / headers /
        // timeout / connectTimeout are normal overrides. baseUrl,
        // name, and models are operator-owned — cwd can't
        // change them.
        String type = cwd.type != null ? cwd.type : globalSpec.type();
        String apiKeyEnv = cwd.apiKeyEnv != null ? cwd.apiKeyEnv : globalSpec.apiKeyEnv();
        // defaultModel: cwd wins, but only when the requested
        // id is in the global model list. A typo'd id (or a
        // model id the operator removed in a later global
        // release) falls back to the global default rather
        // than crashing the daemon with "defaultModel not
        // in models list".
        String defaultModel = globalSpec.defaultModel();
        if (cwd.defaultModel != null && !cwd.defaultModel.isBlank()) {
            boolean known = false;
            for (org.aethercode.core.providers.ModelSpec m : globalSpec.models()) {
                if (cwd.defaultModel.equals(m.id())) {
                    known = true;
                    break;
                }
            }
            if (known) {
                defaultModel = cwd.defaultModel;
            } else {
                LOG.warn("R343: per-project providers.yaml at {} declared unknown defaultModel '{}' (provider '{}') — falling back to global default '{}'",
                        cwdSourceForLogging, cwd.defaultModel, cwd.name, defaultModel);
            }
        }
        String apiKey = cwd.apiKey != null ? cwd.apiKey : globalSpec.apiKey();

        // enabled: tri-state semantics. cwd null → fall through
        // to global. cwd non-null → use cwd value (Boolean.TRUE
        // equals wrapper null is false; cwd.enabled could be
        // missing from the yaml but still non-null if Jackson
        // sees the literal `enabled: false`).
        boolean enabled = cwd.enabled != null ? (cwd.enabled == null || cwd.enabled) : globalSpec.enabled();

        Map<String, String> headers = (cwd.headers != null && !cwd.headers.isEmpty())
                ? cwd.headers
                : globalSpec.customHeaders();
        Integer timeout = cwd.timeout != null ? cwd.timeout : globalSpec.timeoutMs();
        Integer connectTimeout = cwd.connectTimeout != null
                ? cwd.connectTimeout
                : globalSpec.connectTimeoutMs();

        // Models: keep the GLOBAL model list (canonical). If
        // cwd declared any model ids, log warnings for any
        // that aren't in the global list — a typo'd id in
        // cwd is almost certainly a bug.
        if (cwd.models != null && !cwd.models.isEmpty()) {
            java.util.Set<String> globalIds = new java.util.HashSet<>();
            for (org.aethercode.core.providers.ModelSpec m : globalSpec.models()) {
                globalIds.add(m.id());
            }
            for (ModelYaml cwdM : cwd.models) {
                if (cwdM.id != null && !globalIds.contains(cwdM.id)) {
                    LOG.warn("R343: per-project providers.yaml at {} declared unknown model id '{}' " +
                                    "(provider '{}') — dropped. Models must be defined in the global catalogue.",
                            cwdSourceForLogging, cwdM.id, cwd.name);
                }
            }
        }

        // Compact + Variants: cwd wins when non-null (rare for
        // a project to override these — most projects use the
        // global defaults).
        org.aethercode.core.providers.CompactSpec compact;
        if (cwd.compact != null) {
            compact = cwd.compact.toSpec();
        } else {
            compact = globalSpec.compact();
        }
        List<org.aethercode.core.providers.Variant> variants;
        if (cwd.variants != null) {
            variants = VariantYaml.toVariantList(cwd.variants);
        } else {
            variants = globalSpec.variants();
        }

        // baseUrl + name + models are NOT cwd-overridable —
        // they're operator-owned. The merged record reuses
        // the global values verbatim.
        return new org.aethercode.core.providers.ProviderSpec(
                globalSpec.name(),                  // name stays global
                type,
                globalSpec.baseUrl(),               // baseUrl stays global
                apiKeyEnv,
                defaultModel,
                globalSpec.models(),                // models stay global
                compact,
                variants,
                enabled,
                headers,
                timeout,
                connectTimeout,
                apiKey);
    }

    /**
     * Resolve the install directory from a Class reference.
     * Returns the parent of the directory holding the jar
     * that defines {@code ref}'s protection domain. Falls
     * back to {@code null} when the jar location can't be
     * determined (e.g. classes dir in dev / fat-jar).
     *
     * <p>Used by {@code DaemonRunner} / {@code Main} to find
     * the global {@code providers.yaml} that ships next to
     * the daemon jar. On a packaged install (MSI / NSIS) the
     * installer copies a {@code providers.yaml.sample} into
     * the install dir; the operator copies / renames it to
     * {@code providers.yaml} once they've customised it.
     */
    public static Path resolveInstallDir(Class<?> ref) {
        if (ref == null) return null;
        try {
            java.security.ProtectionDomain pd = ref.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null && pd.getCodeSource().getLocation() != null) {
                java.net.URL loc = pd.getCodeSource().getLocation();
                // For "file:/C:/path/to/jar.jar" we want C:/path/to
                // For "file:/C:/path/to/classes/" we want C:/path/to/classes
                if ("file".equalsIgnoreCase(loc.getProtocol())) {
                    try {
                        Path p = java.nio.file.Paths.get(loc.toURI());
                        if (Files.isDirectory(p)) return p;
                        return p.getParent();
                    } catch (java.net.URISyntaxException e) {
                        LOG.debug("R343: failed to convert jar location to path: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("R343: could not resolve install dir from class: {}", e.getMessage());
        }
        return null;
    }

    /**
     * R343: first-install bootstrap. If {@code <installDir>/providers.yaml}
     * is missing, copy the bundled
     * {@code /providers.yaml.sample} resource into place so the
     * daemon starts with a real (commented) file the operator
     * can edit instead of an empty dir. Subsequent daemon
     * starts leave the existing file alone — the sample
     * bootstrap is a one-shot.
     *
     * <p>The MSI / NSIS installers do the same write at install
     * time; this helper covers the dev / portable-jar path
     * (e.g. {@code java -jar aethercode.jar} from a downloaded
     * release) where there's no separate installer step.
     *
     * <p>Returns the path to the live {@code providers.yaml}
     * (which is the installDir path regardless of whether
     * the bootstrap ran — so callers can pass the return
     * value straight into {@link #loadFrom(Path)} /
     * {@link #loadCascade(Path, Path)}).
     *
     * @param installDir the install directory the daemon
     *     lives in. {@code null} or non-existent is OK —
     *     the helper just no-ops.
     * @return the {@code providers.yaml} path the cascade
     *     should read. Always non-null (caller can rely
     *     on the path even when bootstrap didn't run).
     */
    public static Path ensureSampleInstalled(Path installDir) {
        if (installDir == null) {
            return null;
        }
        Path live = installDir.resolve("providers.yaml");
        if (Files.exists(live)) {
            return live;
        }
        if (!Files.isDirectory(installDir)) {
            // installDir doesn't exist or isn't a dir —
            // can't bootstrap. Caller falls back to bundled
            // yaml automatically.
            return live;
        }
        try (java.io.InputStream in = ProviderRegistry.class
                .getResourceAsStream("/providers.yaml.sample")) {
            if (in == null) {
                LOG.warn("R343: providers.yaml.sample not on classpath — skipping first-install bootstrap");
                return live;
            }
            byte[] body = in.readAllBytes();
            java.nio.file.Files.write(live, body);
            LOG.info("R343: bootstrapped {} from bundled providers.yaml.sample ({} bytes)",
                    live, body.length);
        } catch (java.io.IOException e) {
            LOG.warn("R343: failed to bootstrap providers.yaml at {}: {}", live, e.getMessage());
        }
        return live;
    }

    /** R341: load the bundled {@code aethercode-providers.yaml}
     *  resource shipped in the {@code aethercode-core} jar. The
     *  file lives at {@code /aethercode-providers.yaml} on the
     *  classpath (NOT the legacy {@code providers.yaml} that
     *  {@link org.aethercode.models.ModelRegistryLoader} reads
     *  with a different schema — the two coexist by file-name
     *  partitioning).
     *
     *  <p>Falls back to {@link #bundledDefaults()} when the
     *  resource is missing (e.g. a custom build that excludes
     *  resources). When the resource is present but malformed,
     *  the {@link #parse(String)} warning log fires and we also
     *  fall back to {@link #bundledDefaults()} so a syntax error
     *  in the bundled yaml doesn't take down the daemon — the
     *  user gets a working-but-legacy catalogue rather than a
     *  crash. */
    public static ProviderRegistry loadBundled() {
        try (java.io.InputStream in = ProviderRegistry.class
                .getResourceAsStream("/aethercode-providers.yaml")) {
            if (in == null) {
                LOG.warn("bundled aethercode-providers.yaml not found on classpath — using Java bundledDefaults()");
                return new ProviderRegistry(bundledDefaults());
            }
            String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            ProviderRegistry r = parse(raw);
            if (r.byName.isEmpty()) {
                LOG.warn("bundled aethercode-providers.yaml parsed to 0 providers — falling back to Java bundledDefaults()");
                return new ProviderRegistry(bundledDefaults());
            }
            LOG.info("loaded bundled aethercode-providers.yaml: {} providers",
                    r.byName.size());
            return r;
        } catch (java.io.IOException e) {
            LOG.warn("failed to read bundled aethercode-providers.yaml: {} — using Java bundledDefaults()",
                    e.getMessage());
            return new ProviderRegistry(bundledDefaults());
        }
    }

    /** Parse from a raw YAML string. Public for
     *  tests; the file-based loadFrom / loadCascade
     *  is the normal path. */
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

    /**
     * R343: parse a YAML string into raw {@link ProviderYaml}
     * entries WITHOUT calling {@code toSpec()} (which would
     * throw on partial entries — the cwd file is supposed
     * to be partial). Used by {@link #mergeCwd(ProviderRegistry,
     * String, java.nio.file.Path)} so the merge logic can
     * read every field without each cwd entry needing to
     * satisfy ProviderSpec's "baseUrl is required" /
     * "models must be non-empty" validations.
     *
     * <p>Returned entries may have null {@code baseUrl},
     * empty {@code models}, etc. — the merge logic treats
     * null fields as "fall through to global" and unknown
     * model ids are filtered.
     *
     * <p>Returns an empty list for null/blank yaml or on
     * parse failure (logged as a warning so the operator
     * sees the syntax error).
     */
    public static List<ProviderYaml> parseProviderYamlList(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper om = new ObjectMapper(new YAMLFactory());
            YamlShape shape = om.readValue(yaml, YamlShape.class);
            if (shape == null || shape.providers == null) {
                return List.of();
            }
            return List.copyOf(shape.providers);
        } catch (Exception e) {
            LOG.warn("R343: failed to parse cwd providers.yaml: {}", e.getMessage());
            return List.of();
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
    public static class ProviderYaml {
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
        /** R341: provider-level enable flag. When {@code false}
         *  the renderer hides the provider from the picker
         *  even if an API key is configured. Defaults to
         *  {@code true} (missing = enabled) so legacy YAMLs
         *  keep working unchanged. */
        @JsonProperty("enabled")
        public Boolean enabled;
        /** R341: custom HTTP headers sent with every request
         *  to this provider. Map preserves insertion order for
         *  log readability. Header values must NOT contain
         *  secrets (the header audit log dumps these at debug
         *  level). Defaults to empty. */
        @JsonProperty("headers")
        public java.util.Map<String, String> headers;
        /** R341: per-provider overall timeout in milliseconds.
         *  {@code null} means "use the Spring AI default".
         *  Wired through to {@code OpenAiChatOptions.builder()
         *  .withTimeout(...)} at chat-client build time. */
        @JsonProperty("timeout")
        public Integer timeout;
        /** R341: per-provider connect timeout in milliseconds.
         *  {@code null} = Spring default. */
        @JsonProperty("connectTimeout")
        public Integer connectTimeout;
        /** R341: inline API key (highest priority in the
         *  resolution chain). When set, bypasses env-var
         *  lookup entirely. Defaults to {@code null}
         *  (env-var path is the safer default). */
        @JsonProperty("apiKey")
        public String apiKey;

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
            // R341: build the 13-arg form with new fields.
            // `enabled` defaults to true when absent — legacy
            // YAMLs that don't mention the field stay enabled
            // (Boolean.TRUE.equals(null) is false, which would
            // silently hide every pre-R341 provider on the
            // first launch after upgrade).
            return new ProviderSpec(
                    name, type, baseUrl,
                    apiKeyEnv, defaultModel, ms, providerCompact, providerVariants,
                    enabled == null || enabled,
                    headers,
                    timeout,
                    connectTimeout,
                    apiKey);
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
        // glm (智谱) — R340: refreshed model list to
        // current generation. glm-4-flash is the cheapest
        // tier (free in some promos; public price ¥0.1/M
        // input + ¥0.1/M output as of late 2025). glm-4.5
        // is the late-2025 flagship; glm-4.5-air and
        // glm-4.5-flash round out the family. We keep the
        // legacy glm-4-plus / glm-4-air entries so anyone
        // on the older tier still finds their model.
        out.add(new ProviderSpec(
                "glm", "openai-compat",
                "https://open.bigmodel.cn/api/paas/v4",
                "GLM_API_KEY",
                "glm-4-flash",
                List.of(
                        // Free tier / cheapest
                        new ModelSpec("glm-4-flash",   0.0,    0.0,    1_000_000, 1_000_000, false),
                        new ModelSpec("glm-4-air",     0.0001, 0.0001,   128_000, 128_000, false),
                        // R340: late-2025 GLM 4.5 family
                        new ModelSpec("glm-4.5",       0.0006, 0.002,    128_000, 128_000, true),
                        new ModelSpec("glm-4.5-air",   0.0002, 0.0006,  128_000, 128_000, false),
                        new ModelSpec("glm-4.5-flash", 0.0,    0.0,    1_000_000, 1_000_000, false),
                        // R340: GLM Z1 reasoning model (free
                        // tier: glm-z1-air)
                        new ModelSpec("glm-z1-air",    0.0,    0.0,    128_000, 128_000, false),
                        // Legacy entries — kept for users
                        // who already deployed a project on
                        // glm-4-plus / glm-4.6 etc.
                        new ModelSpec("glm-4-plus",    0.0007, 0.0007,  128_000, 128_000, false)
                )));
        // qwen (通义千问, DashScope OpenAI-compat) — R340:
        // refreshed to include qwen3 family (Aug 2025
        // release) and qwen-coder-plus. qwen-turbo is the
        // cheapest tier (¥0.0003/M input); qwen3-max is
        // the late-2025 flagship reasoning model.
        out.add(new ProviderSpec(
                "qwen", "openai-compat",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "DASHSCOPE_API_KEY",
                "qwen-turbo",
                List.of(
                        // Cheapest (free in some promos)
                        new ModelSpec("qwen-turbo",     0.0003, 0.0006,  1_000_000, 1_000_000, false),
                        // R340: qwen3 family
                        new ModelSpec("qwen3-max",      0.002,  0.006,   256_000, 256_000, false),
                        new ModelSpec("qwen3-coder-plus", 0.001, 0.005,   128_000, 128_000, false),
                        new ModelSpec("qwen3-vl-plus", 0.001,  0.004,   128_000, 128_000, false),
                        // Legacy
                        new ModelSpec("qwen-plus",      0.0008, 0.002,   128_000, 128_000, false),
                        new ModelSpec("qwen-max",       0.0002, 0.0006, 128_000, 128_000, false),
                        new ModelSpec("qwen-coder-plus", 0.0008, 0.002, 128_000, 128_000, false)
                )));
        // deepseek — R340: refreshed to include V3.1 and V3
        // series (Aug/Sep 2025). deepseek-chat and V3 share
        // the same endpoint; reasoner (R1) is separate. The
        // V3.x family supports tool calls + 64K context.
        // deepseek-chat itself is the cheapest (cache miss
        // ¥0.27/M input as of late 2025).
        out.add(new ProviderSpec(
                "deepseek", "openai-compat",
                "https://api.deepseek.com",
                "DEEPSEEK_API_KEY",
                "deepseek-chat",
                List.of(
                        // Cheap / general chat
                        new ModelSpec("deepseek-chat",     0.00027, 0.0011, 64_000, 64_000, true),
                        // R340: V3.x series — same price as
                        // chat, supports tool calls + JSON mode.
                        new ModelSpec("deepseek-v3",       0.00027, 0.0011, 64_000, 64_000, false),
                        new ModelSpec("deepseek-v3.1",     0.00027, 0.0011, 64_000, 64_000, false),
                        // Reasoning
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
