package org.aethercode.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code aethercode tui} — proxy to the bundled Node-based
 * Ink TUI. The TUI bundle (ac-tui.js) ships next to the jar in
 * {@code dist/ac-tui/} and talks to the daemon over JSON-RPC
 * stdio. Running this subcommand avoids the user having to know
 * about the {@code node dist/ac-tui/ac-tui.js} incantation.
 *
 * <p>Usage:
 * <pre>
 *   java -jar aethercode-0.2.1.jar tui                # interactive Ink TUI
 *   java -jar aethercode-0.2.1.jar tui --print "hi"   # one-shot headless
 *   java -jar aethercode-0.2.1.jar tui --node /path   # explicit node
 * </pre>
 *
 * <p>The command finds the TUI bundle by:
 * <ol>
 *   <li>{@code --tui-script <path>} explicit override</li>
 *   <li>{@code AETHERCODE_TUI} env var</li>
 *   <li>Same directory as the running jar → {@code ac-tui/ac-tui.js}</li>
 *   <li>Parent directory of the jar → {@code ac-tui/ac-tui.js}</li>
 *   <li>{@code ./dist/ac-tui/ac-tui.js} relative to cwd</li>
 * </ol>
 */
@Command(
        name = "tui",
        mixinStandardHelpOptions = true,
        version = "aethercode-tui 0.2.1",
        description = "Launch the Node-based Ink TUI that talks to the AetherCode daemon over JSON-RPC 2.0."
)
public class TuiCommand implements Callable<Integer> {

    @Parameters(arity = "0..*", description = "Arguments passed through to the TUI (e.g. --print \"...\" or --no-color).")
    List<String> tuiArgs = new ArrayList<>();

    @Option(names = {"--node"}, description = "Path to the node binary. Default: 'node' from PATH.")
    String nodeBinary = "node";

    @Option(names = {"--tui-script"}, description = "Explicit path to ac-tui.js. Default: search next to the running jar.")
    Path tuiScript;

    @Option(names = {"--jar"}, description = "Path to the aethercode-*.jar to pass to the TUI. Default: the jar that contains this class.")
    Path jarOverride;

    @Override
    public Integer call() throws Exception {
        Path script = resolveTuiScript();
        if (script == null) {
            System.err.println("error: could not locate ac-tui.js next to the jar.");
            System.err.println("hint: rebuild the TUI (cd aethercode-tui && npm run build) or set AETHERCODE_TUI / --tui-script.");
            return 3;
        }
        if (!Files.isRegularFile(script)) {
            System.err.println("error: TUI script not found at " + script);
            return 3;
        }
        Path jar = jarOverride != null ? jarOverride : findOwnJar();
        if (jar == null) {
            System.err.println("warning: could not determine own jar location; the TUI may not auto-detect it.");
            System.err.println("         pass --jar <path> if the TUI cannot find aethercode-*.jar.");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(nodeBinary);
        cmd.add(script.toString());
        if (jar != null) {
            cmd.add("--jar");
            cmd.add(jar.toString());
        }
        cmd.addAll(tuiArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .inheritIO()
                .redirectErrorStream(true);
        Process proc = pb.start();
        // Forward SIGINT to the child so Ctrl-C in the TUI also stops
        // the daemon (they share the console).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { proc.destroy(); } catch (Exception ignore) { /* ignore */ }
        }));
        int code = proc.waitFor();
        return code == 0 ? 0 : 1;
    }

    private Path resolveTuiScript() {
        if (tuiScript != null) return tuiScript;
        String env = System.getenv("AETHERCODE_TUI");
        if (env != null && !env.isEmpty()) return Path.of(env);

        // The running jar is at <dist>/aethercode-0.2.1.jar; the TUI
        // is at <dist>/ac-tui/ac-tui.js. Both live in the same dir.
        Path ownJar = findOwnJar();
        if (ownJar != null) {
            Path distDir = ownJar.getParent();
            Path candidate = distDir.resolve("ac-tui/ac-tui.js");
            if (Files.isRegularFile(candidate)) return candidate;
            candidate = distDir.resolve("../aethercode-tui/dist/ac-tui.js");
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }

        // Fall back to cwd-relative locations.
        Path cwdRel = Path.of("dist/ac-tui/ac-tui.js").toAbsolutePath();
        if (Files.isRegularFile(cwdRel)) return cwdRel;
        cwdRel = Path.of("aethercode-tui/dist/ac-tui.js").toAbsolutePath();
        if (Files.isRegularFile(cwdRel)) return cwdRel;
        return null;
    }

    /**
     * Locate the jar that contains this class. The shaded jar is
     * at {@code dist/aethercode-X.Y.Z.jar}; we read the class
     * location and walk up to the jar file.
     */
    private Path findOwnJar() {
        try {
            var src = TuiCommand.class.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            Path code = Path.of(src.getLocation().toURI());
            // When running from a shaded jar, the location is the
            // jar itself. When running from classes/ (unshaded), we
            // need to walk up.
            if (Files.isRegularFile(code) && code.toString().endsWith(".jar")) {
                return code;
            }
            // Walk up to find the jar.
            Path cur = code.toAbsolutePath();
            for (int i = 0; i < 6 && cur != null; i++) {
                try (var ds = Files.newDirectoryStream(cur, "aethercode-*.jar")) {
                    for (Path p : ds) {
                        if (!p.toString().endsWith(".jar.original")) return p;
                    }
                } catch (Exception ignore) { /* ignore */ }
                cur = cur.getParent();
            }
        } catch (Exception ignore) { /* ignore */ }
        return null;
    }
}
