/**
 * Java 21 port of the Python {@code deepagents_code.tui.widgets} package.
 *
 * <p>This package contains the Textual-style widget classes that build the
 * deepagents-code TUI. The Python implementation is built on the
 * <a href="https://textual.textualize.io">Textual</a> reactive framework, which
 * is a substantial dependency that has no Java equivalent. Rather than bind
 * the Java port to a TUI framework that may not exist on the target runtime,
 * the widgets in this package are surfaced as <em>data classes</em> that
 * describe the widget's public API surface, key bindings, and dismissal
 * contract, and they return a structured {@link WidgetNode} tree from
 * {@code render()} that a host TUI layer can project to any concrete
 * rendering pipeline (Textual, JLine, Lanterna, raw ANSI, …).</p>
 *
 * <p>Each ported widget:</p>
 * <ul>
 *   <li>Preserves the Python class name, public method names, and dismiss
 *       contract (a {@code ModalScreen<T>} returns a {@code T} via
 *       {@code dismiss(value)}).</li>
 *   <li>Replaces {@code @dataclass} with Java {@code record}.</li>
 *   <li>Replaces {@code asyncio.Future} with
 *       {@link java.util.concurrent.CompletableFuture}.</li>
 *   <li>Surfaces constants that the Python file declares at module scope.</li>
 *   <li>Provides a {@code render()} method that returns a {@link WidgetNode}
 *       sealed interface — a structural description of what the widget would
 *       show. Actual rendering is the responsibility of a TUI host layer.</li>
 *   <li>Carries {@code TODO} markers where the original Python depends on
 *       Textual primitives (composables, message pumps, focus chains). These
 *       are not lost semantics — they document the port's intentional
 *       cut-line so a future Java-native TUI host can fill them in.</li>
 * </ul>
 *
 * <p>Cross-file references (e.g. {@code cwd_switch.HookTrustScreen},
 * {@code message_store.MessageStore}, the renderer registry in
 * {@code tool_renderers}, and the inline-prompt primitives in
 * {@code _inline_prompt}) are surfaced by name. The host TUI can resolve
 * them through the package's class index; the Java records here do not
 * pre-resolve them at compile time so the widgets can be loaded
 * independently of one another.</p>
 *
 * <p>Wire compatibility: not preserved. This port matches the Python
 * implementation's <em>shape</em>, not its serialization. Hosts needing to
 * interoperate with the Python TUI should run the Python package instead.
 * </p>
 */
package org.aethercode.code.tui.widgets;
