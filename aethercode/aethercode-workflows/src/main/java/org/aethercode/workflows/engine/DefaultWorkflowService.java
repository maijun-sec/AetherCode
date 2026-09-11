package org.aethercode.workflows.engine;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.SessionSpawner;
import org.aethercode.workflows.SkillComposer;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.Workflow;
import org.aethercode.workflows.WorkflowEngine;
import org.aethercode.workflows.WorkflowLoader;
import org.aethercode.workflows.WorkflowParserException;
import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.WorkflowValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Default {@link WorkflowService} backed by the engine in
 * {@code org.aethercode.workflows}. The class is split from
 * the engine so the engine itself can stay test-friendly
 * (it takes a {@link SessionSpawner}); the service is the
 * glue the supervisor and CLI use to talk to the engine.
 *
 * <p>The service is stateless after construction; the
 * caller passes {@code userHome} and {@code cwd} to every
 * method, which makes the same instance safe to share
 * across threads.
 */
public final class DefaultWorkflowService implements WorkflowService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkflowService.class);

    private final WorkflowLoader loader;
    private final WorkflowValidator validator;
    private final SessionSpawner spawner;

    public DefaultWorkflowService(WorkflowLoader loader,
                                  WorkflowValidator validator,
                                  SessionSpawner spawner) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    /** Convenience: a service that uses the default loader and
     *  validator, and the given {@link SessionSpawner} for runs. */
    public static DefaultWorkflowService with(SessionSpawner spawner) {
        return new DefaultWorkflowService(
                new WorkflowLoader(),
                new WorkflowValidator(),
                spawner);
    }

    // -- WorkflowService --------------------------------------------------

    @Override
    public List<WorkflowSummary> list(Path userHome, Path cwd) {
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(cwd, "cwd");
        WorkflowPaths paths = new WorkflowPaths(userHome, cwd);
        List<WorkflowPaths.Entry> entries = paths.listAll();
        List<WorkflowSummary> out = new ArrayList<>(entries.size());
        for (WorkflowPaths.Entry e : entries) {
            String description = safeReadDescription(e.file());
            out.add(new WorkflowSummary(e.name(),
                    e.source().name().toLowerCase(Locale.ROOT),
                    description));
        }
        return out;
    }

    @Override
    public LoadedWorkflow load(Path userHome, Path cwd, String name) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(cwd, "cwd");
        WorkflowPaths paths = new WorkflowPaths(userHome, cwd);
        Path file = paths.resolveFile(name);
        if (file == null) {
            // Bundled resource fallback.
            String resource = "workflows/" + name + ".yaml";
            if (loader.getClass().getClassLoader().getResource(resource) == null) {
                return null;
            }
            try {
                Workflow wf = loader.loadResource(resource);
                List<ValidationError> errs = validator.validate(wf);
                return new LoadedWorkflow(name, "bundled", resourceYaml(resource), wf.raw(), errs);
            } catch (WorkflowParserException wpe) {
                return new LoadedWorkflow(name, "bundled", "",
                        Map.of(),
                        List.of(new ValidationError("(root)", wpe.getMessage())));
            }
        }
        String yaml;
        try {
            yaml = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.IO_ERROR,
                    "could not read " + file + ": " + ioe.getMessage(), ioe);
        }
        try {
            Workflow wf = loader.load(file);
            List<ValidationError> errs = validator.validate(wf);
            return new LoadedWorkflow(name,
                    file.getParent().equals(paths.projectDir()) ? "project" : "user",
                    yaml, wf.raw(), errs);
        } catch (WorkflowParserException wpe) {
            // Even when parsing failed, we still return the
            // LoadedWorkflow so the editor can show the user
            // the raw YAML and the parse error inline.
            return new LoadedWorkflow(name,
                    file.getParent().equals(paths.projectDir()) ? "project" : "user",
                    yaml, Map.of(),
                    List.of(new ValidationError("(root)", wpe.getMessage())));
        }
    }

    @Override
    public SessionRef run(Path userHome, Path cwd, String name, Map<String, Object> inputs) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(cwd, "cwd");
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.INVALID_NAME,
                    iae.getMessage(), iae);
        }
        WorkflowEngine engine = new WorkflowEngine(loader, validator,
                SkillComposer.forCwd(cwd), spawner);
        try {
            return engine.run(name, inputs, cwd, null);
        } catch (WorkflowParserException wpe) {
            // Distinguish "not found" from "validation failed".
            String msg = wpe.getMessage();
            if (msg != null && msg.contains("not found")) {
                throw new WorkflowServiceException(WorkflowServiceException.Code.NOT_FOUND, msg, wpe);
            }
            if (msg != null && msg.contains("validation error")) {
                // The engine formats its own list of errors; we
                // can't recover the individual ValidationError
                // objects from the message, so we re-validate
                // here to get them as records.
                List<ValidationError> errs = lintAndExtract(userHome, cwd, name);
                throw new WorkflowServiceException(
                        WorkflowServiceException.Code.VALIDATION_FAILED,
                        msg, errs, wpe);
            }
            throw new WorkflowServiceException(WorkflowServiceException.Code.BAD_YAML, msg, wpe);
        }
    }

    @Override
    public void upsert(Path userHome, Path cwd, String name, String yaml) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(cwd, "cwd");
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.INVALID_NAME,
                    iae.getMessage(), iae);
        }
        // Parse + validate before we touch the disk.
        Workflow wf;
        try {
            wf = loader.loadFromString(yaml);
        } catch (WorkflowParserException wpe) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.BAD_YAML,
                    wpe.getMessage(), wpe);
        }
        if (!wf.name().equals(name)) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.BAD_YAML,
                    "workflow name in YAML body ('" + wf.name()
                            + "') does not match the file name ('" + name + "')");
        }
        List<ValidationError> errs = validator.validate(wf);
        if (!errs.isEmpty()) {
            throw new WorkflowServiceException(
                    WorkflowServiceException.Code.VALIDATION_FAILED,
                    "workflow '" + name + "' has " + errs.size() + " validation error(s)",
                    errs);
        }
        // Choose the project dir when it exists (or can be
        // created); otherwise the user dir.
        Path target = pickTargetDir(userHome, cwd);
        try {
            Files.createDirectories(target);
            Path file = target.resolve(name + WorkflowPaths.WORKFLOW_EXTENSION);
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            LOG.info("workflow '{}' written to {}", name, file);
        } catch (IOException ioe) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.IO_ERROR,
                    "could not write workflow '" + name + "': " + ioe.getMessage(), ioe);
        }
    }

    @Override
    public boolean delete(Path userHome, Path cwd, String name) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(cwd, "cwd");
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.INVALID_NAME,
                    iae.getMessage(), iae);
        }
        WorkflowPaths paths = new WorkflowPaths(userHome, cwd);
        Path file = paths.resolveFile(name);
        if (file == null) return false;
        try {
            return Files.deleteIfExists(file);
        } catch (IOException ioe) {
            throw new WorkflowServiceException(WorkflowServiceException.Code.IO_ERROR,
                    "could not delete workflow '" + name + "': " + ioe.getMessage(), ioe);
        }
    }

    @Override
    public List<ValidationError> lint(Path userHome, Path cwd, String name) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(cwd, "cwd");
        return lintAndExtract(userHome, cwd, name);
    }

    // -- helpers ----------------------------------------------------------

    private List<ValidationError> lintAndExtract(Path userHome, Path cwd, String name) {
        WorkflowPaths paths = new WorkflowPaths(userHome, cwd);
        Path file = paths.resolveFile(name);
        if (file == null) {
            String resource = "workflows/" + name + ".yaml";
            if (loader.getClass().getClassLoader().getResource(resource) == null) {
                throw new WorkflowServiceException(WorkflowServiceException.Code.NOT_FOUND,
                        "workflow '" + name + "' not found "
                                + "(looked in " + paths.projectDir() + " and "
                                + paths.userDir() + " and the bundled resources)");
            }
            try {
                Workflow wf = loader.loadResource(resource);
                return validator.validate(wf);
            } catch (WorkflowParserException wpe) {
                return List.of(new ValidationError("(root)", wpe.getMessage()));
            }
        }
        try {
            Workflow wf = loader.load(file);
            return validator.validate(wf);
        } catch (WorkflowParserException wpe) {
            return List.of(new ValidationError("(root)", wpe.getMessage()));
        }
    }

    private static String safeReadDescription(Path file) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            // Look for the first `description:` line. We avoid
            // re-parsing the YAML to keep this lightweight.
            for (String line : (Iterable<String>) lines::iterator) {
                String trimmed = line.trim();
                if (trimmed.startsWith("description:")) {
                    String v = trimmed.substring("description:".length()).trim();
                    if (v.startsWith("|") || v.startsWith(">")) {
                        // Multi-line; we just show the indicator.
                        return "(multi-line)";
                    }
                    return v;
                }
                // Stop scanning once we hit the first non-comment,
                // non-empty line that isn't a description.
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")
                        && !trimmed.startsWith("name:")
                        && !trimmed.startsWith("version:")) {
                    break;
                }
            }
            return "";
        } catch (IOException ioe) {
            return "";
        }
    }

    private static String resourceYaml(String resource) {
        try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            return "";
        }
    }

    private static Path pickTargetDir(Path userHome, Path cwd) {
        Path projectDir = (cwd == null ? null : cwd.resolve(".aethercode").resolve("workflows"));
        if (projectDir != null && (Files.isDirectory(projectDir) || canCreate(projectDir))) {
            return projectDir;
        }
        Path userDir = userHome.resolve(".aethercode").resolve("workflows");
        return userDir;
    }

    private static boolean canCreate(Path dir) {
        Path probe = dir.resolve(".write-probe");
        try {
            Files.createDirectories(dir);
            Files.writeString(probe, "");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException ioe) {
            return false;
        }
    }
}
