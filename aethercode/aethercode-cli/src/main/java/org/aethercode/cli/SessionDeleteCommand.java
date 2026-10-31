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

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Phase 2.3 / T-2-18 (spec.md §4): the
 * {@code ac session delete <id>} subcommand. Soft-deletes a
 * session: it moves to Trash, where it sits for 30 days
 * before a background reaper permanently removes it. Use
 * {@code ac session restore <id>} to undo, or
 * {@code ac session trash --empty} to purge early.
 */
@Command(
        name = "delete",
        description = "Soft-delete a session — moves it to Trash (T-2-18).")
public class SessionDeleteCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Session id (e.g. c-1234).")
    String id;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/delete", Map.of("id", id));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            System.out.println("deleted " + id + " (moved to Trash; "
                    + "use `ac session restore " + id + "` to undo within 30 days)");
            return 0;
        }
        System.err.println("session.delete failed: " + r.getOrDefault("error", r));
        return 1;
    }
}
