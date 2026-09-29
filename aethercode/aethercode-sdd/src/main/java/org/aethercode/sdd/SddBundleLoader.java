package org.aethercode.sdd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * R700 — Reads the SDD skill bundle out of the daemon jar at startup.
 *
 * <p>The bundle ships as classpath resources under {@code /skills/sdd/}.
 * Loading happens once during daemon boot so the orchestrator never touches
 * the filesystem at LLM-call time (it streams chat + writes artefacts directly
 * to {@code <cwd>/.aethercode/sdd/<slug>/}). This is what makes SDD work on
 * any machine that has the daemon jar — no external {@code .minimax} path
 * is required.
 *
 * <h2>What's loaded</h2>
 * <ul>
 *   <li>{@code SKILL.md} — entry-point + per-phase decision tree</li>
 *   <li>{@code references/phase-N-<id>.md} (N=1..8) — per-phase instructions</li>
 *   <li>{@code references/templates/<name>.md} — markdown templates the LLM
 *       fills in for each phase</li>
 *   <li>{@code references/phase-protocol.md} + {@code upstream-credits.md} —
 *       protocol detail + upstream attribution</li>
 * </ul>
 *
 * <h2>Failure mode</h2>
 * If any resource is missing, {@link #load()} throws {@link SddException}
 * with a clear message. Callers (daemon boot) should log + continue without
 * SDD rather than crash — the rest of the daemon still works.
 */
public final class SddBundleLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SddBundleLoader.class);

    private final String skillBody;
    private final Map<Integer, String> phaseReferences;  // phase number → body
    private final Map<String, String> templates;          // templateBaseName → body
    private final String phaseProtocol;
    private final String upstreamCredits;

    private SddBundleLoader(String skillBody,
                            Map<Integer, String> phaseReferences,
                            Map<String, String> templates,
                            String phaseProtocol,
                            String upstreamCredits) {
        this.skillBody = skillBody;
        this.phaseReferences = Map.copyOf(phaseReferences);
        this.templates = Map.copyOf(templates);
        this.phaseProtocol = phaseProtocol;
        this.upstreamCredits = upstreamCredits;
    }

    /** Test-only factory. Production code uses {@link #load(ClassLoader)}.
     *  Package-private so only {@code org.aethercode.sdd.*} tests can build
     *  a fake bundle without touching the jar resource layout. */
    static SddBundleLoader forTest(String skillBody,
                                    Map<Integer, String> phaseReferences,
                                    Map<String, String> templates,
                                    String phaseProtocol,
                                    String upstreamCredits) {
        return new SddBundleLoader(skillBody, phaseReferences, templates,
                phaseProtocol, upstreamCredits);
    }

    /**
     * Load the SDD bundle from the supplied class loader. The loader is
     * usually {@code Thread.currentThread().getContextClassLoader()} or
     * {@code getClass().getClassLoader()}.
     *
     * @throws SddException if {@code SKILL.md} or any phase reference is
     *         missing. Missing templates are tolerated (logged) but not
     *         missing phase references — those are required for the
     *         orchestrator to function.
     */
    public static SddBundleLoader load(ClassLoader cl) {
        Objects.requireNonNull(cl, "classLoader");
        try {
            String skill = readResource(cl, SddPhaseSpec.RESOURCE_SKILL);
            Map<Integer, String> phaseRefs = new LinkedHashMap<>();
            for (int n = 1; n <= SddPhaseSpec.TOTAL_PHASES; n++) {
                String path = SddPhaseSpec.resourceForPhaseRef(n);
                phaseRefs.put(n, readResource(cl, path));
            }
            Map<String, String> tpls = new LinkedHashMap<>();
            // Templates the orchestrator may need: constitution / specify / plan / tasks.
            // The _fallback.md is for the LLM's own recovery path — we don't preload it.
            String[] templateNames = {"constitution-template", "specify-template", "plan-template", "tasks-template"};
            for (String name : templateNames) {
                String path = SddPhaseSpec.resourceForTemplate(name);
                try {
                    tpls.put(name, readResource(cl, path));
                } catch (SddException e) {
                    LOG.warn("[sdd] template missing (continuing): {}", path);
                }
            }
            String protocol = readResource(cl, "skills/sdd/references/phase-protocol.md");
            String credits  = readResource(cl, "skills/sdd/references/upstream-credits.md");
            return new SddBundleLoader(skill, phaseRefs, tpls, protocol, credits);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.BUNDLE_MISSING, "failed to read SDD bundle from classpath", ioe);
        }
    }

    /** Convenience overload using the thread context class loader. */
    public static SddBundleLoader load() {
        return load(Thread.currentThread().getContextClassLoader());
    }

    private static String readResource(ClassLoader cl, String path) throws IOException {
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new SddException(SddException.Code.BUNDLE_MISSING,
                        "SDD bundle resource missing: " + path);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) {
                    sb.append(l).append('\n');
                }
                return sb.toString();
            }
        }
    }

    // -------- accessors --------

    /** Body of {@code SKILL.md} (frontmatter stripped). */
    public String skillBody() { return skillBody; }

    /** Body of {@code references/phase-N-<id>.md} for the given phase number. */
    public String phaseReference(int phaseNumber) {
        String body = phaseReferences.get(phaseNumber);
        if (body == null) {
            throw new IllegalArgumentException("no phase reference for phase " + phaseNumber);
        }
        return body;
    }

    /** Body of a template file by base name (e.g. {@code "plan-template"}). */
    public String template(String templateBaseName) {
        String body = templates.get(templateBaseName);
        if (body == null) {
            throw new IllegalArgumentException("no template loaded for " + templateBaseName);
        }
        return body;
    }

    public String phaseProtocol() { return phaseProtocol; }
    public String upstreamCredits() { return upstreamCredits; }

    /** True when {@link #load()} found the {@code SKILL.md} (i.e. the daemon jar
     *  contains the SDD bundle). Always true after a successful {@link #load()}
     *  call; only useful for callers that wrap loader failures as warnings. */
    public boolean isLoaded() { return skillBody != null && !skillBody.isEmpty(); }
}