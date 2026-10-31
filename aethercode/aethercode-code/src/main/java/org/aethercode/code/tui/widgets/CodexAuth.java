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

package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * ChatGPT OAuth sign-in widgets, reachable via {@code /auth} →
 * {@code openai_codex}.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.codex_auth}. The
 * Python module exposes a {@code CodexAuthScreen(ModalScreen[bool])}
 * that drives the ChatGPT OAuth Authorization Code Flow with PKCE, plus
 * a {@code CodexSignedInScreen(ModalScreen[CodexSignedInAction | None])}
 * quick-action overlay shown when {@code openai_codex} is already
 * signed in. The Java port preserves the dismiss contracts.</p>
 */
public final class CodexAuth {

    private CodexAuth() {}

    /** Outcome of the ChatGPT OAuth sign-in. */
    public record Status(boolean signedIn, String planType, String accountId) {}

    /** Modal that runs the ChatGPT OAuth Authorization Code Flow with PKCE inline. */
    public static final class AuthScreen extends ModalScreen<Boolean> {
        public AuthScreen() { super("", "codex-auth-screen"); }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("Sign in with ChatGPT",
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static(
                            "Authorize Deep Agents to call ChatGPT Codex models on "
                                    + "your behalf. We will open your default browser to "
                                    + "openai.com to sign in."),
                    new WidgetNode.Static("Preparing OAuth flow...",
                            WidgetNode.Role.MUTED),
                    new WidgetNode.Static(""),
                    new WidgetNode.Static("Esc cancel · a browser window will open shortly",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "codex-auth-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("escape", "cancel", "Cancel"),
                    KeyBinding.priority("ctrl+c", "cancel", "Cancel"));
        }

        public void actionCancel() { dismiss(Boolean.FALSE); }
    }

    /** Quick-action overlay shown when {@code openai_codex} is already signed in. */
    public enum SignedInAction { SIGN_OUT, REAUTH }

    public static final class SignedInScreen extends ModalScreen<SignedInAction> {
        public SignedInScreen() { super("", "codex-signed-screen"); }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("ChatGPT sign-in",
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static("Signed in to ChatGPT."),
                    new WidgetNode.Static("S sign out · R sign in again · Esc close",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "codex-signed-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("escape", "cancel", "Cancel"),
                    KeyBinding.priority("s", "signout", "Sign out"),
                    KeyBinding.priority("r", "reauth", "Reauth"));
        }

        public void actionSignout() { dismiss(SignedInAction.SIGN_OUT); }
        public void actionReauth() { dismiss(SignedInAction.REAUTH); }
        public void actionCancel() { dismiss(null); }
    }
}
