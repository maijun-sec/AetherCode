package org.aethercode.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the {@link QueryEngine#pickLoopDetector}
 * heuristic + the {@link QueryEngine#looksLikeComplexTask}
 * helper. The engine uses these to opt into the
 * {@link ProgressLoopDetector#forComplexTask()} factory
 * when the user prompt looks like a multi-file project.
 */
class QueryEnginePickLoopDetectorR134Test {

    @Test
    void longPromptTriggersComplexTaskDetector() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1600; i++) sb.append("a");
        // 1600 chars > 1500 threshold
        assertTrue(QueryEngine.looksLikeComplexTask(sb.toString()));
    }

    @Test
    void shortPromptDoesNotTriggerComplexTaskDetector() {
        // 100 chars < 1500
        String s = "what's the weather today?";
        assertEquals(false, QueryEngine.looksLikeComplexTask(s));
    }

    @Test
    void nFilesMentionTriggersComplexTaskDetector() {
        // 3+ files mention
        assertTrue(QueryEngine.looksLikeComplexTask("create 5 files for me"));
        assertTrue(QueryEngine.looksLikeComplexTask("write 3 files in this directory"));
        assertTrue(QueryEngine.looksLikeComplexTask("generate 10+ files"));
    }

    @Test
    void fewerThan3FilesDoesNotTrigger() {
        assertEquals(false, QueryEngine.looksLikeComplexTask("create 2 files"));
        assertEquals(false, QueryEngine.looksLikeComplexTask("create 1 file"));
    }

    @Test
    void ragModuleProjectTriggersComplexTaskDetector() {
        // "RAG" + "file" should trigger
        assertTrue(QueryEngine.looksLikeComplexTask(
                "Build the RAG ingestion layer. Files to create: ..."));
        // "module" + "file" should trigger
        assertTrue(QueryEngine.looksLikeComplexTask(
                "Implement the new module. file 1: types, file 2: config"));
        // "project" + "class" should trigger
        assertTrue(QueryEngine.looksLikeComplexTask(
                "Set up a new project. class Foo, class Bar"));
    }

    @Test
    void ragModuleProjectWithoutTechnicalTermsDoesNotTrigger() {
        // Just "module" or "project" alone is not enough — needs
        // a technical marker too.
        assertEquals(false, QueryEngine.looksLikeComplexTask("tell me about the module"));
        assertEquals(false, QueryEngine.looksLikeComplexTask("what's a good project idea"));
    }

    @Test
    void nullAndEmptyInputHandledGracefully() {
        assertEquals(false, QueryEngine.looksLikeComplexTask(null));
        assertEquals(false, QueryEngine.looksLikeComplexTask(""));
    }

    @Test
    void complexTaskDetectorPickedForLongPrompt() {
        QueryEngine e = newEngine();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("x");
        ProgressLoopDetector d = e.pickLoopDetector(sb.toString());
        assertNotNull(d);
        assertEquals(20, d.window(), "long prompt should pick complex-task detector");
        assertEquals(5, d.fingerprintThreshold());
    }

    @Test
    void defaultDetectorPickedForShortPrompt() {
        QueryEngine e = newEngine();
        ProgressLoopDetector d = e.pickLoopDetector("hi");
        assertNotNull(d);
        assertEquals(8, d.window(), "short prompt should pick default detector");
        assertEquals(3, d.fingerprintThreshold());
    }

    @Test
    void forceComplexTaskDetectorOverridesAll() {
        QueryEngine e = newEngine();
        e.setForceComplexTaskDetector(true);
        // Even a 5-char prompt gets the complex detector when forced.
        ProgressLoopDetector d = e.pickLoopDetector("hello");
        assertEquals(20, d.window());
        assertEquals(5, d.fingerprintThreshold());
    }

    /** Build a QueryEngine without spinning up a real chat
     *  client. The loop-detector picker doesn't need any of
     *  the chat plumbing. */
    private static QueryEngine newEngine() {
        return new QueryEngine(
                new org.aethercode.core.app.AppState("test", java.nio.file.Path.of("")),
                /*chatClient*/ null,
                /*permissionPolicy*/ null,
                /*systemPrompt*/ "",
                /*messageSink*/ msg -> {});
    }
}
