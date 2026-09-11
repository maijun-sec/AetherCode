package org.aethercode.acp.examples;

import org.aethercode.acp.AgentSessionContext;
import org.aethercode.acp.StreamingStateGraph;
import org.aethercode.acp.examples.LocalContext.ExecutableBackend;
import org.aethercode.backends.ExecuteResponse;
import org.aethercode.middleware.Middleware;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;
import org.aethercode.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Middleware for injecting local context into the system
 * prompt.
 *
 * <p>Java-native port of
 * {@code deepagents_acp.examples.local_context.LocalContextMiddleware}.
 * Detects git state, project structure, package managers,
 * runtimes, and directory layout by running a bash script
 * via the backend. Because the script executes inside the
 * backend (local shell or remote sandbox), the same
 * detection logic works regardless of where the agent
 * runs.</p>
 *
 * <p>The Java port adapts the Python
 * {@code beforeAgent}/{@code wrapModelCall} hooks onto the
 * existing {@link Middleware} contract ({@code beforeModel} +
 * {@code wrapModelCall} / {@code awrapModelCall}). Detection
 * runs on the first model call of a session; the local
 * context is appended to the chat model via the system-prompt
 * composition done by the Deep Agent runtime. The middleware
 * exposes the resolved context through the state extension
 * {@value #LOCAL_CONTEXT_KEY} so the host application (or a
 * custom system-prompt composer) can read it.</p>
 *
 * <p>Marked {@code @Example}: kept for parity with the
 * Python {@code examples/local_context.py} but not on the
 * core hot path.</p>
 */
public final class LocalContext {

    private LocalContext() {}

    /** State extension key carrying the resolved local-context markdown. */
    public static final String LOCAL_CONTEXT_KEY = "local_context";

    /** State extension key carrying the cutoff index of the
     *  last summarization event we refreshed for. Mirrors
     *  the Python port's
     *  {@code _local_context_refreshed_at_cutoff}. */
    public static final String REFRESHED_CUTOFF_KEY = "_local_context_refreshed_at_cutoff";

    /** State extension key the summarization middleware writes. */
    public static final String SUMMARIZATION_EVENT_KEY = "_summarization_event";

    private static final Logger LOGGER = Logger.getLogger(LocalContext.class.getName());

    /** Display limit for tool names in the context output. */
    public static final int TOOL_NAME_DISPLAY_LIMIT = 10;

    /**
     * The compiled detection script. Built once on class load
     * by concatenating the section snippets in
     * {@link #buildDetectScript()}. Mirrors the Python
     * module-level {@code DETECT_CONTEXT_SCRIPT}.
     */
    public static final String DETECT_CONTEXT_SCRIPT = buildDetectScript();

    /**
     * Backend contract for executing the detection script.
     * Mirrors the Python port's {@code _ExecutableBackend}
     * protocol.
     */
    public interface ExecutableBackend {
        ExecuteResponse execute(String command);
    }

    /**
     * The local-context middleware. Mirrors
     * {@code LocalContextMiddleware}.
     */
    public static class MiddlewareImpl implements Middleware {
        private final ExecutableBackend backend;

        public MiddlewareImpl(ExecutableBackend backend) {
            this.backend = backend;
        }

        @Override
        public String name() {
            return "local_context";
        }

        public String description() {
            return "Inject local context (git, project, runtimes) into the system prompt.";
        }

        @Override
        public AgentState beforeModel(AgentState state, Runtime runtime) {
            if (state == null) state = AgentState.empty();
            // Post-summarization refresh: re-run when the
            // summarization middleware has bumped the cutoff
            // past the last value we refreshed for.
            Object rawEvent = extension(state, SUMMARIZATION_EVENT_KEY);
            if (rawEvent instanceof Map<?, ?> event) {
                Object cutoff = event.get("cutoff_index");
                Object refreshed = extension(state, REFRESHED_CUTOFF_KEY);
                if (cutoff != null && !cutoff.equals(refreshed)) {
                    String output = runDetectScript();
                    AgentState out = state;
                    if (output != null) {
                        out = withExtension(out, LOCAL_CONTEXT_KEY, output);
                    }
                    return withExtension(out, REFRESHED_CUTOFF_KEY, cutoff);
                }
            }
            // Initial detection: skip if already set.
            if (extension(state, LOCAL_CONTEXT_KEY) != null) {
                return state;
            }
            String output = runDetectScript();
            if (output == null) {
                return state;
            }
            return withExtension(state, LOCAL_CONTEXT_KEY, output);
        }

        @Override
        public Message.AIMessage wrapModelCall(
                BiFunction<List<Message>, Runtime, Message.AIMessage> modelCall,
                List<Message> messages,
                AgentState state,
                Runtime runtime) {
            // The Java port's wrapModelCall does not expose
            // the system prompt directly. The local context
            // is held in state extensions; the host
            // application's system-prompt composer reads
            // {@link #LOCAL_CONTEXT_KEY} and appends it.
            // For the example, the system_prompt is already
            // composed at agent-construction time, so the
            // local context is a no-op here. The middleware
            // still has to delegate to the model so the chain
            // doesn't break.
            return modelCall.apply(messages, runtime);
        }

        @Override
        public CompletableFuture<Message.AIMessage> awrapModelCall(
                BiFunction<List<Message>, Runtime, CompletableFuture<Message.AIMessage>> modelCall,
                List<Message> messages,
                AgentState state,
                Runtime runtime) {
            // Mirror the sync wrapModelCall default: delegate
            // to the model call. The local context is held in
            // state extensions; the host application's
            // system-prompt composer reads it.
            return modelCall.apply(messages, runtime);
        }

        private String runDetectScript() {
            try {
                ExecuteResponse result = backend.execute(DETECT_CONTEXT_SCRIPT);
                String output = (result.output() == null ? "" : result.output()).strip();
                if (result.exitCode().isEmpty() || result.exitCode().get() != 0) {
                    String code = result.exitCode()
                            .map(Object::toString)
                            .orElse("no exit code reported");
                    LOGGER.warning(() -> String.format(
                            "Local context detection script exited with code %s; " +
                                    "context will be omitted. Output: %.200s",
                            code, output));
                    return null;
                }
                if (output.isEmpty()) {
                    LOGGER.fine("Local context detection script succeeded but produced no output");
                    return null;
                }
                return output;
            } catch (Exception e) {
                LOGGER.warning(() -> "Local context detection failed (backend: "
                        + backend.getClass().getName()
                        + "); context will be omitted from system prompt");
                return null;
            }
        }
    }

    private static Object extension(AgentState state, String key) {
        if (state == null) return null;
        Map<String, Object> ext = state.extensions();
        return ext == null ? null : ext.get(key);
    }

    private static AgentState withExtension(AgentState state, String key, Object value) {
        Map<String, Object> ext = state.extensions() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(state.extensions());
        ext.put(key, value);
        return state.withExtensions(ext);
    }

    /**
     * Concatenate all section functions into the full
     * detection script. Independent sections run as parallel
     * background subshells and write to temp files; the
     * orchestrator then concatenates them in display order.
     * The header (CWD / IN_GIT) and project section (sets
     * ROOT) run first because later sections depend on
     * their variables.
     */
    static String buildDetectScript() {
        String serialPrefix = sectionHeader() + "\n" + sectionProject();
        List<String[]> parallelSections = List.of(
                new String[]{"02_pkgmgr", sectionPackageManagers()},
                new String[]{"03_runtimes", sectionRuntimes()},
                new String[]{"04_git", sectionGit()},
                new String[]{"05_testcmd", sectionTestCommand()},
                new String[]{"06_files", sectionFiles()},
                new String[]{"07_tree", sectionTree()},
                new String[]{"08_makefile", sectionMakefile()});
        StringBuilder parallelBlock = new StringBuilder();
        StringBuilder catLine = new StringBuilder("cat ");
        for (String[] s : parallelSections) {
            String name = s[0];
            String body = s[1];
            parallelBlock.append("(\n")
                    .append(body)
                    .append("\n) > \"$_DCT/").append(name).append("\" 2>\"$_DCT/")
                    .append(name).append(".err\" &\n");
            catLine.append("\"$_DCT/").append(name).append("\" ");
        }
        String parallelSetup = "_DCT=$(mktemp -d) || exit 1\ntrap 'rm -rf \"$_DCT\"' EXIT";
        String body = serialPrefix + "\n" + parallelSetup + "\n" + parallelBlock + "wait\n" + catLine;
        return "bash <<'__DETECT_CONTEXT_EOF__'\n" + body + "\n__DETECT_CONTEXT_EOF__\n";
    }

    // -----------------------------------------------------------------
    // Detection-script section snippets. Each is a verbatim
    // port of the matching Python section function.
    // -----------------------------------------------------------------

    static String sectionHeader() {
        return """
                CWD="$(pwd)"
                echo "## Local Context"
                echo ""
                echo "**Current Directory**: \\`${CWD}\\`"
                echo ""

                # --- Check git once ---
                IN_GIT=false
                if command -v git >/dev/null 2>&1 \\
                    && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
                  IN_GIT=true
                fi""";
    }

    static String sectionProject() {
        return """
                # --- Project ---
                PROJ_LANG=""
                [ -f pyproject.toml ] || [ -f setup.py ] && PROJ_LANG="python"
                [ -z "$PROJ_LANG" ] && [ -f package.json ] && PROJ_LANG="javascript/typescript"
                [ -z "$PROJ_LANG" ] && [ -f Cargo.toml ] && PROJ_LANG="rust"
                [ -z "$PROJ_LANG" ] && [ -f go.mod ] && PROJ_LANG="go"
                [ -z "$PROJ_LANG" ] && { [ -f pom.xml ] || [ -f build.gradle ]; } && PROJ_LANG="java"

                MONOREPO=false
                { [ -f lerna.json ] || [ -f pnpm-workspace.yaml ] \\
                  || [ -d packages ] || { [ -d libs ] && [ -d apps ]; } \\
                  || [ -d workspaces ]; } && MONOREPO=true

                ROOT=""
                $IN_GIT && ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"

                ENVS=""
                { [ -d .venv ] || [ -d venv ]; } && ENVS=".venv"
                [ -d node_modules ] && ENVS="${ENVS:+${ENVS}, }node_modules"

                HAS_PROJECT=false
                { [ -n "$PROJ_LANG" ] || { [ -n "$ROOT" ] && [ "$ROOT" != "$CWD" ]; } \\
                  || $MONOREPO || [ -n "$ENVS" ]; } && HAS_PROJECT=true

                if $HAS_PROJECT; then
                  echo "**Project**:"
                  [ -n "$PROJ_LANG" ] && echo "- Language: ${PROJ_LANG}"
                  [ -n "$ROOT" ] && [ "$ROOT" != "$CWD" ] && echo "- Project root: \\`${ROOT}\\`"
                  $MONOREPO && echo "- Monorepo: yes"
                  [ -n "$ENVS" ] && echo "- Environments: ${ENVS}"
                  echo ""
                fi""";
    }

    static String sectionPackageManagers() {
        return """
                # --- Package managers ---
                PKG=""
                if [ -f uv.lock ]; then PKG="Python: uv"
                elif [ -f poetry.lock ]; then PKG="Python: poetry"
                elif [ -f Pipfile.lock ] || [ -f Pipfile ]; then PKG="Python: pipenv"
                elif [ -f pyproject.toml ]; then
                  if grep -q '\\[tool\\.uv\\]' pyproject.toml 2>/dev/null; then PKG="Python: uv"
                  elif grep -q '\\[tool\\.poetry\\]' pyproject.toml 2>/dev/null; then PKG="Python: poetry"
                  else PKG="Python: pip"
                  fi
                elif [ -f requirements.txt ]; then PKG="Python: pip"
                fi

                NODE_PKG=""
                if [ -f bun.lockb ] || [ -f bun.lock ]; then NODE_PKG="Node: bun"
                elif [ -f pnpm-lock.yaml ]; then NODE_PKG="Node: pnpm"
                elif [ -f yarn.lock ]; then NODE_PKG="Node: yarn"
                elif [ -f package-lock.json ] || [ -f package.json ]; then NODE_PKG="Node: npm"
                fi
                [ -n "$NODE_PKG" ] && PKG="${PKG:+${PKG}, }${NODE_PKG}"
                [ -n "$PKG" ] && echo "**Package Manager**: ${PKG}" && echo ""
                """;
    }

    static String sectionRuntimes() {
        return """
                # --- Runtimes ---
                RT=""
                if command -v python3 >/dev/null 2>&1; then
                  PV="$(python3 --version 2>/dev/null | awk '{print $2}')"
                  [ -n "$PV" ] && RT="Python ${PV}"
                fi
                if command -v node >/dev/null 2>&1; then
                  NV="$(node --version 2>/dev/null | sed 's/^v//')"
                  [ -n "$NV" ] && RT="${RT:+${RT}, }Node ${NV}"
                fi
                [ -n "$RT" ] && echo "**Runtimes**: ${RT}" && echo ""
                """;
    }

    static String sectionGit() {
        return """
                # --- Git ---
                if $IN_GIT; then
                  BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
                  GT="**Git**: Current branch \\`${BRANCH}\\`"

                  MAINS=""
                  for b in $(git branch 2>/dev/null | sed 's/^[* ]*//'); do
                    case "$b" in
                      main) MAINS="${MAINS:+${MAINS}, }\\`main\\`" ;;
                      master) MAINS="${MAINS:+${MAINS}, }\\`master\\`" ;;
                    esac
                  done
                  [ -n "$MAINS" ] && GT="${GT}, main branch available: ${MAINS}"

                  DC=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
                  if [ "$DC" -gt 0 ]; then
                    if [ "$DC" -eq 1 ]; then GT="${GT}, 1 uncommitted change"
                    else GT="${GT}, ${DC} uncommitted changes"
                    fi
                  fi

                  echo "$GT"
                  echo ""
                fi""";
    }

    static String sectionTestCommand() {
        return """
                # --- Test command ---
                TC=""
                if [ -f Makefile ] && grep -qE '^tests?:' Makefile 2>/dev/null; then TC="make test"
                elif [ -f pyproject.toml ]; then
                  if grep -q '\\[tool\\.pytest' pyproject.toml 2>/dev/null \\
                      || [ -f pytest.ini ] || [ -d tests ] || [ -d test ]; then
                    TC="pytest"
                  fi
                elif [ -f package.json ] \\
                    && grep -q '"test"' package.json 2>/dev/null; then
                  TC="npm test"
                fi
                [ -n "$TC" ] && echo "**Run Tests**: \\`${TC}\\`" && echo ""
                """;
    }

    static String sectionFiles() {
        return """
                # --- Files ---
                EXCL='node_modules|__pycache__|\\.pytest_cache'
                EXCL="${EXCL}|\\.mypy_cache|\\.ruff_cache|\\.tox"
                EXCL="${EXCL}|\\.coverage|\\.eggs|dist|build"
                FILES=$(
                  { ls -1 2>/dev/null; [ -e .deepagents ] && echo .deepagents; } |
                  grep -vE "^(${EXCL})$" |
                  sort -u
                )
                if [ -n "$FILES" ]; then
                  TOTAL=$(echo "$FILES" | wc -l | tr -d ' ')
                  SHOWN_FILES=$(echo "$FILES" | head -20)
                  SHOWN=$(echo "$SHOWN_FILES" | wc -l | tr -d ' ')
                  echo "**Files** (${SHOWN} shown):"
                  echo "$SHOWN_FILES" | while IFS= read -r f; do
                    if [ -d "$f" ]; then echo "- ${f}/"
                    else echo "- ${f}"
                    fi
                  done
                  [ "$SHOWN" -lt "$TOTAL" ] && echo "... ($((TOTAL - SHOWN)) more files)"
                  echo ""
                fi""";
    }

    static String sectionTree() {
        return """
                # --- Tree ---
                if command -v tree >/dev/null 2>&1; then
                  TREE_EXCL='node_modules|.venv|__pycache__|.pytest_cache'
                  TREE_EXCL="${TREE_EXCL}|.git|.mypy_cache|.ruff_cache"
                  TREE_EXCL="${TREE_EXCL}|.tox|.coverage|.eggs|dist|build"
                  T=$(tree -L 3 --noreport --dirsfirst \\
                    -I "$TREE_EXCL" 2>/dev/null | head -22)
                  if [ -n "$T" ]; then
                    echo "**Tree** (3 levels):"
                    echo '```text'
                    echo "$T"
                    echo '```'
                    echo ""
                  fi
                fi""";
    }

    static String sectionMakefile() {
        return """
                # --- Makefile ---
                MK=""
                if [ -f Makefile ]; then
                  MK="Makefile"
                elif [ -n "$ROOT" ] && [ "$ROOT" != "$CWD" ] && [ -f "${ROOT}/Makefile" ]; then
                  MK="${ROOT}/Makefile"
                fi
                if [ -n "$MK" ]; then
                  echo "**Makefile** (\\`${MK}\\`, first 20 lines):"
                  echo '```makefile'
                  head -20 "$MK"
                  TL=$(wc -l < "$MK" | tr -d ' ')
                  [ "$TL" -gt 20 ] && echo "... (truncated)"
                  echo '```'
                fi""";
    }
}
