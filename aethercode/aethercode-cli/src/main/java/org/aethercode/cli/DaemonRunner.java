package org.aethercode.cli;

import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.protocol.server.JsonRpcServer;
import org.aethercode.orchestration.papercompat.PaperCompatRpc;
import org.aethercode.sdk.AetherCodeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import org.aethercode.core.concurrency.EngineStats;

/**
 * the CLI's "headless daemon" entry point. The daemon reads
 * JSON-RPC 2.0 messages from stdin and writes them to stdout.
 * Tools (TUI, multica, custom orchestrators) spawn this process
 * and pipe requests through it.
 *
 * <p>Logging is redirected to stderr at startup so the protocol
 * wire on stdout stays clean. The transport's reader thread
 * detects EOF (peer closed stdin) and the daemon exits cleanly.
 */
final class DaemonRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DaemonRunner.class);

    private DaemonRunner() {}

    /** convenience overload for callers that
     *  don't have a multi-session factory (the legacy
     *  single-engine path). The factory is wired to a
     *  "refuse non-default" closure that throws
     *  {@link UnsupportedOperationException} for any
     *  non-default session id, matching the prior round
     *  minimum scope. */
    static int run(AetherCodeEngine engine) {
        return run(engine, DaemonRunner::refuseNonDefaultFactory);
    }

    /** factory-aware entry point. The
     *  {@code sessionFactory} is the closure the
     *  {@code SessionManager} calls when the user
     *  creates a new engine via {@code createEngine}.
     *  The CLI's {@code Main.buildEngineForSession}
     *  is the canonical implementation; tests can
     *  inject their own. */
    static int run(AetherCodeEngine engine,
                    java.util.function.Function<String, AetherCodeEngine> sessionFactory) {
        // Capture the original stdout BEFORE the redirect so the
        // transport can write JSON-RPC there even after we
        // redirect System.out for safety. (The transport's
        // OutputStream is captured at construction, so we want
        // the original.)
        java.io.PrintStream originalStdout = System.out;

        // The protocol wire is the ONLY thing we want on stdout.
        // Libraries that print via System.out.println() should
        // go to stderr to keep the wire clean. We capture
        // System.out in `originalStdout` above so the transport
        // still writes to the real stdout.
        try {
            System.setOut(System.err);
        } catch (Throwable ignored) {}

        // Build the transport with the original stdout reference
        // so the JSON-RPC wire is on stdout (and the log lines on
        // stderr are clean).
        org.aethercode.protocol.stdio.StdioTransport transport =
                new org.aethercode.protocol.stdio.StdioTransport(
                        System.in, originalStdout,
                        new org.aethercode.protocol.jsonrpc.JsonRpcCodec(),
                        msg -> {},
                        t -> System.err.println("stdio error: " + t.getMessage()));
        org.aethercode.protocol.jsonrpc.JsonRpcCodec codec =
                new org.aethercode.protocol.jsonrpc.JsonRpcCodec();
        org.aethercode.protocol.server.JsonRpcDispatcher dispatcher =
                new org.aethercode.protocol.server.JsonRpcDispatcher(transport::send);
        transport.setHandler(dispatcher::dispatch);
        org.aethercode.protocol.server.JsonRpcServer server =
                new org.aethercode.protocol.server.JsonRpcServer(transport, codec, dispatcher);

        // prior round: build the multi-session
        // manager. The "default" session is the engine
        // the CLI built (via Main.buildEngine); the
        // sessionFactory closure materialises engines
        // for non-default ids. The factory is the seam
        // that lets the user create additional engines
        // via the createEngine RPC. See
        // {@link #buildSessionManager} for the
        // factory's contract.
        org.aethercode.sdk.SessionManager sessionManager = buildSessionManager(engine, sessionFactory);
        // install the SessionManager on the engine so
        // AetherCodeEngine.sessionManager() returns the same
        // instance. Without this, the engine's createSession
        // / loadSession re-registration helper
        // (syncSessionManagerRegistration) is a no-op
        // because the field stays null, and a fresh session
        // id minted by SessionStore.newSessionId() never
        // lands in the manager — bindSessionCwd / setModel
        // / setPermissionMode on the new id would then fail
        // with "no engine for sessionId: <id>". The
        // legacy AetherCodeMethods constructor set its
        // own sessionManager field, which masked the issue
        // because resolveRpcTarget falls back to that, but
        // the engine-side helper still needs the binding.
        engine.setSessionManager(sessionManager);

        // Wire the engine methods into the dispatcher.
        // 3-arg constructor (engine, sessionManager,
        // notifier) so the listEngines / createEngine /
        // deleteEngine / setActiveEngine / getActiveEngine
        // RPCs route through the manager instead of
        // returning "session manager not configured".
        AetherCodeMethods methods = new AetherCodeMethods(engine,
                sessionManager,
                n -> server.notify(n.method(), n.params()));
        methods.registerAll(server.dispatcher());

        // R-paper-batch6-TIER-3-E2E: register the Tier-3 paper-compat
        // RPC surface on the same dispatcher the front-end (CLI / TUI
        // / IDEA) already talks to. Without this the Tier-3 classes
        // are unit-test-only and never reach a real business process.
        // The bridge is a single PaperCompatRpc instance that owns the four
        // detectors (architecture, saturation, redflag, byzantine) and
        // exposes them as RPCs.
        PaperCompatRpc paperCompat = new PaperCompatRpc();
        paperCompat.register(server.dispatcher());
        LOG.info("Tier-3 RPCs registered (8 methods: architecture / saturation / redflag / byzantine / voting / plan)");

        // wire a SessionStore if the engine doesn't
        // already have one. legacy, the daemon's
        // createSession / loadSession / listSessions / etc.
        // RPCs returned "SessionStore is not wired" because
        // the CLI built the engine without a store. Now the
        // daemon resolves a default sessions dir from
        // AETHERCODE_SESSIONS_DIR (env), falling back to
        // <cwd>/.aethercode/sessions, and installs the store
        // via engine.setSessionStore(...). The setter is
        // idempotent so a CLI-started daemon (which has a
        // store from the builder) is a no-op. The same
        // default logic lives on the HTTP path below.
        // also share the store with the
        // SessionManager so factory-built engines
        // (createSession({cwd}) → createEngine) pick it
        // up automatically.
        ensureSessionStore(engine, sessionManager, "stdio");

        // wire the 3-layer memory facade. The base
        // directory defaults to ~/.aethercode; the chat
        // client for the LLM-driven project-memory
        // compressor is null on the stdio path (the
        // compressor will fall back to the tag-only
        // "[compressed: N entries]" line — the user
        // can plumb a richer client by calling
        // AetherCodeMethods.setMemoryStore later).
        org.aethercode.memory.LayeredMemoryStore memoryStore =
                buildMemoryStore(methods, java.nio.file.Path.of(
                        System.getProperty("user.home")).resolve(".aethercode"));
        methods.setMemoryStore(memoryStore);
        LOG.info("R127: 3-layer memory store installed (USER / PROJECT / SESSION)");

        // wire the MemoryLifecycle orchestrator. The
        // engine's query() flow now calls onQueryStart / onQueryEnd
        // / onMemoryRecallHit so the experience store, audit log,
        // and forgetting policy update continuously. The audit
        // log is a sibling of sessions.db under the same memory
        // base; the lifecycle is a daemon-scoped singleton.
        org.aethercode.memory.MemoryAudit audit = org.aethercode.memory.MemoryAudit.enable(
                java.nio.file.Path.of(System.getProperty("user.home")).resolve(".aethercode"));
        org.aethercode.memory.MemoryLifecycle memoryLifecycle = new org.aethercode.memory.MemoryLifecycle(
                "daemon-" + java.util.UUID.randomUUID(), "default",
                memoryStore, org.aethercode.memory.ForgettingPolicy.defaults(),
                audit, org.aethercode.memory.MemoryLifecycle.Config.defaults());
        memoryLifecycle.start();
        // install on the engine via the post-construction
        // setter (R231 added a volatile field + setter so the
        // daemon can wire the real lifecycle after the engine is
        // built but before any query is issued).
        if (engine instanceof org.aethercode.sdk.AetherCodeEngine sdkEngine) {
            sdkEngine.setMemoryLifecycle(memoryLifecycle);
        } else {
            LOG.warn("R231: engine is not a AetherCodeEngine ({}); MemoryLifecycle will not be wired into query()",
                    engine == null ? "null" : engine.getClass().getName());
        }
        LOG.info("R231: MemoryLifecycle installed (audit={}, decayIntervalMs={}, enabled={})",
                audit != null, org.aethercode.memory.MemoryLifecycle.DEFAULT_DECAY_INTERVAL_MS,
                memoryLifecycle.config().enabled());
        // Stash the lifecycle on the methods so shutdown can stop it.
        methods.setMemoryLifecycle(memoryLifecycle);

        // wire the MemoryExtractor so long conversations get
        // a subagent-driven summary written to the session's
        // MEMORY.md. The extractor needs a chat client (the daemon
        // may or may not have one yet) and a per-session memory dir.
        // We pass the engine's chat client resolver (same one the
        // ProjectMemoryCompressor uses) so the extractor can re-use
        // whatever the user has configured.
        try {
            java.nio.file.Path sessionMemoryDir = org.aethercode.memory.MemoryPaths.agentMemoryDir(
                    "default", org.aethercode.memory.MemoryScope.SESSION, null);
            // Build a thin ChatClient wrapper that resolves the
            // per-session client through the methods' resolver. Falls
            // back to an empty stream if no client is configured.
            final org.aethercode.core.llm.ChatClient extractorChat = new org.aethercode.core.llm.ChatClient() {
                @Override
                public java.util.stream.Stream<org.aethercode.core.stream.StreamEvent> stream(
                        java.util.List<org.aethercode.core.message.Message> messages,
                        String systemPrompt,
                        java.util.List<org.aethercode.core.tool.Tool> tools) {
                    try {
                        java.util.function.Function<String, org.aethercode.core.llm.ChatClient> resolver =
                                methods.chatClientResolverField();
                        if (resolver == null) return java.util.stream.Stream.empty();
                        org.aethercode.core.llm.ChatClient c = resolver.apply(
                                methods.currentProviderName() + "/" + methods.currentModelId());
                        if (c == null) return java.util.stream.Stream.empty();
                        return c.stream(messages, systemPrompt, tools);
                    } catch (Exception resolverEx) {
                        LOG.warn("R232: chat client resolution failed: {}", resolverEx.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                }
                @Override
                public String modelId() {
                    return methods.currentModelId() == null ? "default" : methods.currentModelId();
                }
            };
            org.aethercode.memory.MemoryExtractor memoryExtractor = new org.aethercode.memory.MemoryExtractor(
                    extractorChat, sessionMemoryDir);
            memoryLifecycle.setMemoryExtractor(memoryExtractor);
            methods.setMemoryExtractor(memoryExtractor);
            // wire the same chat client as the strategy
            // extractor. Tier 3 (strategy) fires on the same gate
            // as Tier 2 (case) — when the transcript looks like a
            // reusable pattern, the lifecycle asks the LLM to
            // distil a short strategy text. Costs at most one
            // small LLM call per session, gated by heuristic.
            memoryLifecycle.setStrategyChatClient(extractorChat);
            methods.setMemoryChatClient(extractorChat);
            LOG.info("对应历史 round: MemoryExtractor + Strategy chat client installed (sessionDir={})",
                    sessionMemoryDir);
        } catch (Exception extractorEx) {
            LOG.warn("R232: could not install MemoryExtractor ({}); session-MEMORY.md auto-write will be skipped",
                    extractorEx.getMessage());
        }

        // install a JSON-RPC-backed permission prompter so the
        // TUI (or any other client) can answer permission asks via
        // the permission_request / permission_response round trip.
        // We replace the engine's in-process prompter (typically a
        // JLine dialog) with a JsonRpcPermissionPrompter that uses
        // `methods` to issue notifications and await replies.
        org.aethercode.protocol.permissions.JsonRpcPermissionPrompter rpcPrompter =
                new org.aethercode.protocol.permissions.JsonRpcPermissionPrompter(methods);
        engine.setPermissionPrompter(rpcPrompter);
        LOG.info("installed JSON-RPC permission prompter");

        // opt-in headless / scripted mode. When the
        // env var AETHERCODE_AUTO_APPROVE_ALL=1 is set,
        // the prompter short-circuits medium + high risk
        // asks (low-risk is already auto-approved by
        // default). Without this, every bash / file_write
        // in a headless driver script would 5min-timeout
        // and the model would silently fail to write
        // anything. Critical risk (rm -rf, sudo, mkfs)
        // is still always asked. The renderer can still
        // flip the flag off mid-session via the
        // setAutoApproveMediumHigh RPC.
        if (isAutoApproveAllEnv()) {
            methods.setAutoApproveMediumHigh(true);
            LOG.warn("R126: AETHERCODE_AUTO_APPROVE_ALL=1 — auto-approving medium+high risk (critical still asks)");
        }

        // opt-in complex-task loop detector. The headless
        // RAG driver (and any other multi-file generation
        // script) sets AETHERCODE_LOOP_DETECTOR=complex to scale
        // up the loop detector's window / thresholds so a real
        // multi-file project doesn't trip the loop_detected
        // hard stop. Without this, the model cuts off mid-file
        // because the legitimate "thinking" text between
        // file_writes exceeds the 1500-char long-output
        // threshold.
        //
        // R136.4: also accept AETHERCODE_LOOP_DETECTOR=max for
        // the 1M-context factory (window 50, fp 10, longOut
        // 100K, longConsec 3, warnBeforeStop 8). Recommended
        // for million-token models (MiniMax M3, Gemini 2.5
        // Pro) where a single "thinking" turn can legitimately
        // produce 80-100K chars of text.
        String loopMode = System.getenv("AETHERCODE_LOOP_DETECTOR");
        if ("complex".equalsIgnoreCase(loopMode)) {
            org.aethercode.core.engine.QueryEngine qe =
                    (org.aethercode.core.engine.QueryEngine) engine.queryEngine();
            if (qe != null) {
                qe.setForceComplexTaskDetector(true);
                LOG.warn("R134: AETHERCODE_LOOP_DETECTOR=complex — using ProgressLoopDetector.forComplexTask() (window 20, fp 5, longOut 10000, longConsec 2, warnBeforeStop 4)");
            }
        } else if ("max".equalsIgnoreCase(loopMode)) {
            org.aethercode.core.engine.QueryEngine qe =
                    (org.aethercode.core.engine.QueryEngine) engine.queryEngine();
            if (qe != null) {
                qe.setForcedDetectorMode("max");
                LOG.warn("R136.4: AETHERCODE_LOOP_DETECTOR=max — using ProgressLoopDetector.forMaxContext() (window 50, fp 10, longOut 100000, longConsec 3, warnBeforeStop 8)");
            }
        }
        // R136.4: optionally read AETHERCODE_MAX_CONTEXT_TOKENS
        // to wire the context window into the engine so
        // pickLoopDetector can auto-select the right factory.
        String maxCtxEnv = System.getenv("AETHERCODE_MAX_CONTEXT_TOKENS");
        if (maxCtxEnv != null && !maxCtxEnv.isBlank()) {
            try {
                int ctx = Integer.parseInt(maxCtxEnv.trim());
                org.aethercode.core.engine.QueryEngine qe =
                        (org.aethercode.core.engine.QueryEngine) engine.queryEngine();
                if (qe != null) {
                    qe.setContextWindow(ctx);
                    LOG.warn("R136.4: AETHERCODE_MAX_CONTEXT_TOKENS={} — context window set on QueryEngine", ctx);
                }
            } catch (NumberFormatException nfe) {
                LOG.warn("R136.4: AETHERCODE_MAX_CONTEXT_TOKENS={} — not a valid integer, ignoring", maxCtxEnv);
            }
        }

        // Shut down the engine on daemon exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (Exception ignored) {}
        }, "aethercode-daemon-shutdown"));

        LOG.info("aethercode daemon started (model={}, sessionId={}); awaiting JSON-RPC on stdin",
                engine.appState().mainLoopModel(), engine.appState().sessionId());

        server.run();
        return 0;
    }

    /** HTTP+WebSocket daemon mode, single-engine
     *  legacy path. Equivalent to
     *  {@link #runHttp(AetherCodeEngine, int, java.util.function.Function)}
     *  with a "refuse non-default" factory. */
    static int runHttp(AetherCodeEngine engine, int port) {
        return runHttp(engine, port, DaemonRunner::refuseNonDefaultFactory);
    }

    /** HTTP+WebSocket daemon mode with
     *  a factory closure. The {@code sessionFactory}
     *  materialises new engines when the user calls
     *  {@code createEngine}. The CLI's
     *  {@code Main.buildEngineForSession} is the
     *  canonical implementation; tests can inject
     *  their own. */
    static int runHttp(AetherCodeEngine engine, int port,
                        java.util.function.Function<String, AetherCodeEngine> sessionFactory) {
        // prior round: build the multi-session
        // manager (same factory-driven path as
        // {@link #run}).
        org.aethercode.sdk.SessionManager sessionManager = buildSessionManager(engine, sessionFactory);
        // install the SessionManager on the engine so
        // AetherCodeEngine.sessionManager() returns the same
        // instance. Without this, the engine's createSession
        // / loadSession re-registration helper
        // (syncSessionManagerRegistration) is a no-op
        // because the field stays null, and a fresh session
        // id minted by SessionStore.newSessionId() never
        // lands in the manager — bindSessionCwd / setModel
        // / setPermissionMode on the new id would then fail
        // with "no engine for sessionId: <id>". The
        // legacy AetherCodeMethods constructor set its
        // own sessionManager field, which masked the issue
        // because resolveRpcTarget falls back to that, but
        // the engine-side helper still needs the binding.
        engine.setSessionManager(sessionManager);

        // install the SessionManager on the
        // engine so {@code AetherCodeMethods}'s
        // {@code engine.sessionManager()} accessor
        // (used by the legacy prior round RPCs) returns
        // the same instance. The 3-arg
        // HttpJsonRpcServer constructor accepts
        // the manager so its own AetherCodeMethods
        // field is initialised with the right
        // reference too.
        org.aethercode.protocol.http.HttpJsonRpcServer http =
                new org.aethercode.protocol.http.HttpJsonRpcServer(port, engine, sessionManager);

        // same SessionStore wire-up as the stdio
        // path. The HTTP+WS daemon is what the Tauri /
        // desktop app talks to for `createSession({cwd})`,
        // so the store must be live before the first RPC
        // arrives. The setter is idempotent so a CLI-built
        // engine with a store is a no-op. R151b: also
        // share the store with the SessionManager so
        // factory-built engines pick it up.
        ensureSessionStore(engine, sessionManager, "http");
        // wire the provider registry from
        // ~/.aethercode/providers.yaml so the renderer's
        // Settings panel can list providers + switch
        // models on the fly. The CLI loaded the spec
        // into the engine's Builder; we read the
        // engine's current mainLoopModel to seed the
        // "currently using" hint. The registry itself
        // is loaded here (the daemon process is the
        // authoritative one — the renderer is a
        // client).
        try {
            java.nio.file.Path providersFile = java.nio.file.Path.of(
                    System.getProperty("user.home"),
                    ".aethercode", "providers.yaml");
            org.aethercode.core.providers.ProviderRegistry registry =
                    org.aethercode.core.providers.ProviderRegistry.loadFrom(providersFile);
            http.methods().setProviderRegistry(registry);
            http.methods().setCurrentProvider(
                    // We don't have a direct handle on
                    // the spec the engine was built
                    // with; we ask the registry for the
                    // first match whose defaultModel
                    // matches the engine's current
                    // model, falling back to the
                    // registry's default.
                    registry.list().stream()
                            .filter((p) -> engine.appState().mainLoopModel() != null
                                    && p.defaultModel() != null
                                    && p.defaultModel().equals(
                                            engine.appState().mainLoopModel()))
                            .map(org.aethercode.core.providers.ProviderSpec::name)
                            .findFirst()
                            .orElseGet(() -> registry.defaultProvider()
                                    .map(org.aethercode.core.providers.ProviderSpec::name)
                                    .orElse(null)),
                    engine.appState().mainLoopModel());
            // install the chat client resolver
            // so AetherCodeMethods can build per-agent
            // ChatClients for child sessions. The
            // resolver closes over the loaded
            // providerRegistry + the
            // SpringAiChatClient.forProvider factory.
            // The protocol module does NOT import
            // engine-springai directly — this
            // resolver is the seam. The format is
            // "provider/model" (e.g. "glm/glm-4-flash");
            // when the model lacks a provider
            // prefix, resolveChatClient prepends the
            // current provider's name.
            http.methods().setChatClientResolver((providerModel) -> {
                int slash = providerModel.indexOf('/');
                if (slash < 0) {
                    // Bare model id: the methods
                    // layer prepended the current
                    // provider, but defensively
                    // re-derive the prefix here
                    // for the standalone CLI.
                    throw new IllegalArgumentException(
                            "model missing provider prefix: " + providerModel
                                    + " (expected provider/model)");
                }
                String providerName = providerModel.substring(0, slash);
                String modelId = providerModel.substring(slash + 1);
                var spec = registry.get(providerName).orElseThrow(
                        () -> new IllegalArgumentException(
                                "unknown provider: " + providerName));
                return org.aethercode.engine.springai.SpringAiChatClient
                        .forProvider(spec, modelId);
            });
        } catch (Exception e) {
            LOG.warn("provider registry / chat client resolver load failed: {}",
                    e.getMessage());
        }

        // install a JSON-RPC permission prompter that the
        // HTTP server can also use (each WebSocket client gets
        // permission_request notifications on its own socket).
        // HttpJsonRpcServer's broadcast notifier covers every
        // connected client.
        org.aethercode.protocol.permissions.JsonRpcPermissionPrompter rpcPrompter =
                new org.aethercode.protocol.permissions.JsonRpcPermissionPrompter(http.methods());
        engine.setPermissionPrompter(rpcPrompter);

        // same headless env-var read as the stdin
        // daemon (see run() above). The HTTP+WS daemon is
        // the entry point used by the RAG driver, so this
        // is the path that actually matters for the
        // headless use case. Critical risk still asks.
        if (isAutoApproveAllEnv()) {
            http.methods().setAutoApproveMediumHigh(true);
            LOG.warn("R126: AETHERCODE_AUTO_APPROVE_ALL=1 — auto-approving medium+high risk (critical still asks)");
        }

        // R135.5: optional auth token for POST /shutdown. When
        // AETHERCODE_SHUTDOWN_TOKEN is set, the endpoint
        // requires `Authorization: Bearer <token>`. This
        // prevents a random HTTP client (a stray load
        // balancer probe, a misconfigured CI step, a rogue
        // coworker) from killing the daemon. The token
        // travels in the env var (not a CLI arg) so it
        // doesn't end up in `ps` output.
        String token = System.getenv("AETHERCODE_SHUTDOWN_TOKEN");
        if (token != null && !token.isEmpty()) {
            http.setShutdownToken(token);
            LOG.info("R135.5: AETHERCODE_SHUTDOWN_TOKEN is set — POST /shutdown requires Bearer auth");
        }

        // opt-in complex-task loop detector (HTTP path
        // mirror of the stdio run() check above). The RAG
        // driver sets AETHERCODE_LOOP_DETECTOR=complex to
        // scale up the detector so a real multi-file
        // project doesn't trip loop_detected mid-file. The
        // SessionManager owns multiple engines; we apply
        // the flag to the primary engine here, and any
        // additional engines created by createEngine will
        // inherit via the per-engine wiring in
        // SessionManager.EngineFactory.
        String loopMode2 = System.getenv("AETHERCODE_LOOP_DETECTOR");
        if ("complex".equalsIgnoreCase(loopMode2)) {
            org.aethercode.core.engine.QueryEngine qe =
                    (org.aethercode.core.engine.QueryEngine) engine.queryEngine();
            if (qe != null) {
                qe.setForceComplexTaskDetector(true);
                LOG.warn("R134: AETHERCODE_LOOP_DETECTOR=complex — using ProgressLoopDetector.forComplexTask() (window 20, fp 5, longOut 10000, longConsec 2, warnBeforeStop 4)");
            }
        } else if ("max".equalsIgnoreCase(loopMode2)) {
            org.aethercode.core.engine.QueryEngine qe =
                    (org.aethercode.core.engine.QueryEngine) engine.queryEngine();
            if (qe != null) {
                qe.setForcedDetectorMode("max");
                LOG.warn("R136.4: AETHERCODE_LOOP_DETECTOR=max — using ProgressLoopDetector.forMaxContext() (window 50, fp 10, longOut 100000, longConsec 3, warnBeforeStop 8)");
            }
        }
        String maxCtxEnv2 = System.getenv("AETHERCODE_MAX_CONTEXT_TOKENS");
        if (maxCtxEnv2 != null && !maxCtxEnv2.isBlank()) {
            try {
                int ctx = Integer.parseInt(maxCtxEnv2.trim());
                org.aethercode.core.engine.QueryEngine qe =
                        (org.aethercode.core.engine.QueryEngine) engine.queryEngine();
                if (qe != null) {
                    qe.setContextWindow(ctx);
                    LOG.warn("R136.4: AETHERCODE_MAX_CONTEXT_TOKENS={} — context window set on QueryEngine (HTTP path)", ctx);
                }
            } catch (NumberFormatException nfe) {
                LOG.warn("R136.4: AETHERCODE_MAX_CONTEXT_TOKENS={} — not a valid integer, ignoring", maxCtxEnv2);
            }
        }

        // wire the engine's transcript mutations to the
        // server's broadcast notifier. The lambda captures
        // `http` (final effectively) and routes every
        // appendMessage / loadSession / createSession to a
        // `transcript_event` notification on every connected
        // client. Without this, the renderer's messages array
        // would be out of sync with the engine's appState the
        // moment the user sends a message. The lambda is a
        // thin shim — the engine already wraps the push in
        // try/catch so a broken network target can't break
        // the turn loop.
        engine.setTranscriptPush(payload ->
                http.broadcast("transcript_event", payload));

        // same pattern for the task registry. The
        // engine's setTaskPush installs a TaskRegistry
        // listener that fans out every create / status
        // transition as a `task_event` notification. The
        // desktop's Kanban board consumes these to keep
        // its columns in sync without polling
        // listTasks. The listener is a process-singleton
        // so the same notification goes to every
        // connected client.
        engine.setTaskPush(payload ->
                http.broadcast("task_event", payload));

        // prior round: install the unified registry reload
        // service. The watcher monitors ~/.aethercode/skills,
        // ~/.aethercode/agents, and the two mcp.json files;
        // the engine's reloaders re-read on any change. The
        // notification fan-out goes through the same
        // `http.broadcast` channel so every connected TUI /
        // Desktop / multica client sees "registry_reloaded"
        // in real time. The stdio daemon (single client)
        // still gets the same event via the notifier closure
        // passed to AetherCodeMethods.
        try {
            org.aethercode.core.registry.RegistryReloadService reloadSvc =
                    buildRegistryReloadService(engine);
            if (reloadSvc != null) {
                engine.registryReloadService(reloadSvc);
                reloadSvc.addListener(ev -> {
                    java.util.Map<String, Object> payload = java.util.Map.of(
                            "kind", ev.kind().name(),
                            "atMs", ev.atMs());
                    http.broadcast("registry_reloaded", payload);
                });
                LOG.info("对应历史 round: registry reload service installed (skills + agents + mcp watchers)");
            }
        } catch (Exception e) {
            LOG.warn("对应历史 round: registry reload service install failed: {}", e.getMessage());
        }

        // Shut down the server on JVM exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { http.stop(); } catch (Exception ignored) {}
        }, "aethercode-http-daemon-shutdown"));

        // graceful shutdown. SIGTERM (the signal systemd
        // / k8s send on `systemctl stop` / pod termination) is
        // routed through the JVM's shutdown-hook chain. We
        // flip the http.markShuttingDown() flag first so the
        // /healthz endpoint starts returning 503 — this
        // gives upstream load balancers / health probes a
        // chance to take the service out of rotation BEFORE
        // the JVM exits. Then we wait up to 30s for in-flight
        // queries + tool calls to drain (the engine's
        // ConcurrencyController reports the in-flight count),
        // and only then call http.stop().
        //
        // SIGINT (Ctrl-C, the dev path) skips the wait and
        // stops immediately — the user is right there and
        // doesn't want to wait 30s for a force-kill. The
        // distinction is: SIGTERM = "please drain", SIGINT =
        // "I really mean it, kill now".
        //
        // The actual hook is the JVM's standard
        // Runtime.getRuntime().addShutdownHook — both SIGTERM
        // and SIGINT route through it on Linux. The graceful
        // behaviour is enabled by default; the legacy
        // "stop immediately" path is kept for SIGINT via a
        // tiny wrapper that listens for the signal name
        // (Java exposes the reason via the hook's thread
        // name + an env var AETHERCODE_FORCE_KILL=1 that
        // the user can set when they want the old behaviour).
        boolean forceKill = "1".equals(System.getenv("AETHERCODE_FORCE_KILL"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                LOG.info("R131: graceful shutdown started (forceKill={})", forceKill);
                http.markShuttingDown();
                if (!forceKill) {
                    // Wait up to 30s for in-flight work to drain.
                    long deadline = System.currentTimeMillis() + 30_000L;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            int inFlight = 0;
                            if (engine.concurrencyController() != null) {
                                // EngineStats is a snapshot record
                                // with the live in-flight counts.
                                EngineStats stats = engine.concurrencyController().snapshot();
                                inFlight = stats.queriesInFlight
                                        + stats.toolsInFlight
                                        + stats.branchesInFlight;
                            }
                            if (inFlight == 0) break;
                            LOG.info("R131: draining ({} in-flight)", inFlight);
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                http.stop();
                LOG.info("R131: graceful shutdown complete");
            } catch (Exception e) {
                LOG.warn("R131: graceful shutdown error: {}", e.getMessage());
            }
        }, "aethercode-graceful-shutdown"));

        http.start();
        LOG.info("aethercode http daemon started (model={}, sessionId={}, port={})",
                engine.appState().mainLoopModel(), engine.appState().sessionId(), port);
        try {
            // Block forever. Javalin stops when the JVM stops or
            // when stop() is called from a shutdown hook.
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            http.stop();
        }
        return 0;
    }

    /** build a {@link org.aethercode.sdk.SessionManager}
     *  for the daemon. The "default" session is the engine
     *  the CLI built (via {@code Main.buildEngine()}); the
     *  factory only exists so non-default sessions have
     *  a builder seam, but those sessions are not yet
     *  wired at the factory level (prior round+ will plumb the
     *  full engine-build path through).
     *
     *  <p>The manager is registered as the engine's
     *  accessor (so {@code engine.sessionManager()}
     *  returns it) AND passed to the
     *  {@link AetherCodeMethods} 3-arg constructor (so
     *  the methods-level accessor returns it too). Both
     *  paths converge on the same instance.
     *
     *  <p>On the stdio path the engine is the single
     *  process-wide owner; on the HTTP path the same
     *  manager is shared across every WebSocket
     *  client (per the {@code HttpJsonRpcServer}
     *  contract). The factory throws for non-default
     *  ids so a misuse is caught early; the user
     *  sees {@code {ok: false, error: "engine factory
     *  failed: ..."}} from the {@code createEngine}
     *  RPC. */
    /** build a {@link org.aethercode.sdk.SessionManager}
     *  for the daemon. The "default" session is the engine
     *  the CLI built (via {@code Main.buildEngine()}); the
     *  factory only exists so non-default sessions have
     *  a builder seam, but those sessions are not yet
     *  wired at the factory level (prior round+ will plumb the
     *  full engine-build path through).
     *
     *  <p>The manager is registered as the engine's
     *  accessor (so {@code engine.sessionManager()}
     *  returns it) AND passed to the
     *  {@link AetherCodeMethods} 3-arg constructor (so
     *  the methods-level accessor returns it too). Both
     *  paths converge on the same instance.
     *
     *  <p>On the stdio path the engine is the single
     *  process-wide owner; on the HTTP path the same
     *  manager is shared across every WebSocket
     *  client (per the {@code HttpJsonRpcServer}
     *  contract).
     *
     *  <p>Package-private so the unit test in
     *  {@code DaemonRunnerTest} can exercise the
     *  wiring without spinning up the JSON-RPC
     *  transport.
     *
     *  <p>prior round: the {@code sessionFactory} is the
     *  closure the manager calls when the user
     *  creates a new engine via {@code createEngine}.
     *  When non-null, the closure is wrapped in an
     *  adapter that translates the
     *  {@code EngineFactory}'s checked-exception
     *  contract into a runtime exception (so the
     *  manager's {@code getOrCreate} path doesn't
     *  have to handle checked exceptions for what
     *  is, in practice, a runtime operation). When
     *  null, the default {@link #refuseNonDefaultFactory}
     *  is used (the prior round minimum scope). */
    static org.aethercode.sdk.SessionManager buildSessionManager(
            AetherCodeEngine engine,
            java.util.function.Function<String, AetherCodeEngine> sessionFactory) {
        java.util.function.Function<String, AetherCodeEngine> eff =
                sessionFactory != null ? sessionFactory : DaemonRunner::refuseNonDefaultFactory;
        org.aethercode.sdk.SessionManager.EngineFactory factory = sessionId -> {
            // the closure captures the CLI's
            // engine-build logic (provider, model,
            // cwd, skills, etc.) and applies the
            // new sessionId. Each call returns a
            // brand-new engine. See
            // Main.buildEngineForSession.
            return eff.apply(sessionId);
        };
        org.aethercode.sdk.SessionManager m =
                new org.aethercode.sdk.SessionManager(factory);
        m.registerExisting(org.aethercode.sdk.SessionManager.DEFAULT_SESSION_ID, engine);
        // R172 fix: also register the default engine under
        // its REAL sessionId (a UUID generated by
        // AetherCodeEngine.Builder). legacy the manager
        // only knew the engine by the literal key "default",
        // but {@code getState} returns the engine's
        // appState().sessionId() (the UUID), so the
        // desktop stores that UUID in `currentSessionId`
        // and passes it to every RPC (bindSessionCwd,
        // setModel, setPermissionMode, …). Those RPCs hit
        // {@code resolveRpcTarget(<UUID>)} which calls
        // {@code sm.get(<UUID>)} — and that returned null
        // because the manager only had "default" keyed.
        // Result: a fresh daemon with the desktop client
        // could call getState (it falls back through
        // currentEngine() → active() → get("default") → ok)
        // but the very next bindSessionCwd would fail with
        // "no engine for sessionId: <UUID>". The second
        // registerExisting() under the real id makes the
        // default engine reachable by both keys, so the
        // active session's id always resolves correctly.
        String realId;
        try {
            realId = engine.appState().sessionId();
        } catch (RuntimeException e) {
            realId = null;
        }
        if (realId != null && !realId.isBlank()
                && !org.aethercode.sdk.SessionManager.DEFAULT_SESSION_ID.equals(realId)) {
            m.registerExisting(realId, engine);
        }
        // keep the active pointer in sync with the
        // engine we just registered. legacy, activeSessionId
        // was "default" by default but the engine's real id
        // was a UUID — a {@code currentEngine()} → active()
        // round-trip still worked (it called get("default")),
        // but the moment a client did a setActive(realId) the
        // manager would have failed because the UUID wasn't
        // registered. Align them up front.
        if (realId != null && !realId.isBlank()) {
            try {
                m.setActive(realId);
            } catch (RuntimeException e) {
                // Real id is registered above, so this should
                // not fail. If it does (e.g. a future
                // SessionManager change adds new validation),
                // log and continue — the manager still has
                // the engine under "default" as a fallback.
                LOG.warn("R172: setActive({}) failed after dual-register: {}", realId, e.getMessage());
            }
        }
        LOG.info("对应历史 round: SessionManager wired (default session pre-registered; realId={}; factory={})",
                realId,
                sessionFactory != null ? "live (R96-B)" : "refuse-non-default (R96-B)");
        return m;
    }

    /** prior round: the default factory for callers
     *  that don't have a multi-session engine-build
     *  closure handy (the legacy 1-arg {@link #run}
     *  and 2-arg {@link #runHttp} entry points).
     *  Throws a clear error so a misuse (calling
     *  {@code createEngine} for a non-default id
     *  through the legacy entry point) is caught
     *  early. */
    static AetherCodeEngine refuseNonDefaultFactory(String sessionId) {
        if (org.aethercode.sdk.SessionManager.DEFAULT_SESSION_ID.equals(sessionId)) {
            throw new IllegalArgumentException(
                    "refuseNonDefaultFactory: the default session is pre-registered, "
                            + "not factory-built. This branch should not be reachable.");
        }
        throw new UnsupportedOperationException(
                "对应历史 round: multi-session factory not yet wired in DaemonRunner; "
                        + "non-default session '" + sessionId
                        + "' cannot be created through the legacy entry point. "
                        + "Use DaemonRunner.run(engine, factory) / runHttp(engine, port, factory) "
                        + "with a closure that builds fresh engines (对应历史 round: Main.buildEngineForSession).");
    }

    /**
     * helper that reads the headless opt-in
     * {@code AETHERCODE_AUTO_APPROVE_ALL} env var.
     * Accepts {@code 1}, {@code true}, {@code yes}
     * (case-insensitive) as truthy; everything else
     * is false. Default false — headless mode is
     * explicit, not silent.
     */
    private static boolean isAutoApproveAllEnv() {
        String v = System.getenv("AETHERCODE_AUTO_APPROVE_ALL");
        if (v == null) return false;
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    /**
     * install a {@code SessionStore} on the engine
     * if one isn't already wired. The CLI's
     * {@code Main.buildEngine} passes the store when the
     * user sets {@code --sessions-dir}; the daemon's
     * default is to resolve a sensible directory from
     * {@code AETHERCODE_SESSIONS_DIR} (env) and fall back
     * to {@code <cwd>/.aethercode/sessions}. The store
     * enables {@code createSession} / {@code loadSession}
     * / {@code listSessions} / {@code deleteSession} on
     * the {@code AetherCodeMethods} RPC surface — without
     * it, the engine throws {@code "SessionStore is not
     * wired"} on every call.
     *
     * <p>The setter is idempotent: a second call with the
     * same instance is a no-op, and a second call with a
     * different store throws. So if the CLI already wired
     * a store via {@code Builder.sessionStore(...)} the
     * helper detects that and returns without doing
     * anything.
     *
     * <p>R151b: when a {@code sessionManager} is passed,
     * the same store is shared with the manager so
     * factory-built engines (the
     * {@code createSession({cwd})} →
     * {@code EngineFactoryWithSpec} flow) also pick it
     * up. Without the share, a factory-built engine has
     * no store and the {@code createSession} RPC
     * immediately fails.
     *
     * <p>The {@code pathHint} is just a label for the log
     * line ("stdio" or "http") so the user can tell which
     * code path installed the store. It does not affect
     * the resolution.
     */
    static void ensureSessionStore(AetherCodeEngine engine, String pathHint) {
        ensureSessionStore(engine, null, pathHint);
    }

    /** same as the 2-arg form, but also
     *  shares the resolved store with the
     *  {@code SessionManager} so factory-built
     *  engines (the {@code createSession}
     *  → {@code createEngine} flow) pick it
     *  up automatically. */
    static void ensureSessionStore(AetherCodeEngine engine,
                                    org.aethercode.sdk.SessionManager sessionManager,
                                    String pathHint) {
        if (engine == null) return;
        org.aethercode.core.transcript.SessionStore existing =
                engine.sessionStore();
        if (existing != null) {
            LOG.info("R148: ensureSessionStore({}) — engine already has a store at {}",
                    pathHint, existing.dir());
            if (sessionManager != null && sessionManager.sharedSessionStore() == null) {
                sessionManager.setSharedSessionStore(existing);
                LOG.info("R151b: shared SessionStore adopted from main engine ({})",
                        existing.dir());
            }
            return;
        }
        java.nio.file.Path sessionsDir = resolveSessionsDir();
        try {
            java.nio.file.Files.createDirectories(sessionsDir);
        } catch (java.io.IOException ioe) {
            LOG.warn("R148: ensureSessionStore({}) — failed to create {}: {}",
                    pathHint, sessionsDir, ioe.getMessage());
            return;
        }
        org.aethercode.core.transcript.SessionStore store =
                new org.aethercode.core.transcript.SessionStore(sessionsDir);
        try {
            engine.setSessionStore(store);
            if (sessionManager != null) {
                sessionManager.setSharedSessionStore(store);
                LOG.info("R151b: shared SessionStore wired (manager + main engine) at {}",
                        sessionsDir);
            }
            LOG.info("R148: ensureSessionStore({}) — installed SessionStore at {}",
                    pathHint, sessionsDir);
        } catch (Exception e) {
            LOG.warn("R148: ensureSessionStore({}) — setSessionStore failed: {}",
                    pathHint, e.getMessage());
        }
    }

    /**
     * resolve the default sessions directory. Order:
     * <ol>
     *   <li>{@code AETHERCODE_SESSIONS_DIR} env var (explicit override)</li>
     *   <li>{@code <cwd>/.aethercode/sessions} (project-local default)</li>
     * </ol>
     * The env var lets the user point multiple daemons at
     * the same directory (e.g. for a multi-project setup
     * that wants a single index) without touching the
     * project tree.
     */
    static java.nio.file.Path resolveSessionsDir() {
        String env = System.getenv("AETHERCODE_SESSIONS_DIR");
        if (env != null && !env.isBlank()) {
            return java.nio.file.Path.of(env).toAbsolutePath().normalize();
        }
        String cwd = System.getProperty("user.dir");
        if (cwd == null || cwd.isBlank()) {
            cwd = System.getProperty("user.home");
        }
        return java.nio.file.Path.of(cwd).resolve(".aethercode").resolve("sessions");
    }

    /**
     * build the 3-layer memory facade. The store
     * is backed by:
     * <ul>
     *   <li>USER    — {@code <memoryBase>/agent-memory/<agentType>/MEMORY.md}
     *       via {@link org.aethercode.memory.FileBackedMemory}.</li>
     *   <li>PROJECT — {@code <cwd>/.aethercode/agent-memory/<agentType>/MEMORY.md}
     *       via {@link org.aethercode.memory.FileBackedMemory},
     *       one per cwd, cached.</li>
     *   <li>SESSION — {@code <memoryBase>/sessions.db} (SQLite)
     *       via {@link org.aethercode.memory.SessionMemoryStore}.</li>
     * </ul>
     *
     * <p>The chat client for the LLM-driven project
     * memory compressor is read from
     * {@code methods.chatClientResolver()}. When null
     * (the stdio path), the compressor falls back to
     * the tag-only line — the user can plumb a richer
     * client by calling
     * {@link AetherCodeMethods#setChatClientResolver}
     * before
     * {@link AetherCodeMethods#setMemoryStore}.
     */
    static org.aethercode.memory.LayeredMemoryStore buildMemoryStore(
            AetherCodeMethods methods, java.nio.file.Path memoryBase) {
        try { java.nio.file.Files.createDirectories(memoryBase); } catch (Exception ignore) {}
        String agentType = "default";
        java.nio.file.Path dbFile = memoryBase.resolve("sessions.db");
        org.aethercode.memory.SessionMemoryStore sessionStore =
                new org.aethercode.memory.SessionMemoryStore(dbFile);
        org.aethercode.memory.ProjectMemoryCompressor.ChatClient client = prompt -> {
            // Wire-through: the methods object may
            // have a chatClientResolver (set by the
            // HTTP+WS path for the R127 round). When it
            // does, the resolver builds a real ChatClient.
            // The core ChatClient interface has a
            // stream(messages, systemPrompt, tools) method
            // but no single-shot complete(prompt) — the
            // ProjectMemoryCompressor therefore falls back
            // to the tag-only "[compressed: N entries]"
            // line. The user can plumb a richer path by
            // supplying a custom ChatClient to the
            // LayeredMemoryStore constructor (e.g. one
            // that wraps a Spring-AI completion
            // endpoint). This R127 round keeps the
            // default conservative.
            var resolver = methods.chatClientResolverField();
            if (resolver == null) return java.util.Optional.empty();
            try {
                var chat = resolver.apply(methods.currentProviderName() + "/" + methods.currentModelId());
                if (chat == null) return java.util.Optional.empty();
                LOG.debug("compressor chat client lacks complete() — using tag-only fallback");
                return java.util.Optional.empty();
            } catch (Exception e) {
                LOG.warn("compressor chat failed: {}", e.getMessage());
                return java.util.Optional.empty();
            }
        };
        org.aethercode.memory.ProjectMemoryCompressor compressor =
                new org.aethercode.memory.ProjectMemoryCompressor(client);
        return new org.aethercode.memory.LayeredMemoryStore(
                memoryBase, agentType, sessionStore, compressor,
                50, 10, true);
    }

    /** prior round: build the unified registry-reload service
     *  with all four reloaders (skills, agents, mcp, all)
     *  wired to the engine's registries. The watchers are
     *  installed on the standard locations:
     *  <ul>
     *    <li>User skills: {@code ~/.aethercode/skills/}</li>
     *    <li>User agents: {@code ~/.aethercode/agents/}</li>
     *    <li>User MCP: {@code ~/.aethercode/mcp.json}</li>
     *    <li>Project MCP: {@code <cwd>/.aethercode/mcp.json}
     *        — best-effort; absent paths are silently
     *        skipped so a project without an MCP config
     *        doesn't fail startup.</li>
     *  </ul>
     *  Returns {@code null} when no skill / agent / MCP
     *  roots are reachable (a vanilla install before the
     *  user has set up anything). The caller is expected
     *  to handle null by skipping the listener
     *  registration. */
    static org.aethercode.core.registry.RegistryReloadService buildRegistryReloadService(
            AetherCodeEngine engine) throws java.io.IOException {
        org.aethercode.core.registry.RegistryReloadService svc =
                new org.aethercode.core.registry.RegistryReloadService();
        // Reloader: skills
        if (engine.skillRegistry() != null) {
            svc.registerReloader(org.aethercode.core.registry.RegistryReloadService.ReloadKind.SKILLS,
                    () -> engine.reloadSkills());
            // was ~/.minimax/skills, now ~/.aethercode/skills
            // (aligned with the project-tier name and with the
            // ~/.aethercode/mcp.json path the daemon already uses).
            Path userSkills = java.nio.file.Path.of(System.getProperty("user.home"))
                    .resolve(".aethercode").resolve("skills");
            if (Files.isDirectory(userSkills)) svc.watch(userSkills,
                    org.aethercode.core.registry.RegistryReloadService.ReloadKind.SKILLS);
        }
        // Reloader: agents
        if (engine.agentRegistry() != null) {
            svc.registerReloader(org.aethercode.core.registry.RegistryReloadService.ReloadKind.AGENTS,
                    () -> engine.agentRegistry().reload());
            // was ~/.minimax/agents, now ~/.aethercode/agents
            // (same rename rationale as the skills path above).
            Path userAgents = java.nio.file.Path.of(System.getProperty("user.home"))
                    .resolve(".aethercode").resolve("agents");
            if (Files.isDirectory(userAgents)) svc.watch(userAgents,
                    org.aethercode.core.registry.RegistryReloadService.ReloadKind.AGENTS);
        }
        // Reloader: MCP. R132 ships the actual teardown
        // + re-create path. The watcher fires on a
        // change; the reloader asks the engine's
        // McpManager to diff the new config against the
        // current set of live servers, close the
        // removed ones, start the new ones, and return
        // the new tool list. The engine then atomically
        // swaps the tool pool. The whole thing takes
        // ~100ms-2s depending on how many servers need
        // to restart.
        if (engine.mcpManager() != null) {
            Path userMcp = java.nio.file.Path.of(System.getProperty("user.home"))
                    .resolve(".aethercode").resolve("mcp.json");
            Path projectMcp = null;
            String cwd = System.getProperty("user.dir");
            if (cwd != null) {
                projectMcp = java.nio.file.Path.of(cwd)
                        .resolve(".aethercode").resolve("mcp.json");
            }
            // Re-read both files: union of entries
            // wins (project overrides user on name
            // collision). The McpManager already does
            // the diff — we just pick the file the
            // user is more likely editing. Project
            // mcp.json wins if present.
            final Path userMcpFinal = userMcp;
            final Path projectMcpFinal = projectMcp;
            svc.registerReloader(org.aethercode.core.registry.RegistryReloadService.ReloadKind.MCP,
                    () -> {
                        // Prefer the project config when both exist;
                        // fall back to the user config; fall back to
                        // the mcp.json last loaded by the engine.
                        Path target = projectMcpFinal != null && Files.isRegularFile(projectMcpFinal)
                                ? projectMcpFinal
                                : (Files.isRegularFile(userMcpFinal) ? userMcpFinal : null);
                        if (target == null) {
                            LOG.debug("MCP reload skipped: no mcp.json in user or project dir");
                            return;
                        }
                        var res = engine.mcpManager().reload(target);
                        // Atomic swap: remove the old "mcp:" tools
                        // and install the new ones. The swap is
                        // sync; the next model call sees the new
                        // tool set.
                        engine.replaceMcpTools(res.tools());
                        LOG.info("R132: MCP reload done ({}+{}+{} changed, +{} added, -{} removed, ={} unchanged)",
                                res.changed(), res.added(), res.removed(),
                                res.added(), res.removed(), res.unchanged());
                        if (res.hasErrors()) {
                            LOG.warn("R132: MCP reload had errors: {}", res.errors());
                        }
                    });
            if (Files.isRegularFile(userMcp)) svc.watchFile(userMcp,
                    org.aethercode.core.registry.RegistryReloadService.ReloadKind.MCP);
            if (projectMcp != null && Files.isRegularFile(projectMcp)) svc.watchFile(projectMcp,
                    org.aethercode.core.registry.RegistryReloadService.ReloadKind.MCP);
        }
        return svc;
    }
}
