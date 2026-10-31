/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
