package org.aethercode.tools.shell;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R271 (2026-09-15) — kill the whole process tree, not
 * just the direct child. The user's session was wedged
 * for hours because {@code mvn test} forked surefire
 * booters and test JVMs that survived {@code
 * process.destroyForcibly()} (which only kills the
 * direct child via {@code TerminateProcess(pid)} on
 * Windows). The daemon stayed "busy" and every follow-up
 * prompt was rejected with "[busy] session is busy with
 * run-1".
 *
 * <p>Three tests:
 * <ol>
 *   <li>{@link #killProcessTree_killsLiveProcess} — the
 *       core behaviour: spawn a long-running child,
 *       call {@link BashTool#killProcessTree(Process)},
 *       assert the child PID is no longer alive within a
 *       few seconds.</li>
 *   <li>{@link #killProcessTree_killsGrandchildren} —
 *       the regression that motivated R271: spawn a
 *       tree where the direct child exits immediately
 *       but first forks a long-running grandchild (the
 *       mvn + surefire pattern). After the kill the
 *       grandchild must be reaped.</li>
 *   <li>{@link #killProcessTree_isIdempotentAndSafe} —
 *       defensive: kill on null / dead / twice is a
 *       no-op, never throws. The BashJobRegistry.kill
 *       callsite races with the natural exit, so the
 *       helper must tolerate already-dead handles.</li>
 * </ol>
 */
class BashToolR271Test {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String longRunningCommand() {
        return isWindows()
                ? "ping -n 30 127.0.0.1"   // ~29s
                : "sleep 30";
    }

    private static boolean isProcessAlive(long pid) {
        // The cheap path is ProcessHandle.of(...).isAlive()
        // — works on all modern JDKs and is independent
        // of any shell tool. We also try the OS-level
        // ps/tasklist as a belt-and-suspenders fallback
        // (some CI sandboxes lie about isAlive).
        try {
            ProcessHandle h = ProcessHandle.of(pid).orElse(null);
            if (h != null) {
                if (h.isAlive()) return true;
            }
        } catch (Throwable ignored) {}
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("tasklist", "/FI", "PID eq " + pid)
                    : new ProcessBuilder("ps", "-p", Long.toString(pid));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(3, TimeUnit.SECONDS);
            String s = new String(out, StandardCharsets.UTF_8);
            // tasklist always emits a header line and an
            // empty line; if the process is alive, its PID
            // appears as a row.
            if (isWindows()) {
                return s.lines()
                        .filter(l -> !l.isBlank())
                        .anyMatch(l -> l.trim().startsWith(pid + " "));
            }
            // ps -p <pid> prints "<pid>" on success, blank
            // line otherwise.
            return s.trim().equals(Long.toString(pid));
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    void killProcessTree_killsLiveProcess() throws Exception {
        // spawn a long-running child
        ProcessBuilder pb = new ProcessBuilder(
                isWindows() ? new String[]{"cmd.exe", "/c", longRunningCommand()}
                            : new String[]{"/bin/sh", "-c", longRunningCommand()});
        pb.redirectErrorStream(true);
        Process p = pb.start();
        long pid = p.pid();

        // confirm the child is alive before kill
        assertThat(isProcessAlive(pid))
                .as("child must be alive before kill, pid=%d", pid)
                .isTrue();

        // call the new helper
        BashTool.killProcessTree(p);

        // wait for the OS to reap (Windows taskkill /F is
        // synchronous but the JVM only observes exit after
        // a few hundred ms). 5s covers slow CI.
        assertThat(p.waitFor(5, TimeUnit.SECONDS))
                .as("process.waitFor must return true within 5s after killProcessTree")
                .isTrue();

        // give the OS a beat to fully reap
        Thread.sleep(500);

        assertThat(isProcessAlive(pid))
                .as("child must be gone after killProcessTree, pid=%d", pid)
                .isFalse();
    }

    @Test
    void killProcessTree_killsGrandchildren() throws Exception {
        // spawn a tree where the direct child is
        // LONG-RUNNING and first forks a long-running
        // grandchild (the mvn -> surefire booter -> test
        // JVM pattern). The grandchild is what
        // {@code destroyForcibly} misses; this is the
        // regression we are fixing.
        //
        // Important: the parent must STILL BE ALIVE when
        // killProcessTree runs. taskkill /T /F /PID walks
        // the tree starting from the named PID, so if the
        // parent has already exited, the walker has no
        // anchor and the grandchild stays alive. This
        // mirrors the production path: BashTool.runForeground
        // hits the timeout branch only when waitFor() returns
        // false — i.e. parent is still alive at kill time.
        //
        // Windows: `start /B ping -n 30 ... & ping -n 30 ...`
        // forks a detached grandchild and keeps the parent
        // cmd.exe alive with its own long-running ping.
        // POSIX:  `sleep 30 & sleep 30 ; wait` keeps the
        // shell alive in foreground while the background
        // sleep runs.
        String[] cmd = isWindows()
                ? new String[]{"cmd.exe", "/c",
                        "start /B ping -n 30 127.0.0.1 > NUL & ping -n 30 127.0.0.1"}
                : new String[]{"/bin/sh", "-c",
                        "(sleep 30 &) ; sleep 30"};

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        long parentPid = p.pid();
        // give the tree 2s to settle — cmd's `start /B`
        // and sh's `&` fork lazily.
        Thread.sleep(2_000);

        // sanity: parent is alive (the precondition for
        // taskkill /T to work).
        assertThat(isProcessAlive(parentPid))
                .as("parent must be alive at kill time so taskkill /T has an anchor, pid=%d", parentPid)
                .isTrue();

        // snapshot: at least one ping/sleep is running
        long before = countMatchingProcesses();
        assertThat(before).as("expected at least one grandchild/parent running before kill, found: %d", before)
                .isGreaterThanOrEqualTo(1);

        // call the new helper
        BashTool.killProcessTree(p);

        // wait for the OS to cascade. Windows'
        // TerminateProcess is synchronous per-process
        // but the taskkill /T walk is sequential.
        Thread.sleep(4_000);

        long after = countMatchingProcesses();
        assertThat(after)
                .as("after killProcessTree the grandchild tree must be reaped (before=%d, after=%d)",
                        before, after)
                .isLessThan(before);
    }

    @Test
    void killProcessTree_isIdempotentAndSafe() throws Exception {
        // null is a no-op (BashJobRegistry.kill returns
        // false in that case but the helper itself must
        // never NPE).
        BashTool.killProcessTree(null);

        // already-dead process: also no-op.
        ProcessBuilder pb = new ProcessBuilder(
                isWindows() ? new String[]{"cmd.exe", "/c", "exit 0"}
                            : new String[]{"true"});
        Process p = pb.start();
        p.waitFor(2, TimeUnit.SECONDS);
        // twice in a row, on a dead handle
        BashTool.killProcessTree(p);
        BashTool.killProcessTree(p);

        // live process: kill works the first time,
        // calling again on the (now dying/dead) handle
        // is still safe.
        ProcessBuilder pb2 = new ProcessBuilder(
                isWindows() ? new String[]{"cmd.exe", "/c", "ping -n 5 127.0.0.1"}
                            : new String[]{"/bin/sh", "-c", "sleep 5"});
        Process p2 = pb2.start();
        BashTool.killProcessTree(p2);
        BashTool.killProcessTree(p2);  // second call must not throw
        assertThat(p2.waitFor(3, TimeUnit.SECONDS)).isTrue();
    }

    private static long countMatchingProcesses() {
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq ping.exe")
                    : new ProcessBuilder("pgrep", "-c", "-x", "sleep");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(3, TimeUnit.SECONDS);
            String s = new String(out, StandardCharsets.UTF_8).trim();
            // pgrep -c prints the count. tasklist prints a
            // table; we count non-header rows.
            if (!isWindows()) {
                try { return Long.parseLong(s); }
                catch (NumberFormatException nfe) { return 0; }
            }
            long count = 0;
            for (String line : s.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.toLowerCase().startsWith("imagename")) continue;
                if (t.contains("===")) continue;
                if (t.toLowerCase().startsWith("info:")) continue;
                count++;
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }
}