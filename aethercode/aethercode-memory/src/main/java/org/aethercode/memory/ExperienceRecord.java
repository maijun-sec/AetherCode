package org.aethercode.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * R230 (G1): a single experiential memory record.
 *
 * <p>Stored as a markdown file with front-matter; in-memory representation
 * for the Java side. The {@link ExperienceStore} handles the
 * serialisation round-trip.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — stable identifier (UUID).</li>
 *   <li>{@code kind} — see {@link ExperienceKind}.</li>
 *   <li>{@code title} — short, one-line summary used for the manifest.</li>
 *   <li>{@code body} — full markdown body (trajectory / strategy text).</li>
 *   <li>{@code createdAt} — when the record was first stored.</li>
 *   <li>{@code sourceSessionId} — session the experience was extracted from.</li>
 *   <li>{@code sourceQuery} — short paraphrase of the originating query.</li>
 *   <li>{@code sourceOutcome} — {@code success} / {@code failure} / {@code partial}.</li>
 *   <li>{@code utility} — 0..1; grows with {@link #uses}.</li>
 *   <li>{@code uses} — number of recall hits since creation.</li>
 *   <li>{@code tags} — free-form tag list (e.g. {@code "t-maven", "powerShell"}).</li>
 *   <li>{@code links} — IDs of related experiences; future R-series will
 *       promote this into a 2D graph (arXiv:2512.13564 §3.1.2).</li>
 * </ul>
 */
public record ExperienceRecord(
        String id,
        ExperienceKind kind,
        String title,
        String body,
        Instant createdAt,
        String sourceSessionId,
        String sourceQuery,
        String sourceOutcome,
        double utility,
        long uses,
        List<String> tags,
        List<String> links
) {

    public ExperienceRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(body, "body");
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (title == null) title = "";
        if (createdAt == null) createdAt = Instant.now();
        if (sourceOutcome == null) sourceOutcome = "success";
        if (Double.isNaN(utility)) utility = 0.5;
        if (utility < 0) utility = 0; else if (utility > 1) utility = 1;
        if (uses < 0) uses = 0;
        if (tags == null) tags = List.of(); else tags = List.copyOf(tags);
        if (links == null) links = List.of(); else links = List.copyOf(links);
    }

    /** Bump uses and lift utility toward 1. Returns the new record. */
    public ExperienceRecord withUse() {
        long newUses = uses + 1;
        // Asymptotic utility: u' = u + (1 - u) * 0.1 — first hit gives +0.05, tenth gives +0.085
        double newUtil = utility + (1.0 - utility) * 0.1;
        return new ExperienceRecord(id, kind, title, body, createdAt,
                sourceSessionId, sourceQuery, sourceOutcome,
                newUtil, newUses, tags, links);
    }

    public String sourceOutcome() { return sourceOutcome == null ? "success" : sourceOutcome; }
}
