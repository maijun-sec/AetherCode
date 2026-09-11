package org.aethercode.core.fs.backend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Backend that stores files in a pluggable key/value {@link Store}.
 *
 * <p>Java-native port of deepagents <code>StoreBackend</code>. Mirrors
 * the contract of the Python port exactly: files are scoped by a
 * caller-supplied {@link NamespaceFactory}, and a default in-memory
 * {@link Store} may be injected. The deepagents "Graph runtime" hook
 * (langgraph's <code>get_store()</code>) is not assumed; callers wire
 * an explicit {@code Store} instead.</p>
 */
public class StoreBackend implements BackendProtocol {

    /**
     * Allowed namespace-component pattern. Mirrors
     * {@code _NAMESPACE_COMPONENT_RE}: alphanumeric, hyphen, underscore,
     * dot, {@code @}, {@code +}, colon, tilde.
     */
    private static final Pattern NAMESPACE_COMPONENT_RE =
            Pattern.compile("^[A-Za-z0-9\\-_.@+:~]+$");

    private static final String OUTSIDE_GRAPH_MESSAGE =
            "The namespace factory tried to read the Runtime, but it is "
                    + "unavailable (running outside a LangGraph graph execution). "
                    + "Use StoreBackend inside a graph (e.g. via create_deep_agent), "
                    + "or pass a namespace factory that does not read the Runtime.";

    /**
     * Function from a runtime object (always {@code null} in this Java
     * port — langgraph's <code>Runtime</code> has no Java equivalent)
     * to the namespace tuple for the current call.
     */
    @FunctionalInterface
    public interface NamespaceFactory extends Function<Object, List<String>> {
    }

    private final Store store;
    private final NamespaceFactory namespace;

    public StoreBackend(NamespaceFactory namespace, Store store) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.store = Objects.requireNonNull(store, "store");
    }

    public StoreBackend(NamespaceFactory namespace) {
        this(namespace, new InMemoryStore());
    }

    /**
     * Validate a namespace tuple.
     *
     * <p>Each component must be a non-empty string of safe characters
     * (alphanumeric, hyphen, underscore, dot, {@code @}, {@code +},
     * colon, tilde). Wildcards ({@code *}, {@code ?}, brackets, etc.)
     * are rejected to prevent wildcard injection in store lookups.</p>
     *
     * @throws IllegalArgumentException if the namespace is empty / has
     *         empty components / has disallowed characters
     */
    public static List<String> validateNamespace(List<?> namespace) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("Namespace tuple must not be empty.");
        }
        List<String> validated = new ArrayList<>(namespace.size());
        for (int i = 0; i < namespace.size(); i++) {
            Object component = namespace.get(i);
            if (!(component instanceof String)) {
                throw new IllegalArgumentException(
                        "Namespace component at index " + i + " must be a string, got "
                                + (component == null ? "null" : component.getClass().getSimpleName()) + ".");
            }
            String s = (String) component;
            if (s.isEmpty()) {
                throw new IllegalArgumentException(
                        "Namespace component at index " + i + " must not be empty.");
            }
            if (!NAMESPACE_COMPONENT_RE.matcher(s).matches()) {
                throw new IllegalArgumentException(
                        "Namespace component at index " + i + " contains disallowed characters: '"
                                + s + "'. Only alphanumeric characters, hyphens, underscores, dots, "
                                + "@, +, colons, and tildes are allowed.");
            }
            validated.add(s);
        }
        return List.copyOf(validated);
    }

    // -----------------------------------------------------------------
    // internal helpers
    // -----------------------------------------------------------------

    private Store store() {
        return store;
    }

    /**
     * Resolve the current namespace.
     *
     * <p>Mirrors the Python port: the runtime is always {@code null}
     * in this Java port (no LangGraph Runtime equivalent). A factory
     * that crashes on {@code null} (e.g. accesses
     * {@code rt.server_info.user.identity}) is wrapped in a clear
     * {@link IllegalStateException}; other factory exceptions
     * propagate.</p>
     */
    private List<String> currentNamespace() {
        Object runtime = null;
        try {
            return validateNamespace(namespace.apply(runtime));
        } catch (NullPointerException | ClassCastException exc) {
            if (runtime == null) {
                throw new IllegalStateException(OUTSIDE_GRAPH_MESSAGE, exc);
            }
            throw exc;
        }
    }

    private static FileData fromItem(Item item) {
        Map<String, Object> v = item.value();
        Object raw = v.get("content");
        if (raw == null) {
            throw new IllegalArgumentException(
                    "Store item does not contain valid content field. Got: " + v.keySet());
        }
        FileData fd;
        if (raw instanceof List<?> list) {
            // Legacy list-of-strings: join with "\n" (mirrors Python's `"\n".join(...)`).
            // Non-string items (e.g. legacy corruption) fall through to the error below.
            boolean allStrings = list.stream().allMatch(e -> e instanceof String);
            if (allStrings) {
                String joined = String.join("\n", list.stream().map(Object::toString).toList());
                fd = FileData.of(joined, (String) v.getOrDefault("encoding", FileData.ENCODING_UTF8));
            } else {
                throw new IllegalArgumentException(
                        "Store item `content` must be a `str` or legacy `list[str]`, got list.");
            }
        } else if (raw instanceof String s) {
            fd = FileData.of(s, (String) v.getOrDefault("encoding", FileData.ENCODING_UTF8));
        } else {
            throw new IllegalArgumentException(
                    "Store item `content` must be a `str` or legacy `list[str]`, got "
                            + raw.getClass().getSimpleName() + ".");
        }
        // Carry through created_at / modified_at when present.
        Object created = v.get("created_at");
        if (created instanceof String cs && !cs.isEmpty()) {
            fd = fd.withCreatedAt(cs);
        }
        Object modified = v.get("modified_at");
        if (modified instanceof String ms && !ms.isEmpty()) {
            fd = fd.withModifiedAt(ms);
        }
        return fd;
    }

    private static Map<String, Object> fileDataToValue(FileData fileData) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", fileData.content());
        m.put("encoding", fileData.encoding());
        fileData.createdAtOpt().ifPresent(ts -> m.put("created_at", ts));
        fileData.modifiedAtOpt().ifPresent(ts -> m.put("modified_at", ts));
        return m;
    }

    // -----------------------------------------------------------------
    // ls
    // -----------------------------------------------------------------

    @Override
    public LsResult ls(String path) {
        Store s = store();
        List<String> ns = currentNamespace();
        List<Item> items = searchAll(s, ns);
        List<FileInfo> infos = new ArrayList<>();
        Set<String> subdirs = new LinkedHashSet<>();

        String normalizedPath = path.endsWith("/") ? path : path + "/";
        for (Item item : items) {
            String key = item.key();
            if (!key.startsWith(normalizedPath)) continue;
            String relative = key.substring(normalizedPath.length());
            if (relative.contains("/")) {
                String subdirName = relative.split("/", 2)[0];
                subdirs.add(normalizedPath + subdirName + "/");
                continue;
            }
            try {
                FileData fd = fromItem(item);
                long size = BackendUtils.fileDataToString(fd).length();
                infos.add(FileInfo.file(key, size, fd.modifiedAtOpt().orElse("")));
            } catch (IllegalArgumentException ignored) {
                // Skip items that cannot be parsed.
            }
        }
        for (String subdir : subdirs) {
            infos.add(FileInfo.directory(subdir));
        }
        infos.sort((a, b) -> a.path().compareTo(b.path()));
        return LsResult.of(infos);
    }

    // -----------------------------------------------------------------
    // read
    // -----------------------------------------------------------------

    @Override
    public ReadResult read(String filePath, int offset, int limit) {
        Store s = store();
        List<String> ns = currentNamespace();
        Optional<Item> opt = s.get(ns, filePath);
        if (opt.isEmpty()) {
            return ReadResult.error("File '" + filePath + "' not found");
        }
        // fromItem throws on malformed content (no `content` key OR a list
        // with non-string items). The Python port only catches ValueError,
        // so the type error propagates — Java mirrors that by not catching
        // here at all.
        FileData fileData = fromItem(opt.get());
        if (BackendUtils.getBackendReadFileType(filePath) != BackendUtils.FileType.TEXT) {
            return ReadResult.of(fileData);
        }
        return BackendUtils.sliceReadResponse(fileData, offset, limit);
    }

    @Override
    public CompletableFuture<ReadResult> aread(String filePath, int offset, int limit) {
        Store s = store();
        List<String> ns = currentNamespace();
        return s.aget(ns, filePath).thenApply(opt -> {
            if (opt.isEmpty()) {
                return ReadResult.error("File '" + filePath + "' not found");
            }
            FileData fileData = fromItem(opt.get());
            if (BackendUtils.getBackendReadFileType(filePath) != BackendUtils.FileType.TEXT) {
                return ReadResult.of(fileData);
            }
            return BackendUtils.sliceReadResponse(fileData, offset, limit);
        });
    }

    // -----------------------------------------------------------------
    // write
    // -----------------------------------------------------------------

    @Override
    public WriteResult write(String filePath, String content) {
        Store s = store();
        List<String> ns = currentNamespace();
        Optional<Item> existing = s.get(ns, filePath);
        FileData newFileData = existing.isPresent()
                ? BackendUtils.updateFileData(fromItem(existing.get()), content)
                : BackendUtils.createFileData(content);
        s.put(ns, filePath, fileDataToValue(newFileData));
        return WriteResult.success(filePath);
    }

    @Override
    public CompletableFuture<WriteResult> awrite(String filePath, String content) {
        Store s = store();
        List<String> ns = currentNamespace();
        return s.aget(ns, filePath).thenCompose(existing -> {
            FileData newFileData = existing.isPresent()
                    ? BackendUtils.updateFileData(fromItem(existing.get()), content)
                    : BackendUtils.createFileData(content);
            return s.aput(ns, filePath, fileDataToValue(newFileData))
                    .thenApply(v -> WriteResult.success(filePath));
        });
    }

    // -----------------------------------------------------------------
    // edit
    // -----------------------------------------------------------------

    @Override
    public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
        Store s = store();
        List<String> ns = currentNamespace();
        Optional<Item> opt = s.get(ns, filePath);
        if (opt.isEmpty()) {
            return EditResult.failure("Error: File '" + filePath + "' not found");
        }
        FileData fileData;
        try {
            fileData = fromItem(opt.get());
        } catch (IllegalArgumentException e) {
            return EditResult.failure("Error: " + e.getMessage());
        }
        String content = BackendUtils.fileDataToString(fileData);
        BackendUtils.StringReplacementResult result =
                BackendUtils.performStringReplacement(content, oldString, newString, replaceAll);
        if (result.error() != null) {
            return EditResult.failure(result.error());
        }
        FileData updated = BackendUtils.updateFileData(fileData, result.newContent());
        s.put(ns, filePath, fileDataToValue(updated));
        return EditResult.success(filePath, result.occurrences());
    }

    @Override
    public CompletableFuture<EditResult> aedit(String filePath, String oldString, String newString, boolean replaceAll) {
        Store s = store();
        List<String> ns = currentNamespace();
        return s.aget(ns, filePath).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(
                        EditResult.failure("Error: File '" + filePath + "' not found"));
            }
            FileData fileData;
            try {
                fileData = fromItem(opt.get());
            } catch (IllegalArgumentException e) {
                return CompletableFuture.completedFuture(
                        EditResult.failure("Error: " + e.getMessage()));
            }
            String content = BackendUtils.fileDataToString(fileData);
            BackendUtils.StringReplacementResult result =
                    BackendUtils.performStringReplacement(content, oldString, newString, replaceAll);
            if (result.error() != null) {
                return CompletableFuture.completedFuture(EditResult.failure(result.error()));
            }
            FileData updated = BackendUtils.updateFileData(fileData, result.newContent());
            return s.aput(ns, filePath, fileDataToValue(updated))
                    .thenApply(v -> EditResult.success(filePath, result.occurrences()));
        });
    }

    // -----------------------------------------------------------------
    // delete
    // -----------------------------------------------------------------

    @Override
    public DeleteResult delete(String filePath) {
        Store s = store();
        List<String> ns = currentNamespace();
        List<Item> items = searchAll(s, ns);
        String base = filePath.endsWith("/") ? filePath.substring(0, filePath.length() - 1) : filePath;
        String prefix = base + "/";
        List<String> toDelete = new ArrayList<>();
        for (Item item : items) {
            String k = item.key();
            if (k.equals(base) || k.startsWith(prefix)) {
                toDelete.add(k);
            }
        }
        if (toDelete.isEmpty()) {
            return DeleteResult.failure("Error: File '" + filePath + "' not found");
        }
        List<PutOp> ops = new ArrayList<>(toDelete.size());
        for (String k : toDelete) {
            ops.add(new PutOp(ns, k, null));
        }
        s.batch(ops);
        return DeleteResult.success(filePath);
    }

    @Override
    public CompletableFuture<DeleteResult> adelete(String filePath) {
        Store s = store();
        List<String> ns = currentNamespace();
        return s.asearch(ns, null, null, 0, 0).thenCompose(items -> {
            String base = filePath.endsWith("/") ? filePath.substring(0, filePath.length() - 1) : filePath;
            String prefix = base + "/";
            List<String> toDelete = new ArrayList<>();
            for (Item item : items) {
                String k = item.key();
                if (k.equals(base) || k.startsWith(prefix)) {
                    toDelete.add(k);
                }
            }
            if (toDelete.isEmpty()) {
                return CompletableFuture.completedFuture(
                        DeleteResult.failure("Error: File '" + filePath + "' not found"));
            }
            List<PutOp> ops = new ArrayList<>(toDelete.size());
            for (String k : toDelete) {
                ops.add(new PutOp(ns, k, null));
            }
            return s.abatch(ops).thenApply(v -> DeleteResult.success(filePath));
        });
    }

    // -----------------------------------------------------------------
    // grep
    // -----------------------------------------------------------------

    @Override
    public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
        Map<String, FileData> files = loadAllFiles();
        GrepResult result = BackendUtils.grepMatchesFromFiles(files, pattern, path, glob, maxCount);
        List<GrepMatch> matches = result.matches().orElse(List.of());
        if (maxCount != null && matches.size() >= maxCount) {
            List<GrepMatch> extra = BackendUtils.grepMatchesFromFiles(files, pattern, path, glob, maxCount + 1)
                    .matches().orElse(List.of());
            if (extra.size() > maxCount) {
                return GrepResult.of(matches, true);
            }
        }
        return GrepResult.of(matches);
    }

    // -----------------------------------------------------------------
    // glob
    // -----------------------------------------------------------------

    @Override
    public GlobResult glob(String pattern, String path) {
        Map<String, FileData> files = loadAllFiles();
        try {
            List<String> matched = BackendUtils.globSearchFiles(files, pattern, path);
            List<FileInfo> infos = new ArrayList<>();
            for (String p : matched) {
                FileData fd = files.get(p);
                long size = fd != null ? fd.content().length() : 0L;
                String modifiedAt = fd != null ? fd.modifiedAtOpt().orElse("") : "";
                infos.add(FileInfo.file(p, size, modifiedAt));
            }
            return GlobResult.of(infos);
        } catch (IllegalArgumentException exc) {
            return GlobResult.error("Invalid glob pattern: " + exc.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // upload / download
    // -----------------------------------------------------------------

    @Override
    public List<FileUploadResponse> uploadFiles(List<PathedBytes> files) {
        Store s = store();
        List<String> ns = currentNamespace();
        java.nio.charset.CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        List<FileUploadResponse> responses = new ArrayList<>();
        for (PathedBytes entry : files) {
            String encoding;
            String text;
            try {
                text = strictUtf8.decode(java.nio.ByteBuffer.wrap(entry.content())).toString();
                encoding = FileData.ENCODING_UTF8;
            } catch (Exception e) {
                text = Base64.getEncoder().encodeToString(entry.content());
                encoding = FileData.ENCODING_BASE64;
            }
            FileData fd = BackendUtils.createFileData(text);
            Map<String, Object> v = new HashMap<>();
            v.put("content", text);
            v.put("encoding", encoding);
            fd.createdAtOpt().ifPresent(ts -> v.put("created_at", ts));
            fd.modifiedAtOpt().ifPresent(ts -> v.put("modified_at", ts));
            s.put(ns, entry.path(), v);
            responses.add(FileUploadResponse.success(entry.path()));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(List<String> paths) {
        Store s = store();
        List<String> ns = currentNamespace();
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String path : paths) {
            Optional<Item> opt = s.get(ns, path);
            if (opt.isEmpty()) {
                responses.add(FileDownloadResponse.failure(path, FileOperationError.FILE_NOT_FOUND_CODE));
                continue;
            }
            FileData fileData;
            try {
                fileData = fromItem(opt.get());
            } catch (IllegalArgumentException e) {
                responses.add(FileDownloadResponse.failure(path, FileOperationError.FILE_NOT_FOUND_CODE));
                continue;
            }
            String contentStr = BackendUtils.fileDataToString(fileData);
            byte[] bytes = FileData.ENCODING_BASE64.equals(fileData.encoding())
                    ? Base64.getDecoder().decode(contentStr)
                    : contentStr.getBytes(StandardCharsets.UTF_8);
            responses.add(FileDownloadResponse.success(path, bytes));
        }
        return responses;
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /** Aggregate all items in the namespace by paginating through the store. */
    private static List<Item> searchAll(Store store, List<String> namespace) {
        List<Item> all = new ArrayList<>();
        int offset = 0;
        int pageSize = 100;
        while (true) {
            List<Item> page = store.search(namespace, null, null, pageSize, offset);
            if (page.isEmpty()) break;
            all.addAll(page);
            if (page.size() < pageSize) break;
            offset += pageSize;
        }
        return Collections.unmodifiableList(all);
    }

    private Map<String, FileData> loadAllFiles() {
        List<String> ns = currentNamespace();
        List<Item> items = searchAll(store(), ns);
        Map<String, FileData> out = new HashMap<>();
        for (Item item : items) {
            try {
                out.put(item.key(), fromItem(item));
            } catch (IllegalArgumentException ignored) {
                // skip items with unparseable content
            }
        }
        return out;
    }
}
