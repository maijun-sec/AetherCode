package org.aethercode.core.fs.backend;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Backend that stores files in the agent's working state (ephemeral).
 *
 * <p>Java-native port of deepagents <code>StateBackend</code>. Reads
 * and writes go through the {@link FilesStateStore} provided at
 * construction time. The default in-memory backing is convenient for
 * tests; the graph runtime can plug a channel-bound store so files
 * persist across steps and are checkpointed with the run.</p>
 *
 * <p>Files persist within a thread but not across threads. The files
 * map is automatically checkpointed after each step when a checkpointer
 * is configured.</p>
 */
public class StateBackend implements BackendProtocol {

    private final FilesStateStore store;

    public StateBackend() {
        this(new InMemoryFilesStateStore());
    }

    public StateBackend(FilesStateStore store) {
        this.store = store;
    }

    // -----------------------------------------------------------------
    // ls
    // -----------------------------------------------------------------

    @Override
    public LsResult ls(String path) {
        Map<String, FileData> files = store.read(true);
        List<FileInfo> infos = new ArrayList<>();
        TreeSet<String> subdirs = new TreeSet<>();

        String normalizedPath = path.endsWith("/") ? path : path + "/";
        for (Map.Entry<String, FileData> e : files.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(normalizedPath)) continue;
            String relative = k.substring(normalizedPath.length());
            if (relative.contains("/")) {
                int slash = relative.indexOf('/');
                String subdirName = relative.substring(0, slash);
                subdirs.add(normalizedPath + subdirName + "/");
            } else {
                int size = e.getValue().content().length();
                infos.add(FileInfo.file(k, size, e.getValue().modifiedAtOpt().orElse("")));
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
        Map<String, FileData> files = store.read(true);
        FileData fileData = files.get(filePath);
        if (fileData == null) {
            return ReadResult.error("File '" + filePath + "' not found");
        }
        if (fileData.isBinary()) {
            return ReadResult.of(fileData);
        }
        return BackendUtils.sliceReadResponse(fileData, offset, limit);
    }

    // -----------------------------------------------------------------
    // write
    // -----------------------------------------------------------------

    @Override
    public WriteResult write(String filePath, String content) {
        Map<String, FileData> files = store.read(true);
        FileData existing = files.get(filePath);
        FileData newFileData = existing != null
                ? BackendUtils.updateFileData(existing, content)
                : BackendUtils.createFileData(content);
        Map<String, FileData> update = new HashMap<>();
        update.put(filePath, newFileData);
        store.send(update);
        return WriteResult.success(filePath);
    }

    // -----------------------------------------------------------------
    // edit
    // -----------------------------------------------------------------

    @Override
    public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
        Map<String, FileData> files = store.read(true);
        FileData fileData = files.get(filePath);
        if (fileData == null) {
            return EditResult.failure("Error: File '" + filePath + "' not found");
        }
        String content = fileData.content();
        BackendUtils.StringReplacementResult result =
                BackendUtils.performStringReplacement(content, oldString, newString, replaceAll);
        if (result.error() != null) {
            return EditResult.failure(result.error());
        }
        FileData updated = BackendUtils.updateFileData(fileData, result.newContent());
        Map<String, FileData> update = new HashMap<>();
        update.put(filePath, updated);
        store.send(update);
        return EditResult.success(filePath, result.occurrences());
    }

    // -----------------------------------------------------------------
    // delete
    // -----------------------------------------------------------------

    @Override
    public DeleteResult delete(String filePath) {
        Map<String, FileData> files = store.read(true);
        String base = filePath.endsWith("/") ? filePath.substring(0, filePath.length() - 1) : filePath;
        String prefix = base + "/";
        List<String> toDelete = new ArrayList<>();
        for (String key : files.keySet()) {
            if (key.equals(base) || key.startsWith(prefix)) {
                toDelete.add(key);
            }
        }
        if (toDelete.isEmpty()) {
            return DeleteResult.failure("Error: File '" + filePath + "' not found");
        }
        Map<String, FileData> update = new HashMap<>();
        for (String k : toDelete) {
            update.put(k, null); // null = deletion
        }
        store.send(update);
        return DeleteResult.success(filePath);
    }

    // -----------------------------------------------------------------
    // grep
    // -----------------------------------------------------------------

    @Override
    public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
        Map<String, FileData> files = store.read(true);
        String p = path == null ? "/" : path;
        GrepResult result = BackendUtils.grepMatchesFromFiles(files, pattern, p, glob, maxCount);
        List<GrepMatch> matches = result.matches().orElse(List.of());
        if (maxCount != null && matches.size() >= maxCount) {
            // Conservative: a follow-up scan with budget+1 confirms whether
            // the cap actually clipped a longer result set.
            List<GrepMatch> extra = BackendUtils.grepMatchesFromFiles(files, pattern, p, glob, maxCount + 1)
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
        Map<String, FileData> files = store.read(true);
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
        Map<String, FileData> existing = store.read(true);
        List<FileUploadResponse> responses = new ArrayList<>();
        Map<String, FileData> update = new HashMap<>();
        for (PathedBytes entry : files) {
            String text;
            try {
                text = new String(entry.content(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                text = Base64.getEncoder().encodeToString(entry.content());
            }
            FileData prev = existing.get(entry.path());
            FileData newFd = prev != null
                    ? BackendUtils.updateFileData(prev, text)
                    : BackendUtils.createFileData(text);
            update.put(entry.path(), newFd);
            responses.add(FileUploadResponse.success(entry.path()));
        }
        if (!update.isEmpty()) store.send(update);
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(List<String> paths) {
        Map<String, FileData> stateFiles = store.read(true);
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String path : paths) {
            FileData fd = stateFiles.get(path);
            if (fd == null) {
                responses.add(FileDownloadResponse.failure(path, FileOperationError.FILE_NOT_FOUND_CODE));
                continue;
            }
            String contentStr = fd.content();
            byte[] bytes;
            if (FileData.ENCODING_UTF8.equals(fd.encoding())) {
                bytes = contentStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                bytes = Base64.getDecoder().decode(contentStr);
            }
            responses.add(FileDownloadResponse.success(path, bytes));
        }
        return responses;
    }

    // -----------------------------------------------------------------
    // not implemented
    // -----------------------------------------------------------------

    /** StateBackend has no shell. */
    public UnsupportedOperationException notShellCapable() {
        return new UnsupportedOperationException(
                "StateBackend is a key-value backend and does not support shell execution. "
                        + "Use SandboxBackendProtocol for execute().");
    }

    /** Helper: read-only access to the current files map (test convenience). */
    public Map<String, FileData> snapshot() {
        return Collections.unmodifiableMap(store.read(true));
    }
}
