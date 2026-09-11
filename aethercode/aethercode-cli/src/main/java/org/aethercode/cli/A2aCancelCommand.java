package org.aethercode.cli;

import org.aethercode.a2a.schema.Task;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code aethercode a2a <host> cancel <taskId>} — transition the task to
 * {@code canceled} and print the resulting state; maps to the
 * {@code tasks/cancel} JSON-RPC method.
 */
@Command(
        name = "cancel",
        mixinStandardHelpOptions = true,
        description = "Cancel a task and print the resulting state.")
public class A2aCancelCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "HOST",
            description = "Agent host (host:port or full URL).")
    String host;

    @Parameters(arity = "1", paramLabel = "TASK_ID",
            description = "Task id to cancel.")
    String taskId;

    @Override
    public Integer call() throws Exception {
        A2aCommand.A2aCommandHelper helper =
                new A2aCommand.A2aCommandHelper(host, "http", 5);
        Task task = helper.newClient().cancelTask(taskId);
        System.out.println("task:    " + task.id());
        System.out.println("state:   " + task.status().state());
        return 0;
    }
}
