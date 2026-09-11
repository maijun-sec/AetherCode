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
 * T-2-11 (part 1) acceptance tests for the
 * {@code workflow/upsert} RPC handler. The engine is mocked
 * so the tests verify the handler's name guard, the blank-yaml
 * guard, the success envelope, and the service error
 * translation.
 */
class WorkflowUpsertHandlerTest {

    @Test
    void upsertPassesYamlToServiceAndReturnsAck() {
        // Happy path: name + yaml supplied, service succeeds.
        // The handler returns the name in the ack so the CLI
        // can print "wrote workflow 'foo'".
        var svc = new StubService();
        var handler = new DefaultWorkflowHandlers(svc);
        String yaml = "version: 1\nname: new-flow\n";
        Map<String, Object> r = asMap(handler.upsert(Map.of(
                "name", "new-flow", "yaml", yaml)));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("name", "new-flow");
        assertThat(svc.lastYaml).isEqualTo(yaml);
        assertThat(svc.lastName).isEqualTo("new-flow");
    }

    @Test
    void upsertWithBlankYamlReturnsInvalidParams() {
        // A blank / whitespace-only yaml body is rejected at
        // the handler boundary (rather than letting it through
        // to the parser where it would surface as BAD_YAML).
        var handler = new DefaultWorkflowHandlers(new StubService());
        Map<String, Object> r = asMap(handler.upsert(Map.of(
                "name", "x", "yaml", "   \n\t")));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(r).containsEntry("ok", false);
        assertThat(err).containsEntry("code", -32602);
    }

    @Test
    void upsertWithBadNameReturnsInvalidNameEnvelope() {
        // A path-traversal style name is rejected before the
        // service is called. The file is never written.
        var handler = new DefaultWorkflowHandlers(new StubService());
        Map<String, Object> r = asMap(handler.upsert(Map.of(
                "name", "../escape", "yaml", "version: 1\nname: x\n")));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(err).containsEntry("name", "INVALID_NAME")
                .containsEntry("code", -32105);
    }

    @Test
    void upsertWithBadYamlFromServiceReturnsBadYamlEnvelope() {
        // A yaml body that parses but is semantically broken
        // (e.g. missing the required `name` field) is
        // surfaced by the service as BAD_YAML; the handler
        // translates that into the standard envelope.
        var svc = new StubService();
        svc.throwOnUpsert = WorkflowServiceException.badYaml("missing 'name' field");
        var handler = new DefaultWorkflowHandlers(svc);
        Map<String, Object> r = asMap(handler.upsert(Map.of(
                "name", "broken", "yaml", "version: 1\n")));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) r.get("error");
        assertThat(r).containsEntry("ok", false);
        assertThat(err).containsEntry("name", "BAD_YAML")
                .containsEntry("code", -32102);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }

    private static class StubService implements WorkflowService {
        String lastName;
        String lastYaml;
        WorkflowServiceException throwOnUpsert;
        @Override public List<WorkflowService.WorkflowSummary> list(Path u, Path c) { return List.of(); }
        @Override public LoadedWorkflow load(Path u, Path c, String n) { return null; }
        @Override public SessionRef run(Path u, Path c, String n, Map<String, Object> i) { throw new UnsupportedOperationException(); }
        @Override public void upsert(Path u, Path c, String n, String y) {
            this.lastName = n;
            this.lastYaml = y;
            if (throwOnUpsert != null) throw throwOnUpsert;
        }
        @Override public boolean delete(Path u, Path c, String n) { return false; }
        @Override public List<ValidationError> lint(Path u, Path c, String n) { return List.of(); }
    }
}
