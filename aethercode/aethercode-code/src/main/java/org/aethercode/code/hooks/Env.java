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

package org.aethercode.code.hooks;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sanitized subprocess environments for Hooks v2 command handlers.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.env} module. The sanitizer keeps the
 * process environment but strips values whose names look like
 * secrets.</p>
 */
public final class Env {

    /**
     * Shared bound for legacy hook subprocesses and the migration
     * adapter's nested {@code subprocess.run}. Keep the legacy
     * dispatcher and the Hooks v2 migration aligned.
     */
    public static final double HOOK_SUBPROCESS_TIMEOUT = 5.0;

    private static final Set<String> SECRET_SUFFIXES = Set.of(
            "API_KEY", "APIKEY", "KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL");

    private Env() {}

    /**
     * Build an inherited environment safe to pass to hook subprocesses.
     *
     * <p>Strips values whose names look like secrets. Hooks are
     * user-authored trusted code, but secret values should not be
     * ambiently available.</p>
     *
     * @param source environment to sanitize; defaults to the process env
     * @return a new environment mapping suitable for
     *         {@code ProcessBuilder.environment()}
     */
    public static Map<String, String> sanitizeHookEnviron(Map<String, String> source) {
        Map<String, String> env = source == null ? System.getenv() : source;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!isSecretEnv(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Return whether the given environment variable name looks like a
     * secret. Matches the upstream {@code _is_secret_env} helper.
     */
    public static boolean isSecretEnv(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        for (String suffix : SECRET_SUFFIXES) {
            if (upper.endsWith(suffix)) {
                // Treat any "PASSWORD" or "CREDENTIAL" as a secret, but
                // require a separator or word boundary for shorter
                // suffixes to avoid matching KEY, KEYRING, etc.
                int suffixStart = upper.length() - suffix.length();
                if (suffixStart == 0) return true;
                char prev = upper.charAt(suffixStart - 1);
                if (prev == '_' || Character.isDigit(prev)) return true;
                if ("KEY".equals(suffix) || "TOKEN".equals(suffix) || "SECRET".equals(suffix)) {
                    if (prev == '_') return true;
                }
            }
        }
        return false;
    }
}
