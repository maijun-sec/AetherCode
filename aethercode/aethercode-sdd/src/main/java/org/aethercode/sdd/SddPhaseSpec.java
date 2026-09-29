package org.aethercode.sdd;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * R700 — Static SDD phase specification.
 *
 * <p>Single source of truth for the 8-phase workflow, used by:
 * <ul>
 *   <li>{@link SddBundleLoader} — to look up the phase reference filename
 *       ({@code references/phase-N-<id>.md}) and output artefact filename</li>
 *   <li>{@link SddOrchestrator} — to drive the state machine, decide which
 *       phases are REQUIRED vs OPTIONAL, and enforce the no-skip-on-required
 *       rule</li>
 *   <li>The RPC layer ({@code sdd.*} methods in {@code AetherCodeMethods})
 *       — to surface phase metadata to desktop / TUI clients</li>
 * </ul>
 *
 * <p>Order matters. Phase N reads every {@code phase < N} output as input.
 * The map preserves insertion order so iteration is stable for tests and
 * JSON serialization.
 */
public final class SddPhaseSpec {

    /** IDs match the kebab-case filenames in {@code references/phase-N-<id>.md}
     *  and match the artefact filename where applicable. */
    public enum PhaseId {
        constitution,  // phase 1
        specify,       // phase 2
        clarify,       // phase 3 (optional)
        plan,          // phase 4
        analyze,       // phase 5 (optional)
        tasks,         // phase 6
        implement,     // phase 7
        converge;      // phase 8 (optional)
    }

    /** Map phase number (1-8) → PhaseId. */
    public static final Map<Integer, PhaseId> PHASES = new LinkedHashMap<>();
    static {
        PHASES.put(1, PhaseId.constitution);
        PHASES.put(2, PhaseId.specify);
        PHASES.put(3, PhaseId.clarify);
        PHASES.put(4, PhaseId.plan);
        PHASES.put(5, PhaseId.analyze);
        PHASES.put(6, PhaseId.tasks);
        PHASES.put(7, PhaseId.implement);
        PHASES.put(8, PhaseId.converge);
    }

    /** Phases the user can skip. The orchestrator refuses to skip REQUIRED
     *  phases even when {@code action=skip} arrives. */
    public static final Set<PhaseId> OPTIONAL = Set.of(
            PhaseId.clarify,
            PhaseId.analyze,
            PhaseId.converge);

    /** Output artefact filename for each phase. Phase 7 also writes source
     *  files; the {@code dev.log} is the JSON-tracked artefact. Names are
     *  strictly lowercase per the per-phase reference contract. */
    public static final Map<PhaseId, String> OUTPUT_FILES = new LinkedHashMap<>();
    static {
        OUTPUT_FILES.put(PhaseId.constitution, "constitution.md");
        OUTPUT_FILES.put(PhaseId.specify,      "spec.md");
        OUTPUT_FILES.put(PhaseId.clarify,      "clarify.json");
        OUTPUT_FILES.put(PhaseId.plan,         "design.md");
        OUTPUT_FILES.put(PhaseId.analyze,      "analyze.json");
        OUTPUT_FILES.put(PhaseId.tasks,        "tasks.md");
        OUTPUT_FILES.put(PhaseId.implement,    "dev.log");
        OUTPUT_FILES.put(PhaseId.converge,     "convergence.json");
    }

    /** Bundle resource path for the SKILL.md frontmatter + body.
     *  No leading slash — {@link ClassLoader#getResourceAsStream(String)}
     *  treats leading-slash and no-slash the same, but tests run under
     *  Surefire's classloader behave inconsistently. We standardise on the
     *  no-slash form. */
    public static final String RESOURCE_SKILL = "skills/sdd/SKILL.md";

    /** Bundle resource path for phase reference markdown.
     *  {@code %d} = phase number 1-8; {@code %s} = PhaseId lowercase. */
    public static String resourceForPhaseRef(int phaseNumber) {
        PhaseId id = PHASES.get(phaseNumber);
        if (id == null) {
            throw new IllegalArgumentException("invalid SDD phase number: " + phaseNumber);
        }
        return "skills/sdd/references/phase-" + phaseNumber + "-" + id.name() + ".md";
    }

    /** Bundle resource path for a template file by base name
     *  (e.g. {@code "plan-template"}). */
    public static String resourceForTemplate(String templateBaseName) {
        return "skills/sdd/references/templates/" + templateBaseName + ".md";
    }

    /** Map a PhaseId to its 1-based phase number. Inverse of PHASES. */
    public static int numberOf(PhaseId id) {
        for (var entry : PHASES.entrySet()) {
            if (entry.getValue() == id) return entry.getKey();
        }
        throw new IllegalArgumentException("unknown SDD phase id: " + id);
    }

    /** Chinese display title per phase. The desktop / TUI UIs both render
     *  this in the phase chip strip. */
    public static final Map<PhaseId, String> TITLE_ZH = new LinkedHashMap<>();
    static {
        TITLE_ZH.put(PhaseId.constitution, "项目原则");
        TITLE_ZH.put(PhaseId.specify,      "需求分析");
        TITLE_ZH.put(PhaseId.clarify,      "需求澄清");
        TITLE_ZH.put(PhaseId.plan,         "详细设计");
        TITLE_ZH.put(PhaseId.analyze,      "一致性分析");
        TITLE_ZH.put(PhaseId.tasks,        "任务分析");
        TITLE_ZH.put(PhaseId.implement,    "执行实现");
        TITLE_ZH.put(PhaseId.converge,     "收敛验证");
    }

    public static final int TOTAL_PHASES = PHASES.size();

    private SddPhaseSpec() {}
}