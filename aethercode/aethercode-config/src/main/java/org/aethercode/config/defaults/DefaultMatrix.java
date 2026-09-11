package org.aethercode.config.defaults;

import org.aethercode.config.Action;
import org.aethercode.config.PermissionMatrix;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in default permission matrix. Conservative defaults:
 * <ul>
 *   <li>Read-only tools (file_read, glob, grep, web_fetch) are ALLOW everywhere.</li>
 *   <li>file_write to {@code src/main/**} / {@code *.java} is ASK (sensitive code).</li>
 *   <li>file_write to {@code src/test/**} is ALLOW (tests are recoverable).</li>
 *   <li>file_write to {@code *.md} / docs is ASK.</li>
 *   <li>file_write to {@code package.json}, {@code pom.xml} etc. is ASK.</li>
 *   <li>file_edit follows the same path rules as file_write (sensitivity is path-based).</li>
 *   <li>bash: any DELETE is DENY; any other op is ASK (forces human to skim commands).</li>
 *   <li>file_read is unconditionally ALLOW for any path / op kind.</li>
 * </ul>
 * Project configs override by supplying their own {@code permissionMatrix} — the
 * defaults are not merged with user values. The defaults exist for the
 * "I just created a project and have no config" case.
 */
public final class DefaultMatrix {

    private DefaultMatrix() {}

    public static PermissionMatrix build() {
        Map<String, Map<String, Map<String, String>>> entries = new LinkedHashMap<>();

        // ---------------------------------------------------------------------------------
        // file_read: always allow. Read tools are safe by default.
        // ---------------------------------------------------------------------------------
        add(entries, "file_read", "*", "READ", Action.ALLOW.name());
        add(entries, "file_read", "*", "*", Action.ALLOW.name());

        // ---------------------------------------------------------------------------------
        // file_write: path-sensitive.
        //   src/main/**, *.java, pom.xml/build.gradle/package.json/lock files -> ASK
        //   src/test/**, *.md  -> ASK (we still ask for visibility; user can override)
        //   .aethercode/** -> DENY (do not let the model rewrite its own config)
        // ---------------------------------------------------------------------------------
        add(entries, "file_write", "src/main/**", "CREATE", Action.ASK.name());
        add(entries, "file_write", "src/main/**", "MODIFY", Action.ASK.name());
        add(entries, "file_write", "src/main/**", "DELETE", Action.DENY.name());
        add(entries, "file_write", "src/main/**", "*",      Action.ASK.name());

        add(entries, "file_write", "*.java", "CREATE", Action.ASK.name());
        add(entries, "file_write", "*.java", "MODIFY", Action.ASK.name());
        add(entries, "file_write", "*.java", "DELETE", Action.DENY.name());
        add(entries, "file_write", "*.java", "*",      Action.ASK.name());

        add(entries, "file_write", "pom.xml",      "*", Action.ASK.name());
        add(entries, "file_write", "build.gradle", "*", Action.ASK.name());
        add(entries, "file_write", "build.gradle.kts", "*", Action.ASK.name());
        add(entries, "file_write", "package.json", "*", Action.ASK.name());
        add(entries, "file_write", "package-lock.json", "*", Action.ASK.name());
        add(entries, "file_write", "yarn.lock", "*", Action.ASK.name());
        add(entries, "file_write", "Cargo.toml",  "*", Action.ASK.name());

        add(entries, "file_write", "src/test/**", "CREATE", Action.ALLOW.name());
        add(entries, "file_write", "src/test/**", "MODIFY", Action.ALLOW.name());
        add(entries, "file_write", "src/test/**", "DELETE", Action.ASK.name());
        add(entries, "file_write", "src/test/**", "*",      Action.ALLOW.name());

        add(entries, "file_write", "*.md", "CREATE", Action.ASK.name());
        add(entries, "file_write", "*.md", "MODIFY", Action.ASK.name());
        add(entries, "file_write", "*.md", "*",      Action.ASK.name());

        add(entries, "file_write", ".aethercode/**", "*", Action.DENY.name());

        add(entries, "file_write", "*", "*", Action.ASK.name());

        // ---------------------------------------------------------------------------------
        // file_edit: same path rules as file_write.
        // ---------------------------------------------------------------------------------
        add(entries, "file_edit", "src/main/**", "MODIFY", Action.ASK.name());
        add(entries, "file_edit", "src/main/**", "CREATE", Action.ASK.name());
        add(entries, "file_edit", "src/main/**", "DELETE", Action.DENY.name());
        add(entries, "file_edit", "src/main/**", "*",      Action.ASK.name());

        add(entries, "file_edit", "*.java", "MODIFY", Action.ASK.name());
        add(entries, "file_edit", "*.java", "*",      Action.ASK.name());

        add(entries, "file_edit", "src/test/**", "MODIFY", Action.ALLOW.name());
        add(entries, "file_edit", "src/test/**", "CREATE", Action.ALLOW.name());
        add(entries, "file_edit", "src/test/**", "*",      Action.ALLOW.name());

        add(entries, "file_edit", "*.md", "MODIFY", Action.ASK.name());
        add(entries, "file_edit", "*.md", "*",      Action.ASK.name());

        add(entries, "file_edit", "pom.xml", "*", Action.ASK.name());
        add(entries, "file_edit", "package.json", "*", Action.ASK.name());

        add(entries, "file_edit", ".aethercode/**", "*", Action.DENY.name());

        add(entries, "file_edit", "*", "*", Action.ASK.name());

        // ---------------------------------------------------------------------------------
        // bash: delete is DENY by default; everything else is ASK.
        // ---------------------------------------------------------------------------------
        add(entries, "bash", "*", "DELETE", Action.DENY.name());
        add(entries, "bash", "*", "CREATE", Action.ASK.name());
        add(entries, "bash", "*", "MODIFY", Action.ASK.name());
        add(entries, "bash", "*", "READ",   Action.ALLOW.name());
        add(entries, "bash", "*", "EXEC",   Action.ASK.name());
        add(entries, "bash", "*", "*",      Action.ASK.name());

        // ---------------------------------------------------------------------------------
        // Glob / grep / web_fetch / web_search: read-only, allow.
        // ---------------------------------------------------------------------------------
        add(entries, "glob",      "*", "*", Action.ALLOW.name());
        add(entries, "grep",      "*", "*", Action.ALLOW.name());
        add(entries, "web_fetch", "*", "*", Action.ALLOW.name());
        add(entries, "web_search","*", "*", Action.ALLOW.name());

        return new PermissionMatrix(entries);
    }

    private static void add(Map<String, Map<String, Map<String, String>>> entries,
                            String tool, String pathGlob, String opKind, String action) {
        entries.computeIfAbsent(tool, k -> new LinkedHashMap<>())
               .computeIfAbsent(pathGlob, k -> new LinkedHashMap<>())
               .put(opKind, action);
    }
}
