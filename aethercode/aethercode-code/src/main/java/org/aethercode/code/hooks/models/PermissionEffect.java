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

package org.aethercode.code.hooks.models;

/**
 * Normalized permission result from hook processing.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.PermissionEffect} record.</p>
 */
public record PermissionEffect(String behavior, String reason, boolean interrupt) {
    public static final String ALLOW = "allow";
    public static final String DENY = "deny";
    public static final String ASK = "ask";
    public static final String NONE = "none";

    public static PermissionEffect allow() {
        return new PermissionEffect(ALLOW, null, false);
    }

    public static PermissionEffect deny(String reason) {
        return new PermissionEffect(DENY, reason, false);
    }

    public static PermissionEffect ask(String reason) {
        return new PermissionEffect(ASK, reason, false);
    }

    public static PermissionEffect none() {
        return new PermissionEffect(NONE, null, false);
    }
}
