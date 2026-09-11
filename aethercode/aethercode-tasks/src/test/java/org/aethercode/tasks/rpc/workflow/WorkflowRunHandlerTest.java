package org.aethercode.tasks.rpc.workflow;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.engine.WorkflowService;
import org.aethercode.workflows.engine.WorkflowServiceException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-10 acceptance tests for the {@code workflow/run} RPC
 * handler. The engine + spawn is mocked so the tests verify
 * the success envelope, the input pass-through, the
 * missing/invalid name guards, and the not-found /
 * validation-failed error translation.
 */
class WorkflowRunHandlerTest {

    @Test
    void runReturnsSessionIdAndMetadata() {
        // On a successful run the handler must return the
        // session id + cwd + model + workflow name so the CLI
        // can print "spawned session c-123" and follow up with
        // "ac session show c-123" without re-resolving the
        // workflow.
        var svc = new StubService();
        svc.nextRef = new SessionRef("c-123", "/work/proj", "claude-opus-4-1",
                "tdd-feature", Map.of());
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.run(Map.of(
                "name", "tdd-feature",
                "inputs", Map.of("feature", "add login"))));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("sessionId", "c-123");
        assertThat(r).containsEntry("workflowName", "tdd-feature");
        assertThat(r).containsEntry("model", "claude-opus-4-1");
        // The handler forwards inputs verbatim to the service
        // so the engine can do its variable substitution.
        assertThat(svc.lastInputs).containsEntry("feature", "add login");
    }

    @Test
    void runWithoutInputsDefaultsToEmptyMap() {
        // A workflow that has no declared inputs (e.g.
        // "explain-code" which has no required input) is run
        // with no params — the handler must not throw and must
        // pass an empty map so the service's variable
        // substitution loop has nothing to fill.
        var svc = new StubService();
        svc.nextRef = new SessionRef("c-x", "", "", "explain-code", Map.of());
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.run(Map.of("name", "explain-code")));
        assertThat(r).containsEntry("ok", true);
        assertThat(svc.lastInputs).isEmpty();
    }

    @Test
    void runMissingNameReturnsInvalidParams() {
        // No "name" in the params map → the handler's
        // stringParam helper raises IllegalArgumentException,
        // which the handler translates to a clean envelope
        // rather than letting it propagate (the RPC layer
        // would otherwise turn it into a -32602 JSON-RPC
        // error). Self-contained envelopes make the handler
        // testable in isolation.
        var handler = new DefaultWorkflowHandlers(new StubService());
        Map<String, Object> r = asMap(handler.run(Map.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(r).containsEntry("ok", false);
        assertThat(err).containsEntry("code", -32602);
    }

    @Test
    void runInvalidNameReturnsInvalidNameEnvelope() {
        // A path-traversal style name is rejected by
        // WorkflowPaths.assertSafeName and surfaces as
        // INVALID_NAME. The session is never spawned.
        var handler = new DefaultWorkflowHandlers(new StubService());
        Map<String, Object> r = asMap(handler.run(Map.of("name", "../escape")));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(r).containsEntry("ok", false);
        assertThat(err).containsEntry("name", "INVALID_NAME")
                .containsEntry("code", -32105);
    }

    @Test
    void runServiceNotFoundIsTranslatedToEnvelope() {
        // The service throws WorkflowServiceException(NOT_FOUND)
        // when the workflow doesn't exist; the handler must
        // translate this into the standard {ok: false, error:
        // {name: NOT_FOUND, code: -32101}} envelope.
        var svc = new StubService();
        svc.throwOnRun = WorkflowServiceException.notFound("ghost");
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.run(Map.of("name", "ghost")));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(r).containsEntry("ok", false);
        assertThat(err).containsEntry("name", "NOT_FOUND")
                .containsEntry("code", -32101);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }

    private static class StubService implements WorkflowService {
        SessionRef nextRef;
        WorkflowServiceException throwOnRun;
        Map<String, Object> lastInputs;
        @Override public List<WorkflowService.WorkflowSummary> list(Path u, Path c) { return List.of(); }
        @Override public LoadedWorkflow load(Path u, Path c, String n) { return null; }
        @Override public SessionRef run(Path u, Path c, String n, Map<String, Object> i) {
            this.lastInputs = i;
            if (throwOnRun != null) throw throwOnRun;
            return nextRef;
        }
        @Override public void upsert(Path u, Path c, String n, String y) {}
        @Override public boolean delete(Path u, Path c, String n) { return false; }
        @Override public List<ValidationError> lint(Path u, Path c, String n) { return List.of(); }
    }
}
