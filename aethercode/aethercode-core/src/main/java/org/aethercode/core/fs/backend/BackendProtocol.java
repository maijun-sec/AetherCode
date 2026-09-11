package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The single, unified protocol every memory backend must implement.
 *
 * <p>Java-native port of deepagents <code>BackendProtocol</code>. File
 * operations (grep, glob, ls, read, edit, write, upload, download) live
 * here rather than only on <code>SandboxBackendProtocol</code> because
 * not every backend has a shell. {@code StateBackend} and
 * {@code StoreBackend} have no <code>execute</code> at all.</p>
 *
 * <p>Each sync method has a default async wrapper that delegates via
 * {@code CompletableFuture.supplyAsync}; concrete backends may override
 * the async variants for native async I/O.</p>
 */
public interface BackendProtocol {

    // -----------------------------------------------------------------
    // Sync file operations
    //
    // Each method has a default that raises NotImplementedError, mirroring
    // the Python port's "abstract protocol with NotImplementedError stubs"
    // pattern. A bare `class Foo implements BackendProtocol` compiles
    // (no `abstract` methods to satisfy) and fails at runtime with a clear
    // message; the async wrappers below propagate the error through their
    // CompletableFuture.
    // -----------------------------------------------------------------

    /** List files in a directory (non-recursive). */
    default LsResult ls(String path) { throw new UnsupportedOperationException("ls not implemented"); }

    /** Read a file's content for the requested line range. */
    default ReadResult read(String filePath, int offset, int limit) { throw new UnsupportedOperationException("read not implemented"); }

    /** Search for a literal text pattern in files. */
    default GrepResult grep(String pattern, String path, String glob, Integer maxCount) { throw new UnsupportedOperationException("grep not implemented"); }

    /** Find files matching a glob pattern. */
    default GlobResult glob(String pattern, String path) { throw new UnsupportedOperationException("glob not implemented"); }

    /** Write content to a file, creating or overwriting it. */
    default WriteResult write(String filePath, String content) { throw new UnsupportedOperationException("write not implemented"); }

    /** Perform exact string replacements in an existing file. */
    default EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) { throw new UnsupportedOperationException("edit not implemented"); }

    /** Delete a path, recursively removing anything nested under it. */
    default DeleteResult delete(String filePath) { throw new UnsupportedOperationException("delete not implemented"); }

    /** Upload multiple files. */
    default List<FileUploadResponse> uploadFiles(List<PathedBytes> files) { throw new UnsupportedOperationException("uploadFiles not implemented"); }

    /** Download multiple files. */
    default List<FileDownloadResponse> downloadFiles(List<String> paths) { throw new UnsupportedOperationException("downloadFiles not implemented"); }

    // -----------------------------------------------------------------
    // Async file operations — default delegates to the sync variants
    // -----------------------------------------------------------------

    default CompletableFuture<LsResult>            als(String path) {
        return CompletableFuture.supplyAsync(() -> ls(path));
    }

    default CompletableFuture<ReadResult>          aread(String filePath, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> read(filePath, offset, limit));
    }

    default CompletableFuture<GrepResult>          agrep(String pattern, String path, String glob, Integer maxCount) {
        return CompletableFuture.supplyAsync(() -> grep(pattern, path, glob, maxCount));
    }

    default CompletableFuture<GlobResult>          aglob(String pattern, String path) {
        return CompletableFuture.supplyAsync(() -> glob(pattern, path));
    }

    default CompletableFuture<WriteResult>         awrite(String filePath, String content) {
        return CompletableFuture.supplyAsync(() -> write(filePath, content));
    }

    default CompletableFuture<EditResult>          aedit(String filePath, String oldString, String newString, boolean replaceAll) {
        return CompletableFuture.supplyAsync(() -> edit(filePath, oldString, newString, replaceAll));
    }

    default CompletableFuture<DeleteResult>        adelete(String filePath) {
        return CompletableFuture.supplyAsync(() -> delete(filePath));
    }

    default CompletableFuture<List<FileUploadResponse>> auploadFiles(List<PathedBytes> files) {
        return CompletableFuture.supplyAsync(() -> uploadFiles(files));
    }

    default CompletableFuture<List<FileDownloadResponse>> adownloadFiles(List<String> paths) {
        return CompletableFuture.supplyAsync(() -> downloadFiles(paths));
    }

    /** Default sync grep used when no {@code maxCount} is provided. */
    default GrepResult grep(String pattern, String path, String glob) {
        return grep(pattern, path, glob, null);
    }

    /** Convenience overload: read with default offset/limit. */
    default ReadResult read(String filePath) {
        return read(filePath, 0, 2000);
    }

    /** Convenience overload: edit with replaceAll=false. */
    default EditResult edit(String filePath, String oldString, String newString) {
        return edit(filePath, oldString, newString, false);
    }

    /** Convenience overload: grep with no path/glob. */
    default GrepResult grep(String pattern) {
        return grep(pattern, null, null, null);
    }

    /** Convenience overload: grep with only a path. */
    default GrepResult grep(String pattern, String path) {
        return grep(pattern, path, null, null);
    }

    /** Async convenience overload: read with default offset/limit. */
    default CompletableFuture<ReadResult> aread(String filePath) {
        return aread(filePath, 0, 2000);
    }

    /** Async convenience overload: edit with replaceAll=false. */
    default CompletableFuture<EditResult> aedit(String filePath, String oldString, String newString) {
        return aedit(filePath, oldString, newString, false);
    }

    /** Async convenience overload: grep with no path/glob. */
    default CompletableFuture<GrepResult> agrep(String pattern) {
        return agrep(pattern, null, null, null);
    }

    /** Async convenience overload: grep with only a path. */
    default CompletableFuture<GrepResult> agrep(String pattern, String path) {
        return agrep(pattern, path, null, null);
    }

    /** Async convenience overload: glob. */
    default CompletableFuture<GlobResult> aglob(String pattern) {
        return aglob(pattern, null);
    }

    /** Convenience overload: glob with no path. */
    default GlobResult glob(String pattern) {
        return glob(pattern, null);
    }

    /**
     * Whether {@code execute(command, Integer timeout)} on this backend's
     * implementation accepts the {@code timeout} parameter. Java's static
     * dispatch means we cannot introspect subclasses like Python's
     * {@code inspect.signature} does, so the default is {@code true}
     * (every Java {@link SandboxBackendProtocol} implementation we ship
     * takes a timeout).
     */
    static boolean executeAcceptsTimeout(Class<? extends BackendProtocol> cls) {
        return true;
    }

    /** Simple (path, content) tuple for upload APIs. */
    record PathedBytes(String path, byte[] content) { }
}
