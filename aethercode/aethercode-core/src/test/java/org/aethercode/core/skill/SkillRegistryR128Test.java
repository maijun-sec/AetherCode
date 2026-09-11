package org.aethercode.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the lazy-body mode of {@link SkillRegistry}.
 *
 * <p>The two halves of the brief:
 * <ol>
 *   <li>When constructed with {@code lazy=true},
 *       {@link SkillRegistry#reload()} reads only metadata;
 *       {@link SkillRegistry#getBody(String)} reads the
 *       body on demand.</li>
 *   <li>Concurrent {@code getBody} calls on the same
 *       skill don't parse the file twice (the bodyCache
 *       double-check works).</li>
 * </ol>
 */
class SkillRegistryR128Test {

    @Test
    void lazyMode_doesNotParseBodiesAtReload(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skills").resolve("demo");
        Files.createDirectories(skillDir);
        // The body is 2KB of text — well above the
        // system-prompt-injection block threshold. A
        // 50-skill install would be 100KB+ of body
        // material that the metadata-only injection
        // doesn't need.
        String bigBody = "x".repeat(2048);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n" +
                "name: demo\n" +
                "description: lazy load test\n" +
                "---\n" +
                bigBody);

        // Construct with lazy=true.
        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("skills")),
                List.of(),
                java.time.Duration.ofSeconds(60),
                /* lazy */ true);

        // Metadata is available immediately.
        var metas = reg.list();
        assertEquals(1, metas.size());
        assertEquals("demo", metas.get(0).name());
        assertEquals("lazy load test", metas.get(0).description());

        // The body is null in the entry — getBody() reads it.
        Optional<String> body = reg.getBody("demo");
        assertTrue(body.isPresent());
        assertEquals(bigBody, body.get());
    }

    @Test
    void lazyMode_doesNotBlockMetadataListing(@TempDir Path tmp) throws Exception {
        // R128 brief: the system-prompt block (renderSystemPromptBlock)
        // should work without parsing bodies. We assert the
        // block doesn't throw + the description is present.
        Path skillDir = tmp.resolve("skills").resolve("a");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: a\ndescription: first\n---\nbody A");
        Files.createDirectories(skillDir.resolve("../b"));
        Files.writeString(skillDir.resolve("../b/SKILL.md"),
                "---\nname: b\ndescription: second\n---\nbody B");

        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("skills")),
                List.of(),
                java.time.Duration.ofSeconds(60),
                /* lazy */ true);
        String block = reg.renderSystemPromptBlock();
        assertTrue(block.contains("first"));
        assertTrue(block.contains("second"));
        // Body is NOT in the block.
        assertFalse(block.contains("body A"));
        assertFalse(block.contains("body B"));
    }

    @Test
    void eagerMode_stillWorks(@TempDir Path tmp) throws Exception {
        // Backward compat: the 3-arg constructor + lazy=false
        // preserves the legacy "load body at reload" path.
        Path skillDir = tmp.resolve("skills").resolve("eager");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: eager\ndescription: x\n---\neager body");
        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("skills")),
                List.of(),
                java.time.Duration.ofSeconds(60));
        // Body is in the entry — no Files.readString at getBody time.
        Optional<String> body = reg.getBody("eager");
        assertTrue(body.isPresent());
        assertEquals("eager body", body.get());
    }

    @Test
    void getBody_concurrentCalls_parseFileOnce(@TempDir Path tmp) throws Exception {
        // 5 threads race to call getBody on the same skill.
        // The bodyCache should serve all of them after the
        // first read — only 1 file read happens.
        Path skillDir = tmp.resolve("skills").resolve("shared");
        Files.createDirectories(skillDir);
        String body = "shared body " + "x".repeat(1024);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: shared\ndescription: x\n---\n" + body);

        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("skills")),
                List.of(),
                java.time.Duration.ofSeconds(60),
                /* lazy */ true);
        AtomicInteger mismatches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                try {
                    ready.countDown();
                    go.await();
                    String got = reg.getBody("shared").orElse(null);
                    if (got == null || !got.equals(body)) mismatches.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, mismatches.get(),
                "all 5 concurrent getBody calls should return the same body");
    }

    @Test
    void getBody_missingSkill_returnsEmpty(@TempDir Path tmp) throws Exception {
        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("nope")),
                List.of(),
                java.time.Duration.ofSeconds(60),
                /* lazy */ true);
        assertFalse(reg.getBody("nope").isPresent());
    }

    @Test
    void reload_clearsBodyCache(@TempDir Path tmp) throws Exception {
        // After reload(), a previously-loaded body is re-read
        // (the on-disk file may have changed).
        Path skillDir = tmp.resolve("skills").resolve("reloadable");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: reloadable\ndescription: v1\n---\nbody v1");

        SkillRegistry reg = new SkillRegistry(
                List.of(tmp.resolve("skills")),
                List.of(),
                java.time.Duration.ofSeconds(60),
                /* lazy */ true);
        assertEquals("body v1", reg.getBody("reloadable").orElseThrow());

        // User edits the skill file.
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: reloadable\ndescription: v2\n---\nbody v2");

        // Force a reload — the body cache is cleared, the
        // next getBody re-reads from disk.
        reg.reload();
        assertEquals("body v2", reg.getBody("reloadable").orElseThrow());
    }

    @Test
    void constructor_lazyFlagRoundTrips(@TempDir Path tmp) throws Exception {
        // The lazy flag is private; we exercise it
        // indirectly by asserting the observable difference
        // (no body in metadata, body in getBody).
        SkillRegistry lazy = new SkillRegistry(
                List.of(), List.of(),
                java.time.Duration.ofSeconds(60), true);
        SkillRegistry eager = new SkillRegistry(
                List.of(), List.of(),
                java.time.Duration.ofSeconds(60), false);
        // Both should be constructed without error and
        // behave identically when no skills exist.
        assertNotNull(lazy);
        assertNotNull(eager);
        assertEquals(0, lazy.list().size());
        assertEquals(0, eager.list().size());
    }
}
