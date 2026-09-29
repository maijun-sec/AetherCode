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
            boolean optional) {

        public PhaseEntry withState(PhaseState newState) {
            return new PhaseEntry(id, title, phaseNumber, newState,
                    startedAt, endedAt, path, optional);
        }

        public PhaseEntry withStartedAt(String iso) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    iso, endedAt, path, optional);
        }

        public PhaseEntry withEndedAt(String iso) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    startedAt, iso, path, optional);
        }

        public PhaseEntry withPath(String p) {
            return new PhaseEntry(id, title, phaseNumber, state,
                    startedAt, endedAt, p, optional);
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

    private SddPhaseState(String slug,
                          String intent,
                          String cwd,
                          RunStatus status,
                          Integer currentPhase,
                          List<PhaseEntry> phases,
                          String startedAt,
                          String lastUpdatedAt) {
        this.slug = slug;
        this.intent = intent;
        this.cwd = cwd;
        this.status = status;
        this.currentPhase = currentPhase;
        this.phases = List.copyOf(phases);
        this.startedAt = startedAt;
        this.lastUpdatedAt = lastUpdatedAt;
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
                    SddPhaseSpec.OPTIONAL.contains(id)));
        }
        return new SddPhaseState(
                slug, intent, cwd,
                RunStatus.running,
                1,
                entries,
                Instant.now().toString(),
                Instant.now().toString());
    }

    public SddPhaseState withCurrentPhase(Integer n) {
        return new SddPhaseState(slug, intent, cwd, status, n, phases, startedAt, Instant.now().toString());
    }

    public SddPhaseState withStatus(RunStatus s) {
        return new SddPhaseState(slug, intent, cwd, s, currentPhase, phases, startedAt, Instant.now().toString());
    }

    public SddPhaseState withPhase(int phaseNumber, PhaseEntry entry) {
        List<PhaseEntry> next = new ArrayList<>(phases.size());
        for (var p : phases) {
            next.add(p.phaseNumber() == phaseNumber ? entry : p);
        }
        return new SddPhaseState(slug, intent, cwd, status, currentPhase, next, startedAt, Instant.now().toString());
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
                    Boolean.TRUE.equals(e.get("optional"))));
        }
        return new SddPhaseState(
                slug, intent, cwd, status, currentPhase, entries,
                (String) root.get("startedAt"),
                (String) root.get("lastUpdatedAt"));
    }
}