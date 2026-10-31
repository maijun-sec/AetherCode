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

package org.aethercode.core.cost;

import java.util.List;
import org.aethercode.core.cost.CostExporter.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostExporterTest {

    @Test
    void rowsOf_onePerModel() {
        CostTracker t = new CostTracker()
                .setPrice("a", 0.001, 0.001)
                .setPrice("b", 0.002, 0.002);
        t.record("a", new CostTracker.Usage(1000, 0));
        t.record("b", new CostTracker.Usage(2000, 0));
        List<Row> rows = CostExporter.rowsOf(t);
        assertEquals(2, rows.size());
    }

    @Test
    void rowsOf_emptyForFreshTracker() {
        CostTracker t = new CostTracker();
        assertTrue(CostExporter.rowsOf(t).isEmpty());
    }

    @Test
    void toJson_includesAllFields() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        t.record("m", new CostTracker.Usage(1000, 0));
        String json = CostExporter.toJson(t);
        assertTrue(json.contains("\"totalInput\""));
        assertTrue(json.contains("\"totalOutput\""));
        assertTrue(json.contains("\"totalCostUsd\""));
        assertTrue(json.contains("\"models\""));
        assertTrue(json.contains("\"model\":\"m\""));
    }

    @Test
    void toJson_handlesEmpty() {
        CostTracker t = new CostTracker();
        String json = CostExporter.toJson(t);
        assertTrue(json.contains("\"models\""));
        assertTrue(json.contains("]"));
    }

    @Test
    void toCsv_includesHeader() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        t.record("m", new CostTracker.Usage(1000, 0));
        String csv = CostExporter.toCsv(t);
        assertTrue(csv.startsWith("model,input_tokens,output_tokens,cost_usd"));
        assertTrue(csv.contains("m,1000,0,"));
    }

    @Test
    void toCsv_handlesEmpty() {
        CostTracker t = new CostTracker();
        String csv = CostExporter.toCsv(t);
        assertTrue(csv.startsWith("model,"));
    }

    @Test
    void toCsv_escapesCommasInModelName() {
        CostTracker t = new CostTracker().setPrice("weird,name", 0.001, 0.001);
        t.record("weird,name", new CostTracker.Usage(1000, 0));
        String csv = CostExporter.toCsv(t);
        assertTrue(csv.contains("\"weird,name\""));
    }

    @Test
    void toTextTable_includesHeader() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        t.record("m", new CostTracker.Usage(1000, 0));
        String table = CostExporter.toTextTable(t);
        assertTrue(table.contains("Model"));
        assertTrue(table.contains("TOTAL"));
    }

    @Test
    void toTextTable_handlesEmpty() {
        CostTracker t = new CostTracker();
        String table = CostExporter.toTextTable(t);
        assertTrue(table.contains("no usage recorded"));
    }

    @Test
    void toTextTable_columnsAligned() {
        CostTracker t = new CostTracker()
                .setPrice("a", 0.001, 0.001)
                .setPrice("longer-model-name", 0.001, 0.001);
        t.record("a", new CostTracker.Usage(100, 0));
        t.record("longer-model-name", new CostTracker.Usage(200, 0));
        String table = CostExporter.toTextTable(t);
        // All data rows have the same width
        String[] lines = table.split("\n");
        int headerLen = lines[0].length();
        for (int i = 1; i < lines.length; i++) {
            assertEquals(headerLen, lines[i].length(), "line: " + lines[i]);
        }
    }

    @Test
    void toMap_includesTotals() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        t.record("m", new CostTracker.Usage(1000, 0));
        var m = CostExporter.toMap(t);
        assertEquals(1000L, m.get("totalInput"));
        assertEquals(0.001, (Double) m.get("totalCostUsd"), 1e-9);
    }

    @Test
    void toJsonAlt_producesArray() {
        CostTracker t = new CostTracker().setPrice("m", 0.001, 0.001);
        t.record("m", new CostTracker.Usage(1000, 0));
        String json = CostExporter.toJsonAlt(t);
        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("\"model\":\"m\""));
    }

    @Test
    void row_carriesAllFields() {
        Row r = new Row("m", 100, 200, 0.5);
        assertEquals("m", r.model());
        assertEquals(100, r.inputTokens());
        assertEquals(200, r.outputTokens());
        assertEquals(0.5, r.costUsd());
    }
}
