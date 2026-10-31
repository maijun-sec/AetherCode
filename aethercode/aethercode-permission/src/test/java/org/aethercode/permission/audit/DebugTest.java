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

package org.aethercode.permission.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.permission.audit.GrantsAuditLog.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class DebugTest {

    @Test
    void debugParse(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("dbg.jsonl");
        String line = "{\"ts\":1,\"user\":\"u\",\"kind\":\"created\",\"scope\":\"project\",\"scopeId\":\"p\",\"category\":\"c\",\"decision\":\"allow\",\"reason\":\"r\",\"grantId\":\"g\",\"matchedRules\":[]}";
        Files.writeString(f, "this is not json\n" + line + "\n", StandardCharsets.UTF_8);
        System.out.println("=== File contents ===");
        List<String> readLines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = 0; i < readLines.size(); i++) {
            System.out.println("  line[" + i + "] = [" + readLines.get(i) + "]");
        }
        GrantsAuditLog log = new GrantsAuditLog(f);
        List<Entry> all = log.readAll();
        System.out.println("readAll size = " + all.size());
        for (Entry e : all) {
            System.out.println("  entry: " + e);
        }
        ObjectMapper m = new ObjectMapper();
        try {
            Entry e = m.readValue(line, Entry.class);
            System.out.println("Direct parse: " + e);
        } catch (Exception ex) {
            System.out.println("Direct parse FAILED: " + ex);
            ex.printStackTrace();
        }
    }
}
