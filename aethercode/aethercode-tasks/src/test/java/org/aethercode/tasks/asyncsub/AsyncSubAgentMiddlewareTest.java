package org.aethercode.tasks.asyncsub;

import org.aethercode.tasks.lifecycle.TaskState;
import org.aethercode.tasks.lifecycle.TaskStateMachine;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsyncSubAgentMiddlewareTest {

    private SupervisorStore store;
    private SupervisorService service;
    private TaskStateMachine sm;
    private AsyncSubAgentMiddleware mw;
    private AsyncSubAgentHook hook;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        service = new SupervisorService(store);
        sm = new TaskStateMachine(store);
        List<AsyncSubAgentSpec> specs = List.of(
                AsyncSubAgentSpec.builder("planner", "Plans", "graph-1").build(),
                AsyncSubAgentSpec.builder("researcher", "Searches web", "graph-2").build()
        );
        hook = AsyncSubAgentHook.wire(service, store, sm, specs);
        mw = hook.middleware();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void constructor_rejectsEmptySpecs() throws Exception {
        AsyncSubAgent empty = new AsyncSubAgent(service, store, sm);
        assertThrows(IllegalArgumentException.class, () ->
                new AsyncSubAgentMiddleware(empty, List.of(), null));
    }

    @Test
    void constructor_rejectsDuplicateNames() throws Exception {
        AsyncSubAgent empty = new AsyncSubAgent(service, store, sm);
        List<AsyncSubAgentSpec> dup = List.of(
                AsyncSubAgentSpec.builder("a", "x", "g1").build(),
                AsyncSubAgentSpec.builder("a", "y", "g2").build()
        );
        assertThrows(IllegalArgumentException.class, () ->
                new AsyncSubAgentMiddleware(empty, dup, null));
    }

    @Test
    void toolNames_registersFiveTools() {
        assertEquals(List.of(
                "start_async_task", "check_async_task",
                "update_async_task", "cancel_async_task", "list_async_tasks"
        ), mw.toolNames());
    }

    @Test
    void start_spawnsChild() throws Exception {
        var result = mw.invoke("start_async_task", Map.of(
                "subagent_type", "planner",
                "description", "do work",
                "cwd", "/tmp"
        )).get(2, TimeUnit.SECONDS);
        assertTrue(result.ok());
        assertEquals("planner", result.data().get("subagent_type"));
        assertNotNull(result.data().get("task_id"));
    }

    @Test
    void start_unknownType_returnsError() throws Exception {
        var result = mw.invoke("start_async_task", Map.of(
                "subagent_type", "ghost",
                "description", "x",
                "cwd", "/tmp"
        )).get(2, TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertTrue(result.error().contains("ghost"));
    }

    @Test
    void start_missingDescription_returnsError() throws Exception {
        var result = mw.invoke("start_async_task", Map.of(
                "subagent_type", "planner",
                "cwd", "/tmp"
        )).get(2, TimeUnit.SECONDS);
        assertFalse(result.ok());
    }

    @Test
    void check_returnsCurrentStatus() throws Exception {
        var r1 = mw.invoke("start_async_task", Map.of(
                "subagent_type", "planner",
                "description", "x",
                "cwd", "/tmp"
        )).get(2, TimeUnit.SECONDS);
        String id = (String) r1.data().get("task_id");
        var r2 = mw.invoke("check_async_task", Map.of("task_id", id))
                .get(2, TimeUnit.SECONDS);
        assertTrue(r2.ok());
        assertEquals("QUEUED", r2.data().get("status"));
    }

    @Test
    void update_appendsEvent() throws Exception {
        var r1 = mw.invoke("start_async_task", Map.of(
                "subagent_type", "planner",
                "description", "x",
                "cwd", "/tmp"
        )).get(2, TimeUnit.SECONDS);
        String id = (String) r1.data().get("task_id");
        var r2 = mw.invoke("update_async_task", Map.of(
                "task_id", id,
                "subagent_type", "planner",
                "message", "go deeper"
        )).get(2, TimeUnit.SECONDS);
        assertTrue(r2.ok());
        assertTrue(r2.data().get("event_id") instanceof Number);
    }

    @Test
    void cancel_transitionsToKilled() throws Exception {
        var r1 = mw.invoke("start_async_task", Map.of(
                "subagent_type", "planner",
                "description", "x",
                "cwd", "/tmp"
        )).get(2, TimeUnit.SECONDS);
        String id = (String) r1.data().get("task_id");
        var r2 = mw.invoke("cancel_async_task", Map.of(
                "task_id", id,
                "reason", "abort"
        )).get(2, TimeUnit.SECONDS);
        assertTrue(r2.ok());
        // The check tool sees KILLED.
        var r3 = mw.invoke("check_async_task", Map.of("task_id", id))
                .get(2, TimeUnit.SECONDS);
        assertEquals("KILLED", r3.data().get("status"));
    }

    @Test
    void list_returnsAgentNames() throws Exception {
        var result = mw.invoke("list_async_tasks", Map.of()).get(2, TimeUnit.SECONDS);
        assertTrue(result.ok());
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) result.data().get("agents");
        assertTrue(names.contains("planner"));
        assertTrue(names.contains("researcher"));
    }

    @Test
    void unknownTool_returnsError() throws Exception {
        var result = mw.invoke("nope", Map.of()).get(2, TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertTrue(result.error().contains("nope"));
    }

    @Test
    void startToolDescription_mentionsAllAgents() {
        String desc = mw.startToolDescription();
        assertTrue(desc.contains("planner"));
        assertTrue(desc.contains("researcher"));
    }

    @Test
    void systemPromptFragment_includesAllAgents() {
        AsyncSubAgentMiddleware withPrompt = new AsyncSubAgentMiddleware(
                hook.driver(),
                mw.specs(),
                "Use these wisely."
        );
        String frag = withPrompt.systemPromptFragment();
        assertNotNull(frag);
        assertTrue(frag.contains("planner"));
        assertTrue(frag.contains("researcher"));
        assertTrue(frag.contains("Use these wisely."));
    }

    @Test
    void systemPromptFragment_nullWhenNoPrompt() {
        assertNull(mw.systemPromptFragment());
    }
}
