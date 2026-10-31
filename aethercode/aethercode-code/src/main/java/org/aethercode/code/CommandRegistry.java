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

package org.aethercode.code;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command registry for the TUI's slash commands.
 *
 * <p>Java-native port of the Python {@code deepagents_code.command_registry}
 * module. The Java port exposes a small registry abstraction; the TUI
 * host populates it at startup with the set of available commands.</p>
 */
public final class CommandRegistry {
    private CommandRegistry() {}

    /** Bypass tier for command events that should skip the queue. */
    public enum BypassTier {
        QUEUED, INTERRUPT, FORCE_CLEAR;
    }

    /** A registered slash command. */
    public record Command(
            String name,
            String description,
            List<String> aliases,
            BypassTier bypass,
            boolean requiresTrust) {
        public Command {
            name = name == null ? "" : name;
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            bypass = bypass == null ? BypassTier.QUEUED : bypass;
        }
    }

    private final java.util.LinkedHashMap<String, Command> commands = new java.util.LinkedHashMap<>();

    /** Register a command. */
    public void register(Command command) {
        if (command == null || command.name().isEmpty()) return;
        commands.put(command.name(), command);
    }

    /** Look up a command by name (or alias). */
    public Command lookup(String name) {
        if (name == null) return null;
        Command direct = commands.get(name);
        if (direct != null) return direct;
        for (Command c : commands.values()) {
            if (c.aliases().contains(name)) return c;
        }
        return null;
    }

    /** All registered commands. */
    public Map<String, Command> all() {
        return Map.copyOf(commands);
    }

    /** All registered command names. */
    public Set<String> names() {
        return Set.copyOf(commands.keySet());
    }
}
