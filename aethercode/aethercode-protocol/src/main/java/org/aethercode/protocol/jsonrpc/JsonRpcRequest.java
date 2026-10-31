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

package org.aethercode.protocol.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * a JSON-RPC 2.0 request. Has an id (the caller expects a
 * response keyed by the same id) and a method name. {@code params}
 * is optional and may be a record, map, or array per the spec; we
 * always send a record/map shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") Object id,
        @JsonProperty("method") String method,
        @JsonProperty("params") Object params
) implements JsonRpcMessage {
    public JsonRpcRequest {
        if (jsonrpc == null) jsonrpc = JsonRpcMessage.VERSION;
        if (id == null) throw new IllegalArgumentException("request id is required");
        if (method == null || method.isBlank())
            throw new IllegalArgumentException("request method is required");
    }

    /** Convenience for sending a parameterless request. */
    public static JsonRpcRequest of(Object id, String method) {
        return new JsonRpcRequest(JsonRpcMessage.VERSION, id, method, null);
    }
}
