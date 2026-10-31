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

package org.aethercode.permission.grants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * T-202 / design.md §3.1.1: the on-disk envelope for a
 * {@code grants.json} file.
 *
 * <pre>
 * type GrantsFile = {
 *   schemaVersion: 1;
 *   grants: Grant[];
 * };
 * </pre>
 *
 * <p>Stored as pretty-printed JSON with a single trailing newline so
 *  diffs in version control stay readable. The list is preserved in
 *  append order (oldest first) so the audit trail reads
 *  chronologically without a sort step at read time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"schemaVersion", "grants"})
public record GrantsFile(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("grants") List<Grant> grants
) {

    /** Current on-disk schema version. Bump when an incompatible
     *  field shape lands. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Wire name of the file (constant so callers don't drift). */
    public static final String FILE_NAME = "grants.json";

    @JsonCreator
    public GrantsFile {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported grants.json schemaVersion: " + schemaVersion
                            + " (expected " + CURRENT_SCHEMA_VERSION + ")");
        }
        Objects.requireNonNull(grants, "grants");
        // Defensive copy + null-tolerance: Jackson hands us whatever
        // list it constructed, and a caller passing nulls inside the
        // list would otherwise NPE deep inside Grant's compact ctor
        // with a confusing message.
        List<Grant> copy = new ArrayList<>(grants.size());
        for (Grant g : grants) {
            if (g != null) copy.add(g);
        }
        grants = Collections.unmodifiableList(copy);
    }

    /** Empty file (fresh install — no grants yet). */
    public static GrantsFile empty() {
        return new GrantsFile(CURRENT_SCHEMA_VERSION, List.of());
    }

    /** Append a single grant, returning a new envelope. Pure — does
     *  not touch the file system. The append-and-rotate contract
     *  lives in {@link GrantsStorage}. */
    public GrantsFile append(Grant g) {
        Objects.requireNonNull(g, "grant");
        List<Grant> next = new ArrayList<>(grants.size() + 1);
        next.addAll(grants);
        next.add(g);
        return new GrantsFile(CURRENT_SCHEMA_VERSION, next);
    }
}
