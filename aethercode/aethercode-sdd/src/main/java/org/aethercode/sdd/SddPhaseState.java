package org.aethercode.sdd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R700 — Persistent SDD run state.
 *
 * <p>One run = one {@code <sdd-task-preset>} directory under
 * {@code <cwd>/.aethercode/sdd/}. The state for that run lives in
 * {@code <dir>/phase-state.json} and is mirrored here as an immutable record.
 * Every state change calls {@link #save(Path)} which atomically writes the
 * file via {@code Files.writeString(... CREATE, TRUNCATE_EXISTING)}.
 *
 * <h2>Status lifecycle</h2>
 * <pre>
 *   run.status:    running → idle → done         (happy path)
 *                           → aborted             (user abort)
 *                           → failed              (chat/IO failure)
 *
 *   phase.state:   idle → running → done        (approve after artefact written)
 *                          → pending-confirm     (artefact written, awaiting user)
 *                          → skipped             (optional phase, user skipped)
 *                          → failed              (chat/IO failure mid-phase)
 * </pre>
 *
 * <p>{@code pending-confirm} is the state right after a phase writes its
 * artefact and is waiting for the user's approve/modify/skip reply. The
 * orchestrator moves it to {@code done} (or back to {@code running} on modify)
 * when {@link SddOrchestrator#advance} is called.
 *
 * <h2>JSON shape (stable for desktop / TUI consumers)</h2>
 * <pre>{@code
 * {
 *   "slug": "sorting-lib",
 *   "intent": "Build a sorting library",
 *   "cwd": "/path/to/project",
 *   "status": "running",
 *   "currentPhase": 4,
 *   "phases": [
 *     { "id": "constitution", "title": "项目原则", "phaseNumber": 1,
 *       "state": "done", "startedAt": "...", "endedAt": "...",
 *       "path": "/.../constitution.md", "optional": false },
 *     ...
 *   ],
 *   "startedAt": "2026-09-29T...",
 *   "lastUpdatedAt": "2026-09-29T..."
 * }
 * }</pre>
 */
public final class SddPhaseState {

    public enum RunStatus { running, idle, done, aborted, failed }
    public enum PhaseState { idle, running, pending_confirm, done, skipped, failed }

    public record PhaseEntry(
            SddPhaseSpec.PhaseId id,
            String title,
            int phaseNumber,
            PhaseState state,
            String startedAt,    // ISO-8601; nullable when never started
            String endedAt,      // ISO-8601; nullable when not yet ended
            String path,         // absolute path to artefact; nullable
            boolean optional,
            /**
             * R709-E5: per-phase model override. The user
             * supplies this on the {@code sdd.advance(...)}
             * call (or {@code sdd.start(...)} for phase 1) and
             * the orchestrator pins it on the phase entry so a
             * future engine-side chat client can route the
             * actual stream() call to the right provider /
             * model. The orchestrator itself does not switch
             * ChatClient — that's a daemon-side concern (the
             * engine holds the routing table) — but the wire
             * shape is ready: every phase carries its model
             * intent, and {@code phase-state.json} records
             * which model was used for which phase so a
             * forensic read after the fact shows the
             * "opus-4 wrote the constitution, sonnet wrote
             * the implementation" split the user asked for.
             * Null = the daemon's default model.
             */
            String model) {

        public PhaseEntry withState(PhaseState newState) {
            return new PhaseEntry(id, title, phaseNumber, newState,
                    startedAt, endedAt, path, optional, model);
        }

        public PhaseEntry withStartedAt(String iso) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    iso, endedAt, path, optional, model);
        }

        public PhaseEntry withEndedAt(String iso) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    startedAt, iso, path, optional, model);
        }

        public PhaseEntry withPath(String p) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    startedAt, endedAt, p, optional, model);
        }

        /** R709-E5: pin a model on the entry. Null clears it
         *  (falls back to the daemon default). The model is
         *  captured at the moment the user submits
         *  {@code advance(...)} so even if the daemon
         *  default changes mid-run, this phase keeps the
         *  user's explicit choice. */
        public PhaseEntry withModel(String m) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    startedAt, endedAt, path, optional, m);
        }
    }

    // -------- fields (immutable) --------

    public final String slug;
    public final String intent;
    public final String cwd;
    public final RunStatus status;
    public final Integer currentPhase;   // null when status is done / aborted
    public final List<PhaseEntry> phases;
    public final String startedAt;
    public final String lastUpdatedAt;
    /**
     * R709-E3: the user-supplied reason at the moment the run was
     * aborted. Persisted so future sessions reading {@code
     * phase-state.json} can see why a run was abandoned without
     * having to grep daemon logs. {@code null} while the run is
     * still active; once written, never overwritten (a second
     * abort on an already-aborted run is a no-op).
     */
    public final String abortReason;

    private SddPhaseState(String slug,
                          String intent,
                          String cwd,
                          RunStatus status,
                          Integer currentPhase,
                          List<PhaseEntry> phases,
                          String startedAt,
                          String lastUpdatedAt,
                          String abortReason) {
        this.slug = slug;
        this.intent = intent;
        this.cwd = cwd;
        this.status = status;
        this.currentPhase = currentPhase;
        this.phases = List.copyOf(phases);
        this.startedAt = startedAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.abortReason = abortReason;
    }

    // -------- factory + builders --------

    /** Create a fresh state for a brand-new run. All phases start {@code idle}
     *  except phase 1 which starts {@code running}. */
    public static SddPhaseState fresh(String slug, String intent, String cwd) {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(cwd, "cwd");
        List<PhaseEntry> entries = new ArrayList<>();
        for (var e : SddPhaseSpec.PHASES.entrySet()) {
            int n = e.getKey();
            SddPhaseSpec.PhaseId id = e.getValue();
            entries.add(new PhaseEntry(
                    id,
                    SddPhaseSpec.TITLE_ZH.get(id),
                    n,
                    n == 1 ? PhaseState.running : PhaseState.idle,
                    n == 1 ? Instant.now().toString() : null,
                    null,
                    null,
                    SddPhaseSpec.OPTIONAL.contains(id),
                    null));  // R709-E5: model = null = daemon default
        }
        return new SddPhaseState(
                slug, intent, cwd,
                RunStatus.running,
                1,
                entries,
                Instant.now().toString(),
                Instant.now().toString(),
                null);
    }

    public SddPhaseState withCurrentPhase(Integer n) {
        return new SddPhaseState(slug, intent, cwd, status, n, phases, startedAt, Instant.now().toString(), abortReason);
    }

    public SddPhaseState withStatus(RunStatus s) {
        return new SddPhaseState(slug, intent, cwd, s, currentPhase, phases, startedAt, Instant.now().toString(), abortReason);
    }

    public SddPhaseState withPhase(int phaseNumber, PhaseEntry entry) {
        List<PhaseEntry> next = new ArrayList<>(phases.size());
        for (var p : phases) {
            next.add(p.phaseNumber() == phaseNumber ? entry : p);
        }
        return new SddPhaseState(slug, intent, cwd, status, currentPhase, next, startedAt, Instant.now().toString(), abortReason);
    }

    /**
     * R709-E3: pin a non-null reason on the run's terminal state.
     * Subsequent withXxx() calls preserve this value, so an
     * abort-then-mutate sequence still carries the original reason.
     * Idempotent: a second call with a different reason on an
     * already-aborted run keeps the FIRST reason (the user can
     * always re-abort with a different intent to override).
     */
    public SddPhaseState withAbortReason(String reason) {
        if (abortReason != null) return this;
        return new SddPhaseState(slug, intent, cwd, status, currentPhase, phases, startedAt, Instant.now().toString(), reason);
    }

    public PhaseEntry phaseByNumber(int n) {
        for (var p : phases) {
            if (p.phaseNumber() == n) return p;
        }
        throw new SddException(SddException.Code.INVALID_PHASE,
                "phase " + n + " not in state for slug " + slug);
    }

    public PhaseEntry currentPhaseEntry() {
        if (currentPhase == null) {
            throw new SddException(SddException.Code.INVALID_PHASE,
                    "run " + slug + " has no current phase (status=" + status + ")");
        }
        return phaseByNumber(currentPhase);
    }

    // -------- persistence --------

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Persist this state to {@code <cwd>/.aethercode/sdd/<slug>/phase-state.json}. */
    public void save(Path cwdRoot) throws IOException {
        Path dir = cwdRoot.resolve(".aethercode").resolve("sdd").resolve(slug);
        Files.createDirectories(dir);
        Path file = dir.resolve("phase-state.json");
        String json = MAPPER.writeValueAsString(toJsonMap());
        Files.writeString(file, json);
    }

    /** Read state from {@code <cwdRoot>/.aethercode/sdd/<slug>/phase-state.json}. */
    public static SddPhaseState load(Path cwdRoot, String slug) throws IOException {
        Path file = cwdRoot.resolve(".aethercode").resolve("sdd").resolve(slug).resolve("phase-state.json");
        if (!Files.isRegularFile(file)) {
            throw new SddException(SddException.Code.STATE_FILE_MISSING,
                    "phase-state.json not found at " + file);
        }
        String json = Files.readString(file);
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(json, Map.class);
        } catch (IOException parseErr) {
            throw new SddException(SddException.Code.STATE_FILE_CORRUPT,
                    "phase-state.json at " + file + " is corrupt", parseErr);
        }
        return fromJsonMap(root);
    }

    /** Convert to a Jackson-friendly map. PhaseEntry list is flattened so the
     *  JSON shape matches what desktop / TUI consumers already parse. */
    private Map<String, Object> toJsonMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("intent", intent);
        m.put("cwd", cwd);
        m.put("status", status.name());
        m.put("currentPhase", currentPhase);
        m.put("startedAt", startedAt);
        m.put("lastUpdatedAt", lastUpdatedAt);
        // R709-E3: include abortReason in the JSON shape so desktop /
        // TUI consumers can show "why was this run abandoned?".
        // NON_NULL inclusion (see MAPPER config) skips this key when
        // the run is still active, so existing readers see no diff.
        m.put("abortReason", abortReason);
        List<Map<String, Object>> ph = new ArrayList<>();
        for (var p : phases) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", p.id.name());
            e.put("title", p.title);
            e.put("phaseNumber", p.phaseNumber);
            e.put("state", p.state.name());
            e.put("startedAt", p.startedAt);
            e.put("endedAt", p.endedAt);
            e.put("path", p.path);
            e.put("optional", p.optional);
            // R709-E5: per-phase model override. NON_NULL inclusion
            // (MAPPER config) skips this key when the phase used
            // the daemon default — pre-E5 readers see no diff.
            e.put("model", p.model);
            ph.add(e);
        }
        m.put("phases", ph);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static SddPhaseState fromJsonMap(Map<String, Object> root) {
        String slug = (String) root.get("slug");
        String intent = (String) root.get("intent");
        String cwd = (String) root.get("cwd");
        RunStatus status = RunStatus.valueOf((String) root.get("status"));
        Object cp = root.get("currentPhase");
        Integer currentPhase = cp == null ? null : ((Number) cp).intValue();
        List<Map<String, Object>> phList = (List<Map<String, Object>>) root.get("phases");
        List<PhaseEntry> entries = new ArrayList<>();
        for (var e : phList) {
            entries.add(new PhaseEntry(
                    SddPhaseSpec.PhaseId.valueOf((String) e.get("id")),
                    (String) e.get("title"),
                    ((Number) e.get("phaseNumber")).intValue(),
                    PhaseState.valueOf((String) e.get("state")),
                    (String) e.get("startedAt"),
                    (String) e.get("endedAt"),
                    (String) e.get("path"),
                    Boolean.TRUE.equals(e.get("optional")),
                    (String) e.get("model")));  // R709-E5
        }
        return new SddPhaseState(
                slug, intent, cwd, status, currentPhase, entries,
                (String) root.get("startedAt"),
                (String) root.get("lastUpdatedAt"),
                (String) root.get("abortReason"));
    }
}