package org.aethercode.core.fs.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Filesystem-backed {@link BackendProtocol}.
 *
 * <p>Java-native port of deepagents <code>FilesystemBackend</code>.
 * Reads and writes go through a configurable root directory; {@code virtual_mode}
 * (default on) treats the root as a virtual filesystem root and blocks path
 * traversal, while {@code virtual_mode=False} uses the real host paths. Grep
 * prefers <code>ripgrep</code> when installed and falls back to a streaming
 * Python-style walk; both paths produce identical literal-substring results.</p>
 */
/**
 * Backend implementation that resolves file operations against a local filesystem root.
 *
 * <p>Mirrors the Python source {@code deepagents/backends/filesystem.py}. This is the
 * default backend when no remote or sandbox is configured. All operations are
 * synchronous and bounded by the JDK's NIO. Filesystem permissions, read result
 * pagination, and write/atomic semantics are 1:1 with the Python implementation.
 *
 * @see <a href="https://github.com/langchain-ai/deepagents/blob/main/libs/deepagents/deepagents/backends/filesystem.py">deepagents/backends/filesystem.py</a>
 */
public class FilesystemBackend implements BackendProtocol {

    private static final Logger log = Logger.getLogger(FilesystemBackend.class.getName());

    /** Default wall-clock budget for a single local {@code glob} walk. */
    public static final double DEFAULT_GLOB_TIMEOUT = 5.0;
    /** Default wall-clock budget for one sync grep phase. */
    public static final int DEFAULT_GREP_TIMEOUT = 15;
    /** Timeout for the async grep wrapper (ripgrep + Python fallback). */
    public static final int ASYNC_GREP_TIMEOUT = (2 * DEFAULT_GREP_TIMEOUT) + 5;

    private static final int RIPGREP_STDERR_CAPTURE_LIMIT = 500;
    private static final int RIPGREP_STDERR_READ_SIZE = 8192;

    private final Path cwd;
    private final boolean virtualMode;
    private final long maxFileSizeBytes;

    public FilesystemBackend() {
        this((Path) null, true, 10);
    }

    public FilesystemBackend(String rootDir) {
        this(rootDir, true, 10);
    }

    public FilesystemBackend(String rootDir, boolean virtualMode, int maxFileSizeMb) {
        this(rootDir == null ? Paths.get("").toAbsolutePath().normalize()
                        : Paths.get(rootDir).toAbsolutePath().normalize(),
                virtualMode, maxFileSizeMb);
    }

    public FilesystemBackend(Path rootDir, boolean virtualMode, int maxFileSizeMb) {
        this.cwd = rootDir;
        this.virtualMode = virtualMode;
        this.maxFileSizeBytes = (long) maxFileSizeMb * 1024L * 1024L;
    }

    public Path cwd() { return cwd; }
    public boolean virtualMode() { return virtualMode; }
    public long maxFileSizeBytes() { return maxFileSizeBytes; }

    // -----------------------------------------------------------------
    //  path resolution
    // -----------------------------------------------------------------

    public Path resolvePath(String key) {
        if (virtualMode) {
            String vpath = key.startsWith("/") ? key : "/" + key;
            if (vpath.contains("..") || vpath.startsWith("~")) {
                throw new IllegalArgumentException("Path traversal not allowed");
            }
            Path full = cwd.resolve(vpath.substring(1)).normalize().toAbsolutePath();
            try {
                full = full.toRealPath();
            } catch (IOException ignored) {
                // not-yet-existing files; fall back to the normalized path
            }
            if (!full.startsWith(cwd)) {
                throw new IllegalArgumentException(
                        "Path:" + full + " outside root directory: " + cwd);
            }
            raiseIfSymlinkLoop(full);
            return full;
        }
        Path path = Paths.get(key);
        if (path.isAbsolute()) {
            raiseIfSymlinkLoop(path);
            return path;
        }
        Path resolved = cwd.resolve(path).normalize().toAbsolutePath();
        try {
            resolved = resolved.toRealPath();
        } catch (IOException ignored) {
            // not-yet-existing
        }
        raiseIfSymlinkLoop(resolved);
        return resolved;
    }

    public String toVirtualPath(Path path) throws IOException {
        // Mirror Python: `"/" + path.resolve().relative_to(self.cwd).as_posix()`.
        // - `toRealPath` follows symlinks but throws if the path does not exist;
        //   fall back to `toAbsolutePath().normalize()` so callers can compute
        //   virtual paths for not-yet-created files (e.g. write targets).
        // - Python's `Path.resolve().relative_to(self.cwd)` for the cwd itself
        //   returns `Path('.')` whose `as_posix()` is `"."`. Java's
        //   `relativize` returns an empty path, so emit `"."` explicitly to
        //   keep the test contract `cwd -> "/."`.
        // - Python's `relative_to` raises `ValueError("... is not in the
        //   subpath of ...")` when `path` is not a descendant of `cwd`.
        //   Java's `relativize` would happily produce `..\foo` segments, so
        //   reproduce the guard explicitly.
        Path base = safeRealPath(cwd);
        Path target = safeRealPath(path);
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException(
                    target + " is not in the subpath of " + base);
        }
        Path rel = base.relativize(target);
        if (rel.toString().isEmpty()) {
            return "/.";
        }
        return "/" + rel.toString().replace('\\', '/');
    }

    private static Path safeRealPath(Path p) throws IOException {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    public String displayPath(Path path) {
        if (!virtualMode) return path.toString();
        try {
            return toVirtualPath(path);
        } catch (Exception e) {
            String name = path.getFileName() == null ? null : path.getFileName().toString();
            return name == null || name.isEmpty() ? "/" : name;
        }
    }

    public static void raiseIfSymlinkLoop(Path path) {
        if (!Files.isSymbolicLink(path)) return;
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (FileSystemException e) {
            if (isEloopError(e)) throw new RuntimeException(e);
        } catch (IOException ignored) {
            // Other I/O errors (broken target, permission denied) are left for
            // the caller's existence check to surface.
        }
    }

    private static boolean isEloopError(IOException exc) {
        if (!(exc instanceof FileSystemException)) return false;
        String msg = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase();
        return msg.contains("too many levels") || msg.contains("symlink loop")
                || msg.contains("eloop") || msg.contains("reparse");
    }

    // -----------------------------------------------------------------
    //  ls
    // -----------------------------------------------------------------

    @Override
    public LsResult ls(String path) {
        Path dirPath;
        try {
            dirPath = resolvePath(path);
            if (!Files.exists(dirPath)) {
                return LsResult.error("Path '" + path + "': path_not_found");
            }
            if (!Files.isDirectory(dirPath)) {
                return LsResult.error("Path '" + path + "': not_a_directory");
            }
        } catch (RuntimeException e) {
            return LsResult.error("Cannot list '" + path + "': " + e.getMessage());
        }

        List<FileInfo> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
            for (Path childPath : stream) {
                boolean isFile;
                boolean isDir;
                try {
                    isFile = Files.isRegularFile(childPath);
                    isDir = Files.isDirectory(childPath);
                } catch (RuntimeException e) {
                    errors.add("child error: cannot stat '" + childPath + "': " + e.getMessage());
                    continue;
                }
                if (!isFile && !isDir) {
                    try {
                        if (Files.isSymbolicLink(childPath)) {
                            childPath.toRealPath();
                            raiseIfSymlinkLoop(childPath);
                        }
                    } catch (RuntimeException e) {
                        errors.add("child error: cannot resolve '" + childPath + "': " + e.getMessage());
                    }
                    continue;
                }
                String display = virtualMode ? toVirtualPathSafe(childPath) : childPath.toString();
                if (display == null) continue;
                try {
                    BasicFileAttributes st = Files.readAttributes(childPath, BasicFileAttributes.class);
                    if (isFile) {
                        results.add(FileInfo.file(
                                display,
                                st.size(),
                                Instant.ofEpochSecond(st.lastModifiedTime().toInstant().getEpochSecond()).toString()));
                    } else {
                        results.add(FileInfo.directory(display + "/"));
                    }
                } catch (IOException e) {
                    if (isFile) {
                        results.add(FileInfo.file(display));
                    } else {
                        results.add(FileInfo.directory(display + "/"));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            errors.add("Listing of '" + path + "' aborted: " + e.getMessage());
        }
        results.sort((a, b) -> a.path().compareTo(b.path()));
        String error = errors.isEmpty() ? null : String.join("\n", errors.stream().sorted().toList());
        return LsResult.of(results);
    }

    private String toVirtualPathSafe(Path childPath) {
        try {
            return toVirtualPath(childPath);
        } catch (IOException | RuntimeException e) {
            log.log(Level.FINE, "Skipping path outside root: " + childPath, e);
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  read
    // -----------------------------------------------------------------

    @Override
    public ReadResult read(String filePath, int offset, int limit) {
        Path resolvedPath;
        try {
            // IllegalArgumentException (e.g. path traversal) propagates: the
            // Python port's `ValueError` does the same. Only other
            // RuntimeExceptions are converted to a ReadResult.error.
            resolvedPath = resolvePath(filePath);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            return ReadResult.error("Error reading file '" + filePath + "': " + e.getMessage());
        }
        try {
            if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
                return ReadResult.error("File '" + filePath + "' not found");
            }
            BackendUtils.FileType fileType = BackendUtils.getBackendReadFileType(filePath);
            // .mkv (and other video extras) are treated as text when small (under
            // MAX_VIDEO_INPUT_BYTES) and as binary when oversized. The Python port
            // routes the small case through the text branch; the oversized case
            // returns an error result. Other binary types always go binary.
            boolean isVideoExtra = filePath.toLowerCase().endsWith(".mkv");
            if (fileType != BackendUtils.FileType.TEXT && !isVideoExtra) {
                try (InputStream in = Files.newInputStream(resolvedPath, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] raw = in.readAllBytes();
                    String encoded = Base64.getEncoder().encodeToString(raw);
                    return ReadResult.of(FileData.of(encoded, FileData.ENCODING_BASE64));
                }
            } else {
                if (isVideoExtra) {
                    long size = Files.size(resolvedPath);
                    if (size > BackendUtils.MAX_VIDEO_INPUT_BYTES) {
                        return ReadResult.error("Video file exceeds maximum input size of "
                                + BackendUtils.MAX_VIDEO_INPUT_BYTES + " bytes");
                    }
                }
                String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
                Optional<String> empty = BackendUtils.checkEmptyContent(content);
                if (empty.isPresent()) {
                    return ReadResult.of(FileData.of(empty.get(), FileData.ENCODING_UTF8));
                }
                return BackendUtils.sliceReadResponse(
                        FileData.of(content, FileData.ENCODING_UTF8), offset, limit);
            }
        } catch (IOException | RuntimeException e) {
            return ReadResult.error("Error reading file '" + filePath + "': " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  write
    // -----------------------------------------------------------------

    @Override
    public WriteResult write(String filePath, String content) {
        Path resolvedPath;
        try {
            resolvedPath = resolvePath(filePath);
        } catch (RuntimeException e) {
            return WriteResult.failure("Error writing file '" + filePath + "': " + e.getMessage());
        }
        try {
            if (resolvedPath.getParent() != null) {
                Files.createDirectories(resolvedPath.getParent());
            }
            try (OutputStream out = Files.newOutputStream(
                    resolvedPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return WriteResult.success(filePath);
        } catch (IOException | RuntimeException e) {
            return WriteResult.failure("Error writing file '" + filePath + "': " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  edit
    // -----------------------------------------------------------------

    @Override
    public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
        Path resolvedPath;
        try {
            resolvedPath = resolvePath(filePath);
        } catch (RuntimeException e) {
            return EditResult.failure("Error editing file '" + filePath + "': " + e.getMessage());
        }
        try {
            if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
                return EditResult.failure("Error: File '" + filePath + "' not found");
            }
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            String oldNorm = oldString.replace("\r\n", "\n").replace("\r", "\n");
            String newNorm = newString.replace("\r\n", "\n").replace("\r", "\n");
            BackendUtils.StringReplacementResult result =
                    BackendUtils.performStringReplacement(content, oldNorm, newNorm, replaceAll);
            if (result.error() != null) {
                return EditResult.failure(result.error());
            }
            try (OutputStream out = Files.newOutputStream(
                    resolvedPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                out.write(result.newContent().getBytes(StandardCharsets.UTF_8));
            }
            return EditResult.success(filePath, result.occurrences());
        } catch (IOException | RuntimeException e) {
            return EditResult.failure("Error editing file '" + filePath + "': " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  delete
    // -----------------------------------------------------------------

    @Override
    public DeleteResult delete(String filePath) {
        Path resolvedPath;
        try {
            resolvedPath = resolvePath(filePath);
        } catch (RuntimeException e) {
            return DeleteResult.failure("Error deleting '" + filePath + "': " + e.getMessage());
        }
        try {
            boolean isLink = Files.isSymbolicLink(resolvedPath);
            boolean exists = Files.exists(resolvedPath);
            if (!exists && !isLink) {
                return DeleteResult.failure("Error: '" + filePath + "' not found");
            }
            if (isLink) {
                Files.delete(resolvedPath);
            } else if (Files.isDirectory(resolvedPath)) {
                deleteRecursively(resolvedPath);
            } else {
                Files.delete(resolvedPath);
            }
            return DeleteResult.success(filePath);
        } catch (IOException | RuntimeException e) {
            return DeleteResult.failure("Error deleting '" + filePath + "': " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // -----------------------------------------------------------------
    //  grep
    // -----------------------------------------------------------------

    @Override
    public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
        return grep(pattern, path, glob, maxCount, 0);
    }

    public GrepResult grep(String pattern, String path, String glob,
                           Integer maxCount, int contextLines) {
        if (contextLines < 0) {
            throw new IllegalArgumentException("context_lines must be non-negative");
        }
        Path baseFull;
        try {
            baseFull = resolvePath(path == null ? "." : path);
        } catch (IllegalArgumentException e) {
            return GrepResult.of(List.of());
        } catch (RuntimeException e) {
            return GrepResult.error("Error searching path '" + path + "': " + e.getMessage());
        }
        try {
            if (!Files.exists(baseFull)) {
                return GrepResult.of(List.of());
            }
        } catch (RuntimeException e) {
            return GrepResult.error("Error searching path '" + path + "': " + e.getMessage());
        }

        GrepEngineOutput rg = ripgrepSearch(pattern, baseFull, glob, maxCount);
        Map<String, List<LineMatch>> results;
        boolean truncated;
        String partialError;
        String contextNewline;
        if (rg.results != null) {
            results = rg.results;
            truncated = rg.truncated;
            partialError = null;
            contextNewline = "\n";
        } else {
            PythonEngineOutput py = pythonSearch(pattern, baseFull, glob, maxCount, DEFAULT_GREP_TIMEOUT);
            results = py.results;
            truncated = py.truncated;
            partialError = py.partialError;
            contextNewline = null;
        }

        List<GrepMatch> matches = new ArrayList<>();
        for (Map.Entry<String, List<LineMatch>> e : results.entrySet()) {
            for (LineMatch lm : e.getValue()) {
                matches.add(new GrepMatch(e.getKey(), lm.line(), lm.text(),
                        Optional.empty(), Optional.empty()));
            }
        }
        if (contextLines > 0) {
            partialError = applyGrepContext(matches, contextLines, partialError, pattern, contextNewline);
        }
        return new GrepResult(Optional.ofNullable(partialError), Optional.of(matches), truncated);
    }

    @Override
    public CompletableFuture<GrepResult> agrep(String pattern, String path, String glob, Integer maxCount) {
        return agrep(pattern, path, glob, maxCount, 0);
    }

    public CompletableFuture<GrepResult> agrep(String pattern, String path, String glob,
                                              Integer maxCount, int contextLines) {
        if (contextLines < 0) {
            throw new IllegalArgumentException("context_lines must be non-negative");
        }
        if (contextLines == 0) {
            return BackendProtocol.super.agrep(pattern, path, glob, maxCount);
        }
        return CompletableFuture.supplyAsync(() -> grep(pattern, path, glob, maxCount, contextLines))
                .orTimeout(ASYNC_GREP_TIMEOUT, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.log(Level.WARNING, "agrep timed out: " + ex.getMessage());
                    return new GrepResult(
                            Optional.of("Error: grep timed out after " + ASYNC_GREP_TIMEOUT + "s. "
                                    + "Try a more specific pattern or a narrower path."),
                            Optional.of(List.of()), false);
                });
    }

    // -----------------------------------------------------------------
    //  glob
    // -----------------------------------------------------------------

    @Override
    public GlobResult glob(String pattern, String path) {
        if (virtualMode) {
            String stripped = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            String[] parts = stripped.split("/");
            for (String part : parts) {
                if (part.equals("..")) {
                    throw new IllegalArgumentException("Path traversal not allowed in glob pattern");
                }
            }
        }
        Path searchPath;
        try {
            searchPath = (path == null || "/".equals(path)) ? cwd : resolvePath(path);
            if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
                return GlobResult.of(List.of());
            }
        } catch (RuntimeException e) {
            return GlobResult.error("Error globbing path '" + path + "': " + e.getMessage());
        }
        long deadlineNanos = System.nanoTime() + (long) (DEFAULT_GLOB_TIMEOUT * 1_000_000_000L);
        boolean[] truncatedRef = {false};
        List<FileInfo> results = new ArrayList<>();
        GlobMatchFunction matcher;
        try {
            matcher = BackendUtils.compileRecursiveGlob(pattern);
        } catch (IllegalArgumentException e) {
            return GlobResult.error("Invalid glob pattern: " + e.getMessage());
        }
        try {
            Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                    if (System.nanoTime() > deadlineNanos) {
                        truncatedRef[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    } catch (RuntimeException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (virtualMode) {
                        try {
                            p.toRealPath();
                        } catch (IOException | RuntimeException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    Path rel;
                    try {
                        rel = searchPath.relativize(p);
                    } catch (RuntimeException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!matcher.test(rel.toString().replace('\\', '/'))) {
                        return FileVisitResult.CONTINUE;
                    }
                    String display = virtualMode ? toVirtualPathSafe(p) : p.toString();
                    if (display == null) return FileVisitResult.CONTINUE;
                    try {
                        BasicFileAttributes st = Files.readAttributes(p, BasicFileAttributes.class);
                        results.add(FileInfo.file(
                                display,
                                st.size(),
                                Instant.ofEpochSecond(st.lastModifiedTime().toInstant().getEpochSecond()).toString()));
                    } catch (IOException e) {
                        results.add(FileInfo.file(display));
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IllegalArgumentException e) {
            return GlobResult.error("Invalid glob pattern: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            return GlobResult.error("Glob of '" + path + "' aborted partway: " + e.getMessage());
        }
        if (truncatedRef[0]) {
            log.log(Level.WARNING,
                    "Glob of '" + path + "' timed out after " + DEFAULT_GLOB_TIMEOUT + "s with "
                            + results.size() + " match(es); returning partial results");
        }
        results.sort((a, b) -> a.path().compareTo(b.path()));
        return new GlobResult(Optional.empty(), Optional.of(results), truncatedRef[0]);
    }

    // -----------------------------------------------------------------
    //  upload / download
    // -----------------------------------------------------------------

    @Override
    public List<FileUploadResponse> uploadFiles(List<PathedBytes> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        for (PathedBytes entry : files) {
            try {
                Path resolved = resolvePath(entry.path());
                if (resolved.getParent() != null) {
                    Files.createDirectories(resolved.getParent());
                }
                try (OutputStream out = Files.newOutputStream(
                        resolved,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS)) {
                    out.write(entry.content());
                }
                responses.add(FileUploadResponse.success(entry.path()));
            } catch (Exception exc) {
                String code = mapExceptionToCode(exc);
                if (code == null) throw new RuntimeException(exc);
                responses.add(FileUploadResponse.failure(entry.path(), code));
            }
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String p : paths) {
            try {
                Path resolved = resolvePath(p);
                if (Files.isDirectory(resolved)) {
                    responses.add(FileDownloadResponse.failure(p, FileOperationError.IS_DIRECTORY.code()));
                    continue;
                }
                try (InputStream in = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] content = in.readAllBytes();
                    responses.add(FileDownloadResponse.success(p, content));
                }
            } catch (Exception exc) {
                String code = mapExceptionToCode(exc);
                if (code == null) throw new RuntimeException(exc);
                responses.add(FileDownloadResponse.failure(p, code));
            }
        }
        return responses;
    }

    private static String mapExceptionToCode(Throwable exc) {
        return FileOperationErrorMapper.mapToCode(exc);
    }

    private static boolean isSymlinkLoopError(Throwable exc) {
        if (exc == null) return false;
        if (isEloopThrowable(exc)) return true;
        if (exc.getCause() != null && isSymlinkLoopError(exc.getCause())) return true;
        return false;
    }

    private static boolean isEloopThrowable(Throwable exc) {
        if (!(exc instanceof FileSystemException)) return false;
        String msg = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase();
        return msg.contains("too many levels") || msg.contains("symlink loop")
                || msg.contains("eloop") || msg.contains("reparse");
    }

    // -----------------------------------------------------------------
    //  ripgrep integration
    // -----------------------------------------------------------------

    private record GrepEngineOutput(Map<String, List<LineMatch>> results, boolean truncated) {}

    private record PythonEngineOutput(Map<String, List<LineMatch>> results, boolean truncated, String partialError) {}

    private GrepEngineOutput ripgrepSearch(String pattern, Path baseFull,
                                            String includeGlob, Integer maxCount) {
        String rgPath = resolveRipgrepPath();
        if (rgPath == null) return new GrepEngineOutput(null, false);
        List<String> cmd = new ArrayList<>();
        cmd.add(rgPath);
        cmd.add("--json");
        cmd.add("-F");
        if (maxCount != null) {
            cmd.add("-m");
            cmd.add(String.valueOf(maxCount + 1));
        }
        if (includeGlob != null) {
            cmd.add("--glob");
            cmd.add(includeGlob);
        }
        String rgCwd;
        if (Files.isDirectory(baseFull)) {
            cmd.add("--");
            cmd.add(pattern);
            cmd.add(".");
            rgCwd = baseFull.toString();
        } else {
            cmd.add("--");
            cmd.add(pattern);
            cmd.add(baseFull.toString());
            rgCwd = null;
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (rgCwd != null) pb.directory(new java.io.File(rgCwd));
        pb.redirectErrorStream(false);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            log.log(Level.WARNING, "ripgrep subprocess failed; using Python grep fallback", e);
            resolveRipgrepPathCache.remove("rg");
            return new GrepEngineOutput(null, false);
        }
        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[RIPGREP_STDERR_READ_SIZE];
                int n;
                int remaining = RIPGREP_STDERR_CAPTURE_LIMIT;
                while ((n = r.read(buf)) != -1) {
                    if (remaining > 0) {
                        int take = Math.min(n, remaining);
                        stderrBuf.append(buf, 0, take);
                        remaining -= take;
                    }
                }
            } catch (IOException ignored) {
            }
        }, "rg-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        Map<String, List<LineMatch>> results = new LinkedHashMap<>();
        Path baseResolved;
        try {
            baseResolved = baseFull.toRealPath();
        } catch (IOException e) {
            baseResolved = baseFull;
        }
        int total = 0;
        boolean truncated = false;
        boolean[] timedOut = {false};
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(DEFAULT_GREP_TIMEOUT * 1000L);
                synchronized (timedOut) {
                    timedOut[0] = true;
                }
                proc.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        }, "rg-watchdog");
        timer.setDaemon(true);
        timer.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ParsedMatch parsed = parseRgMatch(line, baseFull, baseResolved);
                if (parsed == null) continue;
                if (maxCount != null && total >= maxCount) {
                    truncated = true;
                    proc.destroy();
                    break;
                }
                results.computeIfAbsent(parsed.virt, k -> new ArrayList<>())
                        .add(new LineMatch(parsed.line, parsed.text));
                total++;
            }
        } catch (IOException ignored) {
        }
        timer.interrupt();
        try {
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (proc.isAlive()) {
            proc.destroyForcibly();
            try {
                proc.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            stderrThread.join();
        } catch (InterruptedException ignored) {
        }
        if (timedOut[0]) {
            if (!results.isEmpty()) {
                log.log(Level.WARNING, "ripgrep timed out; returning partial results");
                return new GrepEngineOutput(results, true);
            }
            return new GrepEngineOutput(null, false);
        }
        if (truncated) return new GrepEngineOutput(results, true);
        if (proc.exitValue() != 0 && proc.exitValue() != 1) {
            log.log(Level.WARNING,
                    "ripgrep exited " + proc.exitValue() + " (stderr=" + stderrBuf + "); using Python fallback");
            return new GrepEngineOutput(null, false);
        }
        return new GrepEngineOutput(results, false);
    }

    private record ParsedMatch(String virt, int line, String text) {}

    private ParsedMatch parseRgMatch(String line, Path baseFull, Path baseResolved) {
        int typeIdx = line.indexOf("\"type\"");
        if (typeIdx < 0) return null;
        int matchIdx = line.indexOf("\"match\"", typeIdx);
        int errIdx = line.indexOf("\"error\"", typeIdx);
        if (matchIdx < 0) return null;
        if (errIdx >= 0 && errIdx < matchIdx) return null;
        int pathTextIdx = line.indexOf("\"text\"");
        if (pathTextIdx < 0) return null;
        int textOpen = line.indexOf('"', pathTextIdx + 7) + 1;
        if (textOpen <= 0) return null;
        int textClose = findJsonStringEnd(line, textOpen - 1);
        if (textClose <= textOpen) return null;
        String ftext = unescapeJson(line.substring(textOpen, textClose));
        if (ftext.isEmpty()) return null;
        Path raw = Paths.get(ftext);
        Path p;
        try {
            p = raw.isAbsolute() ? raw : baseFull.resolve(raw);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            p.toRealPath().relativize(baseResolved);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String virt;
        if (virtualMode) {
            try {
                virt = toVirtualPath(p);
            } catch (Exception e) {
                return null;
            }
        } else {
            virt = p.toString();
        }
        int lnIdx = line.indexOf("\"line_number\"");
        if (lnIdx < 0) return null;
        int colon = line.indexOf(':', lnIdx);
        int comma = line.indexOf(',', colon);
        int brace = line.indexOf('}', colon);
        int end = (comma > 0 && (brace < 0 || comma < brace)) ? comma : brace;
        int lineNum;
        try {
            lineNum = Integer.parseInt(line.substring(colon + 1, end).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int linesIdx = line.indexOf("\"lines\"", lnIdx);
        String text = "";
        if (linesIdx > 0) {
            int ltIdx = line.indexOf("\"text\"", linesIdx);
            if (ltIdx > 0) {
                int lOpen = line.indexOf('"', ltIdx + 7) + 1;
                if (lOpen > 0) {
                    int lClose = findJsonStringEnd(line, lOpen - 1);
                    if (lClose > lOpen) {
                        text = unescapeJson(line.substring(lOpen, lClose));
                        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                    }
                }
            }
        }
        return new ParsedMatch(virt, lineNum, text);
    }

    private static int findJsonStringEnd(String s, int openQuote) {
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return s.length();
    }

    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private final Map<String, String> resolveRipgrepPathCache = new HashMap<>();

    private String resolveRipgrepPath() {
        if (resolveRipgrepPathCache.containsKey("rg")) {
            return resolveRipgrepPathCache.get("rg");
        }
        String found = findExecutableOnPath("rg");
        if (found == null) {
            log.log(Level.INFO,
                    "ripgrep ('rg') not found on PATH; using Python grep fallback. "
                            + "Install ripgrep for faster searches and automatic .gitignore handling.");
        }
        resolveRipgrepPathCache.put("rg", found);
        return found;
    }

    private static String findExecutableOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            java.io.File f = new java.io.File(dir, exe);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
            if (isWindows) {
                java.io.File fe = new java.io.File(dir, exe + ".exe");
                if (fe.exists() && fe.canExecute()) return fe.getAbsolutePath();
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Python search fallback
    // -----------------------------------------------------------------

    private PythonEngineOutput pythonSearch(String pattern, Path baseFull, String includeGlob,
                                            Integer maxCount, int timeout) {
        long deadlineNanos = System.nanoTime() + (long) timeout * 1_000_000_000L;
        GlobMatchFunction globMatcher = includeGlob == null
                ? null : BackendUtils.compileGrepIncludeGlob(includeGlob);
        Map<String, List<LineMatch>> results = new LinkedHashMap<>();
        List<String> fileErrors = new ArrayList<>();
        Path root = Files.isDirectory(baseFull) ? baseFull : baseFull.getParent();
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path fp, BasicFileAttributes attrs) {
                    if (System.nanoTime() > deadlineNanos) {
                        log.log(Level.WARNING, "Grep of '" + baseFull + "' timed out after " + timeout + "s");
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        if (!Files.isRegularFile(fp, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    } catch (RuntimeException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (globMatcher != null) {
                        String rel;
                        try {
                            rel = root.relativize(fp).toString().replace('\\', '/');
                        } catch (RuntimeException e) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!globMatcher.test(rel)) return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (Files.size(fp) > maxFileSizeBytes) return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    String virtPath;
                    if (virtualMode) {
                        try {
                            virtPath = toVirtualPath(fp);
                        } catch (Exception e) {
                            return FileVisitResult.CONTINUE;
                        }
                    } else {
                        virtPath = fp.toString();
                    }
                    int scannedLines = 0;
                    try (BufferedReader r = Files.newBufferedReader(fp, StandardCharsets.UTF_8)) {
                        String raw;
                        int lineNum = 0;
                        while ((raw = r.readLine()) != null) {
                            lineNum++;
                            scannedLines = lineNum;
                            if (lineNum % 2048 == 0 && System.nanoTime() > deadlineNanos) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (!raw.contains(pattern)) continue;
                            if (maxCount != null && countMatches(results) >= maxCount) {
                                truncated[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            String line = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
                            results.computeIfAbsent(virtPath, k -> new ArrayList<>())
                                    .add(new LineMatch(lineNum, line));
                        }
                    } catch (MalformedInputException e) {
                        if (scannedLines > 0 || results.containsKey(virtPath)) {
                            fileErrors.add("- " + virtPath + ": " + safeDetail(e));
                        }
                    } catch (IOException | RuntimeException e) {
                        fileErrors.add("- " + virtPath + ": " + safeDetail(e));
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            String msg = "Grep of '" + displayPath(baseFull) + "' aborted: " + safeDetail(e);
            log.log(Level.WARNING, msg, e);
            return new PythonEngineOutput(results, false, msg);
        }
        String partial = fileErrors.isEmpty() ? null
                : "One or more files could not be fully searched:\n" + String.join("\n", fileErrors);
        return new PythonEngineOutput(results, truncated[0], partial);
    }

    private static int countMatches(Map<String, List<LineMatch>> results) {
        int total = 0;
        for (List<LineMatch> v : results.values()) total += v.size();
        return total;
    }

    private String safeDetail(Throwable exc) {
        if (exc instanceof FileSystemException) {
            return exc.getClass().getSimpleName();
        }
        return exc.getClass().getSimpleName() + ": " + exc.getMessage();
    }

    // -----------------------------------------------------------------
    //  Grep context
    // -----------------------------------------------------------------

    private String applyGrepContext(List<GrepMatch> matches, int contextLines,
                                    String partialError, String pattern, String newline) {
        Map<String, List<GrepMatch>> byPath = new HashMap<>();
        for (GrepMatch m : matches) byPath.computeIfAbsent(m.path(), k -> new ArrayList<>()).add(m);
        List<String> unreadable = new ArrayList<>();
        for (Map.Entry<String, List<GrepMatch>> entry : byPath.entrySet()) {
            String filePath = entry.getKey();
            List<GrepMatch> fileMatches = entry.getValue();
            int[][] ranges = grepContextRanges(fileMatches, contextLines);
            Object[] ctxResult = readGrepContext(filePath, ranges, newline);
            @SuppressWarnings("unchecked")
            Map<Integer, String> context = (Map<Integer, String>) ctxResult[0];
            boolean ok = (boolean) ctxResult[1];
            if (!ok) unreadable.add(filePath);
            Set<Integer> matchLines = new HashSet<>();
            for (GrepMatch m : fileMatches) matchLines.add(m.line());
            TreeSet<Map.Entry<Integer, String>> sortedCtx = new TreeSet<>(Map.Entry.comparingByKey());
            sortedCtx.addAll(context.entrySet());
            List<ContextLine> contextItems = new ArrayList<>();
            List<Integer> contextNumbers = new ArrayList<>();
            for (Map.Entry<Integer, String> e : sortedCtx) {
                if (!matchLines.contains(e.getKey()) && !e.getValue().contains(pattern)) {
                    contextItems.add(new ContextLine(e.getKey(), e.getValue()));
                    contextNumbers.add(e.getKey());
                }
            }
            for (GrepMatch m : fileMatches) {
                int lineNum = m.line();
                int beforeStart = lowerBound(contextNumbers, Math.max(1, lineNum - contextLines));
                int beforeEnd = lowerBound(contextNumbers, lineNum);
                int afterStart = upperBound(contextNumbers, lineNum);
                int afterEnd = upperBound(contextNumbers, lineNum + contextLines);
                List<ContextLine> before = contextItems.subList(beforeStart, beforeEnd);
                List<ContextLine> after = contextItems.subList(afterStart, afterEnd);
                int idx = matches.indexOf(m);
                matches.set(idx, new GrepMatch(m.path(), m.line(), m.text(),
                        Optional.of(before), Optional.of(after)));
            }
        }
        if (unreadable.isEmpty()) return partialError;
        Collections.sort(unreadable);
        String ctxErr = "Error: could not read context for " + unreadable.size()
                + " file(s) (non-UTF-8 or unreadable): " + String.join(", ", unreadable);
        return partialError == null ? ctxErr : partialError + "\n" + ctxErr;
    }

    private static int[][] grepContextRanges(List<GrepMatch> fileMatches, int contextLines) {
        List<Integer> sortedLines = new ArrayList<>();
        for (GrepMatch m : fileMatches) sortedLines.add(m.line());
        Collections.sort(sortedLines);
        List<int[]> ranges = new ArrayList<>();
        for (int lineNum : sortedLines) {
            int start = Math.max(1, lineNum - contextLines);
            int end = lineNum + contextLines;
            if (!ranges.isEmpty() && start <= ranges.get(ranges.size() - 1)[1] + 1) {
                int[] last = ranges.get(ranges.size() - 1);
                last[1] = Math.max(last[1], end);
            } else {
                ranges.add(new int[]{start, end});
            }
        }
        return ranges.toArray(new int[0][]);
    }

    private Object[] readGrepContext(String filePath, int[][] lineRanges, String newline) {
        Map<Integer, String> context = new LinkedHashMap<>();
        try {
            Path resolved = resolvePath(filePath);
            try (BufferedReader r = Files.newBufferedReader(resolved, StandardCharsets.UTF_8)) {
                int rangeIdx = 0;
                String raw;
                int lineNum = 0;
                while ((raw = r.readLine()) != null) {
                    lineNum++;
                    while (rangeIdx < lineRanges.length && lineNum > lineRanges[rangeIdx][1]) rangeIdx++;
                    if (rangeIdx == lineRanges.length) break;
                    if (lineNum >= lineRanges[rangeIdx][0]) {
                        String text = raw;
                        if (text.endsWith("\r\n")) text = text.substring(0, text.length() - 2);
                        else if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                        context.put(lineNum, text);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.log(Level.FINE, "Could not read grep context for " + filePath + ": " + e.getMessage());
            return new Object[]{context, false};
        }
        return new Object[]{context, true};
    }

    private static int lowerBound(List<Integer> list, int target) {
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid) < target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int upperBound(List<Integer> list, int target) {
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid) <= target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** FilesystemBackend has no shell. */
    public UnsupportedOperationException notShellCapable() {
        return new UnsupportedOperationException(
                "FilesystemBackend has no shell. Use LocalShellBackend for execute().");
    }
}
