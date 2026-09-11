package org.aethercode.core.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * apply a {@link PatchFile} (parsed by {@link PatchParser})
 * to disk. The applier is strict by default — a hunk whose context
 * lines don't match the file's actual content is rejected with a
 * {@link PatchApplyException}.
 *
 * <p>Supported operations: modify existing files, create new files
 * (when {@code oldPath} is {@code /dev/null}), delete files (when
 * {@code newPath} is {@code /dev/null}).
 */
public final class PatchApplier {

    private final boolean strict;
    private final boolean createBackup;

    public PatchApplier() { this(true, false); }
    public PatchApplier(boolean strict) { this(strict, false); }
    public PatchApplier(boolean strict, boolean createBackup) {
        this.strict = strict;
        this.createBackup = createBackup;
    }

    /** apply a single patch file. */
    public ApplyResult apply(PatchFile file, Path root) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(root, "root");
        Path newFile = resolveAgainstRoot(file.newPath(), root);
        Path oldFile = resolveAgainstRoot(file.oldPath(), root);
        boolean isAdd = "/dev/null".equals(file.oldPath()) || file.oldPath().isEmpty();
        boolean isDelete = "/dev/null".equals(file.newPath()) || file.newPath().isEmpty();

        if (isDelete) {
            try {
                if (Files.exists(oldFile) && createBackup) backup(oldFile);
                Files.deleteIfExists(oldFile);
                return ApplyResult.deleted(file);
            } catch (IOException e) {
                throw new PatchApplyException("failed to delete " + oldFile + ": " + e.getMessage(), e);
            }
        }

        List<String> original = isAdd ? List.of() : readAll(oldFile);
        List<String> updated = applyHunks(original, file.hunks(), strict);

        if (createBackup && Files.exists(newFile)) {
            try { backup(newFile); }
            catch (IOException e) { throw new PatchApplyException("backup failed: " + e.getMessage(), e); }
        }
        try {
            if (newFile.getParent() != null) Files.createDirectories(newFile.getParent());
            // Use \n explicitly so output is portable across OSes
            String content = String.join("\n", updated) + "\n";
            Files.writeString(newFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new PatchApplyException("failed to write " + newFile + ": " + e.getMessage(), e);
        }
        return ApplyResult.written(file, original.size(), updated.size());
    }

    /** apply all patch files in order. */
    public List<ApplyResult> applyAll(List<PatchFile> files, Path root) {
        List<ApplyResult> out = new ArrayList<>();
        for (PatchFile f : files) out.add(apply(f, root));
        return out;
    }

    private static List<String> applyHunks(List<String> original, List<Hunk> hunks, boolean strict) {
        // Each hunk is anchored to a 1-based old line number. We process hunks
        // in old-line order; for each hunk, walk the source matching context
        // lines, then apply additions/removals.
        List<String> result = new ArrayList<>(original);
        // Apply in reverse so earlier hunks' offsets don't shift
        List<Hunk> reversed = new ArrayList<>(hunks);
        java.util.Collections.reverse(reversed);
        for (Hunk h : reversed) {
            applyHunk(result, h, strict);
        }
        return result;
    }

    private static void applyHunk(List<String> source, Hunk hunk, boolean strict) {
        // hunk.oldStart is 1-based; oldStart=0 means "this is a new file"
        if (hunk.oldStart() == 0 && hunk.oldCount() == 0) {
            // New file: every line should be a '+' addition
            List<String> additions = new ArrayList<>();
            for (DiffLine l : hunk.lines()) {
                if (l.kind() != '+') {
                    throw new PatchApplyException(
                            "new-file hunk must contain only '+' lines, got '" + l.kind() + "' at line " + l.oldLineNo());
                }
                additions.add(l.content());
            }
            source.addAll(0, additions);
            return;
        }
        int idx = hunk.oldStart() - 1;
        if (idx < 0 || idx > source.size()) {
            throw new PatchApplyException(
                    "hunk anchor " + hunk.oldStart() + " is out of range (file has " + source.size() + " lines)");
        }
        // Process the hunk line-by-line, mutating the source in place.
        // - context line: verify and advance
        // - removal: verify and remove at ci (don't advance — next line shifts up)
        // - addition: insert at ci
        int ci = idx;
        for (DiffLine l : hunk.lines()) {
            char k = l.kind();
            switch (k) {
                case ' ' -> {
                    if (ci >= source.size() || !source.get(ci).equals(l.content())) {
                        if (strict) {
                            throw new PatchApplyException(
                                    "context mismatch at line " + (ci + 1) + ": expected '" + l.content() + "', got '" + (ci < source.size() ? source.get(ci) : "<eof>") + "'");
                        }
                    }
                    ci++;
                }
                case '-' -> {
                    if (ci >= source.size() || !source.get(ci).equals(l.content())) {
                        if (strict) {
                            throw new PatchApplyException(
                                    "removal mismatch at line " + (ci + 1) + ": expected '" + l.content() + "', got '" + (ci < source.size() ? source.get(ci) : "<eof>") + "'");
                        }
                    }
                    source.remove(ci); // next line shifts up
                }
                case '+' -> source.add(ci++, l.content());
                default -> throw new PatchApplyException("unexpected hunk line kind: '" + k + "'");
            }
        }
    }

    private static Path resolveAgainstRoot(String path, Path root) {
        if (path == null || path.isEmpty() || "/dev/null".equals(path)) {
            return root.resolve(path == null ? "" : path);
        }
        return root.resolve(path).normalize();
    }

    private static List<String> readAll(Path f) {
        if (!Files.exists(f)) {
            throw new PatchApplyException("file does not exist: " + f);
        }
        try { return Files.readAllLines(f, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new PatchApplyException("failed to read " + f + ": " + e.getMessage(), e); }
    }

    private static void backup(Path f) throws IOException {
        Path bak = f.resolveSibling(f.getFileName() + ".bak");
        Files.copy(f, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** per-file apply outcome. */
    public record ApplyResult(String oldPath, String newPath, int beforeLines, int afterLines, boolean deleted) {
        public static ApplyResult written(PatchFile f, int before, int after) {
            return new ApplyResult(f.oldPath(), f.newPath(), before, after, false);
        }
        public static ApplyResult deleted(PatchFile f) {
            return new ApplyResult(f.oldPath(), f.newPath(), -1, -1, true);
        }
    }
}
