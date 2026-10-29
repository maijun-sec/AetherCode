/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
/**
 * Patch-chain delta encoding for the QuickJS REPL heap snapshot.
 * 1:1 port of the Python <code>langchain_quickjs._snapshot</code>
 * module. Exposes the {@link org.aethercode.partner.quickjs.snapshot.SnapshotCodec}
 * entry point, the {@link org.aethercode.partner.quickjs.snapshot.SnapshotRecord}
 * wire type, and the pluggable
 * {@link org.aethercode.partner.quickjs.snapshot.BinaryDiffer} bsdiff
 * interface.
 */
package org.aethercode.partner.quickjs.snapshot;
