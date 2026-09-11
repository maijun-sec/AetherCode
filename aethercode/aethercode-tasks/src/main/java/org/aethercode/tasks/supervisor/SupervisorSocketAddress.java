package org.aethercode.tasks.supervisor;

import java.nio.file.Path;

/**
 * prior round (T-311/§4.1.2 design.md): the supervisor listens on a
 * platform-specific endpoint under the user's AetherCode home:
 * <ul>
 *   <li>POSIX (mac/linux): a Unix domain socket at
 *       {@code ~/.aethercode/supervisor.sock}</li>
 *   <li>Windows: a named pipe at {@code \\.\pipe\aethercode-supervisor}</li>
 * </ul>
 *
 * <p>Tests may override the location via {@link #override(Path)} so
 * multiple supervisors (or the in-process client used in unit
 * tests) can run side-by-side without colliding.
 */
public final class SupervisorSocketAddress {

    private static volatile Path overridePath;
    private static volatile String overridePipeName;

    private SupervisorSocketAddress() {}

    /** Path on POSIX systems. Returns null if a Windows pipe override is set. */
    public static Path defaultUnixPath() {
        Path ovr = overridePath;
        if (ovr != null) return ovr;
        return SupervisorHome.dir().resolve("supervisor.sock");
    }

    /** Pipe name on Windows. Returns null if a POSIX override is set. */
    public static String defaultWindowsPipeName() {
        String ovr = overridePipeName;
        if (ovr != null) return ovr;
        return "aethercode-supervisor";
    }

    /**
     * Test-only override: redirect the Unix socket to a different
     * path. Pass {@code null} to clear.
     */
    public static void override(Path unixPath, String windowsPipeName) {
        overridePath = unixPath;
        overridePipeName = windowsPipeName;
    }

    /** Clear any test override and revert to defaults. */
    public static void clearOverride() {
        overridePath = null;
        overridePipeName = null;
    }
}
