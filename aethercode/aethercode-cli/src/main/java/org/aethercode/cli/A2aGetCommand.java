package org.aethercode.cli;

import org.aethercode.a2a.schema.Part;
import org.aethercode.a2a.schema.Task;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code aethercode a2a <host> get <taskId>} — look up a task by id and
 * print its current state, history, and artifacts; maps to the
 * {@code tasks/get} JSON-RPC method.
 */
@Command(
        name = "get",
        mixinStandardHelpOptions = true,
        description = "Look up a task by id and print its current state.")
public class A2aGetCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "HOST",
            description = "Agent host (host:port or full URL).")
    String host;

    @Parameters(arity = "1", paramLabel = "TASK_ID",
            description = "Task id (from a previous send / stream).")
    String taskId;

    @Override
    public Integer call() throws Exception {
        A2aCommand.A2aCommandHelper helper =
                new A2aCommand.A2aCommandHelper(host, "http", 5);
        Task task = helper.newClient().getTask(taskId);
        System.out.println("task:    " + task.id());
        System.out.println("state:   " + task.status().state());
        if (task.status().message() != null) {
            System.out.println("message: " + task.status().message());
        }
        if (!task.artifacts().isEmpty()) {
            System.out.println("artifacts:");
            for (var a : task.artifacts()) {
                System.out.println("  " + a.name());
                for (var p : a.parts()) {
                    if (p instanceof Part.TextPart tp) {
                        System.out.println("    " + tp.text());
                    } else {
                        System.out.println("    [" + p.kind() + "]");
                    }
                }
            }
        }
        return 0;
    }
}
