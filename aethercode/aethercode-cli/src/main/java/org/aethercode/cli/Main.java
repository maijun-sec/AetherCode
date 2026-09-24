package org.aethercode.cli;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.tool.Tool;
import org.aethercode.engine.springai.SpringAiChatClient;
import org.aethercode.mcp.McpManager;
import org.aethercode.mcp.McpServers;
import org.aethercode.permission.SettingsPermissions;
import org.aethercode.permission.cli.GrantsCli;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.tasks.cli.TaskCli;
import org.aethercode.tools.StandardTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI entry point. Mirrors the TS {@code entrypoints/cli.tsx} — fast-path dispatch followed
 * by a full REPL bootstrap.
 *
 * <p>Fast paths:
 * <ul>
 *   <li>{@code --version} -&gt; print version and exit</li>
 *   <li>{@code --print "..."} -&gt; non-interactive single turn (headless SDK mode)</li>
 *   <li>{@code --help} / no args -&gt; REPL</li>
 * </ul>
 */
@Command(
        name = "aethercode",
        mixinStandardHelpOptions = true,
        version = "aethercode 0.3.0",
        description = "Java AI agent — local-first, MCP-friendly, ready for the JVM.",
        subcommands = { McpCommand.class, TuiCommand.class, TaskCli.class, GrantsCli.class,
                        SessionCommand.class, WorkflowCommand.class,
                        // aethercode a2a <host> card | send | stream | get | cancel
                        A2aCommand.class }
)
public class Main implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    // ANSI dim/reset codes. The tui-jline module used to expose
    // a {@code TerminalPalette} with the same constants; after the module
    // was retired we inline the two codes we still need (DIM and RESET)
    // to keep the --print tool-use / SideNote lines formatted. Anything
    // fancier (256-color, truecolor) should live in the TUI bundle, not
    // in this headless path.
    private static final String ANSI_DIM = "\u001b[2m";
    private static final String ANSI_RESET = "\u001b[0m";

    @Parameters(arity = "0..1", description = "Optional prompt for non-interactive mode (with --print).")
    String prompt;

    @Option(names = {"--provider"}, description = "Provider name (e.g. minmax, glm, qwen, deepseek, anthropic, openai, gemini). Default: first provider in ~/.aethercode/providers.yaml, or 'minmax' if the file is missing.")
    String providerName;

    @Option(names = {"--model"}, description = "Model id. Default: provider's defaultModel in providers.yaml, AETHERCODE_DEFAULT_MODEL env var, or 'MiniMax-M3' for the legacy single-provider path.")
    String model;

    @Option(names = {"--print", "-p"}, description = "Run a single turn non-interactively and exit.")
    boolean printMode;

    @Option(names = {"--permission-mode"}, description = "Permission mode: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE} (overridden to BYPASS_PERMISSIONS in --print mode since there is no interactive prompt).")
    PermissionMode permissionMode = PermissionMode.DEFAULT;

    @Option(names = {"--cwd"}, description = "Working directory. Default: current dir.")
    Path cwd = Path.of("").toAbsolutePath();

    @Option(names = {"--api-key"}, description = "API key for the chosen provider (or set the provider-specific env var).")
    String apiKey;

    @Option(names = {"--base-url"}, description = "Override MiniMax's base URL (advanced).")
    String baseUrl;

    @Option(names = {"--verbose", "-v"}, description = "Verbose logging (DEBUG level for the LLM client).")
    boolean verbose;

    @Option(names = {"--max-tokens"}, description = "Max tokens in the model's response (default 4096; provider default if 0).")
    int maxTokens = 4096;

    /** per-session context window in tokens. Default 0 (AutoCompact's 200K).
     *  Set to 1_000_000 for million-token models. */
    @Option(names = {"--context-window"}, description = "Context window in tokens (default 0 = AutoCompact's 200K; 1_000_000 for million-token models).")
    int contextWindow = 0;

    @Option(names = {"--max-turns"}, description = "Cap on model turns per query (default 50). 0/-1 = unbounded. The loop detector (see --loop-detect-*) also stops pathological repetition regardless.")
    int maxTurns = 50;

    @Option(names = {"--loop-detect-window"}, description = "Prior round: sliding-window size for the loop detector (default 8). Set to 0 to disable. The same tool call must appear --loop-detect-threshold times in this many turns to trigger a stop.")
    int loopDetectWindow = 8;

    @Option(names = {"--loop-detect-threshold"}, description = "Prior round: number of identical tool calls in the window that triggers a stop (default 3). Must be <= window.")
    int loopDetectThreshold = 3;

    /** threshold for the high-risk tool pattern. Default 4 (was 2
     *  in the prior round). The prior round's default of 2 fired whenever any high-risk
     *  tool was called twice in 8 turns, regardless of input. Bumped
     *  to 4 + full-fingerprint matching so legitimate exploration
     *  (e.g. `ls` then `cat`) is no longer killed as a "loop". */
    @Option(names = {"--loop-detect-high-risk-threshold"}, description = "R80a: number of identical high-risk tool calls (same tool name + same input) that triggers a stop (default 4). Set to 0 to disable the high-risk pattern.")
    int loopDetectHighRiskThreshold = 4;

    @Option(names = {"--no-color"}, description = "Disable ANSI colours in the TUI.")
    boolean noColor;

    /**
     * R343: explicit single-file providers.yaml override. When
     * set, the daemon reads THIS file instead of the
     * install-dir + cwd cascade — useful for ops scripts
     * ("launch the daemon with a custom catalogue for the
     * duration of this CI run") and for testing new
     * catalogues without modifying the install. The
     * {@code AETHERCODE_PROVIDERS_YAML} env var has the
     * same effect; CLI flag wins when both are set.
     *
     * <p>When this flag is unset AND the env var is unset,
     * the daemon uses the R343 cascade (install-dir yaml →
     * cwd yaml → bundled yaml → bundledDefaults()).
     */
    @Option(names = {"--providers-yaml"}, description = "R343: explicit path to a single providers.yaml file. Overrides the install-dir + cwd cascade. Same as AETHERCODE_PROVIDERS_YAML env var (flag wins).")
    Path providersYamlOverride;

    /**
     * R343: explicit install directory. Defaults to the
     * directory holding the daemon jar (resolved via the
     * jar's protection domain). Overriding this is mainly
     * useful for dev / test contexts where the daemon is
     * launched from a build / target dir and the operator
     * wants to point at a separate "installed as if" tree.
     */
    @Option(names = {"--install-dir"}, description = "R343: install directory whose providers.yaml is the global catalogue. Default: parent dir of the daemon jar.")
    Path installDirOverride;

    @Option(names = {"--mcp-config"}, description = "Path to mcp.json. Default: .aethercode/mcp.json in cwd.")
    Path mcpConfig;

    @Option(names = {"--sessions-dir"}, description = "R6: multi-session transcript directory. Default: .aethercode/sessions in cwd.")
    Path sessionsDir;

    /** disable skill discovery. By default the engine
     *  scans {@code ~/.aethercode/skills/} and {@code <cwd>/.aethercode/skills/}
     *  for SKILL.md files; pass this flag to skip the scan
     *  (e.g. in unit tests or to keep startup fast). */
    @Option(names = {"--no-skills"}, description = "Prior round: disable SKILL.md discovery.")
    boolean noSkills = false;

    /** disable agent discovery. By default the engine
     *  scans {@code ~/.aethercode/agents/} for {@code <name>/agent.md};
     *  pass this flag to skip the scan. */
    @Option(names = {"--no-agents"}, description = "Prior round: disable Mavis agent discovery.")
    boolean noAgents = false;

    /**
     * disable the AetherCodeAgent create-agent
     * system-prompt fragment. The fragment adds explicit
     * "call todo_write first", "batch related tool calls",
     * and "do not stop after the first tool call" rules
     * on top of the existing {@code SystemPrompt}. By
     * default the daemon enables it; a user who wants the
     * legacy prompt can pass {@code --no-create-agent} or
     * set {@code AETHERCODE_CREATE_AGENT=false}.
     */
    @Option(names = {"--no-create-agent"},
            description = "R173: disable the AetherCodeAgent create-agent system-prompt fragment.")
    boolean noCreateAgent = false;

    /** opt-out helper. Reads the CLI flag and the
     *  env var, defaulting to {@code true} (enabled) when
     *  neither is set. The env-var spelling matches the
     *  flag: {@code AETHERCODE_CREATE_AGENT=false} disables. */
    boolean createAgentEnabled() {
        if (System.getenv("AETHERCODE_CREATE_AGENT") != null) {
            String v = System.getenv("AETHERCODE_CREATE_AGENT").trim().toLowerCase();
            if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) {
                return false;
            }
        }
        return !noCreateAgent;
    }

    /** headless daemon mode. The process reads JSON-RPC 2.0
     *  messages from stdin and writes them to stdout. Tools (TUI,
     *  multica, custom orchestrators) spawn this process and
     *  pipe requests through it. Mutually exclusive with the
     *  REPL. */
    @Option(names = {"--daemon"}, description = "R29: run as a headless JSON-RPC daemon. Reads requests from stdin, writes responses / notifications to stdout. Logging goes to stderr.")
    boolean daemon;

    /** HTTP listener port. When > 0 (and {@link #daemon} is set),
     *  the daemon also exposes its JSON-RPC interface over HTTP/WebSocket
     *  in addition to stdio. {@code 0} = stdio only (default). */
    @Option(names = {"--daemon-port"}, description = "Prior round: if > 0, expose the JSON-RPC interface over HTTP on the given port. Works alongside --daemon stdio.")
    int daemonPort = 0;

    /** HTTP+WebSocket listener port. When > 0, the daemon runs
     *  in HTTP mode (no stdio) and accepts multiple WebSocket clients
     *  at ws://localhost:N/ws. Set to 0 to disable (default).
     *
     *  <p>HTTP mode is the recommended way to run the daemon for
     *  desktop-app frontends (Tauri, Electron, native, …) because
     *  each client gets its own connection and the engine state is
     *  shared across all of them. */
    @Option(names = {"--http-port"}, description = "R80: if > 0, run the daemon in HTTP+WebSocket mode on the given port. The JSON-RPC 2.0 surface is exposed at ws://localhost:N/ws. Multiple clients share the same engine. Mutually exclusive with --daemon.")
    int httpPort = 0;

    public static void main(String[] args) {
        // removed the JavawAutoRelaunch path. It was only
        // meaningful for the JLine/Lanterna fullscreen REPL (which
        // needed `javaw` on Windows to attach a console). The Ink
        // TUI runs in its own Node process and has no such constraint.
        // Quiet SLF4J bootstrap — logback-classic on the classpath will be configured by the
        // first log statement; no programmatic setup required for R1.
        CommandLine cl = new CommandLine(new Main());
        // the `tui` subcommand forwards unknown args to the
        // Ink TUI. Allow unmatched arguments on the `tui` subcommand
        // and treat unknown options (`--print`, `--no-color`, etc.)
        // as positional args so they reach the Ink process.
        picocli.CommandLine.Model.ParserSpec tuiParser = cl.getSubcommands().get("tui").getCommandSpec().parser();
        tuiParser.unmatchedArgumentsAllowed(true);
        tuiParser.unmatchedOptionsArePositionalParams(true);
        int code = cl.execute(args);
        System.exit(code);
    }

    @Override
    public Integer call() throws Exception {
        // Fast path: --version / --help handled by picocli mixins.

        if (printMode) {
            if (prompt == null || prompt.isBlank()) {
                System.err.println("--print requires a prompt argument");
                return 2;
            }
            // --print is non-interactive. The default permission mode would
            // block on a permission prompt (e.g. for file_write) that nobody
            // can answer in a headless context. Bump to BYPASS_PERMISSIONS by
            // default so file_write / file_edit / bash just work. The user
            // can still override with --permission-mode if they want prompts.
            if (permissionMode == PermissionMode.DEFAULT) {
                permissionMode = PermissionMode.BYPASS_PERMISSIONS;
            }
            return runHeadless(prompt);
        }
        // positional argument without --print is almost always a mistake.
        if (prompt != null && !prompt.isBlank()) {
            System.err.println("Got positional argument \"" + prompt + "\" without --print.");
            System.err.println("Did you mean one of:");
            System.err.println("  --print \"" + prompt + "\"       # non-interactive single turn");
            System.err.println("Or just omit the argument to drop into the REPL.");
            return 2;
        }
        if (daemon) {
            // headless daemon mode — no REPL, no UI. Just
            // build the engine and hand it to DaemonRunner. The
            // daemon will read JSON-RPC from stdin / write to
            // stdout until the peer closes the pipe.
            // pass the buildEngineForSession
            // closure so the daemon's SessionManager
            // can materialise new engines for the
            // createEngine RPC. Without the closure
            // the factory falls back to
            // refuseNonDefaultFactory (the prior round's
            // minimum scope).
            AetherCodeEngine engine = buildEngine();
            return DaemonRunner.run(engine, this::buildEngineForSession);
        }
        if (httpPort > 0) {
            // HTTP+WebSocket daemon mode. One engine, many
            // clients. We use a separate entry point so the run
            // loop blocks on a "server stopped" future rather than
            // stdin EOF.
            // same factory closure as the
            // stdio daemon path; the HTTP server
            // shares the SessionManager across every
            // WebSocket client.
            AetherCodeEngine engine = buildEngine();
            return DaemonRunner.runHttp(engine, httpPort, this::buildEngineForSession);
        }
        return runRepl();
    }

    private int runHeadless(String input) {
        AetherCodeEngine engine = buildEngine();
        // print a "📋 TODO (n/m)" block whenever the in-session todo list
        // changes (the model called todo_write). Without this, the user has
        // no idea how the model is breaking down the request or where it is
        // in the plan — the only signal was the final summary at the end.
        java.util.concurrent.atomic.AtomicReference<java.util.List<java.util.Map<String, Object>>> lastTodo =
                new java.util.concurrent.atomic.AtomicReference<>(engine.appState().todoList());
        if (!lastTodo.get().isEmpty()) printTodoList(lastTodo.get(), "📋 existing plan");
        engine.appState().onTodoUpdate(next -> {
            // De-dupe: TodoWriteTool may be called with an identical list as a
            // status refresh; skip those to keep --print output quiet.
            if (sameTodoList(next, lastTodo.get())) return;
            lastTodo.set(next);
            System.out.println();
            printTodoList(next, "📋 TODO plan");
        });
        engine.query(input).forEach(ev -> {
            if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                System.out.print(td.text());
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.ToolUseStart tu) {
                // surface tool calls in --print mode. Previously these
                // were swallowed (either by spring-ai's internal loop, or by
                // the engine's own batching). With proxyToolCalls(true) the
                // model emits each tool call as a discrete event before the
                // engine runs it; we print "→ toolname(args)" so the user
                // sees the agent's plan as it happens. The actual tool result
                // is fed back to the model — we don't print it (the model's
                // reply to the user covers it).
                String argStr = summariseArgs(tu.input());
                System.out.println();
                System.out.println(ANSI_DIM
                        + "  → " + tu.name() + "(" + argStr + ")"
                        + ANSI_RESET);
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd) {
                System.out.println();
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.SideNote sn
                    && ("task".equals(sn.kind()) || "memory".equals(sn.kind()))) {
                // print task SideNotes so the user can see the task ID.
                // also print memory SideNotes — "recalled N memory file(s)"
                // is useful signal that the agent has the right context.
                System.out.println(ANSI_DIM
                        + "  [" + sn.message() + "]" + ANSI_RESET);
            }
        });
        return 0;
    }

    /** Pretty-print the todo list. Includes a "step N of M" header and
     *  per-item status icons (✓ done / ▶ in progress / ○ pending). */
    private static void printTodoList(java.util.List<java.util.Map<String, Object>> todos, String header) {
        if (todos == null || todos.isEmpty()) return;
        int done = 0, inProgress = 0;
        for (var t : todos) {
            String s = String.valueOf(t.get("status"));
            if ("completed".equals(s)) done++;
            else if ("in_progress".equals(s)) inProgress++;
        }
        System.out.println(header + " — " + done + "/" + todos.size() + " done" + (inProgress > 0 ? " (" + inProgress + " in progress)" : ""));
        int i = 1;
        for (var t : todos) {
            String content = String.valueOf(t.get("content"));
            String s = String.valueOf(t.get("status"));
            String icon = switch (s) {
                case "completed" -> "✓";
                case "in_progress" -> "▶";
                default -> "○";
            };
            System.out.println("  " + icon + " " + i + ". " + content);
            i++;
        }
    }

    /** short summary of tool args for the --print tool-call line. Long
     *  values (multi-line strings, big JSON blobs) are truncated so the
     *  output stays scannable. Returns an empty string when there are no
     *  args, or a compact "k=v, k=v" form otherwise. */
    private static String summariseArgs(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : args.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append(e.getKey()).append('=');
            Object v = e.getValue();
            if (v == null) { sb.append("null"); continue; }
            String s = v.toString();
            if (s.length() > 40) s = s.substring(0, 37) + "...";
            // strip newlines so a multi-line bash command doesn't break our
            // single-line "→ tool(args)" display.
            s = s.replace('\n', ' ').replace('\r', ' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Cheap structural equality for the todo list — same length and each
     *  entry has the same status + content. Avoids re-printing when the
     *  model calls todo_write with an unchanged plan. */
    private static boolean sameTodoList(java.util.List<java.util.Map<String, Object>> a,
                                         java.util.List<java.util.Map<String, Object>> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i); var y = b.get(i);
            if (!String.valueOf(x.get("content")).equals(String.valueOf(y.get("content")))) return false;
            if (!String.valueOf(x.get("status")).equals(String.valueOf(y.get("status")))) return false;
        }
        return true;
    }

    private int runRepl() {
        // the Java REPL (JLine + Lanterna) was retired in favour of
        // the Ink-based TUI (R31+) that ships next to the jar. Running
        // the cli with no arguments now delegates to {@code aethercode tui},
        // which is the canonical interactive surface. Users without Node.js
        // can still drive the engine headlessly via {@code --print "..."}.
        //
        // Why: keeping two interactive surfaces (Java REPL + Ink TUI)
        // doubled the maintenance load and the Java REPL was always the
        // weaker of the two (no markdown, no permission cards, no
        // subagent panel, no slash commands). The TS bundle has had
        // parity (and then some) since R86. The legacy {@code --fullscreen}
        // and {@code --no-fullscreen} flags are dropped — they were
        // only meaningful to the Java REPL.
        TuiCommand tui = new TuiCommand();
        tui.nodeBinary = "node";
        tui.tuiScript = null;
        tui.jarOverride = null;
        tui.tuiArgs = new java.util.ArrayList<>();
        if (noColor) tui.tuiArgs.add("--no-color");
        // The Ink TUI inherits the cli's --cwd so it can resolve
        // project-local rules/skills/agents the same way the daemon would.
        if (cwd != null) {
            tui.tuiArgs.add("--cwd");
            tui.tuiArgs.add(cwd.toString());
        }
        try {
            return tui.call();
        } catch (Exception e) {
            LOG.error("delegating to TuiCommand failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    private AetherCodeEngine buildEngine() {
        // the daemon's SessionManager factory
        // (see DaemonRunner.buildSessionManager) calls
        // back into this method when the user creates a
        // non-default engine. The default engine
        // (returned here) uses the standard
        // {@code null} sessionId which the engine
        // resolves to "default" in AppState.
        return buildEngineForSession(null);
    }

    /** build a fresh engine with the same
     *  configuration as the CLI flags + providers.yaml
     *  + cwd, but with an explicit sessionId override.
     *  Used by the daemon's SessionManager factory to
     *  materialise new engines on demand (when the user
     *  calls {@code createEngine}). The default path
     *  ({@link #buildEngine}) delegates here with a
     *  null sessionId.
     *
     *  <p>Each call returns a brand-new, fully
     *  initialised engine. Skill / agent registries
     *  are re-scanned for the new engine so a fresh
     *  session sees the latest on-disk state (the
     *  registries auto-reload on a 10s schedule, but
     *  for the explicit createEngine path we want
     *  freshness, not whatever the background thread
     *  last observed). */
    AetherCodeEngine buildEngineForSession(String sessionId) {
        // R-paper-batch7-papercompat-engine: include the 8 paper-compat
        // tools (architecture / saturation / redflag / byzantine / voting
        // / plan) on top of the standard 18. A real business process
        // needs the full set; the LLM can decide at runtime whether to
        // call them based on the user's prompt. We compose the pool
        // here (in aethercode-cli) instead of inside StandardTools
        // because adding a tools → orchestration dependency would
        // create a module cycle.
        List<Tool> pool = new ArrayList<>(StandardTools.all());
        pool.addAll(new org.aethercode.orchestration.papercompat.PaperCompatTools().buildAll());
        // working-memory tools. The prior round created the
        // WorkingMemoryBuffer but no one was writing to it. These
        // 4 tools let the model actively curate its own per-query
        // scratchpad (TODO / EVIDENCE / PLAN_STEP / etc).
        pool.addAll(org.aethercode.memory.tools.WorkingMemoryTools.all());
        // stateful MCP manager. We use the manager
        // (not the static McpServers.loadFrom) so the
        // file watcher can call {@code reload()} later
        // and the engine's tool pool is updated in place.
        // The tools get the {@code mcp:} prefix so the
        // engine's {@code replaceMcpTools} can find them
        // for an atomic swap on reload.
        McpManager mcp = new McpManager();
        java.nio.file.Path mcpFile = mcpConfig != null
                ? mcpConfig
                : cwd.resolve(".aethercode").resolve("mcp.json");
        for (Tool t : mcp.loadInitial(mcpFile)) {
            if (t.name() != null && t.name().startsWith("mcp:")) {
                pool.add(t);
            } else {
                // The McpServers factory returns tools named
                // "servername__toolname"; the manager
                // preserves that. Add the prefix so the
                // reload handler can find them later.
                pool.add(prefixMcpTool(t));
            }
        }
        // Load project permissions if present.
        java.nio.file.Path settingsFile = cwd.resolve(".aethercode").resolve("settings.json");
        SettingsPermissions perms = SettingsPermissions.loadFrom(settingsFile);

        // R343: resolve providers via the cascade. The pre-R343
        // flat user-home path is gone — the new two-tier
        // config is (1) install-dir/providers.yaml (global)
        // + (2) cwd/.aethercode/providers.yaml (per-project).
        // Both `--providers-yaml` and `AETHERCODE_PROVIDERS_YAML`
        // short-circuit the cascade for ops / CI scripts.
        org.aethercode.core.providers.ProviderRegistry providers =
                resolveProvidersRegistry(cwd);
        org.aethercode.core.providers.ProviderSpec providerSpec = null;
        if (providerName != null && !providerName.isBlank()) {
            providerSpec = providers.get(providerName)
                    .orElseThrow(() -> new RuntimeException(
                            "unknown provider: " + providerName
                                    + " (known: " + providers.list().stream()
                                    .map(org.aethercode.core.providers.ProviderSpec::name)
                                    .toList() + ")"));
        } else if (!providers.list().isEmpty()) {
            // No explicit --provider: default to the first entry
            // in providers.yaml (or the bundled default if the
            // file is missing).
            providerSpec = providers.defaultProvider().orElse(null);
        }

        AetherCodeEngine.Builder b = AetherCodeEngine.builder()
                .cwd(cwd)
                .provider(providerSpec)
                // model resolution order:
                // 1. --model CLI flag (explicit override)
                // 2. AETHERCODE_DEFAULT_MODEL env var
                //    (allows the daemon to default to
                //    MiniMax-M3 without the TUI having
                //    to pass --model every time)
                // 3. provider's defaultModel in providers.yaml
                // 4. legacy fallback: "MiniMax-M3"
                // The CLI flag wins; the env var is
                // for daemon-only deployments where
                // the user never passes --model.
                .model(resolveModel(model, providerSpec))
                .apiKey(apiKey)
                .maxTurnsPerQuery(maxTurns)
                .loopDetector(loopDetectWindow, loopDetectThreshold)
                .contextWindow(contextWindow)
                .permissionMode(permissionMode)
                .permissions(perms)
                // load the project-level permission matrix from
                // <cwd>/.aethercode/config.json. Falls back to the safe
                // built-in defaults if the file is missing or invalid.
                .permissionMatrix(
                        org.aethercode.config.ConfigEngine.loadFromProjectRoot(cwd)
                                .permissionMatrix)
                // no in-process prompter. A null prompter means
                // "ask" is auto-denied (see ProjectPermissionPolicy), which
                // is the right behaviour for the headless --print path.
                // The daemon / HTTP paths swap a JsonRpcPermissionPrompter
                // in once the dispatcher is wired; the TUI path (runRepl)
                // spawns a separate Node process and never calls
                // buildEngine() at all.
                .tools(pool);
        // install the explicit sessionId when
        // the factory is materialising a new engine.
        // null means "use AppState's default", which
        // the constructor maps to "default".
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        if (maxTokens > 0 || baseUrl != null) {
            // Legacy explicit-options path. Only used
            // when the user passed --base-url or
            // --max-tokens without a provider spec.
            ChatClient.Options opts = providerSpec != null
                    ? new ChatClient.Options(
                            providerSpec.apiKey(),
                            baseUrl != null ? baseUrl : providerSpec.baseUrl(),
                            maxTokens > 0 ? maxTokens : 0,
                            1.0)
                    : SpringAiChatClient.minimaxDefaults();
            if (apiKey != null) opts = opts.withApiKey(apiKey);
            if (maxTokens > 0) opts = opts.withMaxTokens(maxTokens);
            if (baseUrl != null) opts = opts.withBaseUrl(baseUrl);
            b.options(opts);
        }
        if (providerSpec != null) {
            LOG.info("using provider: {} (model: {})", providerSpec.name(),
                    (model == null || model.isBlank()) ? providerSpec.defaultModel() : model);
        }
        // wire the SessionStore into the engine when
        // --sessions-dir is set. The default for HTTP / daemon
        // mode is <cwd>/.aethercode/sessions. Without this
        // wiring, the engine is single-session and the new
        // listSessions / loadSession / createSession /
        // deleteSession RPCs return ENGINE_ERROR (gracefully
        // — they don't crash). The REPL mode already had its
        // own wiring via `repl.withSessions(...)`; this new
        // path lets the daemon use the same store. The REPL
        // path is unchanged so /resume + /fork still work
        // there. */
        if (sessionsDir != null) {
            org.aethercode.core.transcript.SessionStore store =
                    new org.aethercode.core.transcript.SessionStore(sessionsDir);
            b.sessionStore(store);
        }
        // wire the SkillRegistry. The user-tier root
        // defaults to ~/.aethercode/skills (R210 — was ~/.minimax/skills
        // previously; the rename aligns the user-tier with the
        // project-tier name .aethercode/ and matches the existing
        // ~/.aethercode/mcp.json location the daemon already
        // watches). The project-tier root is <cwd>/.aethercode/skills.
        // Both are scanned. --no-skills disables discovery (useful
        // for unit tests). The registry's auto-reload runs
        // every 10s in the background so a newly-added
        // SKILL.md shows up without a daemon restart.
        if (!noSkills) {
            java.util.List<java.nio.file.Path> userSkillDirs = new java.util.ArrayList<>();
            java.nio.file.Path userSkills = resolveAethercodeHome().resolve("skills");
            if (java.nio.file.Files.isDirectory(userSkills)) userSkillDirs.add(userSkills);
            java.util.List<java.nio.file.Path> projectSkillDirs = new java.util.ArrayList<>();
            java.nio.file.Path projectSkills = cwd.resolve(".aethercode").resolve("skills");
            if (java.nio.file.Files.isDirectory(projectSkills)) projectSkillDirs.add(projectSkills);
            b.skillProjectDirs(projectSkillDirs);
            b.skillDirs(userSkillDirs);
        }
        // wire the AgentRegistry from the same AetherCode
        // directory tree. R210 — was ~/.minimax/agents previously,
        // now ~/.aethercode/agents for consistency with the
        // skill / mcp roots. --no-agents disables discovery.
        if (!noAgents) {
            java.nio.file.Path agentsDir = resolveAethercodeHome().resolve("agents");
            if (java.nio.file.Files.isDirectory(agentsDir)) {
                b.agentsDir(agentsDir);
            }
        }
        // enable the AetherCodeAgent create-agent
        // system-prompt fragment by default. The fragment
        // adds explicit "call todo_write first", "batch
        // related tool calls", and "do not stop after the
        // first tool call" rules on top of the existing
        // {@code SystemPrompt.defaultWorkflow()}. The prior
        // behaviour was a model that sometimes skipped the
        // plan step on a fresh session and went straight to
        // one tool call at a time, which the engine then
        // gated on per-tool HIL prompts. The new fragment
        // closes that gap. A user who wants the legacy
        // prompt can pass {@code --no-create-agent} (TBD)
        // or set {@code AETHERCODE_CREATE_AGENT=false} in
        // the environment. The env var is the lightweight
        // opt-out; the CLI flag is for symmetry with the
        // other "off" flags above.
        if (createAgentEnabled()) {
            b.createAgent(true);
        }
        AetherCodeEngine engine = b.build();
        if (sessionId != null && !sessionId.isBlank()) {
            LOG.info("Prior round: built fresh engine for session {} (model: {})",
                    sessionId, engine.appState().mainLoopModel());
        }
        // install the MCP manager so the file
        // watcher's reload trigger can call back into
        // it. The manager holds the live client handles
        // (stdio Process, socket conn, etc.) so a
        // reload is a real teardown + re-create, not a
        // no-op.
        engine.mcpManager(mcp);
        return engine;
    }

    /** rename an MCP tool so the engine's
     *  {@code replaceMcpTools} can identify it on
     *  reload. The McpServers factory's
     *  {@code listTools()} returns tools with names
     *  like {@code "filesystem__read_file"}; we prefix
     *  with {@code "mcp:"} so the engine's
     *  {@code toolPool.removeIf(name.startsWith("mcp:"))}
     *  can find them. Returns a delegating {@link Tool}
     *  that overrides the name (and only the name) —
     *  the call / inputSchema / etc. pass through. */
    /**
     * resolve the model id to use for this
     * daemon build. Priority order:
     * <ol>
     *   <li>{@code --model} CLI flag (explicit
     *       override; wins over everything)</li>
     *   <li>{@code AETHERCODE_DEFAULT_MODEL} env var
     *       (allows the daemon to default to
     *       {@code MiniMax-M3} without the TUI
     *       having to pass {@code --model} every
     *       time)</li>
     *   <li>Provider's {@code defaultModel} in
     *       {@code providers.yaml}</li>
     *   <li>Legacy fallback: {@code "MiniMax-M3"}</li>
     * </ol>
     * The legacy fallback is {@code MiniMax-M3}
     * (not {@code MiniMax-M1}) because the user
     * reported the TUI was showing M1 while the
     * daemon was actually configured for M3.
     * Defaulting to M3 makes the daemon side
     * correct out of the box; the TUI can still
     * downgrade via {@code setModel}.
     */
    static String resolveModel(String cliModel,
                                org.aethercode.core.providers.ProviderSpec providerSpec) {
        if (cliModel != null && !cliModel.isBlank()) {
            return cliModel;
        }
        String env = System.getenv("AETHERCODE_DEFAULT_MODEL");
        if (env != null && !env.isBlank()) {
            return env;
        }
        if (providerSpec != null && providerSpec.defaultModel() != null
                && !providerSpec.defaultModel().isBlank()) {
            return providerSpec.defaultModel();
        }
        return "MiniMax-M3";
    }

    private static Tool prefixMcpTool(Tool original) {
        if (original == null || original.name() == null) return original;
        if (original.name().startsWith("mcp:")) return original;
        final String newName = "mcp:" + original.name();
        final Tool base = original;
        return new Tool() {
            @Override public String name() { return newName; }
            @Override public String description() { return base.description(); }
            @Override public String searchHint() { return base.searchHint(); }
            @Override public java.util.Map<String, Object> inputSchema() { return base.inputSchema(); }
            @Override public boolean isConcurrencySafe(java.util.Map<String, Object> input) {
                return base.isConcurrencySafe(input);
            }
            @Override public boolean isReadOnly(java.util.Map<String, Object> input) {
                return base.isReadOnly(input);
            }
            @Override public boolean isDestructive(java.util.Map<String, Object> input) {
                return base.isDestructive(input);
            }
            @Override public String validateInput(java.util.Map<String, Object> input) {
                return base.validateInput(input);
            }
            @Override public java.util.concurrent.CompletableFuture<ToolResult> call(
                    java.util.Map<String, Object> input, CallContext ctx) {
                return base.call(input, ctx);
            }
            @Override public java.util.concurrent.CompletableFuture<org.aethercode.core.permission.PermissionResult> checkPermissions(
                    java.util.Map<String, Object> input, CallContext ctx) {
                return base.checkPermissions(input, ctx);
            }
            @Override public String userFacingName(java.util.Map<String, Object> input) {
                return base.userFacingName(input);
            }
        };
    }

    /** locate the Mavis data directory. Order:
     *  1. {@code MAVIS_HOME} / {@code MINIMAX_HOME} env var
     *  2. {@code ~/.minimax} (the historical Mavis default)
     *  3. {@code <cwd>/.aethercode} (project-local fallback,
     *     used when the user runs the daemon outside Mavis) */
    private java.nio.file.Path resolveMavisHome() {
        String env = System.getenv("MAVIS_HOME");
        if (env == null || env.isBlank()) env = System.getenv("MINIMAX_HOME");
        if (env != null && !env.isBlank()) {
            return java.nio.file.Path.of(env);
        }
        String home = System.getProperty("user.home", ".");
        return java.nio.file.Path.of(home).resolve(".minimax");
    }

    /** locate the AetherCode user-tier data directory. Order:
     *  1. {@code AETHERCODE_HOME} env var (opt-in override for
     *     sandboxed / multi-user machines where {@code user.home}
     *     is read-only or shared).
     *  2. {@code ~/.aethercode} (the R210 default — was
     *     {@code ~/.minimax} previously; the rename aligns the
     *     user-tier name with the project-tier {@code <cwd>/.aethercode}
     *     and with the {@code ~/.aethercode/mcp.json} / providers.yaml
     *     locations the daemon already uses). The project-tier
     *     counterpart {@code <cwd>/.aethercode/skills/} is
     *     unaffected.
     *
     *  <p>Callers use this to build the user-tier skill / agent
     *  / mcp roots. Old {@code ~/.minimax/skills/} files are NOT
     *  migrated automatically — the user can move them with
     *  `mv ~/.minimax/skills/* ~/.aethercode/skills/` if they had
     *  content there previously.
     */
    private java.nio.file.Path resolveAethercodeHome() {
        String env = System.getenv("AETHERCODE_HOME");
        if (env != null && !env.isBlank()) {
            return java.nio.file.Path.of(env);
        }
        String home = System.getProperty("user.home", ".");
        return java.nio.file.Path.of(home).resolve(".aethercode");
    }

    /**
     * R343: resolve the {@link org.aethercode.core.providers.ProviderRegistry}
     * via the two-tier cascade (install-dir + cwd) or an
     * explicit single-file override.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code --providers-yaml} CLI flag → single file</li>
     *   <li>{@code AETHERCODE_PROVIDERS_YAML} env var → single file</li>
     *   <li>install-dir + cwd cascade (R343 default)</li>
     *   <li>bundled classpath yaml + bundledDefaults() fallback</li>
     * </ol>
     *
     * <p>The cwd is the same value the engine builds against
     * (CLI {@code --cwd} flag or {@code user.dir}). The
     * install dir is resolved from the daemon jar's
     * protection domain unless {@code --install-dir} was
     * passed.
     */
    private org.aethercode.core.providers.ProviderRegistry
            resolveProvidersRegistry(java.nio.file.Path cwd) {
        // 1+2: explicit single-file override (CLI flag wins).
        Path explicit = providersYamlOverride;
        if (explicit == null) {
            String env = System.getenv("AETHERCODE_PROVIDERS_YAML");
            if (env != null && !env.isBlank()) {
                explicit = java.nio.file.Path.of(env);
            }
        }
        if (explicit != null) {
            org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Main.class);
            LOG.info("R343: loading explicit providers.yaml from {}", explicit);
            return org.aethercode.core.providers.ProviderRegistry.loadFrom(explicit);
        }

        // 3: cascade. Install dir defaults to the daemon jar's
        // parent unless --install-dir was passed.
        Path installDir = installDirOverride;
        if (installDir == null) {
            installDir = org.aethercode.core.providers.ProviderRegistry
                    .resolveInstallDir(Main.class);
        }
        // R343: first-install bootstrap. Copies the bundled
        // providers.yaml.sample into <installDir>/providers.yaml
        // when the live file is missing. After the first boot
        // this is a no-op.
        org.aethercode.core.providers.ProviderRegistry
                .ensureSampleInstalled(installDir);
        return org.aethercode.core.providers.ProviderRegistry
                .loadCascade(installDir, cwd);
    }
}
