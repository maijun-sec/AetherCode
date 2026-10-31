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
 * Cached runtime validators for hook model boundaries.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.adapters} module. The Java port
 * does not need pydantic-style runtime validation; records provide
 * structural validation at construction time. This class exists to
 * keep the public surface (the singleton holders) stable for callers
 * that previously imported those names.</p>
 */
public final class HookAdapters {
    private HookAdapters() {}

    /** Returns the {@link HookDomainEvent} class, used as a tag for routing. */
    public static Class<HookDomainEvent> domainEventAdapter() { return HookDomainEvent.class; }

    /** Returns the {@link HookInvocation} class. */
    public static Class<HookInvocation> invocationAdapter() { return HookInvocation.class; }

    /** Returns the {@link HookDecision} sealed interface. */
    public static Class<HookDecision> decisionAdapter() { return HookDecision.class; }

    /** Returns the {@link WireModels.HookWireOutput} class. */
    public static Class<WireModels.HookWireOutput> wireOutputAdapter() {
        return WireModels.HookWireOutput.class;
    }

    /** Returns the {@link WireModels.HookWireInputMarker} sealed interface. */
    public static Class<WireModels.HookWireInputMarker> wireInputAdapter() {
        return WireModels.HookWireInputMarker.class;
    }

    /** Returns the {@link HooksConfig.Config} class. */
    public static Class<HooksConfig.Config> hooksConfigAdapter() { return HooksConfig.Config.class; }

    /** Returns the {@link HookInvocationRequest.Request} class. */
    public static Class<HookInvocationRequest.Request> invocationRequestAdapter() {
        return HookInvocationRequest.Request.class;
    }

    /** Returns the {@link HookInvocationRequest.Response} class. */
    public static Class<HookInvocationRequest.Response> invocationResponseAdapter() {
        return HookInvocationRequest.Response.class;
    }
}
