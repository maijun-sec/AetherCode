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

package org.aethercode.tools.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotebookEditToolTest {

    private static final String SAMPLE = """
            {
              "cells": [
                {"cell_type": "code", "source": ["print(1)"], "metadata": {}, "outputs": [], "execution_count": null}
              ],
              "metadata": {"kernelspec": {"name": "python3"}},
              "nbformat": 4,
              "nbformat_minor": 5
            }
            """;

    @Test
    void replacesCellContent(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("nb.ipynb");
        Files.writeString(f, SAMPLE);
        Tool t = NotebookEditTool.build();
        Tool.ToolResult res = t.call(Map.of(
                "notebook_path", f.toString(),
                "operation", "replace",
                "cell_index", 0,
                "new_source", "print(2)"
        ), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(f.toFile());
        assertThat(root.path("cells").get(0).path("source").get(0).asText()).isEqualTo("print(2)");
    }

    @Test
    void insertsCell(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("nb.ipynb");
        Files.writeString(f, SAMPLE);
        Tool t = NotebookEditTool.build();
        Tool.ToolResult res = t.call(Map.of(
                "notebook_path", f.toString(),
                "operation", "insert",
                "cell_index", 0,
                "cell_type", "markdown",
                "new_source", "# heading"
        ), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(f.toFile());
        assertThat(root.path("cells").size()).isEqualTo(2);
        assertThat(root.path("cells").get(0).path("cell_type").asText()).isEqualTo("markdown");
    }

    @Test
    void deletesCell(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("nb.ipynb");
        Files.writeString(f, SAMPLE);
        Tool t = NotebookEditTool.build();
        Tool.ToolResult res = t.call(Map.of(
                "notebook_path", f.toString(),
                "operation", "delete",
                "cell_index", 0
        ), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isFalse();
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(f.toFile());
        assertThat(root.path("cells").size()).isZero();
    }
}
