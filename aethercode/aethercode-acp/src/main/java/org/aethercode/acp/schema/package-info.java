/**
 * ACP wire-format records.
 *
 * <p>Mirrors the Python {@code acp.schema} package. The
 * Java port models the union types (config options, MCP
 * servers, embedded resources, ...) as sealed
 * {@code interface} hierarchies so call sites can switch
 * on the concrete variant.</p>
 */
package org.aethercode.acp.schema;
