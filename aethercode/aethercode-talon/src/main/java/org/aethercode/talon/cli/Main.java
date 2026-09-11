package org.aethercode.talon.cli;

import org.aethercode.talon.AsyncSubagents;
import org.aethercode.talon.DataLifecycle;
import org.aethercode.talon.FleetImport;
import org.aethercode.talon.Mcp;
import org.aethercode.talon.Speech;
import org.aethercode.talon.TalonConfig;
import org.aethercode.talon.TalonConfigError;
import org.aethercode.talon.channels.telegram.TelegramChannel;
import org.aethercode.talon.channels.telegram.TelegramChannelConfig;
import org.aethercode.talon.channels.whatsapp.WhatsAppChannel;
import org.aethercode.talon.channels.whatsapp.WhatsAppChannelConfig;
import org.aethercode.talon.cron.CronJobStore;
import org.aethercode.talon.cron.PersistentCronScheduler;
import org.aethercode.talon.host.TalonHost;
import org.aethercode.talon.interfaces.ChannelAdapter;
import org.aethercode.talon.runtime.DeepAgentRuntime;
import org.aethercode.talon.runtime.EchoAgentRuntime;
import org.aethercode.talon.runtime.InterruptOnConfigHelper;
import org.aethercode.talon.runtime.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Command line entry point for the Talon runtime host.
 *
 * <p>Java-native port of {@code deepagents_talon.__main__}. Parses the
 * command-line arguments, builds a {@link TalonHost} and either runs
 * it once ({@code --once}) or until interrupted.</p>
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    /**
     * @param args command-line arguments (mirrors the Python port).
     */
    public static void main(String[] args) {
        CommandLine cli = CommandLine.parse(args);
        configureLogging(cli.verbose);
        TalonConfig config;
        try {
            config = TalonConfig.fromEnv(cli.env, cli.baseHome);
        } catch (TalonConfigError e) {
            System.err.println("Talon configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (cli.command == Command.IMPORT_FLEET) {
            int code = runImportFleet(cli, config);
            System.exit(code);
            return;
        }
        if (cli.command == Command.MCP_CONFIG) {
            Mcp.printMcpConfigPaths(config);
            return;
        }
        if (cli.command == Command.MCP_LOGIN) {
            System.err.println(
                    "MCP login requires the deepagents-code module, which is not yet ported.");
            System.exit(1);
            return;
        }

        CronJobStore cronStore = new CronJobStore(config.assistantId(), config.cronDir());
        config.ensureHome();
        DataLifecycle.cleanupSensitiveState(config, cronStore);

        List<ChannelAdapter> channels = channels(config, cli.whatsapp, cli.telegram);
        CompletableFuture<DeepAgentRuntime> runtimeFuture = buildRuntime(config, cronStore);
        TalonHost host = new TalonHost(config, waitForRuntime(runtimeFuture), channels,
                null, Speech.buildVoiceTranscriber(config));
        if (!channels.isEmpty()) {
            host.setScheduler(new PersistentCronScheduler(
                    cronStore,
                    job -> host.runScheduledJob(job),
                    (job, text) -> {
                        deliverCronResult(host, channels, job, text);
                        return CompletableFuture.completedFuture(null);
                    }));
        }
        if (cli.once) {
            try {
                host.start().join();
                host.stop().join();
            } catch (RuntimeException e) {
                log.error("Talon host failed", e);
                System.exit(1);
            }
            return;
        }
        try {
            host.start().join();
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Talon host failed", e);
            System.exit(1);
        } finally {
            host.stop().join();
        }
    }

    // -----------------------------------------------------------------------
    // Command-line parsing
    // -----------------------------------------------------------------------

    enum Command { RUN, IMPORT_FLEET, MCP_CONFIG, MCP_LOGIN }

    private static final class CommandLine {
        boolean once;
        boolean whatsapp;
        boolean telegram;
        boolean verbose;
        Command command = Command.RUN;
        Path fleetExport;
        String assistantId;
        Path targetDir;
        String server;
        String mcpConfigPath;
        Map<String, String> env;
        Path baseHome;

        static CommandLine parse(String[] args) {
            CommandLine cli = new CommandLine();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--once" -> cli.once = true;
                    case "--whatsapp" -> cli.whatsapp = true;
                    case "--telegram" -> cli.telegram = true;
                    case "--verbose", "-v" -> cli.verbose = true;
                    case "import-fleet" -> {
                        cli.command = Command.IMPORT_FLEET;
                        if (i + 1 < args.length) {
                            cli.fleetExport = Paths.get(args[++i]);
                        }
                    }
                    case "--assistant-id" -> {
                        if (i + 1 < args.length) {
                            cli.assistantId = args[++i];
                        }
                    }
                    case "--target-dir" -> {
                        if (i + 1 < args.length) {
                            cli.targetDir = Paths.get(args[++i]);
                        }
                    }
                    case "mcp" -> {
                        if (i + 1 < args.length) {
                            String sub = args[++i];
                            if ("config".equals(sub)) {
                                cli.command = Command.MCP_CONFIG;
                            } else if ("login".equals(sub)) {
                                cli.command = Command.MCP_LOGIN;
                                if (i + 1 < args.length) {
                                    cli.server = args[++i];
                                }
                                if (i + 1 < args.length && "--mcp-config".equals(args[i + 1])) {
                                    i++;
                                    if (i + 1 < args.length) {
                                        cli.mcpConfigPath = args[++i];
                                    }
                                }
                            }
                        }
                    }
                    default -> {
                        // ignore unknown args (matches Python argparse)
                    }
                }
            }
            return cli;
        }
    }

    private static void configureLogging(boolean verbose) {
        // Logging is configured by the host via SLF4J; the verbose flag is
        // a placeholder for future debug-level configuration.
        if (verbose) {
            log.debug("Verbose logging requested");
        }
    }

    // -----------------------------------------------------------------------
    // Subcommands
    // -----------------------------------------------------------------------

    private static int runImportFleet(CommandLine cli, TalonConfig config) {
        if (cli.fleetExport == null) {
            System.err.println("import-fleet: missing <fleet-export.zip> argument");
            return 2;
        }
        Path target = cli.targetDir;
        Path assistantHome = null;
        if (target == null) {
            TalonConfig targetConfig = config;
            if (cli.assistantId != null) {
                java.util.Map<String, String> overrideEnv = new java.util.HashMap<>(config.env());
                overrideEnv.put("DEEPAGENTS_TALON_ASSISTANT_ID", cli.assistantId);
                targetConfig = TalonConfig.fromEnv(overrideEnv, config.home().getParent());
            } else if (!hasConfiguredAssistantId(config.env())) {
                java.util.Map<String, String> overrideEnv = new java.util.HashMap<>(config.env());
                overrideEnv.put("DEEPAGENTS_TALON_ASSISTANT_ID", cli.fleetExport.getFileName()
                        .toString().replaceAll("\\.zip$", ""));
                targetConfig = TalonConfig.fromEnv(overrideEnv, config.home().getParent());
            }
            target = targetConfig.manifestDir();
            assistantHome = targetConfig.home();
        }
        try {
            FleetImport.FleetImportResult result = FleetImport.importFleetZip(
                    cli.fleetExport, target, assistantHome);
            System.out.print(FleetImport.formatImportStdout(result));
            return 0;
        } catch (FleetImport.FleetImportError e) {
            System.err.println("import-fleet: " + e.getMessage());
            return 1;
        }
    }

    private static boolean hasConfiguredAssistantId(Map<String, String> env) {
        return env.containsKey("DEEPAGENTS_TALON_ASSISTANT_ID")
                || env.containsKey("AGENT_ASSISTANT_ID");
    }

    // -----------------------------------------------------------------------
    // Channel construction
    // -----------------------------------------------------------------------

    private static List<ChannelAdapter> channels(TalonConfig config,
                                                boolean whatsapp, boolean telegram) {
        List<ChannelAdapter> out = new ArrayList<>();
        if (whatsapp || envEnabled(config.env(), "DEEPAGENTS_TALON_WHATSAPP_ENABLED")) {
            out.add(new WhatsAppChannel(WhatsAppChannelConfig.fromTalonConfig(config)));
        }
        if (telegram || envEnabled(config.env(), "DEEPAGENTS_TALON_TELEGRAM_ENABLED")) {
            out.add(new TelegramChannel(TelegramChannelConfig.fromTalonConfig(config)));
        }
        return out;
    }

    private static boolean envEnabled(Map<String, String> env, String key) {
        String value = env.getOrDefault(key, "").toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes");
    }

    // -----------------------------------------------------------------------
    // Runtime construction
    // -----------------------------------------------------------------------

    private static CompletableFuture<DeepAgentRuntime> buildRuntime(TalonConfig config,
                                                                    CronJobStore cronStore) {
        return CompletableFuture.supplyAsync(() -> {
            if (config.model() == null) {
                return new DeepAgentRuntime("openai:gpt-4o-mini", List.of(), null, null,
                        null, cronStore, null, null, List.of(), Map.of(), null, null, true,
                        RuntimeEnv.DEFAULT_RECURSION_LIMIT, RuntimeEnv.DEFAULT_MAX_RETRIES,
                        RuntimeEnv.DEFAULT_MAX_CONTINUATIONS, config.env());
            }
            Map<String, String> env = runtimeEnv(config);
            List<?> asyncSubagents = AsyncSubagents.loadAsyncSubagents();
            Mcp.McpTools mcp = Mcp.loadMcpTools(config);
            for (Mcp.ServerInfo server : mcp.servers()) {
                if (server.error() != null) {
                    log.warn("MCP server {} failed: {}", server.name(), server.error());
                } else {
                    log.info("MCP server {} loaded {} tool(s)", server.name(),
                            server.tools().size());
                }
            }
            Map<String, Object> interrupt = InterruptOnConfigHelper.merge(null, env);
            DeepAgentRuntime runtime = new DeepAgentRuntime(
                    config.model(),
                    List.of(),
                    null,
                    (List<?>) asyncSubagents,
                    config.manifestDir(),
                    cronStore,
                    null,
                    null,
                    List.of(),
                    interrupt,
                    null,
                    null,
                    true,
                    RuntimeEnv.DEFAULT_RECURSION_LIMIT,
                    RuntimeEnv.DEFAULT_MAX_RETRIES,
                    RuntimeEnv.DEFAULT_MAX_CONTINUATIONS,
                    env);
            runtime.start().join();
            return runtime;
        });
    }

    private static Map<String, String> runtimeEnv(TalonConfig config) {
        java.util.Map<String, String> values = new java.util.HashMap<>(System.getenv());
        values.putAll(config.env());
        return values;
    }

    private static org.aethercode.talon.interfaces.AgentRuntime waitForRuntime(
            CompletableFuture<DeepAgentRuntime> future) {
        DeepAgentRuntime runtime;
        try {
            runtime = future.join();
        } catch (RuntimeException e) {
            log.error("Failed to construct DeepAgentRuntime; falling back to echo runtime", e);
            return new EchoAgentRuntime();
        }
        return runtime;
    }

    private static void deliverCronResult(TalonHost host, List<ChannelAdapter> channels,
                                          org.aethercode.talon.cron.CronJob job, String text) {
        for (ChannelAdapter channel : channels) {
            String provider;
            try {
                provider = channel.status().join().provider();
            } catch (RuntimeException e) {
                provider = null;
            }
            if (job.origin().channel().isEmpty()
                    || (provider != null && provider.equals(job.origin().channel().get()))) {
                host.deliverScheduledResult(channel, job, text).join();
                return;
            }
        }
    }
}
