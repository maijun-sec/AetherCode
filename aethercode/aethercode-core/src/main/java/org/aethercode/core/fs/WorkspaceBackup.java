package org.aethercode.core.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * a directory snapshotter. Walks a root path, copies every
 * file into a backup directory under a generated name, and records
 * a {@link Manifest} of what was copied. Symlinks are NOT followed
 * (so a circular structure doesn't crash the walker). Hidden
 * files and {@code .git} are excluded by default.
 */
public class WorkspaceBackup {

    public record Manifest(
            String backupName,
            Path sourceRoot,
            Path backupRoot,
            long timestampMs,
            int fileCount,
            long totalBytes
    ) {}

    private final Path sourceRoot;
    private final Path backupRoot;
    private final String backupName;
    private final boolean includeHidden;
    private final boolean includeGit;
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong fileCount = new AtomicLong();

    public WorkspaceBackup(Path sourceRoot, Path backupRoot, String backupName) {
        this(sourceRoot, backupRoot, backupName, false, false);
    }

    public WorkspaceBackup(Path sourceRoot, Path backupRoot, String backupName, boolean includeHidden, boolean includeGit) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(backupRoot, "backupRoot");
        if (backupName == null || backupName.isBlank()) throw new IllegalArgumentException("backupName");
        this.sourceRoot = sourceRoot;
        this.backupRoot = backupRoot;
        this.backupName = backupName;
        this.includeHidden = includeHidden;
        this.includeGit = includeGit;
    }

    public Manifest snapshot() throws IOException {
        if (!Files.exists(sourceRoot)) throw new IOException("source does not exist: " + sourceRoot);
        Path target = backupRoot.resolve(backupName);
        Files.createDirectories(target);
        walk(sourceRoot, target);
        Manifest m = new Manifest(backupName, sourceRoot, target,
                System.currentTimeMillis(), (int) fileCount.get(), totalBytes.get());
        writeManifest(m, target);
        return m;
    }

    private void walk(Path src, Path dst) throws IOException {
        try (Stream<Path> stream = Files.list(src)) {
            List<Path> children = stream.toList();
            for (Path child : children) {
                String name = child.getFileName().toString();
                // .git is excluded unless explicitly included; other hidden files only if allowed
                if (name.equals(".git")) {
                    if (!includeGit) continue;
                } else if (name.startsWith(".")) {
                    if (!includeHidden) continue;
                }
                Path target = dst.resolve(name);
                try {
                    if (Files.isDirectory(child)) {
                        Files.createDirectories(target);
                        try { walk(child, target); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    } else if (Files.isRegularFile(child)) {
                        Files.copy(child, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        totalBytes.addAndGet(Files.size(target));
                        fileCount.incrementAndGet();
                    }
                } catch (IOException e) {
                    throw new RuntimeException("failed to copy " + child + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private void writeManifest(Manifest m, Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("backup_name: ").append(m.backupName()).append("\n");
        sb.append("source_root: ").append(m.sourceRoot()).append("\n");
        sb.append("backup_root: ").append(m.backupRoot()).append("\n");
        sb.append("timestamp: ").append(m.timestampMs()).append("\n");
        sb.append("file_count: ").append(m.fileCount()).append("\n");
        sb.append("total_bytes: ").append(m.totalBytes()).append("\n");
        Files.writeString(dir.resolve("MANIFEST"), sb.toString(), StandardCharsets.UTF_8);
    }

    /** list the files that would be backed up, without copying them. */
    public List<Path> listFiles() throws IOException {
        if (!Files.exists(sourceRoot)) return List.of();
        java.util.List<Path> out = new java.util.ArrayList<>();
        collect(sourceRoot, out);
        return out;
    }

    private void collect(Path src, java.util.List<Path> out) throws IOException {
        try (Stream<Path> stream = Files.list(src)) {
            stream.forEach(child -> {
                String name = child.getFileName().toString();
                if (!includeHidden && name.startsWith(".")) return;
                if (!includeGit && name.equals(".git")) return;
                if (Files.isDirectory(child)) {
                    try { collect(child, out); } catch (IOException ignored) {}
                } else if (Files.isRegularFile(child)) {
                    out.add(child);
                }
            });
        }
    }

    public Manifest manifestOf(Path backupDir) throws IOException {
        Path m = backupDir.resolve("MANIFEST");
        if (!Files.exists(m)) throw new IOException("not a backup: " + backupDir);
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : Files.readAllLines(m, StandardCharsets.UTF_8)) {
            int i = line.indexOf(':');
            if (i > 0) kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        return new Manifest(
                kv.getOrDefault("backup_name", ""),
                Path.of(kv.getOrDefault("source_root", "")),
                Path.of(kv.getOrDefault("backup_root", "")),
                Long.parseLong(kv.getOrDefault("timestamp", "0")),
                Integer.parseInt(kv.getOrDefault("file_count", "0")),
                Long.parseLong(kv.getOrDefault("total_bytes", "0"))
        );
    }
}
