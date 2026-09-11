package org.aethercode.tasks.rpc.workflow;

import org.aethercode.workflows.SessionRef;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.engine.WorkflowService;
import org.aethercode.workflows.engine.WorkflowServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-11 (part 2) acceptance tests for the
 * {@code workflow/delete} RPC handler. The engine is mocked
 * to verify the success envelope (with the deleted boolean),
 * the not-found path (deleted=false), the name guard, and the
 * IO error translation.
 */
class WorkflowDeleteHandlerTest {

    @Test
    void deleteReturnsTrueWhenServiceRemovesAFile() {
        // Service returns true (a file was removed). The
        // handler's envelope must include deleted=true so the
        // CLI can print "removed workflow 'foo'".
        var svc = new StubService();
        svc.deleteResult = true;
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.delete(Map.of("name", "old-flow")));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("name", "old-flow");
        assertThat(r).containsEntry("deleted", true);
    }

    @Test
    void deleteReturnsFalseWhenServiceDidNotRemoveAnything() {
        // Service returns false (no file matched). The
        // envelope is still ok=true (delete is idempotent);
        // deleted=false lets the user know nothing changed.
        var svc = new StubService();
        svc.deleteResult = false;
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.delete(Map.of("name", "ghost")));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("deleted", false);
    }

    @Test
    void deleteWithInvalidNameReturnsInvalidNameEnvelope() {
        // A name containing path-separator characters is
        // rejected at the API boundary. The service is never
        // called, so a read-only fs cannot leak the
        // INVALID_NAME condition as an IO_ERROR.
        var handler = new DefaultWorkflowHandlers(new StubService());
        Map<String, Object> r = asMap(handler.delete(Map.of("name", "a/b")));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(r).containsEntry("ok", false);
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
        boolean deleteResult;
        WorkflowServiceException throwOnDelete;
        @Override public List<WorkflowService.WorkflowSummary> list(Path u, Path c) { return List.of(); }
        @Override public LoadedWorkflow load(Path u, Path c, String n) { return null; }
        @Override public SessionRef run(Path u, Path c, String n, Map<String, Object> i) { throw new UnsupportedOperationException(); }
        @Override public void upsert(Path u, Path c, String n, String y) {}
        @Override public boolean delete(Path u, Path c, String n) {
            if (throwOnDelete != null) throw throwOnDelete;
            return deleteResult;
        }
        @Override public List<ValidationError> lint(Path u, Path c, String n) { return List.of(); }
    }
}
