package org.aethercode.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Permission matrix lookup semantics. The order in
 * {@link PermissionMatrix#entries} matters because first-match-wins.
 */
class PermissionMatrixLookupTest {

    @Test
    void fileWrite_toJavaSource_isAsk() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("file_write", "src/main/java/Foo.java", OpKind.CREATE);
        assertThat(a).isEqualTo(Action.ASK);
    }

    @Test
    void fileWrite_toTestPath_createIsAllow_modifyIsAllow_deleteIsAsk() {
        Action create = DefaultMatrixHolder.MATRIX.lookup("file_write", "src/test/java/FooTest.java", OpKind.CREATE);
        Action modify = DefaultMatrixHolder.MATRIX.lookup("file_write", "src/test/java/FooTest.java", OpKind.MODIFY);
        Action delete = DefaultMatrixHolder.MATRIX.lookup("file_write", "src/test/java/FooTest.java", OpKind.DELETE);
        assertThat(create).isEqualTo(Action.ALLOW);
        assertThat(modify).isEqualTo(Action.ALLOW);
        assertThat(delete).isEqualTo(Action.ASK);
    }

    @Test
    void fileWrite_toAethercodeConfigPath_isDeny() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("file_write", ".aethercode/config.json", OpKind.MODIFY);
        assertThat(a).isEqualTo(Action.DENY);
    }

    @Test
    void fileEdit_toJavaSource_isAsk() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("file_edit", "src/main/java/Foo.java", OpKind.MODIFY);
        assertThat(a).isEqualTo(Action.ASK);
    }

    @Test
    void fileRead_alwaysAllow() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("file_read", "anywhere/at/all", OpKind.READ);
        assertThat(a).isEqualTo(Action.ALLOW);
    }

    @Test
    void bash_deleteIsAlwaysDeny() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("bash", null, OpKind.DELETE);
        assertThat(a).isEqualTo(Action.DENY);
    }

    @Test
    void bash_execIsAsk() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("bash", null, OpKind.EXEC);
        assertThat(a).isEqualTo(Action.ASK);
    }

    @Test
    void bash_readLikeCommand_isAllow() {
        // bash 'ls' / 'cat' is READ in spirit; matches READ -> ALLOW.
        Action a = DefaultMatrixHolder.MATRIX.lookup("bash", null, OpKind.READ);
        assertThat(a).isEqualTo(Action.ALLOW);
    }

    @Test
    void unknownTool_failsClosedToAsk() {
        Action a = DefaultMatrixHolder.MATRIX.lookup("totally_made_up_tool", null, OpKind.EXEC);
        assertThat(a).isEqualTo(Action.ASK);
    }

    @Test
    void unknownOpKind_fallsBackToWildcard() {
        // Pick a tool that has a "*" entry but no specific entry for, say, LIST.
        Action a = DefaultMatrixHolder.MATRIX.lookup("bash", null, OpKind.LIST);
        assertThat(a).isEqualTo(Action.ASK); // bash * -> ASK
    }

    @Test
    void withOverride_doesNotMutateOriginal() {
        PermissionMatrix m1 = DefaultMatrixHolder.MATRIX;
        // Override with a path-glob that does NOT collide with a more specific default.
        // Using "src/main/java/**" so it matches the test path but is more specific than "*"
        // AND more specific than the existing "src/main/**" default? No — that would lose.
        // The semantic: override path-glob is inserted at the front, so the override wins
        // first-match when its glob is at least as specific. Pick a path that ONLY the
        // override covers: a docs path that defaults to ASK.
        PermissionMatrix m2 = m1.withOverride("file_write", "docs/auto/**", OpKind.CREATE, Action.ALLOW);
        assertThat(m1.lookup("file_write", "docs/auto/x.md", OpKind.CREATE))
                .as("original unchanged").isEqualTo(Action.ASK);
        assertThat(m2.lookup("file_write", "docs/auto/x.md", OpKind.CREATE))
                .as("override applied").isEqualTo(Action.ALLOW);
    }

    @Test
    void withOverride_specificPath_winsOverCatchAll() {
        // Override on a path that the default only catches via "*" -> ALLOW beats ASK.
        PermissionMatrix m1 = DefaultMatrixHolder.MATRIX;
        PermissionMatrix m2 = m1.withOverride("file_write", "scripts/build/**", OpKind.CREATE, Action.ALLOW);
        assertThat(m1.lookup("file_write", "scripts/build/seed.sh", OpKind.CREATE))
                .as("default catch-all is ASK").isEqualTo(Action.ASK);
        assertThat(m2.lookup("file_write", "scripts/build/seed.sh", OpKind.CREATE))
                .as("specific override beats catch-all").isEqualTo(Action.ALLOW);
    }

    @Test
    void matchesPath_starMatchesEverything() {
        assertThat(PermissionMatrix.matchesPath("*", "anywhere")).isTrue();
        assertThat(PermissionMatrix.matchesPath("*", null)).isTrue();
        assertThat(PermissionMatrix.matchesPath("", "x")).isTrue();
    }

    @Test
    void matchesPath_globHonoursWildcards() {
        // glob "*.java" should match a file name ending with .java
        assertThat(PermissionMatrix.matchesPath("*.java", "Foo.java")).isTrue();
        // And NOT match a path without .java suffix
        assertThat(PermissionMatrix.matchesPath("*.java", "Foo.kt")).isFalse();
    }

    /** Test holder: cache the default matrix once. */
    private static final class DefaultMatrixHolder {
        static final PermissionMatrix MATRIX = org.aethercode.config.defaults.DefaultMatrix.build();
    }
}
