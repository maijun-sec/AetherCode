package org.aethercode.cli;

import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.a2a.schema.Task;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code aethercode a2a <host> send "<text>"} — send a user message via
 * {@code message/send} and print the resulting task.
 *
 * <p>The default text shape covers the common case; multi-part / file parts
 * are not yet exposed on the CLI (the A2A client API supports them — a flag
 * can be added later if needed).</p>
 */
@Command(
        name = "send",
        mixinStandardHelpOptions = true,
        description = "Send a user message and print the resulting task.")
public class A2aSendCommand implements Callable<Integer> {

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
        Task task = helper.newClient().sendMessage(user);
        // Print id + state first, then list artifacts in a human-readable form.
        System.out.println("task:    " + task.id());
        System.out.println("state:   " + task.status().state());
        if (!task.artifacts().isEmpty()) {
            for (var a : task.artifacts()) {
                System.out.println("artifact " + a.name() + ":");
                for (var p : a.parts()) {
                    if (p instanceof Part.TextPart tp) {
                        System.out.println("  " + tp.text());
                    } else {
                        System.out.println("  [" + p.kind() + "]");
                    }
                }
            }
        }
        return 0;
    }
}
