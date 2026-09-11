package org.aethercode.prompts;

/** Tiny harness that prints the size of the default
 *  SystemPrompt so an prior round reviewer can eyeball whether
 *  the revamp hit the expected range (~12-16 KB for
 *  the new identity + workflow). Run with:
 *
 *  <pre>
 *    java org.aethercode.prompts.DefaultPromptSize
 *  </pre>
 */
public final class DefaultPromptSize {
    public static void main(String[] args) {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        System.out.println("=== default SystemPrompt size ===");
        System.out.println("total chars:    " + rp.text().length());
        for (var s : rp.sections()) {
            System.out.printf("  %-14s %5d chars  source=%s%n",
                    s.name(), s.text().length(), s.source());
        }
    }
}
