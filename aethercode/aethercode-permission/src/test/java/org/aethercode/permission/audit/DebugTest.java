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
