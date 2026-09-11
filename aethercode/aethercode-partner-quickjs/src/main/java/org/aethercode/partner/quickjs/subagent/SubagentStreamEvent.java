package org.aethercode.partner.quickjs.subagent;

/**
 * Discriminated union of the three subagent lifecycle events. 1:1
 * port of the Python
 * <code>SubagentStreamEvent = SubagentStartEvent | SubagentCompleteEvent | SubagentErrorEvent</code>.
 *
 * <p>Consumers should tolerate unrecognized {@code phase} values
 * rather than assume the union is closed, so a future phase can be
 * added without breaking them.</p>
 */
public sealed interface SubagentStreamEvent
        permits SubagentStartEvent, SubagentCompleteEvent, SubagentErrorEvent {

    String type();

    String phase();

    String id();
}
