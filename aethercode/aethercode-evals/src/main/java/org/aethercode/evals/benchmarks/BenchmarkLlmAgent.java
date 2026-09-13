package org.aethercode.evals.benchmarks;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end benchmark agent backed by a real {@link ChatClient}.
 * <p>
 * For each task, it:
 * <ol>
 *   <li>Builds a single-user message from the task's prompt.</li>
 *   <li>Calls {@link ChatClient#stream} with a minimal system prompt.</li>
 *   <li>Collects the streamed text into a single response string.</li>
 *   <li>Returns the response as the agent's output.</li>
 * </ol>
 * <p>
 * The agent is stateless across tasks (no multi-turn). Tools are
 * not passed (this is the simplest end-to-end; for tool-using
 * benchmarks, a more elaborate loop would be needed).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   ChatClient client = new MockChatClient();
 *   BenchmarkLlmAgent agent = new BenchmarkLlmAgent(client);
 *   for (BenchmarkTask task : adapter) {
 *       String output = agent.run(task);
 *       boolean pass = adapter.grade(task, output);
 *   }
 * }</pre>
 */
public final class BenchmarkLlmAgent implements BenchmarkAgent {

    private final ChatClient client;
    private final String systemPrompt;

    public BenchmarkLlmAgent(ChatClient client) {
        this(client, "You are an AI agent. Answer the user's question concisely and accurately.");
    }

    public BenchmarkLlmAgent(ChatClient client, String systemPrompt) {
        this.client = client;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String run(BenchmarkTask task) {
        List<Message> messages = List.of(
            new Message(null, Role.USER, List.of(new ContentBlock.TextBlock(task.prompt())),
                null, null)
        );
        List<Tool> tools = List.of();

        StringBuilder out = new StringBuilder();
        client.stream(messages, systemPrompt, tools).forEach(event -> {
            if (event instanceof StreamEvent.TextDelta delta) {
                out.append(delta.text());
            } else if (event instanceof StreamEvent.RunEnd end) {
                // if no text was streamed, fall back to finalBlocks
                if (out.length() == 0 && end.finalBlocks() != null) {
                    for (ContentBlock b : end.finalBlocks()) {
                        if (b instanceof ContentBlock.TextBlock t) {
                            out.append(t.text());
                        }
                    }
                }
            }
        });
        String raw = out.toString().trim();
        // Strip <think>...</think> blocks so the grading logic sees just the answer.
        // MiniMax-M3 (and most reasoning models) emits a think preamble; the
        // benchmark grader only cares about the final answer.
        return raw.replaceAll("(?is)<think>.*?</think>", "").trim();
    }

    /** Adapter so this can be used outside the test class too. */
    public String answer(String prompt) {
        return run(new BenchmarkTask("ad-hoc", prompt, "", List.of(), java.util.Map.of()));
    }
}
