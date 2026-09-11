package org.aethercode.tasks.cli;

import org.aethercode.tasks.supervisor.SupervisorHome;
import org.aethercode.tasks.supervisor.SupervisorProcess;
import org.aethercode.tasks.supervisor.SupervisorSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-380..T-385): the {@code aethercode task ...} CLI
 * subcommands. The tests start a real
 * {@link SupervisorProcess} in-process (in a private tmp
 * dir, with the {@code AETHERCODE_HOME} override) and then
 * invoke each subcommand with picocli's programmatic API.
 *
 * <p>Why not just call the subcommands via a process spawn?
 * Spawning the {@code aethercode} jar would require a
 * shaded build. The CLI's input/output is also clean
 * {@code System.out} / {@code System.err} so we can
 * exercise it with a {@code ByteArrayOutputStream} and
 * the same picocli API a real invocation would use.
 */
class TaskCliTest {

    private Path tmpDir;
    private SupervisorProcess process;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("aethercode-task-cli-");
        SupervisorHome.override(tmpDir);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SupervisorSocketAddress.override(
                tmpDir.resolve("supervisor-" + suffix + ".sock"),
                "aethercode-supervisor-cli-test-" + suffix);
        Path db = tmpDir.resolve("sessions.db");
        process = new SupervisorProcess(db);
        process.start();
    }

    @AfterEach
    void tearDown() {
        if (process != null && process.isRunning()) process.stop();
        SupervisorSocketAddress.clearOverride();
        SupervisorHome.clearOverride();
        if (tmpDir != null) {
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    private String runCli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outSave = System.out;
        PrintStream errSave = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            int code = new CommandLine(new TaskCli()).execute(args);
            assertEquals(0, code,
                    "exit code non-zero; out=" + out + " err=" + err);
            return out.toString();
        } finally {
            System.setOut(outSave);
            System.setErr(errSave);
        }
    }

    @Test
    void spawnPrintsChildId() {
        String out = runCli("spawn", "explain the diff");
        String childId = out.trim();
        assertTrue(childId.startsWith("c-"),
                "expected c- prefixed child id, got: " + childId);
    }

    @Test
    void spawnAcceptsCwdAndModel() {
        String out = runCli("spawn", "--cwd", tmpDir.toString(),
                "--model", "MiniMax-M3", "summarise this");
        assertTrue(out.trim().startsWith("c-"));
    }

    @Test
    void spawnAcceptsLimitsFlags() {
        String out = runCli("spawn", "x", "--tokens", "5000", "--calls", "50");
        assertTrue(out.trim().startsWith("c-"));
    }

    @Test
    void listPrintsTabSeparatedTable() {
        runCli("spawn", "first");
        runCli("spawn", "second");
        String out = runCli("list");
        assertTrue(out.contains("CHILD_ID"));
        assertTrue(out.contains("STATUS"));
        assertTrue(out.contains("QUEUED"));
    }

    @Test
    void listFilterByStatusReturnsOnlyMatching() {
        runCli("spawn", "x");
        String out = runCli("list", "--status", "COMPLETED");
        // No COMPLETED children exist yet, so the table body
        // is empty; the header is still printed.
        assertTrue(out.contains("CHILD_ID"));
        assertFalse(out.contains("\tQUEUED\t"));
    }

    @Test
    void attachPrintsChildAndEvents() {
        String childId = runCli("spawn", "x").trim();
        String out = runCli("attach", childId);
        assertTrue(out.contains("child: " + childId));
        assertTrue(out.contains("status=QUEUED"));
    }

    @Test
    void killPrintsKilledMessage() {
        String childId = runCli("spawn", "x").trim();
        String out = runCli("kill", childId, "--reason", "user abort");
        assertTrue(out.contains("killed " + childId),
                "expected 'killed " + childId + "' in output, got: " + out);
    }

    @Test
    void awaitReturnsStatusCode() {
        String childId = runCli("spawn", "x").trim();
        runCli("kill", childId);
        // await on a terminal child returns its status and
        // an exit code (2 for KILLED).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outSave = System.out;
        PrintStream errSave = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            int code = new CommandLine(new TaskCli()).execute(
                    "await", childId, "--timeout-ms", "2000");
            assertEquals(2, code, "KILLED should exit 2; out=" + out);
            assertEquals("KILLED", out.toString().trim());
        } finally {
            System.setOut(outSave);
            System.setErr(errSave);
        }
    }

    @Test
    void setLimitsParsesKVFlags() {
        String childId = runCli("spawn", "x").trim();
        String out = runCli("setLimits", childId, "tokens=5000", "calls=50");
        assertTrue(out.contains("ok=true"));
        assertTrue(out.contains("tokens=5000") || out.contains("tokens=5"));
        assertTrue(out.contains("calls=50"));
    }

    @Test
    void retryCreatesNewChild() {
        String first = runCli("spawn", "x").trim();
        String second = runCli("retry", first).trim();
        assertNotEquals(first, second);
        assertTrue(second.startsWith("c-"));
    }

    @Test
    void resumeOnQueuedFails() {
        String childId = runCli("spawn", "x").trim();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outSave = System.out;
        PrintStream errSave = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            // picocli's exit code for a method that threw is 1.
            int code = new CommandLine(new TaskCli()).execute("resume", childId);
            assertNotEquals(0, code,
                    "resume on QUEUED should fail; out=" + out + " err=" + err);
        } finally {
            System.setOut(outSave);
            System.setErr(errSave);
        }
    }

    @Test
    void helpListsAllSubcommands() {
        String out = runCli("--help");
        for (String sub : new String[]{
                "spawn", "list", "attach", "kill", "await",
                "resume", "retry", "setLimits"}) {
            assertTrue(out.contains(sub), "expected " + sub + " in help text");
        }
    }
}
