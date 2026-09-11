package org.aethercode.code.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.code.hooks.Snapshot.SourcedGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validated Hooks v2 configuration loading, merging, and hashing.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.loading} module. Sources are concatenated
 * per event in precedence order (project, user, plugin). Each handler
 * is matched against an invocation by name, pattern, or set.</p>
 */
public final class Loading {

    private static final Logger LOG = LoggerFactory.getLogger(Loading.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** Date when the legacy hooks system will be removed. */
    public static final String LEGACY_HOOKS_REMOVAL_DATE = "September 1, 2026";

    /** Provenance for groups handled without it, adding no origin or env overlay. */
    public static final HooksSource UNSOURCED = new HooksSource.FileHooksSource("");

    private Loading() {}

    /**
     * Validated configuration plus load diagnostics and source paths.
     */
    public record LoadedHooksConfig(
            HookConfigTypes.HooksConfig config,
            List<HookDiagnostic> diagnostics,
            List<Path> sources,
            String snapshotId,
            Map<HookEvent, List<SourcedGroup>> groups,
            boolean projectSourceLoaded,
            String projectSourceFingerprint) {}

    /** Return the project-scoped hooks configuration path. */
    public static Path projectHooksPath(Path projectRoot) {
        return projectRoot.resolve(".deepagents").resolve("hooks.json");
    }

    /** Return the user-scoped hooks configuration path. */
    public static Path userHooksPath(Path configDir) {
        return configDir == null ? Path.of("~/.deepagents").resolve("hooks.json")
                : configDir.resolve("hooks.json");
    }

    /**
     * Load, validate, merge, and hash Hooks v2 configuration.
     */
    public static LoadedHooksConfig loadHooksConfig(
            Path projectRoot,
            boolean workspaceTrusted,
            Path configDir,
            List<Path> paths,
            List<HookSourceDocument> documents,
            List<HookDiagnostic> documentDiagnostics) {
        List<HookDiagnostic> diagnostics = new ArrayList<>();
        if (documentDiagnostics != null) diagnostics.addAll(documentDiagnostics);
        Map<HookEvent, List<SourcedGroup>> merged = new EnumMap<>(HookEvent.class);
        List<Path> loadedPaths = new ArrayList<>();
        boolean projectSourceLoaded = false;
        String projectSourceFingerprint = null;

        if (paths != null) {
            for (Path path : paths) {
                ingest(path, false, merged, loadedPaths, diagnostics,
                        new boolean[1], new String[1]);
            }
        } else if (workspaceTrusted) {
            Path projectPath = projectHooksPath(projectRoot);
            Path userPath = userHooksPath(configDir);
            boolean[] projectFlag = {false};
            String[] fingerprintHolder = {null};
            ingest(projectPath, true, merged, loadedPaths, diagnostics,
                    projectFlag, fingerprintHolder);
            if (!userPath.equals(projectPath)) {
                ingest(userPath, false, merged, loadedPaths, diagnostics,
                        new boolean[1], new String[1]);
            }
            projectSourceLoaded = projectFlag[0];
            projectSourceFingerprint = fingerprintHolder[0];
        } else {
            Path userPath = userHooksPath(configDir);
            ingest(userPath, false, merged, loadedPaths, diagnostics,
                    new boolean[1], new String[1]);
        }

        if (documents != null) {
            for (HookSourceDocument doc : documents) {
                HookConfigTypes.HooksConfig validated = validateHooksDocument(
                        doc.data(), doc.source().location() == null ? "" : doc.source().location(),
                        new ArrayList<>());
                if (validated != null) {
                    for (Map.Entry<HookEvent, List<HookConfigTypes.MatcherGroup>> e
                            : validated.hooks().entrySet()) {
                        List<SourcedGroup> list = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                        for (HookConfigTypes.MatcherGroup group : e.getValue()) {
                            list.add(new SourcedGroup(doc.source(), group));
                        }
                    }
                }
            }
        }

        Map<HookEvent, List<SourcedGroup>> groups = new LinkedHashMap<>();
        for (Map.Entry<HookEvent, List<SourcedGroup>> e : merged.entrySet()) {
            groups.put(e.getKey(), List.copyOf(e.getValue()));
        }
        Map<HookEvent, List<HookConfigTypes.MatcherGroup>> configHooks = new LinkedHashMap<>();
        for (Map.Entry<HookEvent, List<SourcedGroup>> e : groups.entrySet()) {
            List<HookConfigTypes.MatcherGroup> list = new ArrayList<>();
            for (SourcedGroup sg : e.getValue()) {
                list.add(sg.group());
            }
            configHooks.put(e.getKey(), list);
        }
        HookConfigTypes.HooksConfig config = new HookConfigTypes.HooksConfig(configHooks);
        String snapshotId = computeSnapshotId(config, groups);
        return new LoadedHooksConfig(
                config,
                List.copyOf(diagnostics),
                List.copyOf(loadedPaths),
                snapshotId,
                groups,
                projectSourceLoaded,
                projectSourceFingerprint);
    }

    private static void ingest(Path path, boolean asProject,
                               Map<HookEvent, List<SourcedGroup>> merged,
                               List<Path> loadedPaths,
                               List<HookDiagnostic> diagnostics,
                               boolean[] projectLoadedFlag, String[] fingerprintHolder) {
        Path resolved = path.toAbsolutePath().normalize();
        DocumentRead read = readHooksJson(resolved);
        if (read == null) return;
        diagnostics.addAll(read.diagnostics());
        if (asProject) {
            projectLoadedFlag[0] = true;
            fingerprintHolder[0] = read.fingerprint();
        }
        HookConfigTypes.HooksConfig document = read.document();
        if (document == null) return;
        loadedPaths.add(resolved);
        HooksSource source = new HooksSource.FileHooksSource(resolved.toString());
        for (Map.Entry<HookEvent, List<HookConfigTypes.MatcherGroup>> e : document.hooks().entrySet()) {
            List<SourcedGroup> list = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (HookConfigTypes.MatcherGroup g : e.getValue()) {
                list.add(new SourcedGroup(source, g));
            }
        }
    }

    /**
     * Decode a hooks document and fingerprint the exact bytes read.
     *
     * @return decoded document, diagnostics, and SHA-256 fingerprint;
     *         {@code null} when the file is missing
     */
    public static DocumentRead readHooksJson(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            byte[] content = Files.readAllBytes(path);
            String fingerprint = sha256Hex(content);
            Object decoded = MAPPER.readValue(content, Object.class);
            List<HookDiagnostic> diagnostics = new ArrayList<>();
            if (Migration.isLegacyHooksDocument(decoded)) {
                List<Map<String, Object>> legacyEntries = new ArrayList<>();
                if (decoded instanceof Map<?, ?> m && m.get("hooks") instanceof List<?> hooks) {
                    for (Object item : hooks) {
                        if (item instanceof Map<?, ?> entry) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> e : entry.entrySet()) {
                                copy.put(String.valueOf(e.getKey()), e.getValue());
                            }
                            legacyEntries.add(copy);
                        }
                    }
                }
                HookConfigTypes.HooksConfig migrated = Migration.migrateLegacyHooks(legacyEntries);
                String migrationMessage = migrated.hooks().isEmpty()
                        ? "Legacy hooks at " + path + " contained no events that are safe to migrate to Hooks v2"
                        : "Migrated semantically equivalent legacy hooks from " + path
                                + "; unsupported legacy events remain unmapped";
                diagnostics.add(new HookDiagnostic(
                        "legacy_deprecated", HookDiagnostic.Severity.WARNING,
                        "Legacy hooks configuration at " + path + " is deprecated and will "
                                + "stop being supported on " + LEGACY_HOOKS_REMOVAL_DATE,
                        null, path.toString()));
                diagnostics.add(new HookDiagnostic(
                        migrated.hooks().isEmpty() ? "legacy_unmapped" : "legacy_migrated",
                        HookDiagnostic.Severity.WARNING,
                        migrationMessage, null, path.toString()));
                return new DocumentRead(migrated, diagnostics, fingerprint);
            }
            HookConfigTypes.HooksConfig validated = validateHooksDocument(
                    decoded, path.toString(), new ArrayList<>());
            diagnostics.addAll(new ArrayList<>(List.of()));
            return new DocumentRead(validated, diagnostics, fingerprint);
        } catch (IOException ex) {
            String message = "Failed to read hooks config at " + path + ": " + ex.getMessage();
            LOG.warn(message);
            return new DocumentRead(null,
                    List.of(new HookDiagnostic("config_read_failed",
                            HookDiagnostic.Severity.WARNING, message, null, path.toString())),
                    null);
        }
    }

    private static HookConfigTypes.HooksConfig validateHooksDocument(Object data, String pathLabel,
                                                                     List<HookDiagnostic> diagnostics) {
        if (!(data instanceof Map<?, ?> root)) {
            diagnostics.add(invalidConfig(pathLabel, "", "expected an object"));
            return null;
        }
        Object rawHooks = root.get("hooks");
        if (!(rawHooks instanceof Map<?, ?> rawMap)) {
            diagnostics.add(invalidConfig(pathLabel, "hooks", "expected an object"));
            return null;
        }
        Map<HookEvent, List<HookConfigTypes.MatcherGroup>> hooks = new EnumMap<>(HookEvent.class);
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String eventField = "hooks." + entry.getKey();
            if (!(entry.getKey() instanceof String rawEvent)) {
                diagnostics.add(invalidConfig(pathLabel, eventField, "unknown hook event"));
                continue;
            }
            HookEvent event;
            try {
                event = HookEvent.fromWireName(toPascalCase(rawEvent));
            } catch (IllegalArgumentException ex) {
                diagnostics.add(invalidConfig(pathLabel, eventField, "unknown hook event"));
                continue;
            }
            if (!(entry.getValue() instanceof List<?> rawGroups)) {
                diagnostics.add(invalidConfig(pathLabel, eventField, "expected a list of matcher groups"));
                continue;
            }
            List<HookConfigTypes.MatcherGroup> groups = new ArrayList<>();
            int index = 0;
            for (Object rawGroup : rawGroups) {
                String groupField = eventField + "[" + index + "]";
                HookConfigTypes.MatcherGroup group = validateMatcherGroup(
                        rawGroup, pathLabel, groupField, diagnostics);
                if (group != null) groups.add(group);
                index++;
            }
            hooks.put(event, groups);
        }
        return new HookConfigTypes.HooksConfig(hooks);
    }

    private static HookConfigTypes.MatcherGroup validateMatcherGroup(
            Object data, String pathLabel, String field, List<HookDiagnostic> diagnostics) {
        if (!(data instanceof Map<?, ?> raw)) {
            diagnostics.add(invalidConfig(pathLabel, field, "expected an object"));
            return null;
        }
        Object rawHandlers = raw.get("hooks");
        if (!(rawHandlers instanceof List<?> rawList)) {
            diagnostics.add(invalidConfig(pathLabel, field + ".hooks", "expected a list of handlers"));
            return null;
        }
        List<HookConfigTypes.CommandHandlerSpec> handlers = new ArrayList<>();
        int index = 0;
        for (Object rawHandler : rawList) {
            String handlerField = field + ".hooks[" + index + "]";
            if (rawHandler instanceof Map<?, ?> hm) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : hm.entrySet()) {
                    copy.put(String.valueOf(e.getKey()), e.getValue());
                }
                handlers.add(new HookConfigTypes.CommandHandlerSpec(
                        (String) copy.get("type"),
                        (String) copy.get("command"),
                        copy.get("argv") instanceof List<?> argv
                                ? argv.stream().map(String::valueOf).toList() : null,
                        copy.get("timeout") instanceof Number n ? n.doubleValue() : null,
                        (String) copy.get("statusMessage")));
            } else {
                diagnostics.add(invalidConfig(pathLabel, handlerField, "expected an object"));
            }
            index++;
        }
        if (rawList.isEmpty()) return null;
        String matcher = raw.get("matcher") == null ? "*" : String.valueOf(raw.get("matcher"));
        return new HookConfigTypes.MatcherGroup(matcher, handlers);
    }

    private static HookDiagnostic invalidConfig(String pathLabel, String field, String detail) {
        String location = field == null || field.isEmpty() ? pathLabel : pathLabel + ":" + field;
        String message = "Invalid hooks config at " + location + ": " + detail;
        LOG.warn(message);
        return new HookDiagnostic("invalid_config", HookDiagnostic.Severity.WARNING,
                message, null, location);
    }

    /**
     * Return the canonical SHA-256 snapshot id for {@code config}.
     */
    public static String computeSnapshotId(HookConfigTypes.HooksConfig config,
                                           Map<HookEvent, List<SourcedGroup>> groups) {
        return sha256Hex(canonicalHooksBytes(config, groups));
    }

    /**
     * Serialize configuration into a stable byte representation.
     */
    public static byte[] canonicalHooksBytes(HookConfigTypes.HooksConfig config,
                                             Map<HookEvent, List<SourcedGroup>> groups) {
        Map<HookEvent, List<SourcedGroup>> known = groups == null ? Map.of() : groups;
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> hooksMap = new LinkedHashMap<>();
        for (HookEvent event : HookEvent.values()) {
            List<SourcedGroup> list = known.getOrDefault(event,
                    config.hooks().getOrDefault(event, List.of()).stream()
                            .map(g -> new SourcedGroup(UNSOURCED, g)).toList());
            if (list.isEmpty()) continue;
            List<Map<String, Object>> eventList = new ArrayList<>();
            for (SourcedGroup sg : list) {
                eventList.add(canonicalGroup(sg));
            }
            hooksMap.put(event.wireName(), eventList);
        }
        payload.put("hooks", hooksMap);
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to canonicalize hooks config", ex);
        }
    }

    private static Map<String, Object> canonicalGroup(SourcedGroup sg) {
        HooksSource source = sg.source();
        HookConfigTypes.MatcherGroup group = sg.group();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> handlers = new ArrayList<>();
        for (HookConfigTypes.CommandHandlerSpec spec : group.hooks()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", spec.type());
            if (spec.command() != null) entry.put("command", spec.command());
            if (spec.argv() != null && !spec.argv().isEmpty()) entry.put("argv", spec.argv());
            if (spec.timeout() != null) entry.put("timeout", spec.timeout());
            if (spec.statusMessage() != null) entry.put("statusMessage", spec.statusMessage());
            handlers.add(entry);
        }
        result.put("hooks", handlers);
        if (group.matcher() != null) result.put("matcher", group.matcher());
        if (source instanceof HooksSource.PluginHooksSource plugin) {
            result.put("origin", plugin.pluginId());
            if (!plugin.env().isEmpty()) {
                TreeMap<String, String> sortedEnv = new TreeMap<>(plugin.env());
                result.put("env", new LinkedHashMap<>(sortedEnv));
            }
        }
        return result;
    }

    private static String toPascalCase(String name) {
        StringBuilder out = new StringBuilder();
        boolean upperNext = true;
        for (char c : name.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') {
                upperNext = true;
            } else if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Outcome of {@link #readHooksJson(Path)}. */
    public record DocumentRead(
            HookConfigTypes.HooksConfig document,
            List<HookDiagnostic> diagnostics,
            String fingerprint) {
    }

    /** Pair of source and decoded JSON used by plugin document loading. */
    public record HookSourceDocument(HooksSource source, Object data) {}
}
