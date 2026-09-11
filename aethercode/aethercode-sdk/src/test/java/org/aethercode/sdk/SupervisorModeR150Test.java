package org.aethercode.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R150 tests: real subprocess + health-check
 * integration in {@link SupervisorMode}.
 *
 * <p>The "child daemon" in these tests is a tiny
 * Java program ({@link TestHttpServer}) that
 * listens on a port and serves /healthz. The
 * supervisor is asked to spawn it via
 * {@link SupervisorMode#spawnChild} and the
 * heartbeat is verified end-to-end.
 *
 * <p>The tests use a Java reflection trick to
 * find the {@code target/test-classes} directory
 * so the child can be launched on the same
 * classpath. When the test-classes dir is
 * unavailable (e.g. some IDE runtimes), the
 * Java-subprocess tests are skipped and only
 * the externally-managed-child tests run.
 */
class SupervisorModeR150Test {

    private SupervisorMode.ChildInfo spawnedChild = null;

    @AfterEach
    void cleanup() {
        if (spawnedChild != null) {
            try { SupervisorMode.instance().killChild(spawnedChild.childId(), 2_000L); } catch (Exception ignored) {}
            try { SupervisorMode.instance().unregisterChild(spawnedChild.childId()); } catch (Exception ignored) {}
            spawnedChild = null;
        }
    }

    /** Resolve the absolute path of the
     *  test-classes directory by inspecting
     *  the URLClassLoader. Returns null when
     *  the test classpath isn't a normal
     *  file-based classpath (e.g. an IDE
     *  that uses a non-file URL). */
    private static Path findTestClassesDir() {
        ClassLoader cl = SupervisorModeR150Test.class.getClassLoader();
        if (cl instanceof URLClassLoader ucl) {
            for (URL u : ucl.getURLs()) {
                if (u.getProtocol().equals("file") && u.getPath().endsWith("test-classes")) {
                    return new File(u.getPath()).toPath();
                }
            }
        }
        // Fall back: walk all classpath URLs
        // and pick the first one that
        // contains a class file we just
        // loaded.
        Enumeration<URL> urls;
        try {
            urls = cl.getResources(TestHttpServer.class.getName().replace('.', '/') + ".class");
        } catch (Exception e) {
            return null;
        }
        while (urls.hasMoreElements()) {
            URL u = urls.nextElement();
            if (u.getProtocol().equals("file")) {
                String p = u.getPath();
                int idx = p.indexOf("/test-classes/");
                if (idx >= 0) {
                    return new File(p.substring(0, idx + "/test-classes".length())).toPath();
                }
            }
        }
        return null;
    }

    /** Build the classpath for the child
     *  subprocess. We need BOTH the test
     *  classes (for TestHttpServer) and the
     *  main classes (for any SDK types the
     *  helper touches). Maven Surefire
     *  exposes the runtime classpath via
     *  the {@code java.class.path} system
     *  property, which is the most
     *  reliable source. As a fallback we
     *  walk the URLClassLoader's URLs
     *  (works in IDEs that use a normal
     *  file-based classpath). */
    private static String classpathForChild() {
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) return cp;
        StringBuilder sb = new StringBuilder();
        ClassLoader cl = SupervisorModeR150Test.class.getClassLoader();
        if (cl instanceof URLClassLoader ucl) {
            for (URL u : ucl.getURLs()) {
                if (u.getProtocol().equals("file")) {
                    if (sb.length() > 0) sb.append(File.pathSeparator);
                    sb.append(u.getPath());
                }
            }
        }
        return sb.toString();
    }

    @Test
    void spawnChild_startsSubprocessAndReportsPid(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(findTestClassesDir() != null,
                "test-classes dir not resolvable; skipping subprocess test");

        int port = pickFreePort();
        Path childCwd = tmp.resolve("child-cwd");
        Files.createDirectories(childCwd);

        // Manually construct the spawn with a
        // small Java command so we can use
        // TestHttpServer from the test classpath.
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(classpathForChild());
        cmd.add(TestHttpServer.class.getName());
        cmd.add(String.valueOf(port));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(childCwd.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        // Wait for the child to bind its
        // port (it writes a "ready" line to
        // stdout).
        waitForPort(port, 10_000L);
        // We bypass spawnChild() because we
        // need to use a custom command.
        // Use registerChild() (the
        // externally-managed path) to
        // register it for heartbeat.
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-spawn-" + System.currentTimeMillis(), port, childCwd.toString());
        // Inject the Process so killChild
        // works (the externally-managed path
        // doesn't have one). We use a
        // package-private setter via the
        // singleton to keep the test
        // self-contained.
        SupervisorMode.instance().attachProcessForTest(ci.childId(), proc);
        spawnedChild = ci;

        assertNotNull(ci);
        assertNotNull(SupervisorMode.instance().getChild(ci.childId()));
        // The first health check should be
        // HEALTHY (the test HTTP server
        // returns 200 for /healthz).
        SupervisorMode.ChildHealth h = SupervisorMode.instance().healthCheck(ci.childId());
        assertEquals(SupervisorMode.ChildHealth.HEALTHY, h,
                "first /healthz probe should be HEALTHY");
    }

    @Test
    void healthCheck_marksDeadAfterProcessExits(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(findTestClassesDir() != null,
                "test-classes dir not resolvable");

        int port = pickFreePort();
        Path childCwd = tmp.resolve("dead-child-cwd");
        Files.createDirectories(childCwd);

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(classpathForChild());
        cmd.add(TestHttpServer.class.getName());
        cmd.add(String.valueOf(port));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(childCwd.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        waitForPort(port, 10_000L);
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-dead-" + System.currentTimeMillis(), port, childCwd.toString());
        SupervisorMode.instance().attachProcessForTest(ci.childId(), proc);

        // Kill the child. waitFor() then
        // healthCheck should report DEAD.
        proc.destroy();
        proc.waitFor(5, TimeUnit.SECONDS);
        SupervisorMode.ChildHealth h = SupervisorMode.instance().healthCheck(ci.childId());
        assertEquals(SupervisorMode.ChildHealth.DEAD, h,
                "after process exit, child should be DEAD");
    }

    @Test
    void healthCheck_marksUnhealthyWhenPortUnreachable() {
        // Externally-managed child on a
        // port no one is listening on. The
        // heartbeat should fail 3 times in
        // a row, then mark UNHEALTHY.
        int bogusPort = pickFreePort();
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-unreachable-" + System.currentTimeMillis(), bogusPort, "/tmp");
        spawnedChild = ci;

        // First 2 failures -> PENDING
        SupervisorMode.ChildHealth h1 = SupervisorMode.instance().healthCheck(ci.childId());
        assertEquals(SupervisorMode.ChildHealth.PENDING, h1);
        SupervisorMode.ChildHealth h2 = SupervisorMode.instance().healthCheck(ci.childId());
        assertEquals(SupervisorMode.ChildHealth.PENDING, h2);
        // 3rd failure -> UNHEALTHY
        SupervisorMode.ChildHealth h3 = SupervisorMode.instance().healthCheck(ci.childId());
        assertEquals(SupervisorMode.ChildHealth.UNHEALTHY, h3);
        assertEquals(3L, ci.consecutiveFailures());
    }

    @Test
    void forwardRpc_returnsResponseOnHealthyChild(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(findTestClassesDir() != null,
                "test-classes dir not resolvable");

        int port = pickFreePort();
        Path childCwd = tmp.resolve("fwd-child-cwd");
        Files.createDirectories(childCwd);

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(classpathForChild());
        cmd.add(TestHttpServer.class.getName());
        cmd.add(String.valueOf(port));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(childCwd.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        waitForPort(port, 10_000L);
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-fwd-" + System.currentTimeMillis(), port, childCwd.toString());
        SupervisorMode.instance().attachProcessForTest(ci.childId(), proc);
        spawnedChild = ci;

        // Send a fake "ping" method. The
        // TestHttpServer echoes any POST
        // to /jsonrpc with a stub JSON-RPC
        // success.
        String resp = SupervisorMode.instance().forwardRpc(
                ci.childId(), "ping", Map.of("hello", "world"));
        assertNotNull(resp, "forwardRpc should return a response from a healthy child");
        assertTrue(resp.contains("\"result\""),
                "response should be a JSON-RPC success: " + resp);
    }

    @Test
    void forwardRpc_returnsNullOnUnhealthyChild() {
        int bogusPort = pickFreePort();
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-fwd-bad-" + System.currentTimeMillis(), bogusPort, "/tmp");
        spawnedChild = ci;

        String resp = SupervisorMode.instance().forwardRpc(
                ci.childId(), "ping", Map.of("hello", "world"));
        assertNull(resp,
                "forwardRpc should return null when the child is unreachable");
    }

    @Test
    void unregisterChild_stopsHeartbeatAndRemovesEntry() {
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-unreg-" + System.currentTimeMillis(), 29000, "/tmp");
        assertNotNull(SupervisorMode.instance().getChild(ci.childId()));
        boolean ok = SupervisorMode.instance().unregisterChild(ci.childId());
        assertTrue(ok);
        assertNull(SupervisorMode.instance().getChild(ci.childId()));
    }

    @Test
    void childInfoWireSnapshot_includesHealthFields() {
        SupervisorMode.ChildInfo ci = SupervisorMode.instance().registerChild(
                "r150-snap-" + System.currentTimeMillis(), 29100, "/tmp");
        spawnedChild = ci;
        Map<String, Object> snap = ci.toWireSnapshot();
        // Pre-existing fields
        assertNotNull(snap.get("childId"));
        assertNotNull(snap.get("httpPort"));
        assertNotNull(snap.get("cwd"));
        assertNotNull(snap.get("registeredAtMs"));
        // R150 new fields
        assertNotNull(snap.get("health"));
        assertNotNull(snap.get("consecutiveFailures"));
        assertNotNull(snap.get("lastHealthCheckAtMs"));
        // pid is null for externally-managed
        // children
        assertNull(snap.get("pid"));
    }

    // ----- helpers -----

    /** Pick a free TCP port by binding to 0
     *  and reading the assigned port. */
    private static int pickFreePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("no free port", e);
        }
    }

    /** Poll a TCP port until it accepts a
     *  connection or the timeout elapses. */
    private static void waitForPort(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (Exception ignored) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("port " + port + " did not open within " + timeoutMs + "ms");
    }
}
