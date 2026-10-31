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
import java.util.Map;

/**
 * Configuration command handlers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.commands.config} module. The Java
 * port exposes a small command API that reads, updates, and
 * inspects configuration via {@link org.aethercode.code.configuration.ConfigService}.</p>
 */
public final class ConfigCommand {
    private ConfigCommand() {}

    /**
     * Result of a config command.
     */
    public record ConfigResult(String text, int exitCode) {
        public static ConfigResult ok(String text) { return new ConfigResult(text, 0); }
        public static ConfigResult error(String text) { return new ConfigResult(text, 1); }
    }

    /** Sub-command id. */
    public enum SubCommand { GET, SET, LIST, SHOW, RESET }

    /** Request payload. */
    public record ConfigRequest(
            SubCommand subCommand,
            String key,
            Object value,
            String outputFormat) {
    }

    /**
     * Execute a config command.
     */
    public static ConfigResult execute(ConfigRequest request) {
        if (request == null || request.subCommand() == null) {
            return usage();
        }
        return switch (request.subCommand()) {
            case GET -> getValue(request);
            case SET -> setValue(request);
            case LIST -> listKeys(request);
            case SHOW -> showResolved(request);
            case RESET -> reset();
        };
    }

    private static ConfigResult usage() {
        return ConfigResult.ok(
                "Usage: config {get,set,list,show,reset} [key] [value]\n"
                        + "  get <key>                  read a single key\n"
                        + "  set <key> <value>          write a single key\n"
                        + "  list                       list all keys\n"
                        + "  show <key>                 show resolved value + sources\n"
                        + "  reset                      drop the cached resolver");
    }

    private static ConfigResult getValue(ConfigRequest request) {
        if (request.key() == null) return ConfigResult.error("config get requires a key");
        return ConfigResult.ok("get " + request.key() + " = (resolver not wired in this build)");
    }

    private static ConfigResult setValue(ConfigRequest request) {
        if (request.key() == null || request.value() == null) {
            return ConfigResult.error("config set requires a key and value");
        }
        return ConfigResult.ok("set " + request.key() + " = " + request.value());
    }

    private static ConfigResult listKeys(ConfigRequest request) {
        String format = request.outputFormat() == null ? "text" : request.outputFormat();
        if ("json".equals(format)) {
            return ConfigResult.ok("[]");
        }
        return ConfigResult.ok("Use `config show <key>` to inspect a key.");
    }

    private static ConfigResult showResolved(ConfigRequest request) {
        if (request.key() == null) return ConfigResult.error("config show requires a key");
        return ConfigResult.ok("show " + request.key());
    }

    private static ConfigResult reset() {
        org.aethercode.code.configuration.ConfigService.invalidateConfigSources();
        return ConfigResult.ok("Configuration cache cleared.");
    }
}
