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

package org.aethercode.examples.llmwiki;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared data models for LLM wiki workflows.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/models.py}. The Java
 * port uses records instead of Python {@code @dataclass} and
 * functional interfaces instead of {@code Callable} references.</p>
 */
public final class LlmWikiModels {
    private LlmWikiModels() {}

    /** The mode the wiki runner is in. */
    public enum Mode { INIT, INGEST, QUERY, LINT }

    /** Parsed runner configuration. */
    public record RunnerConfig(
            Mode mode,
            String topic,
            String repo,
            Optional<String> owner,
            Path topicDir,
            List<Path> sources,
            Optional<String> note,
            Optional<String> question,
            Optional<String> model,
            Optional<String> description,
            boolean review) {

        public RunnerConfig {
            sources = List.copyOf(sources);
        }
    }

    /** Injectable dependencies for tests. */
    public record CliDeps(
            Function<List<String>, ProcessResult> runLangSmithCli,
            QuadFunction<Path, String, String, String, String> runAgentMode,
            QuadFunction<Path, String, String, String, String> runAgentReviewMode,
            Function<String, String> askUser,
            Supplier<TempDir> tempDirFactory) {}

    /** Output from a runner invocation. */
    public record RunResult(Optional<String> answer, Optional<String> hubUrl) {}

    /** Stand-in for {@code subprocess.CompletedProcess[str]}. */
    public record ProcessResult(int returnCode, String stdout, String stderr) {}

    /** Stand-in for {@code tempfile.TemporaryDirectory[str]}. */
    public interface TempDir extends AutoCloseable {
        String path();
        @Override void close();
    }

    /** Quad-arity function used in {@link CliDeps}. */
    @FunctionalInterface
    public interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }

    /** Stand-in for {@code Callable[[], R]} factories. */
    @FunctionalInterface
    public interface Supplier<R> {
        R get();
    }
}
