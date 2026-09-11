package org.aethercode.cli;

import org.aethercode.a2a.A2AClient;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code aethercode a2a <host> stream "<text>"} — sends a user message via
 * {@code message/sendSubscribe} and streams the server's SSE events to stdout.
 * One event per line so the output is grep-friendly.
 *
 * <p>Output format:</p>
 * <pre>
 *   status   &lt;state&gt; [message]
 *   artifact &lt;name&gt;
 *     &lt;part-kind&gt;: &lt;text&gt;
 *   error    &lt;code&gt; &lt;message&gt
 * </pre>
 * <p>The {@code status} and {@code artifact} lines mirror the wire's TaskUpdate
 * shape; the {@code error} line is a single-line rendering of a JSON-RPC error
 * frame the server emitted (protocol-level failures such as invalid params or
 * unknown method).</p>
 */
@Command(
        name = "stream",
        mixinStandardHelpOptions = true,
        description = "Send a user message and stream the agent's events.")
public class A2aStreamCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "HOST",
            description = "Agent host (host:port or full URL).")
    String host;

    @Parameters(arity = "1", paramLabel = "TEXT",
            description = "Text to send as the user message.")
    String text;

    @Override
    public Integer call() throws Exception {
        A2aCommand.A2aCommandHelper helper =
                new A2aCommand.A2aCommandHelper(host, "http", 5);
        Message user = Message.user(Part.TextPart.of(text));
        // Block on the stream; the A2A client walks SSE frames synchronously
        // and fires this callback per frame. The line-buffered print means
        // `pipe | tail -f` shows events as they arrive.
        helper.newClient().subscribeMessage(user, new A2AClient.StreamObserver() {
            @Override
            public void onUpdate(Map<String, Object> update) {
                String event = (String) update.get("event");
                // Wire shape: the server's SSE data line is the contents of
                // TaskUpdate.toMap() (no outer `data` envelope), and the A2A
                // client merges the event name onto the same map via
                // putIfAbsent. So the status / artifact / error payload all
                // sits at the top level of the map.
                if ("error".equals(event)) {
                    System.out.println("error    " + update.get("code")
                            + " " + update.get("message"));
                    return;
                }
                if ("status".equals(event)) {
                    // Status events put the state under a nested status map:
                    // {status: {state, message}, event}.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) update.get("status");
                    if (s == null) {
                        System.out.println("status   <malformed: no status field>");
                        return;
                    }
                    String state = (String) s.get("state");
                    Object msg = s.get("message");
                    System.out.println("status   " + state
                            + (msg == null ? "" : " " + msg));
                } else if ("artifact".equals(event)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> a = (Map<String, Object>) update.get("artifact");
                    if (a == null) {
                        System.out.println("artifact <malformed: no artifact field>");
                        return;
                    }
                    System.out.println("artifact " + a.get("name"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> parts =
                            (List<Map<String, Object>>) a.get("parts");
                    if (parts != null) {
                        for (Map<String, Object> p : parts) {
                            Object textVal = p.get("text");
                            System.out.println("  " + p.get("kind")
                                    + ": " + (textVal == null ? "" : textVal));
                        }
                    }
                } else {
                    // Unknown event — print the raw map so a developer
                    // debugging the wire shape can copy it at a glance.
                    System.out.println("event    " + event + " " + update);
                }
            }
        });
        return 0;
    }
}
