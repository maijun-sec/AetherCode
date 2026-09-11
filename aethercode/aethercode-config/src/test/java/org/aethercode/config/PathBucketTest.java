package org.aethercode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathBucketTest {

    @Test
    void srcMainPaths() {
        assertThat(PathBucket.classify("src/main/java/Foo.java", null)).isEqualTo(PathBucket.SRC_MAIN);
        assertThat(PathBucket.classify("lib/util.js", null)).isEqualTo(PathBucket.SRC_MAIN);
        assertThat(PathBucket.classify("app/server/main.go", null)).isEqualTo(PathBucket.SRC_MAIN);
    }

    @Test
    void srcTestPaths() {
        assertThat(PathBucket.classify("src/test/java/FooTest.java", null)).isEqualTo(PathBucket.SRC_TEST);
        assertThat(PathBucket.classify("test/parser_test.py", null)).isEqualTo(PathBucket.SRC_TEST);
        assertThat(PathBucket.classify("tests/integration/api.test.ts", null)).isEqualTo(PathBucket.SRC_TEST);
        assertThat(PathBucket.classify("__tests__/foo.test.js", null)).isEqualTo(PathBucket.SRC_TEST);
        assertThat(PathBucket.classify("spec/foo_spec.rb", null)).isEqualTo(PathBucket.SRC_TEST);
    }

    @Test
    void buildPaths() {
        assertThat(PathBucket.classify("target/classes/Foo.class", null)).isEqualTo(PathBucket.BUILD);
        assertThat(PathBucket.classify("build/output.json", null)).isEqualTo(PathBucket.BUILD);
        assertThat(PathBucket.classify("dist/bundle.js", null)).isEqualTo(PathBucket.BUILD);
        assertThat(PathBucket.classify("out/production/App.class", null)).isEqualTo(PathBucket.BUILD);
    }

    @Test
    void cachePaths() {
        assertThat(PathBucket.classify("node_modules/lodash/index.js", null)).isEqualTo(PathBucket.CACHE);
        assertThat(PathBucket.classify(".gradle/build/output", null)).isEqualTo(PathBucket.CACHE);
        assertThat(PathBucket.classify(".idea/workspace.xml", null)).isEqualTo(PathBucket.CACHE);
        assertThat(PathBucket.classify(".vscode/settings.json", null)).isEqualTo(PathBucket.CACHE);
        assertThat(PathBucket.classify(".pytest_cache/v/cache", null)).isEqualTo(PathBucket.CACHE);
    }

    @Test
    void aethercodeConfigPaths() {
        assertThat(PathBucket.classify(".aethercode/config.json", null)).isEqualTo(PathBucket.CONFIG);
        assertThat(PathBucket.classify(".aethercode/sessions/foo.json", null)).isEqualTo(PathBucket.CONFIG);
        assertThat(PathBucket.classify("nested/.aethercode/x.json", null)).isEqualTo(PathBucket.CONFIG);
    }

    @Test
    void docsPaths() {
        assertThat(PathBucket.classify("docs/RFC.md", null)).isEqualTo(PathBucket.DOCS);
        assertThat(PathBucket.classify("README.md", null)).isEqualTo(PathBucket.DOCS);
        assertThat(PathBucket.classify("CHANGELOG.rst", null)).isEqualTo(PathBucket.DOCS);
    }

    @Test
    void externalPaths() {
        assertThat(PathBucket.classify("/etc/passwd", null)).isEqualTo(PathBucket.EXTERNAL);
        assertThat(PathBucket.classify("/usr/local/bin/foo", null)).isEqualTo(PathBucket.EXTERNAL);
    }

    @Test
    void nullOrBlank_returnsOther() {
        assertThat(PathBucket.classify(null, null)).isEqualTo(PathBucket.OTHER);
        assertThat(PathBucket.classify("", null)).isEqualTo(PathBucket.OTHER);
    }

    @Test
    void projectRootRelative_returnsOtherWhenInside() {
        Path root = Path.of("/tmp/proj");
        assertThat(PathBucket.classify("foo/bar.txt", root)).isEqualTo(PathBucket.OTHER);
    }

    @Test
    void projectRootRelative_returnsExternalWhenOutside() {
        Path root = Path.of("/tmp/proj");
        assertThat(PathBucket.classify("../sibling/file.txt", root)).isEqualTo(PathBucket.EXTERNAL);
    }

    @Test
    void windowsPath_backslashesAreHandled() {
        assertThat(PathBucket.classify("src\\main\\java\\Foo.java", null)).isEqualTo(PathBucket.SRC_MAIN);
        assertThat(PathBucket.classify("src\\test\\java\\FooTest.java", null)).isEqualTo(PathBucket.SRC_TEST);
    }
}
