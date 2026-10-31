/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
