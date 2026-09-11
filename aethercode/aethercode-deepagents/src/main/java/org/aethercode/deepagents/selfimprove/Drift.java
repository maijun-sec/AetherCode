package org.aethercode.deepagents.selfimprove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R243.3 (O-3): <b>D</b>ynamic <b>R</b>ule <b>I</b>nduction
 * <b>F</b>rom <b>T</b>races — turn high-utility
 * {@link ReasoningUnit}s into a markdown section in a
 * host-managed file (typically {@code AGENTS.md}) that
 * the next session picks up automatically.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link #runOnce(ReasoningBank, Path, DriftConfig)}
 * scans the bank, picks the {@code topKPerKind} units
 * per {@code taskKind} whose stored {@code utility ≥
 * minUtility}, and writes them as a markdown
 * {@code ### {taskKind}} block under a single
 * {@code ## {sectionTitle}} heading. The block is
 * wrapped in {@code <!-- DRIFT:START -->} /
 * {@code <!-- DRIFT:END -->} markers, so the host's
 * hand-written content outside the markers is never
 * touched.
 *
 * <h2>Why opt-in</h2>
 *
 * <p>DRIFT writes to a file the host cares about. The
 * bank is internal state; the markdown file is the
 * assistant's <em>public</em> contract. Putting DRIFT in
 * the hot path would risk corrupting AGENTS.md on every
 * tool call. The opt-in lets the host decide:
 *
 * <ul>
 *   <li>After every N tool calls.</li>
 *   <li>On a cron (e.g. once an hour).</li>
 *   <li>At session end (host-owned, not the bank's
 *       concern).</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Re-running DRIFT on the same bank with the same
 * config produces a deterministic file: same kind order
 * (by utility, then by id), same strategies (deduped by
 * strategy text). The drift writes only the marker
 * block; the rest of the file is read in byte-for-byte.
 *
 * <h2>Failure modes</h2>
 *
 * <p>{@link #runOnce} never throws. A missing file is
 * created; an unreadable file is logged and skipped
 * (DRIFT returns a no-op result). The host should not
 * need a try/catch around the call.
 */
public final class Drift {

    private static final Logger LOG = LoggerFactory.getLogger(Drift.class);

    private Drift() {}

    /**
     * Run DRIFT once. Reads {@code agentsMdPath} (or
     * creates an empty file if missing), scans the bank,
     * and rewrites the marker block. Never throws.
     *
     * @param bank         the strategy bank to scan.
     * @param agentsMdPath the markdown file to update.
     *                     Created (with an empty file) if
     *                     it does not exist.
     * @param config       drift thresholds and markers.
     * @return what DRIFT did, suitable for logging.
     */
    public static DriftResult runOnce(ReasoningBank bank,
                                       Path agentsMdPath,
                                       DriftConfig config) {
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(agentsMdPath, "agentsMdPath");
        Objects.requireNonNull(config, "config");
        List<ReasoningUnit> allUnits = bank.all();
        if (allUnits.isEmpty()) {
            return DriftResult.noop(0, 0, agentsMdPath.toString());
        }
        // 1. Filter by utility threshold and select top-K
        //    per kind. The bank's stored utility is
        //    decay-aware *only if* the host has run
        //    decayPass; DRIFT itself does not run decay
        //    (decomposition of concerns).
        //
        //    R245.4 (O-3 + O-6): the ranking now uses
        //    {@code confidence × utility} instead of bare
        //    {@code utility}. This matches the live
        //    {@link BankRecallMiddleware#recallAllKinds}
        //    ranking (R244.1) so a unit that the agent
        //    has actually followed-and-tested outranks a
        //    unit of the same utility that has never been
        //    observed. Without this alignment, a unit
        //    promoted to AGENTS.md by DRIFT could be
        //    ranked below a different unit when the next
        //    session recalls it — confusing for the
        //    human reading AGENTS.md.
        Map<String, List<ReasoningUnit>> byKind = new TreeMap<>();
        int below = 0;
        for (ReasoningUnit u : allUnits) {
            if (u.utility() < config.minUtility()) {
                below++;
                continue;
            }
            byKind.computeIfAbsent(u.taskKind(), k -> new ArrayList<>()).add(u);
        }
        for (List<ReasoningUnit> list : byKind.values()) {
            // Two-factor score: importance (utility) × trust
            // (Laplace-smoothed confidence from R244.1).
            // Same comparator as BankRecallMiddleware so the
            // user sees the same "best units" in AGENTS.md
            // and in the live recall list.
            list.sort(Comparator
                    .comparingDouble((ReasoningUnit u) -> u.confidence() * u.utility()).reversed()
                    .thenComparing(Comparator.comparingLong(ReasoningUnit::uses).reversed())
                    .thenComparing(Comparator.comparing(ReasoningUnit::id)));
            if (list.size() > config.topKPerKind()) {
                list.subList(config.topKPerKind(), list.size()).clear();
            }
        }
        if (byKind.isEmpty()) {
            return DriftResult.noop(allUnits.size(), below, agentsMdPath.toString());
        }
        // 2. Render the new DRIFT block.
        String newBlock = renderBlock(config, byKind);
        // 3. Read the existing file (creating empty if
        //    missing). Locate the marker block; replace or
        //    append.
        String original = readAllUtf8(agentsMdPath);
        String updated = applyBlock(original, config, newBlock);
        // 4. Write back atomically (tmp + rename). Skip
        //    write if content unchanged.
        if (updated.equals(original)) {
            return new DriftResult(allUnits.size(), below,
                    byKind.values().stream().mapToInt(List::size).sum(),
                    List.copyOf(byKind.keySet()),
                    agentsMdPath.toString(), false);
        }
        try {
            writeUtf8Atomic(agentsMdPath, updated);
        } catch (IOException e) {
            LOG.warn("drift: failed to write {}: {}", agentsMdPath, e.getMessage());
            return DriftResult.noop(allUnits.size(), below, agentsMdPath.toString());
        }
        int written = byKind.values().stream().mapToInt(List::size).sum();
        LOG.info("drift: wrote {} units across {} kind(s) to {}",
                written, byKind.size(), agentsMdPath);
        return new DriftResult(allUnits.size(), below, written,
                List.copyOf(byKind.keySet()),
                agentsMdPath.toString(), true);
    }

    // -----------------------------------------------------------------
    //  Rendering
    // -----------------------------------------------------------------

    static String renderBlock(DriftConfig config,
                                Map<String, List<ReasoningUnit>> byKind) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.startMarker()).append('\n');
        sb.append("## ").append(config.sectionTitle()).append('\n');
        sb.append('\n');
        for (Map.Entry<String, List<ReasoningUnit>> e : byKind.entrySet()) {
            sb.append("### ").append(e.getKey()).append('\n');
            for (ReasoningUnit u : e.getValue()) {
                String strategy = u.fixStrategy();
                if (strategy == null || strategy.isBlank()) continue;
                sb.append("- ").append(strategy.trim()).append('\n');
            }
            sb.append('\n');
        }
        sb.append(config.endMarker()).append('\n');
        return sb.toString();
    }

    /**
     * Apply {@code newBlock} to {@code original}. If the
     * markers exist, replace the content between them;
     * otherwise append the block at the end. Returns
     * {@code original} unchanged when nothing would change
     * (e.g. the existing block is byte-identical).
     */
    static String applyBlock(String original, DriftConfig config, String newBlock) {
        Pattern p = Pattern.compile(
                Pattern.quote(config.startMarker()) + ".*?"
                        + Pattern.quote(config.endMarker()) + "\\R?",
                Pattern.DOTALL);
        Matcher m = p.matcher(original);
        if (m.find()) {
            // Preserve a trailing newline if the
            // surrounding context had one; the rendered
            // block always ends with endMarker + '\n'.
            String prefix = original.substring(0, m.start());
            String suffix = original.substring(m.end());
            // If the original ended with a newline and
            // the new block already supplies one, drop
            // the duplicate.
            String cleanedPrefix = prefix.endsWith("\n") || prefix.isEmpty()
                    ? prefix
                    : prefix + "\n";
            return cleanedPrefix + newBlock + suffix;
        }
        // No marker — append. If the file is non-empty
        // and doesn't already end with a newline, add
        // one so the marker block doesn't fuse with the
        // last host line.
        if (original.isEmpty()) {
            return newBlock;
        }
        String sep = original.endsWith("\n") ? "" : "\n";
        return original + sep + "\n" + newBlock;
    }

    // -----------------------------------------------------------------
    //  IO helpers
    // -----------------------------------------------------------------

    private static String readAllUtf8(Path path) {
        try {
            if (!Files.exists(path)) return "";
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("drift: failed to read {}: {}", path, e.getMessage());
            return "";
        }
    }

    private static void writeUtf8Atomic(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, "drift-", ".md.tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
        }
    }
}
