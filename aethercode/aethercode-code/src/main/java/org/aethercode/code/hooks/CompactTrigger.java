/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.code.hooks;

/**
 * Source of a {@link HookEvent#PRE_COMPACT} hook invocation.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.CompactTrigger} {@code StrEnum}.</p>
 */
public enum CompactTrigger {
    MANUAL,
    AUTO
}
