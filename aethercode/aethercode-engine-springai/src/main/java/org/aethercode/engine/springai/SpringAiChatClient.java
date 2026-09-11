package org.aethercode.engine.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.llm.ChatClient.Options;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * {@link ChatClient} implementation backed by spring-ai's
 * {@link OpenAiChatModel} (the OpenAI-compatible chat model, used here for the
 * MiniMax Coding Plan). Default base URL: {@code https://api.minimaxi.com/v1};
 * default model: {@code MiniMax-M3}. Default {@code max_tokens=1024}.
 *
 * <p>Streaming: we run spring-ai's reactive {@code ChatModel.stream(Prompt)} on
 * a worker thread and push the assembled events into a blocking queue; the
 * returned {@link Stream} is a tail of that queue. This keeps the public API on
 * {@code Stream<StreamEvent>} instead of Reactor's {@code Flux<ChatResponse>}.
 *
 * <p>Tool calling (prior round): the caller's tool list is converted to spring-ai's
 * {@code FunctionCallback} via {@link ToolAdapter} and registered on the per-call
 * {@link Prompt} via {@code options.toolCallbacks(...)}. We set
 * {@code proxyToolCalls(true)} so spring-ai returns the model's tool-call
 * request in the final response but does NOT execute it. We then surface each
 * tool call as a {@code ToolUseStart} event in the output stream; the engine's
 * {@code StreamingToolExecutor} runs the actual tool (with permissions + hooks)
 * and appends the result to the transcript. The engine then loops back and
 * calls us again with the updated transcript. Compared to
 * {@code internalToolExecutionEnabled(true)} (prior round) this exposes the
 * per-step tool calls to the consumer, so the TUI can paint "tool N is
 * running" and the CLI can print "→ calling tool X(args)" as they happen.
 */
public class SpringAiChatClient implements ChatClient {

    private static final Logger LOG = LoggerFactory.getLogger(SpringAiChatClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object POISON = new Object();

    private final String model;
    private final Options options;
    private final OpenAiChatModel chatModel;
    /** optional AppState so tools can publish per-session state (e.g. the
     *  current todo list) without needing the spring-ai path to know about
     *  the engine. Set via {@link #appState} after construction. */
    private volatile org.aethercode.core.app.AppState appState;

    public SpringAiChatClient(String model, Options options) {
        this.model = model == null || model.isBlank() ? "MiniMax-M3" : model;
        this.options = options == null ? minimaxDefaults() : options;
        if (this.options.apiKey() == null || this.options.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "MiniMax API key is required. Set MINIMAX_API_KEY or pass --api-key.");
        }
        // R15 fix: spring-ai's OpenAiApi treats baseUrl as the bare host. The chat
        // completion path is hard-coded to /v1/chat/completions. So if the user
        // gives us "https://api.minimaxi.com/v1" as the base URL, the client
        // builds "https://api.minimaxi.com/v1/v1/chat/completions" and gets a
        // 404. Strip any trailing /v1 (or anything after the host) so the
        // assembled URL is correct.
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(stripV1(this.options.baseUrl()))
                .apiKey(this.options.apiKey())
                .build();
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(this.model)
                // R136.4: if maxTokens is 0, fall back to 64K (was
                // 1024). Models that publish a 512K ceiling (MiniMax
                // M3) get the full 512K via the model-specific
                // default; 64K is the safe per-call cap for any
                // model whose ceiling we don't know.
                .maxTokens(this.options.maxTokens() > 0 ? this.options.maxTokens() : 65_536)
                .temperature(Double.isNaN(this.options.temperature()) ? 1.0 : this.options.temperature())
                .build();
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(chatOptions)
                .build();
    }

    @Override
    public String modelId() { return model; }

    /** set the AppState so tools running through this client can read /
     *  write session state (notably the todo list). */
    public void appState(org.aethercode.core.app.AppState appState) { this.appState = appState; }

    /** set the SubagentEngine so multi-step {@code AgentTool}
     *  invocations via the spring-ai path can re-enter the engine
     *  loop. Set by {@code AetherCodeEngine} after construction.
     *  Null in test contexts that don't have an engine. */
    public void subagentEngine(org.aethercode.core.agent.Subagent.SubagentEngine e) {
        this.subagentEngine = e;
    }
    private volatile org.aethercode.core.agent.Subagent.SubagentEngine subagentEngine;

    @Override
    public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        // do NOT emit our own RunStart. The QueryEngine already emits a
        // RunStart at the start of each turn (with a per-query runId + per-turn
        // counter). Emitting ours too produced a duplicate RunStart in the
        // consumer's stream ("turn started" twice in a row). Data events
        // (TextDelta, ToolUseStart) are still emitted; control events (RunStart,
        // terminal RunEnd) are owned by the engine.
        Thread runner = new Thread(() -> {
            try {
                runCall(messages, systemPrompt, tools, queue);
                queue.offer(POISON);
            } catch (Throwable t) {
                LOG.error("spring-ai call failed: {}", t.getMessage(), t);
                queue.offer(new StreamEvent.RunEnd(
                        "error: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        List.of()));
                queue.offer(POISON);
            }
        }, "springai-runner");
        runner.setDaemon(true);
        runner.start();

        java.util.Spliterator<StreamEvent> sp = new java.util.Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE, java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
            @Override public boolean tryAdvance(java.util.function.Consumer<? super StreamEvent> action) {
                try {
                    while (true) {
                        Object e = queue.take();
                        if (e == POISON) return false;
                        action.accept((StreamEvent) e);
                        return true;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
        return StreamSupport.stream(sp, false).onClose(runner::interrupt);
    }

    private void runCall(List<Message> messages, String systemPrompt, List<Tool> tools,
                          BlockingQueue<Object> queue) {
        // Build the spring-ai Prompt.
        List<org.springframework.ai.chat.messages.Message> saMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            saMessages.add(new SystemMessage(systemPrompt));
        }
        for (Message m : messages) {
            if (m.role() == Role.SYSTEM) continue;
            if (m.role() == Role.USER) {
                saMessages.add(translateUser(m));
            } else if (m.role() == Role.ASSISTANT) {
                saMessages.add(translateAssistant(m));
            } else if (m.role() == Role.TOOL_RESULT) {
                for (ContentBlock b : m.content()) {
                    if (b instanceof ContentBlock.ToolResultBlock tr) {
                        var resp = new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                                tr.toolUseId(), tr.toolUseId(), stringContent(tr.content()));
                        saMessages.add(new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(resp)));
                    }
                }
            }
        }

        // register tools via spring-ai's M6 FunctionCallback API and
        // set proxyToolCalls(true) so spring-ai returns the model's tool-call
        // request in the final response but does NOT execute it. The engine's
        // QueryEngine then runs the tool (via StreamingToolExecutor, with
        // permissions + hooks + parallelism) and loops back to re-call the
        // model with the updated transcript. Compared to the prior round
        // internalToolExecutionEnabled(true) path this exposes every step to
        // the consumer: TextDelta chunks stream in live, and each tool call
        // is emitted as a ToolUseStart event before the engine runs it. The
        // TUI can paint "tool running", the CLI can print "→ bash(...)", etc.
        List<org.springframework.ai.model.function.FunctionCallback> callbacks = new ArrayList<>();
        if (tools != null) {
            for (Tool t : tools) {
                if (t == null) continue;
                // pass `this` (the SpringAiChatClient itself) as the
                // chat client so tools like AgentTool can spawn subagents
                // from inside the spring-ai tool loop. prior round: also pass the
                // SubagentEngine so multi-step AgentTool can re-enter the
                // engine loop.
                callbacks.add(ToolAdapter.adapt(t, this.appState, this, this.subagentEngine));
            }
        }
        var optsBuilder = OpenAiChatOptions.builder()
                .model(this.model)
                // R136.4: same 64K fallback as the constructor —
                // the per-call `stream(...)` should never cap at
                // the R15 default of 1024.
                .maxTokens(this.options.maxTokens() > 0 ? this.options.maxTokens() : 65_536)
                .temperature(Double.isNaN(this.options.temperature()) ? 1.0 : this.options.temperature());
        if (!callbacks.isEmpty()) {
            optsBuilder.toolCallbacks(callbacks);
            // proxy tool calls. We expose them to our event stream and
            // run them via the engine's StreamingToolExecutor. This is what
            // gives us per-event visibility. Without this flag, spring-ai
            // would run the model→tool→model loop internally and we'd only
            // see the final assistant message (the old prior round behaviour).
            optsBuilder.proxyToolCalls(true);
        }
        OpenAiChatOptions opts = optsBuilder.build();
        Prompt prompt = new Prompt(saMessages, opts);

        // use the streaming API so we can surface per-chunk TextDelta
        // events. Each ChatResponse in the Flux is a partial delta; the
        // FINAL one carries the complete AssistantMessage (text + tool calls).
        // We accumulate text across chunks and emit one TextDelta per non-empty
        // chunk, then take the last AssistantMessage and emit ToolUseStart
        // events for each real tool call. This gives the TUI a live
        // character-by-character stream of the model's reply.
        reactor.core.publisher.Flux<ChatResponse> flux = chatModel.stream(prompt);
        AssistantMessage finalMsg = null;
        List<ContentBlock> finalBlocks = new ArrayList<>();
        StringBuilder textBuf = new StringBuilder();
        // accumulate per-call token usage. spring-ai's ChatResponse
        // exposes Usage via getMetadata().getUsage(); the numbers
        // sometimes grow across chunks (incremental) and sometimes
        // appear only on the final response. We sum everything we
        // see and emit a single Usage event at the end so the engine
        // doesn't double-count.
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        try {
            // Block on the Flux — this is on the springai-runner thread, so
            // blocking here is fine. toIterable() drains the Flux and gives
            // us a plain Iterator<ChatResponse> we can loop over.
            java.util.Iterator<ChatResponse> it = flux.toIterable().iterator();
            while (it.hasNext()) {
                ChatResponse resp = it.next();
                if (resp == null) continue;
                // capture token usage. spring-ai's Usage lives on
                // the response metadata; we read it defensively because
                // not every transport (and not every intermediate
                // chunk) populates it. The numbers are per-call deltas
                // from the provider; summing is safe even if the
                // provider sends the same totals on every chunk
                // because we emit ONCE at the end of the call below.
                try {
                    var meta = resp.getMetadata();
                    if (meta != null && meta.getUsage() != null) {
                        var u = meta.getUsage();
                        Integer p = u.getPromptTokens();
                        Integer c = u.getCompletionTokens();
                        if (p != null) totalInputTokens = Math.max(totalInputTokens, p);
                        if (c != null) totalOutputTokens = Math.max(totalOutputTokens, c);
                    }
                } catch (Throwable ignored) {
                    // Some ChatResponse implementations may not have
                    // metadata. That's fine — Usage event is best-effort.
                }
                Generation gen = resp.getResult();
                if (gen == null) continue;
                AssistantMessage am = gen.getOutput();
                if (am == null) continue;
                // Always update finalMsg — the last ChatResponse in the stream
                // carries the complete (text + toolCalls) AssistantMessage.
                finalMsg = am;
                String chunk = am.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    textBuf.append(chunk);
                    queue.offer(new StreamEvent.TextDelta(chunk));
                }
            }
        } catch (Exception e) {
            LOG.error("streaming call failed: {}", e.getMessage(), e);
            queue.offer(new StreamEvent.RunEnd(
                    "error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    List.of()));
            return;
        }
        if (LOG.isDebugEnabled()) {
            String snippet = textBuf.length() == 0 ? "null"
                    : "'" + textBuf.substring(0, Math.min(60, textBuf.length())) + "...'";
            LOG.debug("[spring-ai] text={} toolCalls={}",
                    snippet,
                    finalMsg == null ? "null" : finalMsg.getToolCalls());
        }
        // The accumulated text becomes a TextBlock in the final assistant message.
        if (textBuf.length() > 0) {
            finalBlocks.add(new ContentBlock.TextBlock(textBuf.toString()));
        }
        // prior round: be defensive about tool calls. spring-ai 1.0.0-M6
        // sometimes returns an empty/null tool-call entry even for pure-text
        // responses (the assistant message declares a tool_call slot with
        // empty id and name). We drop blank tool calls here so they don't
        // pollute the consumer stream with a ToolUseStart(id="", name="").
        // We also gate on toolsWereRegistered so we never emit a tool call
        // when the caller didn't actually pass any tools in.
        //
        // prior round follow-up: spring-ai's OpenAI streaming aggregation can
        // occasionally produce DUPLICATE entries for the same tool call
        // (we observed this in a real --print run — different ids, same
        // name + same args). The model is presumably being indecisive, but
        // the engine should not pay for it: we dedupe by (name, args) so
        // each unique tool call is only added to the assistant message
        // once. Without this the engine would run the same tool twice
        // (once per duplicate tool_use block). The model genuinely wanting
        // to call the same tool twice with the same args is a model-level
        // bug we can address later.
        List<AssistantMessage.ToolCall> rawCalls = finalMsg == null ? List.of() : finalMsg.getToolCalls();
        List<AssistantMessage.ToolCall> realCalls = new ArrayList<>();
        java.util.Set<String> seenCallKeys = new java.util.HashSet<>();
        if (rawCalls != null) {
            for (var tc : rawCalls) {
                if (tc == null) continue;
                if (tc.id() == null || tc.id().isBlank()) continue;
                if (tc.name() == null || tc.name().isBlank()) continue;
                String key = tc.name() + "|" + (tc.arguments() == null ? "" : tc.arguments());
                if (!seenCallKeys.add(key)) continue;  // dedupe by (name, args)
                realCalls.add(tc);
            }
        }
        // ToolUseStart is NOT emitted from runCall. The engine's
        // QueryEngine already emits one when the StreamingToolExecutor
        // actually starts the tool (see `Event.Started` handler in
        // QueryEngine.tryAdvance), with the same id/name/input. Emitting
        // it twice would surface "→ bash" twice in --print and the TUI for
        // every tool call. We still add the ToolUseBlock to finalBlocks so
        // the assistant message in the transcript records the model's
        // intent — that part is for the model itself, not the user.
        if (realCalls.isEmpty() || tools == null || tools.isEmpty()) {
            // emit per-call token usage before the final RunEnd so
            // the engine's stream handler can record it on the metrics
            // collector. We emit the totals we accumulated across all
            // ChatResponse chunks (best-effort: some providers don't
            // populate Usage, in which case we emit zeros).
            if (totalInputTokens > 0 || totalOutputTokens > 0) {
                queue.offer(new StreamEvent.Usage(totalInputTokens, totalOutputTokens));
            }
            queue.offer(new StreamEvent.RunEnd("stop", List.copyOf(finalBlocks)));
        } else {
            for (var tc : realCalls) {
                // pre-parse, log the tool name
                // alongside the args so we can pinpoint
                // which tool the model is mis-firing. The
                // log fires in three places: (1) args
                // is null/blank, (2) args parses to
                // empty map, (3) args fails JSON parse.
                // All three end up returning Map.of() so
                // the tool runs with no params and the
                // engine surfaces "X is required". Now we
                // can correlate the empty-input tool_use
                // with the specific tool name to find
                // which tools the model is failing on.
                if (tc.arguments() == null || tc.arguments().isBlank()
                        || tc.arguments().equals("{}")) {
                    LOG.warn("R225: empty-args tool_use detected: tool={}, id={}, raw_args={}",
                            tc.name(), tc.id(),
                            tc.arguments() == null ? "null"
                                    : (tc.arguments().length() > 200
                                            ? tc.arguments().substring(0, 200) + "...[truncated]"
                                            : tc.arguments()));
                }
                Map<String, Object> input = parseJsonArgs(tc.arguments());
                finalBlocks.add(new ContentBlock.ToolUseBlock(tc.id(), tc.name(), input));
            }
            if (totalInputTokens > 0 || totalOutputTokens > 0) {
                queue.offer(new StreamEvent.Usage(totalInputTokens, totalOutputTokens));
            }
            queue.offer(new StreamEvent.RunEnd("tool_calls", List.copyOf(finalBlocks)));
        }
    }

    private static UserMessage translateUser(Message m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) sb.append(t.text());
        }
        return new UserMessage(sb.toString());
    }

    private static AssistantMessage translateAssistant(Message m) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                if (text.length() > 0) text.append('\n');
                text.append(t.text());
            } else if (b instanceof ContentBlock.ToolUseBlock u) {
                String args;
                try { args = MAPPER.writeValueAsString(u.input()); }
                catch (Exception e) { args = "{}"; }
                tcs.add(new AssistantMessage.ToolCall(u.id(), "function", u.name(), args));
            }
        }
        return new AssistantMessage(text.toString(), Map.of(), tcs);
    }

    private static String stringContent(Object c) {
        if (c == null) return "";
        if (c instanceof String s) return s;
        try { return MAPPER.writeValueAsString(c); }
        catch (Exception e) { return c.toString(); }
    }

    private static Map<String, Object> parseJsonArgs(String s) {
        if (s == null || s.isBlank()) {
            // empty args is suspicious. The model
            // was given a tool with required parameters
            // (e.g. `bash` requires `command`); emitting
            // an empty tool_use is the only way the model
            // could end up here. Log it so we can see
            // how often this happens in the wild and
            // cross-reference with the tool name to know
            // which tool the model is mis-firing.
            LOG.warn("R225: parseJsonArgs received null/blank args. The model emitted a tool_use with empty arguments.");
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(s, new TypeReference<Map<String, Object>>() {});
            if (parsed == null || parsed.isEmpty()) {
                // args parsed as valid JSON but
                // resolved to an empty map. Same root
                // cause as the blank case (model emitted
                // a tool_use with no params) but worth a
                // separate log line so we can tell them
                // apart. We log the first 200 chars of
                // `s` so we can see if it's literally
                // `"{}"` or some non-empty JSON that
                // happens to have no top-level keys.
                String preview = s.length() > 200 ? s.substring(0, 200) + "...[truncated]" : s;
                LOG.warn("R225: parseJsonArgs parsed args but got an empty map. Raw args (first 200 chars): {}", preview);
            }
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            // the args string was not valid JSON.
            // legacy the catch silently returned Map.of()
            // and the model got "command is required" with
            // no diagnostic — impossible to tell whether
            // the model was emitting Mavis XML, bare tags,
            // empty content, or something else. Log the
            // first 200 chars of the failing args so a
            // session.jsonl dump or a fresh daemon start
            // can both pinpoint the issue. The second
            // line is the JSON parse error so we know
            // which failure mode (malformed JSON,
            // unrecognised type, etc.) it was.
            String preview = s.length() > 200 ? s.substring(0, 200) + "...[truncated]" : s;
            LOG.warn("R225: parseJsonArgs failed (args was not valid JSON); first 200 chars: {}", preview);
            LOG.warn("R225: parseJsonArgs error: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Default {@link Options} for the MiniMax Coding Plan.
     *  R136.4: bumped maxTokens from the prior round hardcoded 1024
     *  to 64K — a sensible fallback when the caller doesn't
     *  know the model's actual ceiling. MiniMax-M3 supports
     *  512K but 64K is enough for the vast majority of tool
     *  calls and avoids accidentally running the model for
     *  several minutes on a runaway thinking loop. The engine
     *  can override per-call via {@link #stream}. */
    public static Options minimaxDefaults() {
        return new Options(System.getenv("MINIMAX_API_KEY"),
                "https://api.minimaxi.com/v1", 65_536, 1_000_000, 1.0);
    }

    /** build a {@link SpringAiChatClient} for a
     *  specific provider from the
     *  {@link org.aethercode.core.providers.ProviderSpec}.
     *  Resolves the provider's {@code apiKeyEnv} at
     *  call time, throws when the env var is unset.
     *  The model defaults to the provider's
     *  {@code defaultModel} when {@code model} is
     *  null — the user can override per session. */
    public static SpringAiChatClient forProvider(
            org.aethercode.core.providers.ProviderSpec spec,
            String model) {
        if (spec == null) {
            throw new IllegalArgumentException("provider spec is required");
        }
        String key = spec.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "provider " + spec.name() + " requires env var "
                            + spec.apiKeyEnv() + " (unset)");
        }
        String m = (model == null || model.isBlank())
                ? spec.defaultModel()
                : model;
        if (m == null || m.isBlank()) {
            throw new IllegalStateException(
                    "provider " + spec.name() + " has no defaultModel and caller did not specify one");
        }
        // R136.4: pull the model's max output ceiling
        // from ProviderSpec so the chat-completion
        // `max_tokens` param isn't capped at 1024 (the
        // R15 default). Also pull the context window
        // through to ChatClient.Options for the
        // compactor.
        var modelSpec = spec.models().stream()
                .filter(ms -> m.equals(ms.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "provider " + spec.name() + " has no model " + m
                                + " in its model list"));
        return new SpringAiChatClient(m,
                new Options(key, spec.baseUrl(),
                        modelSpec.maxOutput(),
                        modelSpec.context(),
                        1.0));
    }

    private static String stripV1(String baseUrl) {
        if (baseUrl == null) return "https://api.minimaxi.com";
        // Strip trailing /v1 (with or without slashes) so spring-ai's hard-coded
        // /v1/chat/completions path doesn't double up.
        String s = baseUrl;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/v1")) s = s.substring(0, s.length() - 3);
        return s;
    }
}
