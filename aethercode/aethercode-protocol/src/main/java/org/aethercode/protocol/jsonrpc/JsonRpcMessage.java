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
 * a sealed marker for the three JSON-RPC 2.0 message shapes
 * (Request, Response, Notification). A request expects a response;
 * a notification does not. The wire envelope is:
 *
 * <pre>{@code
 *   request      → { "jsonrpc": "2.0", "id": N, "method": "X", "params": {...} }
 *   response     → { "jsonrpc": "2.0", "id": N, "result": ... }
 *   error        → { "jsonrpc": "2.0", "id": N, "error": { "code": -N, "message": "..." } }
 *   notification → { "jsonrpc": "2.0", "method": "X", "params": {...} }   // no id
 * }</pre>
 *
 * <p>Pre-2.0 messages (no "jsonrpc" field) are NOT supported. Callers
 * MUST always send a 2.0 envelope; we treat missing fields as
 * malformed and reply with {@link JsonRpcError#PARSE_ERROR}.
 */
public sealed interface JsonRpcMessage
        permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification {

    /** Always "2.0" per the spec. */
    String VERSION = "2.0";
}
