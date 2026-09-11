package org.aethercode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpKindDetectorTest {

    @Test
    void fileRead_isRead() {
        assertThat(OpKindDetector.detect("file_read", Map.of("file_path", "x"), null))
                .isEqualTo(OpKind.READ);
    }

    @Test
    void fileWrite_toExistingPath_isModify() throws IOException {
        Path tmp = Files.createTempFile("r98-", ".txt");
        OpKind k = OpKindDetector.detect("file_write",
                Map.of("file_path", tmp.toString()), null);
        assertThat(k).isEqualTo(OpKind.MODIFY);
        Files.deleteIfExists(tmp);
    }

    @Test
    void fileWrite_toNewPath_isCreate(@TempDir Path tmp) {
        Path newFile = tmp.resolve("fresh.txt");
        OpKind k = OpKindDetector.detect("file_write",
                Map.of("file_path", newFile.toString()), tmp);
        assertThat(k).isEqualTo(OpKind.CREATE);
    }

    @Test
    void fileWrite_toDevNull_isDelete() {
        OpKind k = OpKindDetector.detect("file_write",
                Map.of("file_path", "/dev/null"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void fileWrite_toWindowsNul_isDelete() {
        OpKind k = OpKindDetector.detect("file_write",
                Map.of("file_path", "NUL"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void fileEdit_isAlwaysModify() {
        OpKind k = OpKindDetector.detect("file_edit",
                Map.of("file_path", "x", "old_string", "a", "new_string", "b"), null);
        assertThat(k).isEqualTo(OpKind.MODIFY);
    }

    @Test
    void glob_isList() {
        assertThat(OpKindDetector.detect("glob", Map.of("pattern", "*"), null))
                .isEqualTo(OpKind.LIST);
    }

    @Test
    void grep_isList() {
        assertThat(OpKindDetector.detect("grep", Map.of("pattern", "x"), null))
                .isEqualTo(OpKind.LIST);
    }

    @Test
    void webFetch_isRead() {
        assertThat(OpKindDetector.detect("web_fetch", Map.of("url", "http://x"), null))
                .isEqualTo(OpKind.READ);
    }

    @Test
    void webSearch_isRead() {
        assertThat(OpKindDetector.detect("web_search", Map.of("query", "x"), null))
                .isEqualTo(OpKind.READ);
    }

    @Test
    void bash_rm_isDelete() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "rm foo.txt"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void bash_rmRecursiveForce_isDelete() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "rm -rf build/"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void bash_delWindowsStyle_isDelete() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "del /f /q file.txt"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void bash_touch_isCreate() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "touch new.txt"), null);
        assertThat(k).isEqualTo(OpKind.CREATE);
    }

    @Test
    void bash_mkdir_isCreate() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "mkdir -p out"), null);
        assertThat(k).isEqualTo(OpKind.CREATE);
    }

    @Test
    void bash_sed_isModify() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "sed -i 's/a/b/' file.txt"), null);
        assertThat(k).isEqualTo(OpKind.MODIFY);
    }

    @Test
    void bash_cat_isRead() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "cat README.md"), null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    @Test
    void bash_ls_isRead() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "ls -la"), null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    @Test
    void bash_mvn_isExec() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "mvn -B -pl aethercode-config test"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void bash_curl_isExec() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "curl -s http://example.com"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void bash_gitStatus_isRead() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "git status"), null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    @Test
    void bash_gitCommit_isModify() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "git commit -m x"), null);
        assertThat(k).isEqualTo(OpKind.MODIFY);
    }

    @Test
    void bash_gitCheckout_isCreate() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "git checkout -b feature"), null);
        assertThat(k).isEqualTo(OpKind.CREATE);
    }

    @Test
    void bash_pipeWithQuotedPath_tokenizeCorrectly() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "rm \"my file.txt\""), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void bash_envAssignmentPrefix_isSkipped() {
        // FOO=bar mvn test -> mvn is the actual command
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "JAVA_HOME=/opt mvn -B test"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void bash_fullyQualifiedPath_isResolved() {
        // /bin/rm foo -> rm
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "/bin/rm foo.txt"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void bash_windowsExeSuffix_isStripped() {
        // rm.exe foo -> rm
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "rm.exe foo.txt"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void unknownTool_isExec() {
        OpKind k = OpKindDetector.detect("totally_made_up_tool", Map.of(), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void bash_emptyCommand_isExec() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", ""), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    // The legacy detector matched any flag containing the letters
    // 'r' or 'f' (because the regex was `-[a-z]*[rf][a-z]*\b`),
    // so `java -version` and `mvn -version` were classified as
    // DELETE and the matrix denied them out of the prompter path.
    // The user-visible symptom was the model unable to do basic
    // diagnostic commands in the same daemon session.
    @Test
    void r181_javaVersion_isExec() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "java -version"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void r181_mvnVersion_isExec() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "mvn -version"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void r181_javacVersion_isExec() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "javac -version"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void r181_chainedDiagnosticCommand_isExec() {
        // The exact command from the stuck session:
        // `cd /d D:\tmp\abc_1 && dir && java -version 2>&1 && mvn -version 2>&1`
        // legacy: classified as DELETE because `cd /d` matches the
        // /d...flag pattern. afterward: cd is a shell builtin, returns EXEC.
        OpKind k = OpKindDetector.detect("bash", Map.of("command",
                "cd /d D:\\tmp\\abc_1 && dir && java -version 2>&1 && mvn -version 2>&1"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void r181_dirRecursive_isRead() {
        // `dir /s /b` is a recursive LIST, not DELETE.
        // legacy: matched `/[sqf]\b` and got escalated to DELETE.
        OpKind k = OpKindDetector.detect("bash", Map.of("command",
                "cmd /c \"dir /s /b C:\\*.exe 2>nul | findstr /I \\\"java mvn javac\\\" \" 2>&1 | head -n 20"),
                null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    @Test
    void r181_findstrIsRead_isRead() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command",
                "findstr /I \"java mvn javac\" foo.txt"), null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    @Test
    void r181_whereCommand_isRead() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command",
                "where java & where mvn & where javac"), null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    @Test
    void r181_powershellGetCommand_isRead() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command",
                "powershell -NoProfile -Command \"Get-Command java,mvn,javac -ErrorAction SilentlyContinue\""),
                null);
        assertThat(k).isEqualTo(OpKind.READ);
    }

    // Regression: existing destructive detection must still work.
    @Test
    void r181_rmRf_isStillDelete() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "rm -rf build/"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void r181_delRecursiveForce_isStillDelete() {
        OpKind k = OpKindDetector.detect("bash",
                Map.of("command", "del /f /q file.txt"), null);
        assertThat(k).isEqualTo(OpKind.DELETE);
    }

    @Test
    void r181_cdAlone_isExec() {
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "cd /d D:\\foo"), null);
        assertThat(k).isEqualTo(OpKind.EXEC);
    }

    @Test
    void r181_pipeChain_isFirstTokenClassified() {
        // `cat foo.txt | head` — first token is `cat`, classified as READ.
        OpKind k = OpKindDetector.detect("bash", Map.of("command", "cat foo.txt | head -n 5"), null);
        assertThat(k).isEqualTo(OpKind.READ);
    }
}
