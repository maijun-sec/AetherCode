package org.aethercode.tasks.rpc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Phase 1.2 (T-1-18): task-level handler for the four
 * {@code grants/*} JSON-RPC methods.
 *
 * <p>This class lives in aethercode-tasks (not
 * aethercode-protocol) because aethercode-tasks already
 * depends on aethercode-protocol — adding the reverse
 * dependency would cycle through aethercode-sdk. The pattern
 * matches the existing {@code WorkflowHandlers} interface in
 * the same package: the methods accept raw {@code Object}
 * params and return raw {@code Map<String, Object>} results;
 * a thin protocol-side class wraps the methods into
 * {@code JsonRpcMethodHandler} adapters.
 *
 * <p>The class is constructed with four
 * {@link Function}/{@link Consumer}/{@link BiFunction}
 * callbacks that delegate to the existing
 * {@code PermissionMethods} (in aethercode-protocol) plus a
 * preset writer. The protocol-side adapter injects the
 * callbacks; aethercode-tasks never sees a protocol type.
 *
 * <p>Wire format:
 * <pre>
 *   grants/list    { scope?, category?, sessionId? } -> { grants: [...] }
 *   grants/revoke  { id } -> { ok, id }
 *   grants/clear   { scope, sessionId? } -> { ok, revoked, scope }
 *   grants/setPreset { preset: "permissive"|"cautious"|"strict" }
 *                  -> { ok, preset }
 * </pre>
 */
public final class GrantHandlers {

    private final Path permissionsFile;
    private final Function<Map<String, Object>, Object> listAdapter;
    private final BiFunction<String, String, Integer> clearAdapter;
    private final Consumer<Map<String, Object>> revokeAdapter;
    private final TriConsumer<Path, String, Void> presetAdapter;

    public GrantHandlers(Path permissionsFile,
                         Function<Map<String, Object>, Object> listAdapter,
                         BiFunction<String, String, Integer> clearAdapter,
                         Consumer<Map<String, Object>> revokeAdapter,
                         TriConsumer<Path, String, Void> presetAdapter) {
        this.permissionsFile = permissionsFile;
        this.listAdapter = Objects.requireNonNull(listAdapter, "listAdapter");
        this.clearAdapter = Objects.requireNonNull(clearAdapter, "clearAdapter");
        this.revokeAdapter = Objects.requireNonNull(revokeAdapter, "revokeAdapter");
        this.presetAdapter = Objects.requireNonNull(presetAdapter, "presetAdapter");
    }

    /** {@code grants/list}. Forwards to the existing
     *  {@code permission/list} via the {@code listAdapter};
     *  applies the optional {@code category} filter here. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(Object params) {
        Map<String, Object> p = asMap(params);
        Object rows = listAdapter.apply(p);
        List<Map<String, Object>> list;
        if (rows instanceof List<?> l) {
            list = (List<Map<String, Object>>) l;
        } else {
            list = List.of();
        }
        Object cat = p.get("category");
        Map<String, Object> out = new LinkedHashMap<>();
        if (cat == null) {
            out.put("grants", list);
            return out;
        }
        String want = cat.toString();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> g : list) {
            Object gc = g.get("category");
            if (gc != null && want.equals(gc.toString())) filtered.add(g);
        }
        out.put("grants", filtered);
        return out;
    }

    /** {@code grants/revoke}. Forwards to
     *  {@code permission/revoke} via the {@code revokeAdapter}. */
    public Map<String, Object> revoke(Object params) {
        Map<String, Object> p = asMap(params);
        revokeAdapter.accept(p);
        String id = p.get("id") == null ? "" : p.get("id").toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        return out;
    }

    /** {@code grants/clear}. Forwards to
     *  {@code permission/clear} via the {@code clearAdapter}. */
    public Map<String, Object> clear(Object params) {
        Map<String, Object> p = asMap(params);
        String scope = p.get("scope") == null ? "" : p.get("scope").toString();
        String sessionId = p.get("sessionId") == null ? "" : p.get("sessionId").toString();
        int revoked = clearAdapter.apply(scope, sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("revoked", revoked);
        out.put("scope", scope);
        return out;
    }

    /** {@code grants/setPreset}. Validates the preset name
     *  and writes the preset via the {@code presetAdapter}. */
    public Map<String, Object> setPreset(Object params) {
        Map<String, Object> p = asMap(params);
        Object preset = p.get("preset");
        if (preset == null) {
            throw new IllegalArgumentException("preset is required");
        }
        String name = preset.toString();
        switch (name) {
            case "permissive":
            case "cautious":
            case "strict":
                break;
            default:
                throw new IllegalArgumentException("unknown preset: " + name);
        }
        presetAdapter.accept(permissionsFile, name, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("preset", name);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object params) {
        if (params instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    /** Three-arg consumer; Java 17 doesn't have a stdlib one. */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
