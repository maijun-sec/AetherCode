// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

/**
 * Public types for the AetherCode context compression pipeline.
 *
 * Mirrors `design.md §2.1` and `spec.md §2`. All types are
 * implementation-agnostic: they describe shape only, not storage.
 */
/** Read-class tool names that Layer 1 inspects for body-clearing. */
export const READ_CLASS_TOOL_NAMES = new Set([
    "read_file",
    "bash",
    "grep_files",
    "glob_files",
    "web_search",
    "web_fetch",
    "edit_file",
    "write_file",
]);
/** Placeholder text used when a read-class tool result is cleared. */
export const CLEARED_PLACEHOLDER = "[Old tool result content cleared]";
