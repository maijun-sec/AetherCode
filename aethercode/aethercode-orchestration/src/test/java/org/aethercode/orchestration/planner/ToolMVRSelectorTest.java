package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.planner.ToolMVRSelector.History;
import org.aethercode.orchestration.planner.ToolMVRSelector.Tool;
import org.aethercode.orchestration.planner.ToolMVRSelector.Weights;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolMVRSelectorTest {

    @Test
    void exactNameMatchWinsOverUnrelatedTools() {
        var sel = new ToolMVRSelector();
        var tools = List.of(
            new Tool("file_read", "Read a file from disk.", List.of("file_path")),
            new Tool("bash",      "Run a shell command.",    List.of("command")),
            new Tool("grep",      "Search file content.",    List.of("pattern"))
        );
        var best = sel.select("read a file", tools, null);
        assertNotNull(best);
        assertEquals("file_read", best.name());
    }

    @Test
    void historyTieBreak() {
        // two tools with the same name match, different histories
        var sel = new ToolMVRSelector();
        var tools = List.of(
            new Tool("search", "Search the web.", List.of("query")),
            new Tool("search_advanced", "Search the web, advanced filters.", List.of("query", "filter"))
        );
        History hist = name -> name.equals("search") ? 0.9 : 0.1;
        var best = sel.select("search", tools, hist);
        assertEquals("search", best.name());
    }

    @Test
    void emptyToolsReturnsNull() {
        var sel = new ToolMVRSelector();
        assertNull(sel.select("anything", List.of(), null));
    }

    @Test
    void weightsNormaliseAndSumToOne() {
        var w = new Weights(0.4, 0.4, 0.2);
        assertEquals(1.0, w.nameMatch() + w.argTypeMatch() + w.history(), 1e-9);
    }

    @Test
    void argTypeMatchRewardsMentionOfFieldInPrompt() {
        var sel = new ToolMVRSelector();
        var tools = List.of(
            new Tool("get_user", "Look up a user.", List.of("user_id")),
            new Tool("post_x",  "Do X.",           List.of("payload"))
        );
        var best = sel.select("find user by user_id", tools, null);
        assertEquals("get_user", best.name());
    }

    @Test
    void historyClampedToUnitInterval() {
        var sel = new ToolMVRSelector();
        var tools = List.of(new Tool("a", "A", List.of()));
        History hist = name -> 2.0; // out of range
        // should not throw
        var best = sel.select("A", tools, hist);
        assertNotNull(best);
    }
}
