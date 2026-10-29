/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.code.hooks;

/**
 * Which side of the wire owns execution of a hook event.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookOwner} {@code StrEnum}.
 * <ul>
 *   <li>{@link #CLIENT} — the client runtime fires and consumes the event.</li>
 *   <li>{@link #SERVER} — the server (LangGraph middleware) emits the event
 *       through the interrupt transport and the client fulfills the
 *       invocation.</li>
 * </ul>
 */
public enum HookOwner {
    CLIENT,
    SERVER
}
