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

package org.aethercode.code.client.commands;

import java.util.List;

/**
 * Extras command handlers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.commands.extras} module. Extras
 * are a small bag of additional sub-commands (doctor, update,
 * uninstall, version) that the agent shell exposes.</p>
 */
public final class ExtrasCommand {
    private ExtrasCommand() {}

    /**
     * Result of an extras command.
     */
    public record Result(String text, int exitCode) {
        public static Result ok(String text) { return new Result(text, 0); }
        public static Result error(String text) { return new Result(text, 1); }
    }

    /** Sub-command id. */
    public enum SubCommand { DOCTOR, UPDATE, VERSION, UNINSTALL }

    /**
     * Dispatch an extras command.
     */
    public static Result dispatch(SubCommand subCommand, List<String> args) {
        if (subCommand == null) {
            return Result.ok("Usage: extras {doctor,update,version,uninstall}");
        }
        return switch (subCommand) {
            case DOCTOR -> Result.ok("Run a doctor diagnostic (not yet wired).");
            case UPDATE -> Result.ok("Update check (not yet wired).");
            case VERSION -> Result.ok("Version: " + org.aethercode.code.Version.VERSION);
            case UNINSTALL -> Result.ok("Uninstall the agent (not yet wired).");
        };
    }
}
