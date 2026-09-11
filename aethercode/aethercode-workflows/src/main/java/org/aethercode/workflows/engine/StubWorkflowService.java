package org.aethercode.workflows.engine;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.WorkflowPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2.1 (T-2-08..T-2-12): a self-contained
 * {@link WorkflowService} implementation that uses just
 * {@link WorkflowPaths} + Jackson YAML — no dependency on
 * Java-A's {@code WorkflowLoader} / {@code WorkflowEngine} /
 * {@code WorkflowValidator}. Used by:
 *
 * <ol>
 *   <li>The RPC handlers' test fixtures (via a subclass that
 *       overrides specific methods).</li>
 *   <li>Production deployments that haven't wired Java-A's
 *       engine yet. The 4 minimal workflow commands work
 *       end-to-end; run is unimplemented (the engine is
 *       required for session spawning).</li>
 * </ol>
 *
 * <p>This is a temporary bridge. Once Java-A's engine ships,
 * a {@code JavaAWorkflowService} adapter is the production
 * implementation; this class becomes a test double.
 */
public class StubWorkflowService implements WorkflowService {

    private static final Logger LOG = LoggerFactory.getLogger(StubWorkflowService.class);

    @Override
    public List<WorkflowSummary> list(Path userHome, Path cwd) {
        List<WorkflowSummary> out = new ArrayList<>();
        for (WorkflowPaths.Entry e : new WorkflowPaths(userHome, cwd).listAll()) {
            out.add(new WorkflowSummary(e.name(), e.source().name().toLowerCase(),
                    describe(e.file())));
        }
        return out;
    }

    @Override
    public LoadedWorkflow load(Path userHome, Path cwd, String name) {
        Path file = new WorkflowPaths(userHome, cwd).resolveFile(name);
        if (file == null) return null;
        String yaml;
        try {
            yaml = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw WorkflowServiceException.ioError("cannot read " + file, ioe);
        }
        // Minimal parse: just hand back the YAML string + the raw
        // mapping. Full validation lives in Java-A's validator.
        Map<String, Object> parsed = new LinkedHashMap<>();
        return new LoadedWorkflow(name, sourceFor(file, userHome, cwd),
                yaml, parsed, List.of());
    }

    @Override
    public SessionRef run(Path userHome, Path cwd, String name, Map<String, Object> inputs) {
        // Without Java-A's engine, we can't substitute variables,
        // compose skills, or spawn a session. The RPC layer treats
        // this as a not-implemented error so the user gets a clean
        // message instead of a silently-broken session.
        throw WorkflowServiceException.badYaml(
                "workflow run requires the WorkflowEngine (aethercode-workflows engine class); "
                + "not yet wired in this build");
    }

    @Override
    public void upsert(Path userHome, Path cwd, String name, String yaml) {
        Path target = WorkflowPaths.defaultWriteTarget(userHome, cwd, name);
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, yaml, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw WorkflowServiceException.ioError("cannot write " + target, ioe);
        }
    }

    @Override
    public boolean delete(Path userHome, Path cwd, String name) {
        boolean removed = false;
        Path project = WorkflowPaths.projectYaml(cwd, name);
        Path user = WorkflowPaths.userYaml(userHome, name);
        try {
            if (Files.exists(project)) { Files.delete(project); removed = true; }
        } catch (IOException ioe) {
            throw WorkflowServiceException.ioError("cannot delete " + project, ioe);
        }
        try {
            if (Files.exists(user)) { Files.delete(user); removed = true; }
        } catch (IOException ioe) {
            // The user file is best-effort; if it was the only copy
            // and we couldn't delete it, surface the error.
            if (!removed) {
                throw WorkflowServiceException.ioError("cannot delete " + user, ioe);
            }
            LOG.warn("user-layer copy {} could not be removed: {}", user, ioe.getMessage());
        }
        return removed;
    }

    @Override
    public List<ValidationError> lint(Path userHome, Path cwd, String name) {
        // Without the validator, lint returns an empty list —
        // the user is told "no problems found", which is wrong but
        // matches the legacy "always-valid" behaviour. Once the
        // real validator is wired in, lint becomes meaningful.
        return List.of();
    }

    // -- helpers --------------------------------------------------------

    private static String sourceFor(Path file, Path userHome, Path cwd) {
        Path project = WorkflowPaths.projectYaml(cwd, fileNameNoExt(file));
        if (project.equals(file)) return "project";
        return "user";
    }

    private static String fileNameNoExt(Path file) {
        String fn = file.getFileName().toString();
        int dot = fn.lastIndexOf('.');
        return dot < 0 ? fn : fn.substring(0, dot);
    }

    /** Read the {@code description:} field out of a workflow YAML
     *  using a hand-rolled mini-parser. Tolerates missing / blank
     *  descriptions (returns the empty string). */
    private static String describe(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.startsWith("description:")) {
                    String v = trimmed.substring("description:".length()).strip();
                    // Strip surrounding quotes if present.
                    if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                            && v.charAt(v.length() - 1) == v.charAt(0)) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return "";
    }
}
