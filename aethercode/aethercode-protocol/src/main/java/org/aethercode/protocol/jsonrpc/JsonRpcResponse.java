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
 * a JSON-RPC 2.0 response. Either {@code result} OR
 * {@code error} is set, never both. The id echoes the request id so
 * the caller can route it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") Object id,
        @JsonProperty("result") Object result,
        @JsonProperty("error") JsonRpcError error
) implements JsonRpcMessage {
    public JsonRpcResponse {
        if (jsonrpc == null) jsonrpc = JsonRpcMessage.VERSION;
        if (id == null) throw new IllegalArgumentException("response id is required");
        if ((result == null) == (error == null))
            throw new IllegalArgumentException(
                    "response must have exactly one of {result, error}");
    }

    public static JsonRpcResponse ok(Object id, Object result) {
        return new JsonRpcResponse(JsonRpcMessage.VERSION, id, result, null);
    }

    public static JsonRpcResponse err(Object id, JsonRpcError error) {
        return new JsonRpcResponse(JsonRpcMessage.VERSION, id, null, error);
    }
}
