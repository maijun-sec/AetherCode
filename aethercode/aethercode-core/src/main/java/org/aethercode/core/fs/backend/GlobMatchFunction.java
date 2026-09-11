package org.aethercode.core.fs.backend;

import java.util.function.Predicate;

/**
 * Functional interface mirroring deepagents'
 * <code>compile_grep_include_glob</code> / <code>compile_recursive_glob</code>.
 *
 * <p>Both Python helpers return a predicate accepting a search-root-relative
 * POSIX path; their Java port wraps {@link GlobMatcher} as a
 * {@link Predicate}.</p>
 */
@FunctionalInterface
public interface GlobMatchFunction extends Predicate<String> {

    /** Apply this matcher to a search-root-relative POSIX path. */
    @Override
    boolean test(String relativePath);
}
