package org.aethercode.code.hooks;

import java.util.Collections;
import java.util.Map;

/**
 * Origin of the matcher groups contributed by one hooks document.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.loading.HooksSource} abstract base. A
 * source declares a location and resolves variable references that may
 * appear in its contributed matcher groups.</p>
 */
public abstract sealed class HooksSource
        permits HooksSource.FileHooksSource, HooksSource.PluginHooksSource {

    private final String location;

    protected HooksSource(String location) {
        this.location = location;
    }

    public String location() {
        return location;
    }

    /**
     * Resolve the variable references this source defines.
     *
     * @param value        one {@code argv} element, or a shell-form {@code command}
     * @param shellSyntax  whether a shell interprets {@code value}
     * @return the resolved argument or command
     */
    public abstract String resolveVariables(String value, boolean shellSyntax);

    /** A project or user hooks file, which defines no variables. */
    public static final class FileHooksSource extends HooksSource {
        public FileHooksSource(String location) {
            super(location == null ? "" : location);
        }

        @Override
        public String resolveVariables(String value, boolean shellSyntax) {
            return value;
        }
    }

    /**
     * Origin and environment for groups one enabled plugin contributed.
     */
    public static final class PluginHooksSource extends HooksSource {
        private final String pluginId;
        private final Map<String, String> env;

        public PluginHooksSource(String pluginId, Map<String, String> env) {
            super(pluginId == null ? "" : pluginId);
            this.pluginId = pluginId == null ? "" : pluginId;
            this.env = env == null ? Map.of() : Collections.unmodifiableMap(Map.copyOf(env));
        }

        public String pluginId() {
            return pluginId;
        }

        public Map<String, String> env() {
            return env;
        }

        @Override
        public String resolveVariables(String value, boolean shellSyntax) {
            if (value == null) return null;
            String result = value;
            for (Map.Entry<String, String> e : env.entrySet()) {
                String token = "${" + e.getKey() + "}";
                if (shellSyntax) {
                    result = result.replace(token, "%" + e.getKey() + "%");
                } else {
                    result = result.replace(token, e.getValue());
                }
            }
            return result;
        }
    }
}
