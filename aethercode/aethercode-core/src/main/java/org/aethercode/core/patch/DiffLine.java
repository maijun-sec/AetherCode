package org.aethercode.core.patch;

import java.util.Objects;

/**
 * a single line in a unified diff hunk. {@code kind} is one of
 * {@code ' '}, {@code '+'}, {@code '-'} for context, addition, removal.
 * Old/new line numbers may be 0 (e.g. for an addition that has no old side).
 */
public record DiffLine(char kind, String content, int oldLineNo, int newLineNo) {
    public DiffLine {
        if (kind != ' ' && kind != '+' && kind != '-') {
            throw new IllegalArgumentException("kind must be ' ', '+' or '-', got '" + kind + "'");
        }
        content = content == null ? "" : content;
    }
    public boolean isContext() { return kind == ' '; }
    public boolean isAddition() { return kind == '+'; }
    public boolean isRemoval() { return kind == '-'; }

    /** the change as a single string like " +hello". Used for re-serialization. */
    public String asRaw() {
        return kind + content;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof DiffLine dl)) return false;
        return kind == dl.kind
                && oldLineNo == dl.oldLineNo
                && newLineNo == dl.newLineNo
                && Objects.equals(content, dl.content);
    }
}
