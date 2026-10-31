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

/**
 * a checked-style wrapper for JSON-RPC protocol errors. The
 * codec and dispatcher throw this when the wire format is bad,
 * a method is missing, a parameter is malformed, etc. The caller
 * turns the embedded {@link JsonRpcError} into a JSON-RPC error
 * response and sends it back to the peer.
 */
public class JsonRpcProtocolException extends RuntimeException {

    private final JsonRpcError error;

    public JsonRpcProtocolException(String message, JsonRpcError error) {
        super(message);
        this.error = error;
    }

    public JsonRpcProtocolException(String message, Throwable cause, JsonRpcError error) {
        super(message, cause);
        this.error = error;
    }

    public JsonRpcError error() { return error; }
}
