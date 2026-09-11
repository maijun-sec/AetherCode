package org.aethercode.examples.nvidiadeepagent;

import org.aethercode.backends.BackendProtocol;
import org.aethercode.backends.DeleteResult;
import org.aethercode.backends.EditResult;
import org.aethercode.backends.FileData;
import org.aethercode.backends.FileDownloadResponse;
import org.aethercode.backends.FileInfo;
import org.aethercode.backends.FileUploadResponse;
import org.aethercode.backends.GlobResult;
import org.aethercode.backends.GrepMatch;
import org.aethercode.backends.GrepResult;
import org.aethercode.backends.LsResult;
import org.aethercode.backends.ReadResult;
import org.aethercode.backends.WriteResult;

import java.util.List;
import java.util.Map;

/**
 * Backend configuration for the NVIDIA Deep Agent Skills example.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/nvidia_deep_agent/src/backend.py}.
 * The Python port creates a {@code ModalSandbox} with NVIDIA RAPIDS
 * and uploads skills + memory on creation. The Java port does not
 * depend on {@code modal} or {@code langchain-modal}; it provides
 * an illustrative {@link SandboxBackend} interface plus a
 * {@link #createBackend(RuntimeContext)} factory that returns a
 * stub implementation. Plug a real sandbox client into
 * {@link #createBackend(RuntimeContext)} for end-to-end use.</p>
 */
public final class NvidiaBackend {
    private NvidiaBackend() {}

    /** Public name for the modal app / sandbox bucket. */
    public static final String MODAL_SANDBOX_NAME = "nemotron-deep-agent";

    /** RAPIDS Docker image used in GPU mode. */
    public static final String RAPIDS_IMAGE = "nvcr.io/nvidia/rapidsai/base:25.02-cuda12.8-py3.12";

    /** CPU-only image for the {@code sandbox_type=cpu} path. */
    public static final String CPU_IMAGE_TAG = "python:3.12-slim";

    /**
     * Sandbox selection. Mirrors the {@code Context.sandbox_type}
     * field from the Python port's TypedDict.
     */
    public enum SandboxType { GPU, CPU }

    /** Runtime context fed into the backend factory. */
    public record RuntimeContext(SandboxType sandboxType) {
        public RuntimeContext {
            if (sandboxType == null) sandboxType = SandboxType.GPU;
        }
        public static RuntimeContext of(String type) {
            return new RuntimeContext("cpu".equalsIgnoreCase(type)
                    ? SandboxType.CPU : SandboxType.GPU);
        }
    }

    /**
     * Backend for an actual remote sandbox. The Java port ships a
     * minimal stub implementation; replace with a real sandbox
     * client (Modal, Daytona, agentcore, ...) to run end-to-end.
     */
    public interface SandboxBackend extends BackendProtocol {
        /** Upload one (virtual path, content-bytes) tuple. */
        List<FileUploadResponse> uploadFilesToSandbox(List<NvidiaBackend.PathedBytes> files);
        /** Execute a shell command inside the sandbox. */
        String execute(String command, Map<String, String> env, int timeoutSeconds);
    }

    /**
     * Default stub sandbox backend used when no real client is
     * wired up. Implements {@link BackendProtocol} with empty
     * results so the agent assembly path is exercised even
     * without a remote sandbox.
     */
    public static class StubSandboxBackend implements SandboxBackend {
        private final String label;

        public StubSandboxBackend(String label) {
            this.label = label;
        }

        @Override public LsResult ls(String path) { return LsResult.of(List.of()); }
        @Override public ReadResult read(String filePath, int offset, int limit) {
            return ReadResult.empty();
        }
        @Override public WriteResult write(String filePath, String content) {
            return WriteResult.success(filePath);
        }
        @Override public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
            return EditResult.success(filePath, 1);
        }
        @Override public DeleteResult delete(String filePath) {
            return DeleteResult.success(filePath);
        }
        @Override public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
            return GrepResult.of(List.of());
        }
        @Override public GlobResult glob(String pattern, String path) {
            return GlobResult.of(List.of());
        }
        @Override public List<FileUploadResponse> uploadFiles(List<PathedBytes> files) {
            return files.stream()
                    .map(f -> FileUploadResponse.success(f.path()))
                    .toList();
        }
        @Override public List<FileDownloadResponse> downloadFiles(List<String> paths) {
            return paths.stream()
                    .map(p -> FileDownloadResponse.success(p, new byte[0]))
                    .toList();
        }
        @Override public List<FileUploadResponse> uploadFilesToSandbox(List<NvidiaBackend.PathedBytes> files) {
            // Convert our PathedBytes to BackendProtocol.PathedBytes for
            // the inherited uploadFiles method, then map the result.
            return uploadFiles(files.stream()
                    .map(p -> new org.aethercode.backends.BackendProtocol.PathedBytes(p.path(), p.content()))
                    .toList());
        }
        @Override public String execute(String command, Map<String, String> env, int timeoutSeconds) {
            return "[stub " + label + "] " + command;
        }
    }

    /**
     * Pre-load the local {@code skills/} and {@code AGENTS.md} files
     * into a freshly created sandbox. Mirrors the Python port's
     * {@code _seed_sandbox}.
     */
    public static void seedSandbox(SandboxBackend backend,
                                   java.nio.file.Path skillsDir,
                                   java.nio.file.Path memoryFile) {
        if (skillsDir != null && java.nio.file.Files.isDirectory(skillsDir)) {
            try (var stream = java.nio.file.Files.list(skillsDir)) {
                stream.filter(java.nio.file.Files::isDirectory)
                        .forEach(skillDir -> {
                            java.nio.file.Path skillMd = skillDir.resolve("SKILL.md");
                            if (java.nio.file.Files.isRegularFile(skillMd)) {
                                try {
                                    List<PathedBytes> toUpload = List.of(new PathedBytes(
                                            "/skills/" + skillDir.getFileName() + "/SKILL.md",
                                            java.nio.file.Files.readAllBytes(skillMd)));
                                    backend.uploadFilesToSandbox(toUpload);
                                } catch (java.io.IOException exc) {
                                    // Best-effort upload.
                                }
                            }
                        });
            } catch (java.io.IOException exc) {
                // Skills directory missing — nothing to seed.
            }
        }
        if (memoryFile != null && java.nio.file.Files.isRegularFile(memoryFile)) {
            try {
                List<PathedBytes> toUpload = List.of(new PathedBytes(
                        "/memory/AGENTS.md",
                        java.nio.file.Files.readAllBytes(memoryFile)));
                backend.uploadFilesToSandbox(toUpload);
            } catch (java.io.IOException exc) {
                // Best-effort upload.
            }
        }
    }

    /**
     * Create a backend for the requested runtime. Mirrors the
     * Python port's {@code create_backend(runtime)} function.
     */
    public static BackendProtocol createBackend(RuntimeContext runtime) {
        SandboxType type = runtime.sandboxType();
        String sandboxName = MODAL_SANDBOX_NAME + "-" + (type == SandboxType.GPU ? "gpu" : "cpu");
        // The Java port cannot reach Modal directly. Return a stub
        // labeled with the requested mode; callers wanting the real
        // sandbox should plug in their own client here.
        return new StubSandboxBackend(sandboxName);
    }

    /** (virtual path, content-bytes) tuple. */
    public record PathedBytes(String path, byte[] content) {
        public PathedBytes {
            content = content == null ? new byte[0] : content.clone();
        }
    }
}
