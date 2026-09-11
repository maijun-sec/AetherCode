package org.aethercode.tasks.rpc.workflow;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.engine.WorkflowService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-09 acceptance tests for the {@code workflow/show} RPC
 * handler. The engine is mocked so the tests verify the
 * YAML + parsed-JSON envelope shape, the not-found path, and
 * the name-validation path.
 */
class WorkflowShowHandlerTest {

    @Test
    void showReturnsYamlAndParsedEnvelope() {
        var svc = new StubService();
        svc.loaded = new WorkflowService.LoadedWorkflow(
                "tdd-feature", "user",
                "version: 1\nname: tdd-feature\n",
                Map.of("version", 1, "name", "tdd-feature"),
                List.of());
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.show(Map.of("name", "tdd-feature")));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("name", "tdd-feature");
        assertThat(r).containsEntry("source", "user");
        assertThat((String) r.get("yaml")).contains("name: tdd-feature");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) r.get("parsed");
        assertThat(parsed).containsEntry("name", "tdd-feature");
        assertThat((List<?>) r.get("validationErrors")).isEmpty();
    }

    @Test
    void showReturnsNotFoundEnvelopeWhenServiceReturnsNull() {
        // The service signals "not found" by returning null
        // (rather than throwing); the handler translates this
        // into the standard {ok: false, error: {name: NOT_FOUND,
        // code: -32101}} envelope. The same envelope is also
        // produced when a path-traversal name is rejected at
        // the API boundary — the two paths exercise the
        // distinct error branches.
        var svc = new StubService();
        svc.loaded = null;
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.show(Map.of("name", "missing")));
        assertThat(r).containsEntry("ok", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(err).containsEntry("name", "NOT_FOUND")
                .containsEntry("code", -32101);
    }

    @Test
    void showReturnsInvalidNameEnvelopeForPathTraversal() {
        // A name that fails WorkflowPaths.assertSafeName is
        // caught at the API boundary and surfaced as
        // INVALID_NAME, not as a file-not-found or a
        // successful load of a parent directory.
        var handler = new DefaultWorkflowHandlers(new StubService());
        Map<String, Object> r = asMap(handler.show(Map.of("name", "../etc/passwd")));
        assertThat(r).containsEntry("ok", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(err).containsEntry("name", "INVALID_NAME")
                .containsEntry("code", -32105);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }

    private static class StubService implements WorkflowService {
        LoadedWorkflow loaded;
        @Override public List<WorkflowService.WorkflowSummary> list(Path u, Path c) { return List.of(); }
        @Override public LoadedWorkflow load(Path u, Path c, String n) { return loaded; }
        @Override public SessionRef run(Path u, Path c, String n, Map<String, Object> i) { throw new UnsupportedOperationException(); }
        @Override public void upsert(Path u, Path c, String n, String y) {}
        @Override public boolean delete(Path u, Path c, String n) { return false; }
        @Override public List<ValidationError> lint(Path u, Path c, String n) { return List.of(); }
    }
}
