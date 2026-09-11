package org.aethercode.core.fs.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for shell-driven {@link SandboxBackendProtocol} implementations.
 *
 * <p>Java-native port of deepagents <code>BaseSandbox</code>. All file
 * operations route through the abstract {@link #execute(String, Integer)}
 * primitive; concrete subclasses add a way to call a remote shell
 * (LangSmith, Daytona, Morph, etc.).</p>
 *
 * <p>The reference scripts (ls/read/grep/glob/edit) are inlined as Java
 * text blocks and shipped to the remote shell via {@code python3 -c "..."}
 * the same way the Python port does. The output parsers are the same
 * shape; only the JSON-parsing surface is rewritten using
 * {@link MiniJson} to avoid pulling in Jackson.</p>
 */
public abstract class BaseSandbox implements SandboxBackendProtocol {

    private static final Logger log = Logger.getLogger(BaseSandbox.class.getName());

    public static final int ASYNC_GREP_TIMEOUT = (2 * FilesystemBackend.DEFAULT_GREP_TIMEOUT) + 5;
    public static final int _EDIT_INLINE_MAX_BYTES = 4 * 1024;
    public static final int _EXECUTE_CAPTURE_MAX_BYTES = 10 * 1024 * 1024;
    public static final int _EXECUTE_CAPTURE_HEAD_LINES = 5;
    public static final int _EXECUTE_CAPTURE_TAIL_LINES = 5;
    public static final int _EXECUTE_CAPTURE_HEAD_BYTES = 2000;
    public static final int _EXECUTE_CAPTURE_TAIL_BYTES = 2000;
    public static final String _EXECUTE_CAPTURE_SENTINEL = "__DEEPAGENTS_EXEC_META__";

    /** Maximum size of a binary file returned by {@code read()} as base64. */
    public static final int MAX_BINARY_BYTES = 500 * 1024;

    /** Maximum size of rendered text content returned by {@code read()}. */
    public static final int MAX_OUTPUT_BYTES = 500 * 1024;

    /** Sentinel appended to {@code read()} content when {@link #MAX_OUTPUT_BYTES} is hit. */
    public static final String TRUNCATION_MSG = "\n\n[Output was truncated due to size limits. "
            + "This paginated read result exceeded the sandbox stdout limit. "
            + "Continue reading with a larger offset or smaller limit to inspect the rest of the file.]";

    /**
     * Whether {@code FilesystemMiddleware} may use capture-at-source offload
     * for {@code execute}. Subclasses known to be compatible set this to
     * {@code true}; the default is opt-in because the capture wrapper has
     * shell/coreutils assumptions.
     */
    public boolean enableCaptureOffload = false;

    // -----------------------------------------------------------------
    //  abstract surface
    // -----------------------------------------------------------------

    @Override
    public abstract String id();

    @Override
    public abstract List<FileUploadResponse> uploadFiles(List<PathedBytes> files);

    @Override
    public abstract List<FileDownloadResponse> downloadFiles(List<String> paths);

    // -----------------------------------------------------------------
    //  execute with offload
    // -----------------------------------------------------------------

    public ExecuteOffloadResult executeWithOffload(String command, String capturePath,
                                                   int maxInlineBytes,
                                                   Integer maxCaptureBytes,
                                                   Integer timeout) {
        int cap = maxCaptureBytes != null ? maxCaptureBytes : _EXECUTE_CAPTURE_MAX_BYTES;
        boolean useTimeout = timeout != null && BackendProtocol.executeAcceptsTimeout(this.getClass());
        if (!enableCaptureOffload) {
            ExecuteResponse r = useTimeout ? execute(command, timeout) : execute(command);
            return new ExecuteOffloadResult(false, r);
        }
        String wrapper = SandboxCmds.buildCaptureExecuteCmd(command, capturePath, maxInlineBytes, cap);
        ExecuteResponse r = useTimeout ? execute(wrapper, timeout) : execute(wrapper);
        return SandboxCmds.parseCaptureExecuteOutput(r.output(), r.truncated());
    }

    public CompletableFuture<ExecuteOffloadResult> aexecuteWithOffload(String command, String capturePath,
                                                                       int maxInlineBytes,
                                                                       Integer maxCaptureBytes,
                                                                       Integer timeout) {
        return CompletableFuture.supplyAsync(() ->
                executeWithOffload(command, capturePath, maxInlineBytes, maxCaptureBytes, timeout));
    }

    // -----------------------------------------------------------------
    //  ls
    // -----------------------------------------------------------------

    @Override
    public LsResult ls(String path) {
        ExecuteResponse r = execute(SandboxCmds.buildLsCmd(path));
        return SandboxCmds.parseLsOutput(r.output(), path);
    }

    public CompletableFuture<LsResult> als(String path) {
        return aexecute(SandboxCmds.buildLsCmd(path)).thenApply(r -> SandboxCmds.parseLsOutput(r.output(), path));
    }

    // -----------------------------------------------------------------
    //  read
    // -----------------------------------------------------------------

    @Override
    public ReadResult read(String filePath, int offset, int limit) {
        ExecuteResponse r = execute(SandboxCmds.buildReadCmd(filePath, offset, limit));
        return SandboxCmds.parseReadOutput(r.output(), filePath);
    }

    @Override
    public CompletableFuture<ReadResult> aread(String filePath, int offset, int limit) {
        return aexecute(SandboxCmds.buildReadCmd(filePath, offset, limit))
                .thenApply(r -> SandboxCmds.parseReadOutput(r.output(), filePath));
    }

    // -----------------------------------------------------------------
    //  write (preflight + upload)
    // -----------------------------------------------------------------

    protected WriteResult writePreflight(String filePath) {
        ExecuteResponse r = execute(SandboxCmds.buildWritePreflightCmd(filePath));
        return SandboxCmds.checkPreflightResult(r, filePath);
    }

    protected CompletableFuture<WriteResult> awritePreflight(String filePath) {
        return aexecute(SandboxCmds.buildWritePreflightCmd(filePath))
                .thenApply(r -> SandboxCmds.checkPreflightResult(r, filePath));
    }

    @Override
    public WriteResult write(String filePath, String content) {
        WriteResult pre = writePreflight(filePath);
        if (pre != null) return pre;
        List<FileUploadResponse> responses = uploadFiles(List.of(
                new PathedBytes(filePath, content.getBytes(StandardCharsets.UTF_8))));
        if (responses.isEmpty()) {
            throw new AssertionError("uploadFiles returned no response for " + filePath);
        }
        FileUploadResponse r = responses.get(0);
        if (r.error().isPresent()) {
            return WriteResult.failure("Failed to write file '" + filePath + "': " + r.error().get());
        }
        return WriteResult.success(filePath);
    }

    @Override
    public CompletableFuture<WriteResult> awrite(String filePath, String content) {
        return aexecute(SandboxCmds.buildWritePreflightCmd(filePath))
                .thenCompose(r -> {
                    WriteResult pre = SandboxCmds.checkPreflightResult(r, filePath);
                    if (pre != null) return CompletableFuture.completedFuture(pre);
                    return auploadFiles(List.of(new PathedBytes(filePath, content.getBytes(StandardCharsets.UTF_8))))
                            .thenApply(responses -> {
                                if (responses.isEmpty()) {
                                    throw new AssertionError("auploadFiles returned no response for " + filePath);
                                }
                                FileUploadResponse ru = responses.get(0);
                                if (ru.error().isPresent()) {
                                    return WriteResult.failure("Failed to write file '" + filePath + "': " + ru.error().get());
                                }
                                return WriteResult.success(filePath);
                            });
                });
    }

    // -----------------------------------------------------------------
    //  edit (inline or via upload)
    // -----------------------------------------------------------------

    @Override
    public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
        int payloadSize = oldString.getBytes(StandardCharsets.UTF_8).length
                + newString.getBytes(StandardCharsets.UTF_8).length;
        if (payloadSize <= _EDIT_INLINE_MAX_BYTES) {
            return editInline(filePath, oldString, newString, replaceAll);
        }
        return editViaUpload(filePath, oldString, newString, replaceAll);
    }

    @Override
    public CompletableFuture<EditResult> aedit(String filePath, String oldString, String newString, boolean replaceAll) {
        return CompletableFuture.supplyAsync(() -> edit(filePath, oldString, newString, replaceAll));
    }

    private EditResult editInline(String filePath, String oldString, String newString, boolean replaceAll) {
        ExecuteResponse r = execute(SandboxCmds.buildEditInlineCmd(filePath, oldString, newString, replaceAll));
        return SandboxCmds.parseEditOutput(r.output(), filePath, oldString);
    }

    private EditResult editViaUpload(String filePath, String oldString, String newString, boolean replaceAll) {
        String uid = SandboxCmds.randomEditUid();
        String oldTmp = "/tmp/.deepagents_edit_" + uid + "_old";
        String newTmp = "/tmp/.deepagents_edit_" + uid + "_new";
        List<FileUploadResponse> resps = uploadFiles(List.of(
                new PathedBytes(oldTmp, oldString.getBytes(StandardCharsets.UTF_8)),
                new PathedBytes(newTmp, newString.getBytes(StandardCharsets.UTF_8))));
        if (resps.size() < 2) {
            return EditResult.failure("Error editing file '" + filePath + "': upload returned no response");
        }
        for (FileUploadResponse r : resps) {
            if (r.error().isPresent()) {
                return EditResult.failure("Error editing file '" + filePath + "': " + r.error().get());
            }
        }
        ExecuteResponse r = execute(SandboxCmds.buildEditTmpfileCmd(filePath, oldTmp, newTmp, replaceAll));
        return SandboxCmds.parseEditOutput(r.output(), filePath, oldString, () ->
                execute("rm -f " + SandboxCmds.shQuote(oldTmp) + " " + SandboxCmds.shQuote(newTmp)));
    }

    // -----------------------------------------------------------------
    //  delete
    // -----------------------------------------------------------------

    @Override
    public DeleteResult delete(String filePath) {
        String quoted = SandboxCmds.shQuote(filePath);
        ExecuteResponse probe = execute("test -e " + quoted + " || test -L " + quoted);
        if (probe.exitCode().isPresent() && probe.exitCode().get() != 0) {
            return DeleteResult.failure("Error: '" + filePath + "' not found");
        }
        ExecuteResponse result = execute("rm -rf " + quoted);
        if (result.exitCode().isPresent() && result.exitCode().get() == 0) {
            return DeleteResult.success(filePath);
        }
        return DeleteResult.failure("Error deleting file '" + filePath + "': "
                + (result.output().strip().isEmpty() ? "unknown error" : result.output().strip()));
    }

    // -----------------------------------------------------------------
    //  grep
    // -----------------------------------------------------------------

    @Override
    public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
        ExecuteResponse r = execute(SandboxCmds.buildGrepCmd(pattern, path, glob, maxCount));
        return SandboxCmds.parseGrepOutput(r, path, maxCount);
    }

    @Override
    public CompletableFuture<GrepResult> agrep(String pattern, String path, String glob, Integer maxCount) {
        return aexecute(SandboxCmds.buildGrepCmd(pattern, path, glob, maxCount))
                .thenApply(r -> SandboxCmds.parseGrepOutput(r, path, maxCount));
    }

    // -----------------------------------------------------------------
    //  glob
    // -----------------------------------------------------------------

    @Override
    public GlobResult glob(String pattern, String path) {
        String searchPath = path != null ? path : "/";
        ExecuteResponse r = execute(SandboxCmds.buildGlobCmd(pattern, searchPath));
        return SandboxCmds.parseGlobOutput(r, searchPath);
    }

    @Override
    public CompletableFuture<GlobResult> aglob(String pattern, String path) {
        String searchPath = path != null ? path : "/";
        return aexecute(SandboxCmds.buildGlobCmd(pattern, searchPath))
                .thenApply(r -> SandboxCmds.parseGlobOutput(r, searchPath));
    }
}
