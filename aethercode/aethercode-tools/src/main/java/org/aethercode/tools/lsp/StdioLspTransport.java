package org.aethercode.tools.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * real LSP stdio transport. Spawns the language server as a child process, wires
 * the lsp4j {@link LanguageServer} interface to the JVM side.
 */
public class StdioLspTransport implements LspTransport {

    private static final Logger LOG = LoggerFactory.getLogger(StdioLspTransport.class);

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;

    public StdioLspTransport(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args == null ? List.of() : args;
        this.env = env;
    }

    @Override
    public String name() { return "stdio"; }

    public StdioLspSession start(LanguageClient client) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        pb.command(cmd);
        if (env != null) pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        InputStream in = proc.getInputStream();
        OutputStream out = proc.getOutputStream();
        Launcher<LanguageServer> launcher = Launcher.createLauncher(client, LanguageServer.class, in, out);
        java.util.concurrent.Future<Void> listen = launcher.startListening();
        LanguageServer server = launcher.getRemoteProxy();
        return new StdioLspSession(server, CompletableFuture.completedFuture(null), proc);
    }

    public static List<String> args(String... a) { return List.of(a); }
}
