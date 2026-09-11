package org.aethercode.core.fs.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Composite backend that routes file operations by path prefix.
 *
 * <p>Java-native port of deepagents <code>CompositeBackend</code>.
 * Routes operations to different backends based on path prefixes
 * (e.g. <code>/memories/</code> -&gt; persistent store,
 * <code>/tmp/</code> -&gt; state). Unmatched paths use the default
 * backend. Routes are evaluated longest-first so a more specific
 * prefix always wins over a more general one.</p>
 *
 * <p>At the root path (<code>/</code> or <code>null</code>), listing
 * aggregates the default backend's entries plus a directory entry per
 * route, and <code>grep</code>/<code>glob</code> search every backend
 * in route order, merging results with path remapping.</p>
 */
public class CompositeBackend implements BackendProtocol {

    private final BackendProtocol defaultBackend;
    private final Map<String, BackendProtocol> routes;
    private final List<Map.Entry<String, BackendProtocol>> sortedRoutes;
    private final String artifactsRoot;

    public CompositeBackend(BackendProtocol defaultBackend,
                            Map<String, BackendProtocol> routes) {
        this(defaultBackend, routes, "/");
    }

    public CompositeBackend(BackendProtocol defaultBackend,
                            Map<String, BackendProtocol> routes,
                            String artifactsRoot) {
        this.defaultBackend = defaultBackend;
        this.routes = routes == null ? Map.of() : Map.copyOf(routes);
        this.sortedRoutes = this.routes.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();
        this.artifactsRoot = artifactsRoot;
    }

    public BackendProtocol defaultBackend()         { return defaultBackend; }
    public Map<String, BackendProtocol> routes()    { return routes; }
    public String artifactsRoot()                   { return artifactsRoot; }

    // -----------------------------------------------------------------
    //  Routing
    // -----------------------------------------------------------------

    /**
     * Route a path to a backend, returning the backend, the path as it
     * should be passed to that backend (with the route prefix stripped),
     * and the matched route prefix (or {@code null} for the default).
     */
    public Routed resolveRoute(String path) {
        for (Map.Entry<String, BackendProtocol> e : sortedRoutes) {
            String routePrefix = e.getKey();
            String prefixNoSlash = routePrefix.endsWith("/")
                    ? routePrefix.substring(0, routePrefix.length() - 1)
                    : routePrefix;
            if (path.equals(prefixNoSlash)) {
                return new Routed(e.getValue(), "/", routePrefix);
            }
            String normalized = routePrefix.endsWith("/")
                    ? routePrefix
                    : routePrefix + "/";
            if (path.startsWith(normalized)) {
                String suffix = path.substring(normalized.length());
                String backendPath = suffix.isEmpty() ? "/" : "/" + suffix;
                return new Routed(e.getValue(), backendPath, routePrefix);
            }
        }
        return new Routed(defaultBackend, path, null);
    }

    public record Routed(BackendProtocol backend, String backendPath, String routePrefix) { }

    private BackendProtocol selectBackend(String path) {
        return resolveRoute(path).backend();
    }

    private String selectBackendPath(String path) {
        return resolveRoute(path).backendPath();
    }

    // -----------------------------------------------------------------
    //  ls
    // -----------------------------------------------------------------

    @Override
    public LsResult ls(String path) {
        Routed r = resolveRoute(path);
        if (r.routePrefix() != null) {
            LsResult res = r.backend().ls(r.backendPath());
            if (res.error().isPresent()) return res;
            return LsResult.of(remapInfos(res.entries().orElse(List.of()), r.routePrefix()));
        }
        if ("/".equals(path)) {
            List<FileInfo> results = new ArrayList<>();
            LsResult defRes = defaultBackend.ls(path);
            if (defRes.error().isPresent()) return defRes;
            results.addAll(defRes.entries().orElse(List.of()));
            for (Map.Entry<String, BackendProtocol> e : sortedRoutes) {
                results.add(FileInfo.directory(e.getKey()));
            }
            results.sort((a, b) -> a.path().compareTo(b.path()));
            return LsResult.of(results);
        }
        return defaultBackend.ls(path);
    }

    @Override
    public CompletableFuture<LsResult> als(String path) {
        return CompletableFuture.supplyAsync(() -> ls(path));
    }

    // -----------------------------------------------------------------
    //  read
    // -----------------------------------------------------------------

    @Override
    public ReadResult read(String filePath, int offset, int limit) {
        return selectBackend(filePath).read(selectBackendPath(filePath), offset, limit);
    }

    @Override
    public CompletableFuture<ReadResult> aread(String filePath, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> read(filePath, offset, limit));
    }

    // -----------------------------------------------------------------
    //  grep
    // -----------------------------------------------------------------

    @Override
    public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
        if (path != null) {
            Routed r = resolveRoute(path);
            if (r.routePrefix() != null) {
                GrepResult gr = r.backend().grep(pattern, r.backendPath(), glob, maxCount);
                if (gr.error().isPresent()) return gr;
                List<GrepMatch> remapped = new ArrayList<>();
                for (GrepMatch m : gr.matches().orElse(List.of())) {
                    remapped.add(remapGrepMatch(m, r.routePrefix()));
                }
                return GrepResult.of(remapped, gr.truncated());
            }
        }

        // path is None or "/" — search every backend.
        if (path == null || "/".equals(path)) {
            List<GrepMatch> allMatches = new ArrayList<>();
            boolean truncated = false;

            GrepResult defRes = defaultBackend.grep(pattern, path, glob, maxCount);
            if (defRes.error().isPresent()) return defRes;
            allMatches.addAll(defRes.matches().orElse(List.of()));
            truncated = truncated || defRes.truncated();

            for (Map.Entry<String, BackendProtocol> e : routes.entrySet()) {
                int remaining = remainingBudget(maxCount, allMatches.size());
                if (remaining == 0) {
                    truncated = true;
                    break;
                }
                GrepResult sub = e.getValue().grep(pattern, "/", glob, remaining);
                if (sub.error().isPresent()) return sub;
                for (GrepMatch m : sub.matches().orElse(List.of())) {
                    allMatches.add(remapGrepMatch(m, e.getKey()));
                }
                truncated = truncated || sub.truncated();
            }
            if (maxCount != null && allMatches.size() > maxCount) {
                allMatches = new ArrayList<>(allMatches.subList(0, maxCount));
                truncated = true;
            }
            return GrepResult.of(allMatches, truncated);
        }
        return defaultBackend.grep(pattern, path, glob, maxCount);
    }

    @Override
    public CompletableFuture<GrepResult> agrep(String pattern, String path, String glob, Integer maxCount) {
        return CompletableFuture.supplyAsync(() -> grep(pattern, path, glob, maxCount));
    }

    // -----------------------------------------------------------------
    //  glob
    // -----------------------------------------------------------------

    @Override
    public GlobResult glob(String pattern, String path) {
        if (path != null) {
            Routed r = resolveRoute(path);
            if (r.routePrefix() != null) {
                GlobResult sub = r.backend().glob(pattern, r.backendPath());
                if (sub.error().isPresent()) return sub;
                List<FileInfo> remapped = remapInfos(sub.matches().orElse(List.of()), r.routePrefix());
                return GlobResult.of(remapped, sub.truncated());
            }
        }
        if (path == null || "/".equals(path)) {
            GlobResult def = defaultBackend.glob(pattern, path);
            if (def.error().isPresent()) return def;
            List<FileInfo> all = new ArrayList<>(def.matches().orElse(List.of()));
            boolean truncated = def.truncated();
            for (Map.Entry<String, BackendProtocol> e : routes.entrySet()) {
                String stripped = stripRouteFromPattern(pattern, e.getKey());
                GlobResult sub = e.getValue().glob(stripped, "/");
                if (sub.error().isPresent()) return sub;
                all.addAll(remapInfos(sub.matches().orElse(List.of()), e.getKey()));
                truncated = truncated || sub.truncated();
            }
            all.sort((a, b) -> a.path().compareTo(b.path()));
            return GlobResult.of(all, truncated);
        }
        return defaultBackend.glob(pattern, path);
    }

    @Override
    public CompletableFuture<GlobResult> aglob(String pattern, String path) {
        return CompletableFuture.supplyAsync(() -> glob(pattern, path));
    }

    // -----------------------------------------------------------------
    //  write / edit / delete
    // -----------------------------------------------------------------

    @Override
    public WriteResult write(String filePath, String content) {
        BackendProtocol b = selectBackend(filePath);
        String p = selectBackendPath(filePath);
        WriteResult res = b.write(p, content);
        if (res.path().isPresent()) {
            return WriteResult.success(filePath);
        }
        return res;
    }

    @Override
    public CompletableFuture<WriteResult> awrite(String filePath, String content) {
        return CompletableFuture.supplyAsync(() -> write(filePath, content));
    }

    @Override
    public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
        BackendProtocol b = selectBackend(filePath);
        String p = selectBackendPath(filePath);
        EditResult res = b.edit(p, oldString, newString, replaceAll);
        if (res.path().isPresent()) {
            return EditResult.success(filePath, res.occurrences().orElse(0));
        }
        return res;
    }

    @Override
    public CompletableFuture<EditResult> aedit(String filePath, String oldString, String newString, boolean replaceAll) {
        return CompletableFuture.supplyAsync(() -> edit(filePath, oldString, newString, replaceAll));
    }

    @Override
    public DeleteResult delete(String filePath) {
        BackendProtocol b = selectBackend(filePath);
        String p = selectBackendPath(filePath);
        DeleteResult res = b.delete(p);
        // The original Python code overwrites the path with the composite path
        // so the caller sees what they asked for, not the per-route path.
        if (res.path().isPresent()) {
            return DeleteResult.success(filePath);
        }
        return res;
    }

    @Override
    public CompletableFuture<DeleteResult> adelete(String filePath) {
        return CompletableFuture.supplyAsync(() -> delete(filePath));
    }

    // -----------------------------------------------------------------
    //  upload / download
    // -----------------------------------------------------------------

    @Override
    public List<FileUploadResponse> uploadFiles(List<PathedBytes> files) {
        List<FileUploadResponse> out = new ArrayList<>();
        for (PathedBytes p : files) {
            BackendProtocol b = selectBackend(p.path());
            String key = selectBackendPath(p.path());
            List<PathedBytes> one = List.of(new PathedBytes(key, p.content()));
            out.addAll(b.uploadFiles(one));
        }
        return out;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(List<String> paths) {
        List<FileDownloadResponse> out = new ArrayList<>();
        for (String p : paths) {
            BackendProtocol b = selectBackend(p);
            String key = selectBackendPath(p);
            // Preserve the original composite path in the response so callers
            // can correlate the result with the input list (mirrors
            // test_composite_download_preserves_original_paths).
            for (FileDownloadResponse r : b.downloadFiles(List.of(key))) {
                if (Objects.equals(r.path(), key) && !Objects.equals(p, key)) {
                    out.add(FileDownloadResponse.success(p, r.content().orElse(null)));
                } else {
                    out.add(r);
                }
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    //  helpers
    // -----------------------------------------------------------------

    private static int remainingBudget(Integer maxCount, int collected) {
        if (maxCount == null) return Integer.MAX_VALUE;
        return Math.max(maxCount - collected, 0);
    }

    private static List<FileInfo> remapInfos(List<FileInfo> entries, String routePrefix) {
        List<FileInfo> out = new ArrayList<>();
        for (FileInfo fi : entries) {
            out.add(remapFileInfoPath(fi, routePrefix));
        }
        return out;
    }

    private static FileInfo remapFileInfoPath(FileInfo fi, String routePrefix) {
        String prefixNoSlash = routePrefix.endsWith("/")
                ? routePrefix.substring(0, routePrefix.length() - 1)
                : routePrefix;
        String remapped = prefixNoSlash + fi.path();
        if (fi.isDir()) {
            return FileInfo.directory(remapped);
        }
        return FileInfo.file(remapped, fi.size(), fi.modifiedAtOpt().orElse(""));
    }

    private static GrepMatch remapGrepMatch(GrepMatch m, String routePrefix) {
        String prefixNoSlash = routePrefix.endsWith("/")
                ? routePrefix.substring(0, routePrefix.length() - 1)
                : routePrefix;
        return new GrepMatch(
                prefixNoSlash + m.path(),
                m.line(),
                m.text(),
                m.contextBefore(),
                m.contextAfter());
    }

    /**
     * If a pattern starts with the route prefix, strip the prefix so the
     * pattern is relative to the backend's internal root.
     */
    private static String stripRouteFromPattern(String pattern, String routePrefix) {
        String barePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String barePrefix = routePrefix.replaceAll("^/|/$", "") + "/";
        if (barePattern.startsWith(barePrefix)) {
            return "/" + barePattern.substring(barePrefix.length());
        }
        return pattern;
    }

    // -----------------------------------------------------------------
    //  execute / aexecute (sandbox-only; not part of BackendProtocol)
    // -----------------------------------------------------------------

    /**
     * Execute a shell command via the default backend.
     *
     * <p>Execution is not path-routable &mdash; it always delegates to the
     * default backend. If the default backend isn't a
     * {@link SandboxBackendProtocol}, this throws
     * {@link UnsupportedOperationException} (a safety fallback; the
     * runtime check in the execute tool should reject non-sandbox
     * backends earlier).</p>
     *
     * <p>The {@code timeout} is forwarded only when the default backend's
     * {@code execute} accepts a timeout parameter (see
     * {@link BackendProtocol#executeAcceptsTimeout(Class)}). In Java
     * static dispatch means we can't introspect the override &mdash; the
     * default implementation returns {@code true}, so legacy
     * backends that don't take a timeout would still cause a compile
     * error rather than a runtime TypeError.</p>
     */
    public ExecuteResponse execute(String command, Integer timeout) {
        if (!(defaultBackend instanceof SandboxBackendProtocol s)) {
            throw new UnsupportedOperationException(
                    "CompositeBackend.execute requires the default backend to implement SandboxBackendProtocol");
        }
        if (timeout != null && BackendProtocol.executeAcceptsTimeout(defaultBackend.getClass())) {
            return s.execute(command, timeout);
        }
        return s.execute(command, null);
    }

    public CompletableFuture<ExecuteResponse> aexecute(String command, Integer timeout) {
        return CompletableFuture.supplyAsync(() -> execute(command, timeout));
    }
}
