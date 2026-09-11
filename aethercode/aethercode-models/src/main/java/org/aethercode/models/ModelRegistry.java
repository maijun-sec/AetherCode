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
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Phase 2.2 / T-2-15 (design.md §3.7): the in-memory model
 * catalogue, layered on top of the low-level
 * {@link ModelRegistryLoader}.
 *
 * <p>Layering rules (matching spec.md §10.2 + design.md §3.7):
 * <ol>
 *   <li>The shipped {@code providers.yaml} on the classpath provides the
 *       baseline catalogue. A missing / unreadable file is not an error —
 *       the registry starts empty.</li>
 *   <li>{@code <userHome>/.aethercode/providers.yaml} is layered on top:
 *       models with the same {@code name} replace the shipped copy; new
 *       models are appended; the file's {@code active} list is merged.</li>
 *   <li>{@code <cwd>/.aethercode/providers.yaml} is layered last. The
 *       project layer wins over the user layer, which wins over the
 *       shipped baseline — exactly the same precedence rule as
 *       {@code WorkflowPaths} and {@code grants.json}.</li>
 * </ol>
 *
 * <p>Read path: {@link #reload()} (re-)reads all three layers. The
 * read is cheap (a few hundred models max), so we don't bother with
 * cache invalidation — callers that want freshness call {@code reload}
 * explicitly before listing.
 *
 * <p>Write path: {@link #setActive(String)} writes the active model
 * name to the project-layer file (or the user-layer file if the
 * project layer doesn't exist). The shape written is intentionally
 * minimal: just the top-level {@code active} field, so we don't
 * clobber the rest of the user's file.
 */
public final class ModelRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ModelRegistry.class);

    /** Conventional user-layer directory name. */
    public static final String USER_DIR_NAME = ".aethercode";
    /** Conventional providers filename. */
    public static final String PROVIDERS_FILE = "providers.yaml";

    private final ModelRegistryLoader loader;
    private final Path userHome;
    private final Path cwd;

    private final Map<String, ModelProfile> byName = new LinkedHashMap<>();
    /** Project-declared active names, in declaration order. Project
     *  wins over {@link #userActiveNames}. */
    private final List<String> projectActiveNames = new ArrayList<>();
    /** User-declared active names, in declaration order. */
    private final List<String> userActiveNames = new ArrayList<>();
    private String shippedResourcePath = "/providers.yaml";

    public ModelRegistry(Path userHome, Path cwd) {
        this(userHome, cwd, new ModelRegistryLoader());
    }

    public ModelRegistry(Path userHome, Path cwd, ModelRegistryLoader loader) {
        this.userHome = userHome;
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Override the classpath resource used for the shipped baseline
     *  (tests use this to point at a fixture YAML). */
    public ModelRegistry withShippedResource(String resourcePath) {
        this.shippedResourcePath = resourcePath;
        return this;
    }

    /**
     * (Re-)read all three layers and rebuild the in-memory catalogue.
     * Safe to call repeatedly; the registry is thread-confined — callers
     * that need concurrent access should wrap it.
     */
    public ModelRegistry reload() {
        byName.clear();
        projectActiveNames.clear();
        userActiveNames.clear();

        // 1) Shipped baseline.
        List<ModelProfile> shipped = loadShipped();
        for (ModelProfile p : shipped) {
            byName.put(p.name(), p);
        }

        // 2) User layer.
        if (userHome != null) {
            Path userFile = userHome.resolve(USER_DIR_NAME).resolve(PROVIDERS_FILE);
            mergeLayer(userFile);
            userActiveNames.addAll(loader.loadActiveNames(userFile));
        }

        // 3) Project layer — wins on every key. The reload() of
        // active names is a "replace on collision" so the project
        // file's active list overrides the user's.
        if (cwd != null) {
            Path projectFile = cwd.resolve(USER_DIR_NAME).resolve(PROVIDERS_FILE);
            mergeLayer(projectFile);
            projectActiveNames.addAll(loader.loadActiveNames(projectFile));
        }

        return this;
    }

    /**
     * All visible models, sorted by provider then name. The result is
     * a new list each call so callers can mutate it without affecting
     * the registry state.
     */
    public List<ModelProfile> list() {
        return ModelRegistryLoader.sorted(List.copyOf(byName.values()));
    }

    /**
     * Filter by provider name (e.g. {@code "anthropic"}). An unknown
     * provider yields an empty list, not an error.
     */
    public List<ModelProfile> listByProvider(String provider) {
        if (provider == null || provider.isBlank()) return list();
        List<ModelProfile> out = new ArrayList<>();
        for (ModelProfile p : byName.values()) {
            if (provider.equals(p.provider())) out.add(p);
        }
        return ModelRegistryLoader.sorted(out);
    }

    /**
     * Lookup a model by name. {@link Optional#empty()} if not present.
     * The lookup is case-sensitive — model names are exact-match keys
     * (the design.md §3.7 spec calls them out as unique global ids).
     */
    public Optional<ModelProfile> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The currently active model. If the user/project files declare
     * multiple {@code active} names, the first one that actually exists
     * in the catalogue wins. The project layer's active names take
     * precedence over the user layer's (matching the project-overrides-
     * user rule for the model catalogue itself). If none of the
     * declared names exist, falls back to the first model in
     * {@link #list()}.
     */
    public Optional<ModelProfile> active() {
        for (String n : projectActiveNames) {
            ModelProfile p = byName.get(n);
            if (p != null) return Optional.of(p);
        }
        for (String n : userActiveNames) {
            ModelProfile p = byName.get(n);
            if (p != null) return Optional.of(p);
        }
        List<ModelProfile> all = list();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Mark a model as the user's default. Writes {@code active: <name>}
     * to the project-layer {@code providers.yaml} (creating the file +
     * parent directory as needed). The write is atomic: we stage to a
     * {@code .tmp} sibling and then rename, so a crash mid-write can't
     * corrupt the existing file.
     *
     * @throws ModelRegistryException if the model name is not in the
     *         current catalogue (caller has to add it first via a
     *         manual file edit — the CLI doesn't yet expose
     *         {@code model add}).
     */
    public ModelProfile setActive(String name) {
        ModelProfile p = byName.get(name);
        if (p == null) {
            throw ModelRegistryException.notFound(name);
        }
        Path target = writeTarget();
        Path parent = target.getParent();
        try {
            if (parent != null) Files.createDirectories(parent);
            writeActiveField(target, name);
            // Update the in-memory list so subsequent active() calls
            // reflect the new default without needing a reload().
            if (target.equals(cwd != null
                    ? cwd.resolve(USER_DIR_NAME).resolve(PROVIDERS_FILE) : null)) {
                projectActiveNames.remove(name);
                projectActiveNames.add(name);
            } else {
                userActiveNames.remove(name);
                userActiveNames.add(name);
            }
            return p;
        } catch (IOException e) {
            throw ModelRegistryException.ioError("cannot write " + target, e);
        }
    }

    /**
     * Where {@link #setActive} should write. The project layer is
     * always preferred; if the project directory is not writable
     * (read-only fs, sandbox) we fall back to the user layer. A
     * null user home is treated as "no fallback"; the write will
     * surface an {@link ModelRegistryException} on the project
     * path.
     */
    private Path writeTarget() {
        if (cwd != null) {
            Path projectFile = cwd.resolve(USER_DIR_NAME).resolve(PROVIDERS_FILE);
            if (Files.isWritable(projectFile.getParent())
                    || canCreate(projectFile.getParent())) {
                return projectFile;
            }
        }
        if (userHome != null) {
            return userHome.resolve(USER_DIR_NAME).resolve(PROVIDERS_FILE);
        }
        // best-effort: project path
        return cwd.resolve(USER_DIR_NAME).resolve(PROVIDERS_FILE);
    }

    private static boolean canCreate(Path dir) {
        if (dir == null) return false;
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException e) {
            return false;
        }
    }

    /** Write the YAML for the {@code active} field. The exact field
     *  shape matches what {@link ModelRegistryLoader#loadActiveNames}
     *  reads. We preserve any other top-level keys in the existing
     *  file by reading + re-writing the parsed tree. */
    private void writeActiveField(Path target, String name) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        if (Files.exists(target)) {
            try (InputStream in = Files.newInputStream(target)) {
                Map<String, Object> existing = new ObjectMapper(new YAMLFactory())
                        .readValue(in, new TypeReference<>() {});
                if (existing != null) root.putAll(existing);
            } catch (IOException parse) {
                LOG.warn("existing {} could not be parsed ({}), rewriting", target, parse.getMessage());
            }
        }
        root.put("active", name);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        ObjectMapper writer = new ObjectMapper(new YAMLFactory());
        writer.writeValue(tmp.toFile(), root);
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amne) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Read the classpath-baseline YAML. */
    private List<ModelProfile> loadShipped() {
        try (InputStream in = ModelRegistry.class.getResourceAsStream(shippedResourcePath)) {
            if (in == null) {
                LOG.debug("shipped providers resource not found: {}", shippedResourcePath);
                return List.of();
            }
            return loader.loadFromStream(in);
        } catch (IOException e) {
            LOG.warn("cannot read shipped providers: {}", e.getMessage());
            return List.of();
        }
    }

    /** Merge a single layer (user or project) on top of the current
     *  catalogue. Missing / unreadable files are silently ignored;
     *  malformed YAML is logged + skipped. */
    private void mergeLayer(Path file) {
        if (!Files.exists(file)) return;
        try {
            for (ModelProfile p : loader.loadFromFile(file)) {
                byName.put(p.name(), p);
            }
        } catch (ModelRegistryException e) {
            LOG.warn("skipping layer {}: {}", file, e.getMessage());
        }
    }

    /** For tests + diagnostics: the raw list of active names from both
     *  layers, project first then user (the order {@link #active()}
     *  checks them in). */
    public List<String> activeNames() {
        List<String> all = new ArrayList<>(projectActiveNames.size() + userActiveNames.size());
        all.addAll(projectActiveNames);
        all.addAll(userActiveNames);
        return List.copyOf(all);
    }

    /** For tests + diagnostics: a count of loaded models. */
    public int size() {
        return byName.size();
    }

    /** Convenience: pretty-group the catalogue by provider for the
     *  TUI / Desktop's {@code ModelPicker}. The outer map is sorted
     *  alphabetically by provider key. */
    public Map<String, List<ModelProfile>> grouped() {
        TreeMap<String, List<ModelProfile>> out = new TreeMap<>();
        for (ModelProfile p : list()) {
            out.computeIfAbsent(p.provider(), k -> new ArrayList<>()).add(p);
        }
        return out;
    }
}
