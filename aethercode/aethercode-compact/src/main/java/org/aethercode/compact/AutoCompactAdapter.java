package org.aethercode.compact;

import org.aethercode.core.compact.Compactor;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.message.ContentBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * adapter that bridges the core {@link Compactor} interface to
 * {@link AutoCompact}. legacy the two types were inconsistent:
 * {@code Compactor.compact} returns {@code List<Message>} but
 * {@code AutoCompact.compact} returns its own {@code Result} record.
 * This adapter translates: when {@code AutoCompact} reports a successful
 * compaction, the resulting summary is wrapped in a single USER message
 * and returned as the new transcript. Otherwise {@code null} is returned
 * (the engine treats null as "no change" and leaves the transcript intact).
 *
 * <p>The single-message replacement is the convention Claude Code uses
 * for its own compaction — a single USER message ("Here is a summary
 * of our conversation so far: ...") replaces the whole prefix, and
 * recent turns are preserved verbatim by the engine.
 */
public final class AutoCompactAdapter implements Compactor {

    private static final Logger LOG = LoggerFactory.getLogger(AutoCompactAdapter.class);

    private final AutoCompact delegate;

    public AutoCompactAdapter(AutoCompact delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean shouldCompact(List<Message> messages) {
        return delegate.shouldCompact(messages);
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        AutoCompact.Result r = delegate.compact(messages);
        if (r == null || !r.wasCompacted() || r.summary() == null || r.summary().isBlank()) {
            // No compaction happened (model skipped or failed). Return null
            // so the engine leaves the transcript intact.
            return null;
        }
        String wrapped = "Here is a summary of our conversation so far:\n\n" + r.summary()
                + "\n\n(The previous turns were compacted into this summary. "
                + "Recent turns follow below.)";
        Message m = new Message(
                null, Role.USER,
                List.of(new ContentBlock.TextBlock(wrapped)),
                null, Map.of());
        // also tag the message with a content attribute so the engine /
        // TUI can flag it as a compaction result. The Message.content()
        // shape doesn't have a dedicated marker; we use Role.USER with a
        // recognizable prefix instead. A future "compaction" role / metadata
        // field is R19.
        LOG.debug("compaction produced {} chars of summary", r.summary().length());
        return List.of(m);
    }
}
