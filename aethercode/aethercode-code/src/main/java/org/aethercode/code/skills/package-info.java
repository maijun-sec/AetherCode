/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
/**
 * Skill discovery, loading, merging, trust, and invocation helpers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.skills} package. Skill discovery walks the
 * built-in, user, project, agent-specific, Claude-style, and
 * extra-allowed directories; merging resolves name collisions with
 * last-one-wins; trust gates skill-content loading against a list of
 * approved directories.</p>
 */
package org.aethercode.code.skills;
