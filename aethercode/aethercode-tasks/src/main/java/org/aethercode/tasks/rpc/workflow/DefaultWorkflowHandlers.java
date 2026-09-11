package org.aethercode.tasks.rpc.workflow;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.WorkflowPaths;
import org.aethercode.workflows.engine.WorkflowService;
import org.aethercode.workflows.engine.WorkflowServiceException;
import org.aethercode.workflows.engine.WorkflowService.WorkflowSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2.1 / T-2-08..T-2-12 (design.md §3.6): default
 * implementation of {@link WorkflowHandlers} that delegates to
 * a {@link WorkflowService} provided by the {@code aethercode-workflows}
 * module.
 *
 * <p>The handlers are deliberately small: they translate JSON-RPC
 * params to the service's Java API, then translate the service's
 * return values back to JSON-RPC results. The actual workflow
 * logic (load, validate, run, lint) lives in the service
 * implementation (in {@code aethercode-workflows}), not here.
 *
 * <p>Error shape (matching the spec):
 * <pre>
 *   { "ok": false,
 *     "error": { "name": "VALIDATION_FAILED",
 *                "code": -32103,
 *                "message": "...",
 *                "errors": [ {path, message}, ... ] } }
 * </pre>
 * A successful response is just the result map; failures are
 * the same envelope the grants/* and session/* methods use.
 */
public final class DefaultWorkflowHandlers implements WorkflowHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkflowHandlers.class);

    /** {@code -32101} / {@code workflow/not_found}. */
    private static final int ERR_NOT_FOUND = -32101;
    /** {@code -32102} / {@code workflow/bad_yaml}. */
    private static final int ERR_BAD_YAML = -32102;
    /** {@code -32103} / {@code workflow/validation_failed}. */
    private static final int ERR_VALIDATION = -32103;
    /** {@code -32104} / {@code workflow/io_error}. */
    private static final int ERR_IO = -32104;
    /** {@code -32105} / {@code workflow/invalid_name}. */
    private static final int ERR_INVALID_NAME = -32105;
    /** {@code -32602} / {@code invalid params} (re-used from JSON-RPC). */
    private static final int ERR_INVALID_PARAMS = -32602;

    private final WorkflowService service;

    public DefaultWorkflowHandlers(WorkflowService service) {
        this.service = service;
    }

    // ----- workflow/list ---------------------------------------------

    @Override
    public Object list(Map<String, Object> params) {
        PathContext ctx = resolveCwd(params);
        List<WorkflowSummary> summaries = service.list(ctx.userHome, ctx.cwd);
        List<Map<String, Object>> out = new ArrayList<>(summaries.size());
        for (WorkflowSummary s : summaries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", s.name());
            row.put("source", s.source());
            row.put("description", s.description() == null ? "" : s.description());
            out.add(row);
        }
        return Map.of("ok", true, "workflows", out);
    }

    // ----- workflow/show ---------------------------------------------

    @Override
    public Object show(Map<String, Object> params) {
        String name;
        try {
            name = stringParam(params, "name");
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS, iae.getMessage());
        }
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_NAME", ERR_INVALID_NAME, iae.getMessage());
        }
        PathContext ctx = resolveCwd(params);
        WorkflowService.LoadedWorkflow loaded = service.load(ctx.userHome, ctx.cwd, name);
        if (loaded == null) {
            return errorEnvelope("NOT_FOUND", ERR_NOT_FOUND, "workflow not found: " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("name", loaded.name());
        result.put("source", loaded.source());
        result.put("yaml", loaded.yaml());
        result.put("parsed", loaded.parsed());
        result.put("validationErrors", validationToMaps(loaded.validationErrors()));
        return result;
    }

    // ----- workflow/run ----------------------------------------------

    @Override
    public Object run(Map<String, Object> params) {
        String name;
        try {
            name = stringParam(params, "name");
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS, iae.getMessage());
        }
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_NAME", ERR_INVALID_NAME, iae.getMessage());
        }
        Map<String, Object> inputs;
        try {
            inputs = mapParam(params, "inputs");
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS, iae.getMessage());
        }
        PathContext ctx = resolveCwd(params);
        try {
            SessionRef ref = service.run(ctx.userHome, ctx.cwd, name, inputs);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("sessionId", ref.id());
            result.put("cwd", ref.cwd());
            result.put("model", ref.model());
            result.put("workflowName", ref.workflowName());
            return result;
        } catch (WorkflowServiceException wse) {
            return translate(wse);
        }
    }

    // ----- workflow/upsert -------------------------------------------

    @Override
    public Object upsert(Map<String, Object> params) {
        String name;
        try {
            name = stringParam(params, "name");
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS, iae.getMessage());
        }
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_NAME", ERR_INVALID_NAME, iae.getMessage());
        }
        String yaml;
        try {
            yaml = stringParam(params, "yaml");
            if (yaml.isBlank()) {
                return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS,
                        "yaml is required and must be non-blank");
            }
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS, iae.getMessage());
        }
        PathContext ctx = resolveCwd(params);
        try {
            service.upsert(ctx.userHome, ctx.cwd, name, yaml);
            return Map.of("ok", true, "name", name);
        } catch (WorkflowServiceException wse) {
            return translate(wse);
        }
    }

    // ----- workflow/delete -------------------------------------------

    @Override
    public Object delete(Map<String, Object> params) {
        String name;
        try {
            name = stringParam(params, "name");
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_PARAMS", ERR_INVALID_PARAMS, iae.getMessage());
        }
        try {
            WorkflowPaths.assertSafeName(name);
        } catch (IllegalArgumentException iae) {
            return errorEnvelope("INVALID_NAME", ERR_INVALID_NAME, iae.getMessage());
        }
        PathContext ctx = resolveCwd(params);
        try {
            boolean removed = service.delete(ctx.userHome, ctx.cwd, name);
            return Map.of("ok", true, "name", name, "deleted", removed);
        } catch (WorkflowServiceException wse) {
            return translate(wse);
        }
    }

    // ----- shared helpers --------------------------------------------

    private static PathContext resolveCwd(Map<String, Object> params) {
        String cwdStr = params == null ? null : stringOrNull(params.get("cwd"));
        Path cwd = (cwdStr == null || cwdStr.isBlank())
                ? WorkflowPaths.defaultCwd()
                : Path.of(cwdStr);
        return new PathContext(WorkflowPaths.defaultUserHome(), cwd);
    }

    private static String stringParam(Map<String, Object> params, String key) {
        if (params == null) {
            throw new IllegalArgumentException("params is required");
        }
        Object v = params.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required param: " + key);
        }
        String s = v.toString();
        if (s.isBlank()) {
            throw new IllegalArgumentException("param '" + key + "' is blank");
        }
        return s;
    }

    private static Map<String, Object> mapParam(Map<String, Object> params, String key) {
        if (params == null) return Map.of();
        Object v = params.get(key);
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        throw new IllegalArgumentException("param '" + key + "' must be an object");
    }

    private static String stringOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static Map<String, Object> errorEnvelope(String name, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("name", name);
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", err);
        return out;
    }

    /** Translate a service exception into the JSON-RPC error envelope. */
    private static Map<String, Object> translate(WorkflowServiceException wse) {
        return switch (wse.code()) {
            case NOT_FOUND -> errorEnvelope("NOT_FOUND", ERR_NOT_FOUND, wse.getMessage());
            case BAD_YAML -> errorEnvelope("BAD_YAML", ERR_BAD_YAML, wse.getMessage());
            case VALIDATION_FAILED -> {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("name", "VALIDATION_FAILED");
                err.put("code", ERR_VALIDATION);
                err.put("message", wse.getMessage());
                err.put("errors", validationToMaps(wse.validationErrors()));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("error", err);
                yield out;
            }
            case IO_ERROR -> errorEnvelope("IO_ERROR", ERR_IO, wse.getMessage());
            case INVALID_NAME -> errorEnvelope("INVALID_NAME", ERR_INVALID_NAME, wse.getMessage());
        };
    }

    private static List<Map<String, Object>> validationToMaps(List<ValidationError> errs) {
        if (errs == null || errs.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(errs.size());
        for (ValidationError e : errs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", e.path());
            row.put("message", e.message());
            out.add(row);
        }
        return out;
    }

    private record PathContext(Path userHome, Path cwd) {}
}
