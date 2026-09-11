package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link MemoryScope#AUTO} resolution.
 */
class MemoryScopeTest {

    @Test
    void nonAutoScopesPassThrough() {
        assertEquals(MemoryScope.USER, MemoryScope.USER.resolve(null));
        assertEquals(MemoryScope.PROJECT, MemoryScope.PROJECT.resolve(Path.of("/anywhere")));
        assertEquals(MemoryScope.LOCAL, MemoryScope.LOCAL.resolve(Path.of("/anywhere")));
    }

    @Test
    void autoInsideGitRepo_resolvesToProject(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        assertEquals(MemoryScope.PROJECT, MemoryScope.AUTO.resolve(tmp));
    }

    @Test
    void autoOutsideGitRepo_resolvesToUser(@TempDir Path tmp) {
        // No .git directory anywhere up the tree (using @TempDir which is fresh).
        assertEquals(MemoryScope.USER, MemoryScope.AUTO.resolve(tmp));
    }

    @Test
    void autoInNestedDirInsideGitRepo_resolvesToProject(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Path nested = Files.createDirectories(tmp.resolve("a/b/c"));
        assertEquals(MemoryScope.PROJECT, MemoryScope.AUTO.resolve(nested));
    }
}
