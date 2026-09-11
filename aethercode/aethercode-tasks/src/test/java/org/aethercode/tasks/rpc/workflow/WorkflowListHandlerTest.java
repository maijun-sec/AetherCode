package org.aethercode.tasks.rpc.workflow;

import org.aethercode.workflows.engine.WorkflowService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-08 acceptance tests for the {@code workflow/list} RPC
 * handler. The engine is mocked via a hand-rolled stub of
 * {@link WorkflowService} so the test exercises the handler's
 * JSON-RPC envelope and the cwd pass-through, not the real
 * file I/O (which is covered by
 * {@code org.aethercode.workflows.WorkflowPathsTest}).
 */
class WorkflowListHandlerTest {

    @Test
    void emptyListStillReturnsOkEnvelope() {
        // No workflows on disk → empty list, but the envelope is
        // still the standard {ok: true, workflows: []} so the
        // TUI/Desktop can render a "no workflows" empty state.
        var handler = new DefaultWorkflowHandlers(new NoopService());
        Map<String, Object> r = asMap(handler.list(Map.of()));
        assertThat(r).containsEntry("ok", true);
        assertThat((List<?>) r.get("workflows")).isEmpty();
    }

    @Test
    void summariesFromServiceArePassedThroughWithCorrectShape() {
        // The service returns three summaries; the handler must
        // hand them to the wire unchanged in count, with each
        // entry having exactly the spec'd keys (name, source,
        // description). The dedup / project-precedence logic
        // lives in WorkflowPaths.list, not in the handler.
        var svc = new RecordingService();
        svc.summaries.add(new WorkflowService.WorkflowSummary(
                "explain-code", "project", "Explain some code"));
        svc.summaries.add(new WorkflowService.WorkflowSummary(
                "tdd-feature", "user", "TDD loop"));
        svc.summaries.add(new WorkflowService.WorkflowSummary(
                "ship-it", "project", "Ship the change end-to-end"));
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.list(Map.of()));
        assertThat(r).containsEntry("ok", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workflows = (List<Map<String, Object>>) r.get("workflows");
        assertThat(workflows).hasSize(3);
        for (Map<String, Object> w : workflows) {
            assertThat(w.keySet()).containsExactlyInAnyOrder("name", "source", "description");
        }
        assertThat(workflows.get(0)).containsEntry("name", "explain-code")
                .containsEntry("source", "project")
                .containsEntry("description", "Explain some code");
    }

    @Test
    void cwdParamIsPassedThroughToTheService() {
        // The RPC accepts an optional `cwd`; when supplied, the
        // handler must hand it verbatim to the service (the
        // service decides which directory to scan). The test
        // verifies the cwd is not silently dropped or replaced
        // with the JVM user.dir.
        var svc = new RecordingService();
        var handler = new DefaultWorkflowHandlers(svc);
        handler.list(Map.of("cwd", "/some/other/dir"));
        assertThat(svc.lastCwd).isEqualTo(Path.of("/some/other/dir"));
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }

    private static class NoopService implements WorkflowService {
        @Override public List<WorkflowService.WorkflowSummary> list(Path u, Path c) { return List.of(); }
        @Override public LoadedWorkflow load(Path u, Path c, String n) { return null; }
        @Override public org.aethercode.workflows.SessionRef run(Path u, Path c, String n, Map<String, Object> i) { throw new UnsupportedOperationException(); }
        @Override public void upsert(Path u, Path c, String n, String y) {}
        @Override public boolean delete(Path u, Path c, String n) { return false; }
        @Override public List<org.aethercode.workflows.ValidationError> lint(Path u, Path c, String n) { return List.of(); }
    }

    private static class RecordingService extends NoopService {
        Path lastCwd;
        List<WorkflowService.WorkflowSummary> summaries = new ArrayList<>();
        @Override public List<WorkflowService.WorkflowSummary> list(Path u, Path c) {
            this.lastCwd = c;
            return summaries;
        }
    }
}
