package org.aethercode.talon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fleet zip import support for Talon local agent directories.
 *
 * <p>Java-native port of {@code deepagents_talon.fleet_import}. Materializes
 * a Fleet zip export into a Talon assistant directory, normalising the
 * structure into {@code AGENTS.md}, {@code skills/}, and per-subagent
 * {@code agents/<name>/{AGENTS.md,tools.json}} files, and emitting a
 * generated {@code .mcp.json} plus a human-readable {@code .mcp.json.setup}
 * handoff for operators.</p>
 */
public final class FleetImport {

    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final String MCP_CONFIG_FILENAME = ".mcp.json";
    private static final String SETUP_FILENAME = ".mcp.json.setup";
    private static final int ZIP_FILE_TYPE_MASK = 0170000;
    private static final int ZIP_SYMLINK_TYPE = 0120000;
    private static final int SUBAGENT_FILE_PARTS = 3;
    private static final int MAX_ZIP_ENTRY_COUNT = 10_000;
    private static final long MAX_ZIP_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_ZIP_COMPRESSION_RATIO = 100;
    private static final int COPY_CHUNK_SIZE = 1024 * 1024;

    private static final Pattern SECRET_PATH_PATTERN = Pattern.compile(
            "(?:" +
                    "bearer[-_a-z0-9]*|" +
                    "token[-_a-z0-9]*|" +
                    "key[-_a-z0-9]*|" +
                    "secret[-_a-z0-9]*|" +
                    "cookie[-_a-z0-9]*|" +
                    "oauth[-_a-z0-9]*|" +
                    "sk-[A-Za-z0-9]{20,}|" +
                    "gh[opu]_[A-Za-z0-9]{20,}|" +
                    "lsv2_pt_[A-Za-z0-9]+" +
                    ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SECRET_PATH_MARKER_PATTERN = Pattern.compile(
            "(?:" +
                    "bearer|token|access[-_]?token|refresh[-_]?token|" +
                    "api[-_]?key|key|secret|cookie|oauth" +
                    ")",
            Pattern.CASE_INSENSITIVE);

    private FleetImport() {}

    /** Raised when a Fleet zip cannot be materialized. */
    public static class FleetImportError extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public FleetImportError(String message) {
            super(message);
        }
        public FleetImportError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Summary of a completed Fleet zip import.
     */
    public record FleetImportResult(
            Path targetDir,
            int rootPromptCount,
            int subagentPromptCount,
            boolean configIgnored,
            String mcpNotes,
            List<String> interruptTools) {
    }

    /**
     * Materialize a Fleet zip export into a Talon local agent directory.
     */
    public static FleetImportResult importFleetZip(Path zipPath, Path targetDir, Path assistantHome) {
        Path source = zipPath.toAbsolutePath();
        Path target = targetDir.toAbsolutePath();
        Path home = (assistantHome == null) ? target : assistantHome.toAbsolutePath();
        try (ZipFile archive = new ZipFile(source.toFile())) {
            Map<String, ZipEntry> entries = validatedEntries(archive);
            if (!entries.containsKey("AGENTS.md")) {
                throw new FleetImportError("AGENTS.md: missing required root prompt");
            }
            Path staging = Files.createTempDirectory("deepagents-talon-import-");
            try {
                materializeStaging(archive, entries, staging);
                List<ServerSummary> summaries = mcpSummaries(staging, source);
                String notes = formatSetupNotes(source.getFileName().toString(), summaries);
                String mcpConfig = formatMcpConfig(summaries);
                boolean configIgnored = Files.isRegularFile(staging.resolve("config.json"));
                refreshTarget(staging, target, home, notes, mcpConfig);
                return new FleetImportResult(target, 1,
                        subagentPromptPaths(home).size(),
                        configIgnored, notes,
                        interruptTools(summaries));
            } finally {
                deleteRecursively(staging);
            }
        } catch (java.util.zip.ZipException e) {
            throw new FleetImportError(source + ": invalid zip file", e);
        } catch (IOException e) {
            throw new FleetImportError(target + ": " + e.getMessage(), e);
        }
    }

    /** Render a concise user-facing import summary. */
    public static String formatImportStdout(FleetImportResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fleet import complete.\n");
        sb.append("Agent files imported to: ").append(result.targetDir()).append('\n');
        sb.append("Root prompts written: ").append(result.rootPromptCount()).append('\n');
        sb.append("Subagent prompts written: ").append(result.subagentPromptCount()).append('\n');
        sb.append("config.json: ")
                .append(result.configIgnored() ? "ignored" : "not present")
                .append('\n');
        sb.append('\n');
        sb.append("Next steps:\n");
        if (result.mcpNotes() == null) {
            sb.append("- No Fleet MCP tool requirements were found.\n");
            sb.append("- Add MCP servers to .mcp.json if this assistant needs local tools.\n");
        } else {
            sb.append("- Review .mcp.json before running Talon.\n");
            sb.append("- Review .mcp.json.setup for requested tools and setup details.\n");
        }
        if (!result.interruptTools().isEmpty()) {
            sb.append("- Add HITL for sensitive tools with ")
                    .append("DEEPAGENTS_TALON_INTERRUPT_ON_TOOLS=")
                    .append(String.join(",", result.interruptTools()))
                    .append(".\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Internals (mirroring the Python port)
    // -----------------------------------------------------------------------

    private static Map<String, ZipEntry> validatedEntries(ZipFile archive) {
        Map<String, ZipEntry> entries = new LinkedHashMap<>();
        long totalSize = 0;
        for (java.util.Enumeration<? extends ZipEntry> en = archive.entries();
             en.hasMoreElements(); ) {
            ZipEntry info = en.nextElement();
            String name = normalizedZipName(info.getName());
            if (name == null) {
                continue;
            }
            if (isUnsafeZipPath(name)) {
                throw new FleetImportError(info.getName() + ": unsafe zip path");
            }
            if (isSymlink(info)) {
                throw new FleetImportError(name + ": symlink entries are not supported");
            }
            if (info.isDirectory()) {
                continue;
            }
            validateZipEntrySize(name, info);
            if (entries.size() >= MAX_ZIP_ENTRY_COUNT) {
                throw new FleetImportError(archive.getName() + ": too many zip entries");
            }
            totalSize += info.getSize();
            if (totalSize > MAX_ZIP_UNCOMPRESSED_BYTES) {
                throw new FleetImportError(
                        archive.getName() + ": zip uncompressed size exceeds limit");
            }
            entries.put(name, info);
        }
        return entries;
    }

    private static String normalizedZipName(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.isEmpty() || normalized.endsWith("/")) {
            return null;
        }
        return normalized;
    }

    private static boolean isUnsafeZipPath(String name) {
        if (Paths.get(name).isAbsolute()) {
            return true;
        }
        try {
            URI uri = new URI(null, null, name, null);
            String raw = uri.getRawPath();
            if (raw == null) {
                return false;
            }
            for (String part : raw.split("/")) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    return true;
                }
            }
        } catch (URISyntaxException ignored) {
            return true;
        }
        return false;
    }

    private static boolean isSymlink(ZipEntry info) {
        // The Java standard library does not expose a portable way to read
        // the upper 16 bits of the external attributes (which carry the
        // POSIX file-type mask). Probe the entry's name suffix and the
        // extra field as a best-effort fallback, matching the spirit of
        // the Python port's check.
        if (info.isDirectory()) {
            return false;
        }
        String name = info.getName();
        if (name.endsWith("/")) {
            return false;
        }
        // Most zip tools encode symlinks with mode 0120000 in the upper
        // 16 bits of external_attr; the JDK only exposes a getter for the
        // lower 16 bits (the DOS attributes), so we conservatively treat
        // entries whose extra field carries the Unix mode prefix as
        // symlinks when the bits match 0120000.
        byte[] extra = info.getExtra();
        if (extra != null && extra.length >= 6) {
            int tag = (extra[0] & 0xff) | ((extra[1] & 0xff) << 8);
            int size = (extra[2] & 0xff) | ((extra[3] & 0xff) << 8);
            if (tag == 0x5855 /* "UX" */ && size >= 4 && extra.length >= 6 + size) {
                // UX header layout (Info-ZIP):
                //   byte 0: version (must be 1)
                //   byte 1: UID size
                //   byte 2: GID size
                //   byte 3: variable flags (high byte is mode bits 16-23)
                int modeHi = extra[6] & 0xff;
                int modeLo = extra.length > 7 ? (extra[7] & 0xff) : 0;
                int mode = (modeHi << 8) | modeLo;
                if ((mode & ZIP_FILE_TYPE_MASK) == ZIP_SYMLINK_TYPE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateZipEntrySize(String name, ZipEntry info) {
        if (info.getSize() > MAX_ZIP_UNCOMPRESSED_BYTES) {
            throw new FleetImportError(name + ": zip entry uncompressed size exceeds limit");
        }
        if (info.getCompressedSize() == 0) {
            return;
        }
        if (info.getSize() > info.getCompressedSize() * MAX_ZIP_COMPRESSION_RATIO) {
            throw new FleetImportError(name + ": zip entry compression ratio exceeds limit");
        }
    }

    private static void materializeStaging(ZipFile archive, Map<String, ZipEntry> entries,
                                           Path staging) throws IOException {
        copyZipFile(archive, entries.get("AGENTS.md"), staging.resolve("AGENTS.md"));
        for (Map.Entry<String, ZipEntry> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.startsWith("skills/")) {
                copyZipFile(archive, e.getValue(), staging.resolve(name));
            } else if (isSubagentPromptPath(name)) {
                String subagent = name.split("/")[1];
                validateAgentName(subagent, name);
                copyZipFile(archive, e.getValue(),
                        staging.resolve("agents/" + subagent + "/AGENTS.md"));
            } else if (name.equals("config.json") || name.equals("tools.json")) {
                copyZipFile(archive, e.getValue(), staging.resolve(name));
            } else if (isSubagentToolsPath(name)) {
                String subagent = name.split("/")[1];
                validateAgentName(subagent, name);
                copyZipFile(archive, e.getValue(),
                        staging.resolve("agents/" + subagent + "/tools.json"));
            }
        }
    }

    private static void copyZipFile(ZipFile archive, ZipEntry info, Path target) throws IOException {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createDirectories(target.getParent(),
                    PosixFilePermissions.asFileAttribute(perms));
        } catch (UnsupportedOperationException | SecurityException ex) {
            Files.createDirectories(target.getParent());
        }
        long expected = info.getSize();
        long copied = 0;
        try (InputStream in = archive.getInputStream(info);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[COPY_CHUNK_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                copied += n;
                if (copied > expected || copied > MAX_ZIP_UNCOMPRESSED_BYTES) {
                    throw new FleetImportError(
                            info.getName() + ": zip entry expanded beyond declared size");
                }
                out.write(buffer, 0, n);
            }
        }
        try {
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException | SecurityException | IOException ignored) {
            // best-effort
        }
    }

    private static void validateAgentName(String name, String path) {
        if (!AGENT_ID_PATTERN.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new FleetImportError(path + ": unsafe subagent name '" + name + "'");
        }
    }

    private static boolean isSubagentPromptPath(String name) {
        String[] parts = name.split("/");
        return parts.length == SUBAGENT_FILE_PARTS
                && "subagents".equals(parts[0]) && "AGENTS.md".equals(parts[2]);
    }

    private static boolean isSubagentToolsPath(String name) {
        String[] parts = name.split("/");
        return parts.length == SUBAGENT_FILE_PARTS
                && "subagents".equals(parts[0]) && "tools.json".equals(parts[2]);
    }

    private record ToolRequest(String name, String serverUrl, String serverName,
                              String scope, boolean interrupt) {}

    private record ServerSummary(String serverUrl, String serverName,
                                 java.util.Set<String> scopes,
                                 java.util.Set<String> tools,
                                 java.util.Set<String> interruptTools) {
        void add(ToolRequest tool) {
            scopes.add(tool.scope());
            tools.add(tool.name());
            if (tool.interrupt()) {
                interruptTools.add(tool.name());
            }
        }

        static ServerSummary fromTool(ToolRequest tool) {
            ServerSummary summary = new ServerSummary(tool.serverUrl(), tool.serverName(),
                    new java.util.LinkedHashSet<>(), new java.util.LinkedHashSet<>(),
                    new java.util.LinkedHashSet<>());
            summary.add(tool);
            return summary;
        }
    }

    private static List<ServerSummary> mcpSummaries(Path staging, Path source) {
        Map<String, ServerSummary> grouped = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : toolsJsonPaths(staging)) {
            for (ToolRequest tool : loadToolRequests(e.getKey(), e.getValue(), source)) {
                String key = tool.serverUrl() + "\u0000" + tool.serverName();
                ServerSummary summary = grouped.get(key);
                if (summary == null) {
                    summary = ServerSummary.fromTool(tool);
                    grouped.put(key, summary);
                } else {
                    summary.add(tool);
                }
            }
        }
        List<ServerSummary> out = new ArrayList<>(grouped.values());
        out.sort(Comparator.comparing((ServerSummary s) -> s.serverName().toLowerCase())
                .thenComparing(s -> s.serverUrl()));
        return out;
    }

    private static List<Map.Entry<Path, String>> toolsJsonPaths(Path staging) {
        List<Map.Entry<Path, String>> out = new ArrayList<>();
        Path root = staging.resolve("tools.json");
        if (Files.isRegularFile(root)) {
            out.add(Map.entry(root, "root"));
        }
        Path agents = staging.resolve("agents");
        if (Files.isDirectory(agents)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(agents)) {
                List<Path> children = new ArrayList<>();
                for (Path c : stream) {
                    children.add(c);
                }
                children.sort(Comparator.comparing(p -> p.getFileName().toString()));
                for (Path child : children) {
                    Path tools = child.resolve("tools.json");
                    if (Files.isRegularFile(tools)) {
                        out.add(Map.entry(tools, child.getFileName().toString()));
                    }
                }
            } catch (IOException e) {
                throw new FleetImportError("could not list " + agents + ": " + e.getMessage());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolRequest> loadToolRequests(Path path, String scope, Path source) {
        Map<String, Object> data;
        try {
            data = JsonUtils.parseMap(Files.readString(path));
        } catch (IOException e) {
            throw new FleetImportError(
                    source + ": " + displayPath(path) + ": " + e.getMessage());
        }
        Object rawTools = data.get("tools");
        if (!(rawTools instanceof List<?> list)) {
            throw new FleetImportError(
                    source + ": " + displayPath(path) + ": malformed tools.json: expected tools list");
        }
        Object interruptsRaw = data.get("interrupt_config");
        Map<String, Object> interrupts = (interruptsRaw instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : Map.of();

        List<ToolRequest> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> rawItem)) {
                throw new FleetImportError(source + ": " + displayPath(path)
                        + ": tools[" + i + "] must be an object");
            }
            Map<String, Object> tool = (Map<String, Object>) rawItem;
            String name = requiredString(tool, "name", path, i, source);
            String serverUrl = sanitizeServerUrl(
                    requiredString(tool, "mcp_server_url", path, i, source));
            String serverName = requiredString(tool, "mcp_server_name", path, i, source);
            out.add(new ToolRequest(name, serverUrl, serverName, scope,
                    toolInterruptEnabled(tool, interrupts, name, serverUrl, serverName)));
        }
        return out;
    }

    private static String requiredString(Map<String, Object> item, String key, Path path,
                                         int index, Path source) {
        Object value = item.get(key);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new FleetImportError(source + ": " + displayPath(path)
                    + ": tools[" + index + "]." + key + " must be a non-empty string");
        }
        return s;
    }

    private static boolean toolInterruptEnabled(Map<String, Object> item,
                                                Map<String, Object> interrupts,
                                                String name, String serverUrl,
                                                String serverName) {
        if (Boolean.TRUE.equals(item.get("interrupt_config"))) {
            return true;
        }
        String unsanitized = (item.get("mcp_server_url") instanceof String s) ? s : "";
        for (String key : new String[]{
                serverUrl + "::" + name + "::" + serverName,
                unsanitized + "::" + name + "::" + serverName,
                name}) {
            if (Boolean.TRUE.equals(interrupts.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeServerUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            String port = (uri.getPort() < 0) ? "" : ":" + uri.getPort();
            String path = sanitizeUrlPath(uri.getRawPath() == null ? "" : uri.getRawPath());
            return scheme + "://" + host + port + path;
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private static String sanitizeUrlPath(String path) {
        String[] parts = path.split("/");
        if (parts.length == 0) {
            return "";
        }
        List<String> sanitized = new ArrayList<>();
        boolean redactNext = false;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            boolean marker = SECRET_PATH_MARKER_PATTERN.matcher(part).matches();
            boolean secret = SECRET_PATH_PATTERN.matcher(part).find();
            if (redactNext || marker || secret) {
                sanitized.add("<secret-redacted>");
            } else {
                sanitized.add(part);
            }
            redactNext = marker;
        }
        if (sanitized.isEmpty()) {
            return "";
        }
        return "/" + String.join("/", sanitized);
    }

    private static String formatSetupNotes(String sourceName, List<ServerSummary> summaries) {
        if (summaries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Fleet MCP setup notes for ").append(sourceName).append("\n\n");
        sb.append("Generated .mcp.json contains the suggested server configuration.\n");
        for (ServerSummary summary : summaries) {
            sb.append("\nServer: ").append(summary.serverName()).append('\n');
            sb.append("URL: ").append(summary.serverUrl()).append('\n');
            sb.append("Tool count: ").append(summary.tools().size()).append('\n');
            sb.append("Scopes: ").append(String.join(", ",
                    sorted(summary.scopes()))).append('\n');
            sb.append("Requested tools:\n");
            for (String tool : sorted(summary.tools())) {
                sb.append("- ").append(tool).append('\n');
            }
            List<String> interrupts = sorted(summary.interruptTools());
            sb.append("Interrupt-enabled tools: ")
                    .append(interrupts.isEmpty() ? "none" : String.join(", ", interrupts))
                    .append('\n');
            sb.append("\nSuggested .mcp.json fragment:\n");
            sb.append(JsonUtils.toPrettyJson(suggestedConfigFragment(summary, sorted(summary.tools()))));
        }
        return sb.toString();
    }

    private static String formatMcpConfig(List<ServerSummary> summaries) {
        if (summaries.isEmpty()) {
            return null;
        }
        Map<String, Object> servers = new LinkedHashMap<>();
        for (ServerSummary summary : summaries) {
            String id = uniqueServerId(serverId(summary), servers);
            servers.put(id, serverConfig(summary, sorted(summary.tools())));
        }
        return JsonUtils.toPrettyJson(Map.of("mcpServers", servers)) + "\n";
    }

    private static Map<String, Object> suggestedConfigFragment(ServerSummary summary, List<String> tools) {
        return Map.of(serverId(summary), serverConfig(summary, tools));
    }

    private static Map<String, Object> serverConfig(ServerSummary summary, List<String> tools) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "http");
        out.put("url", summary.serverUrl());
        out.put("auth", "oauth");
        out.put("allowedTools", tools);
        return out;
    }

    private static String uniqueServerId(String baseId, Map<String, Object> servers) {
        if (!servers.containsKey(baseId)) {
            return baseId;
        }
        int suffix = 2;
        while (servers.containsKey(baseId + "-" + suffix)) {
            suffix++;
        }
        return baseId + "-" + suffix;
    }

    private static String serverId(ServerSummary summary) {
        String raw = summary.serverName();
        if (raw == null || raw.isBlank()) {
            try {
                raw = URI.create(summary.serverUrl()).getHost();
            } catch (IllegalArgumentException ignored) {
                raw = "server";
            }
        }
        String normalized = raw.toLowerCase(Locale.ROOT).strip()
                .replaceAll("[^A-Za-z0-9_-]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return normalized.isEmpty() ? "server" : normalized;
    }

    private static void refreshTarget(Path staging, Path target, Path assistantHome,
                                      String notes, String mcpConfig) throws IOException {
        Set<PosixFilePermission> homePerms = PosixFilePermissions.fromString("rwx------");
        try {
            Files.createDirectories(assistantHome,
                    PosixFilePermissions.asFileAttribute(homePerms));
        } catch (UnsupportedOperationException | SecurityException ex) {
            Files.createDirectories(assistantHome);
        }
        try {
            Files.createDirectories(target, PosixFilePermissions.asFileAttribute(homePerms));
        } catch (UnsupportedOperationException | SecurityException ex) {
            Files.createDirectories(target);
        }
        replaceFile(staging.resolve("AGENTS.md"), target.resolve("AGENTS.md"));
        replaceTree(staging.resolve("skills"), target.resolve("skills"));
        replaceTree(staging.resolve("agents"), assistantHome.resolve("agents"));
        if (!assistantHome.equals(target)) {
            removePath(target.resolve("agents"));
        }
        removePath(target.resolve("subagents"));

        Path setup = target.resolve(SETUP_FILENAME);
        if (notes == null) {
            try {
                Files.deleteIfExists(setup);
            } catch (IOException ignored) {
                // best-effort
            }
        } else {
            Files.writeString(setup, notes);
            setRwPerms(setup);
        }
        Path config = target.resolve(MCP_CONFIG_FILENAME);
        if (mcpConfig == null) {
            try {
                Files.deleteIfExists(config);
            } catch (IOException ignored) {
                // best-effort
            }
        } else {
            Files.writeString(config, mcpConfig);
            setRwPerms(config);
        }
    }

    private static void replaceFile(Path source, Path target) throws IOException {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createDirectories(target.getParent(),
                    PosixFilePermissions.asFileAttribute(perms));
        } catch (UnsupportedOperationException | SecurityException ex) {
            Files.createDirectories(target.getParent());
        }
        Path tmp = target.getParent().resolve("." + target.getFileName() + ".tmp");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        setRwPerms(tmp);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void replaceTree(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            if (Files.isDirectory(target)) {
                deleteRecursively(target);
            } else {
                Files.deleteIfExists(target);
            }
        }
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    try {
                        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    } catch (IOException ignored) {
                        // best-effort
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path rel = source.relativize(file);
                        Files.copy(file, target.resolve(rel.toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void removePath(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            deleteRecursively(path);
        } else if (Files.exists(path)) {
            Files.deleteIfExists(path);
        }
    }

    private static List<Path> subagentPromptPaths(Path assistantHome) {
        Path agents = assistantHome.resolve("agents");
        if (!Files.isDirectory(agents)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(agents)) {
            for (Path child : stream) {
                Path md = child.resolve("AGENTS.md");
                if (Files.isRegularFile(md)) {
                    out.add(md);
                }
            }
        } catch (IOException e) {
            // best-effort
        }
        Collections.sort(out);
        return out;
    }

    private static String displayPath(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if ("agents".equals(path.getName(i).toString())) {
                return path.subpath(i, path.getNameCount()).toString();
            }
        }
        return path.getFileName().toString();
    }

    private static void setRwPerms(Path path) {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | SecurityException | IOException ignored) {
            // best-effort
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static List<String> sorted(java.util.Set<String> set) {
        List<String> out = new ArrayList<>(set);
        Collections.sort(out);
        return out;
    }

    private static List<String> interruptTools(List<ServerSummary> summaries) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (ServerSummary s : summaries) {
            all.addAll(s.interruptTools());
        }
        List<String> out = new ArrayList<>(all);
        Collections.sort(out);
        return out;
    }
}
