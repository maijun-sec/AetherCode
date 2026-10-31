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
 * Phase 2.3 / T-2-18 (spec.md §4.2): the
 * {@code ac session restore <id>} subcommand. Restores a
 * session from Trash back to the active list. Idempotent:
 * restoring a session that's already active is a no-op (the
 * RPC returns ok=true with a note).
 */
@Command(
        name = "restore",
        description = "Restore a session from Trash (T-2-18).")
public class SessionRestoreCommand implements Callable<Integer> {

    @Parameters(arity = "1", description = "Session id (e.g. c-1234).")
    String id;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> r = SupervisorRpc.call("session/restore", Map.of("id", id));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            System.out.println("restored " + id);
            return 0;
        }
        System.err.println("session.restore failed: " + r.getOrDefault("error", r));
        return 1;
    }
}
