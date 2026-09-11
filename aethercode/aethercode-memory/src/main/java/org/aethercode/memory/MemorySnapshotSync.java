package org.aethercode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * memory snapshot sync via git tags. Modelled on the TS
 * {@code tools/AgentTool/agentMemorySnapshot.ts} sync hint.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Build a {@link MemorySnapshot} for the local memory dir</li>
 *   <li>Compare to the snapshot stored at {@code refs/aethercode-memory-snapshot}</li>
 *   <li>If different (or absent), write the new snapshot to a temp file, then run
 *       {@code git tag -f aethercode-memory-snapshot <commit>}</li>
 *   <li>Push the tag with {@code git push origin refs/tags/aethercode-memory-snapshot}
 *       (only if {@code AETHERCODE_MEMORY_AUTO_PUSH=1} is set)</li>
 * </ol>
 *
 * <p>The tag carries a payload via the tag's message body, so the remote side can
 * see what changed without checking out the tree. {@link #diff(MemorySnapshot, MemorySnapshot)}
 * returns a human-readable change summary.
 */
public class MemorySnapshotSync {

    private static final Logger LOG = LoggerFactory.getLogger(MemorySnapshotSync.class);
    public static final String TAG_NAME = "aethercode-memory-snapshot";

    private final Path gitRoot;
    private final Path memoryDir;

    public MemorySnapshotSync(Path gitRoot, Path memoryDir) {
        this.gitRoot = gitRoot;
        this.memoryDir = memoryDir;
    }

    public MemorySnapshot buildLocal() {
        return MemorySnapshot.build("<local>", MemoryScope.LOCAL, memoryDir);
    }

    public MemorySnapshot readRemoteTag() {
        Path tagFile = gitRoot.resolve(".git").resolve("refs").resolve("tags").resolve(TAG_NAME);
        if (!Files.exists(tagFile)) return null;
        try {
            String content = Files.readString(tagFile);
            // The tag file contains a commit hash. We then read the snapshot blob
            // from .git/objects via a rev-parse. For R5 we shortcut: read the tag's
            // annotated message via `git tag -l --format`.
            Process p = new ProcessBuilder("git", "-C", gitRoot.toString(),
                    "tag", "-l", "--format=%(contents)", TAG_NAME)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String body;
            try (var in = p.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (body.isEmpty()) return null;
            // Write the body to a temp file and read it back via MemorySnapshot.readFrom.
            Path tmp = Files.createTempFile("aethercode-snap-", ".json");
            Files.writeString(tmp, body);
            try {
                return MemorySnapshot.readFrom(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            LOG.warn("read remote tag failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Push the local snapshot as a git tag. Returns true on success.
     */
    public boolean pushLocal(MemorySnapshot snap) {
        try {
            Path tmp = Files.createTempFile("aethercode-snap-", ".json");
            snap.writeTo(tmp);
            try {
                // Add the snapshot file to the index, commit, then tag.
                runGit("add", tmp.toString());
                runGit("commit", "-m", "memory snapshot: " + snap.agentType + " (" + snap.createdAt + ")");
                runGit("tag", "-f", "-F", tmp.toString(), TAG_NAME);
                if ("1".equals(System.getenv("AETHERCODE_MEMORY_AUTO_PUSH"))) {
                    runGit("push", "origin", "refs/tags/" + TAG_NAME);
                }
                return true;
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            LOG.warn("push local snapshot failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Compute a human-readable diff between two snapshots. Used by the TUI / SDK to
     * tell the user "your local memory is older than the remote snapshot" or
     * "the snapshot is identical".
     */
    public String diff(MemorySnapshot local, MemorySnapshot remote) {
        if (remote == null) return "no remote snapshot (fresh project)";
        if (local.files.equals(remote.files)) return "snapshots match";
        StringBuilder sb = new StringBuilder("changes since last sync:\n");
        for (var e : remote.files.entrySet()) {
            var localEntry = local.files.get(e.getKey());
            if (localEntry == null) {
                sb.append("  + ").append(e.getKey()).append(" (").append(e.getValue().size()).append(" bytes)\n");
            } else if (!localEntry.sha256().equals(e.getValue().sha256())) {
                sb.append("  ~ ").append(e.getKey()).append(" (modified)\n");
            }
        }
        for (var e : local.files.entrySet()) {
            if (!remote.files.containsKey(e.getKey())) {
                sb.append("  - ").append(e.getKey()).append(" (removed)\n");
            }
        }
        return sb.toString().trim();
    }

    private void runGit(String... args) throws IOException, InterruptedException {
        String[] all = new String[args.length + 2];
        all[0] = "git";
        all[1] = "-C";
        all[2] = gitRoot.toString();
        System.arraycopy(args, 0, all, 3, args.length);
        Process p = new ProcessBuilder(all).redirectErrorStream(true).start();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git command timed out");
        }
        if (p.exitValue() != 0) {
            try (var in = p.getInputStream()) {
                throw new IOException("git failed: " + new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
