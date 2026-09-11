package org.aethercode.core.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * prior round: tests for the unified {@link RegistryReloadService}.
 *
 * <p>Each test uses a fresh {@code @TempDir} so file-watcher
 * state is isolated. The service is closed in a try/finally
 * so a failing test can't leak a watcher thread.
 */
class RegistryReloadServiceR128Test {

    @Test
    void constructor_createsService(@TempDir Path tmp) throws Exception {
        try (RegistryReloadService svc = new RegistryReloadService()) {
            assertNotNull(svc);
            assertEquals(0L, svc.lastReloadMs());
        }
    }

    @Test
    void manualRefresh_firesListener(@TempDir Path tmp) throws Exception {
        try (RegistryReloadService svc = new RegistryReloadService()) {
            AtomicInteger count = new AtomicInteger(0);
            svc.registerReloader(RegistryReloadService.ReloadKind.SKILLS, count::incrementAndGet);
            svc.addListener(ev -> count.addAndGet(100));
            svc.refresh(RegistryReloadService.ReloadKind.SKILLS);
            // Both the reloader (1) and the listener (100) fire.
            assertEquals(101, count.get());
        }
    }

    @Test
    void manualRefreshAll_invokesEveryReloader(@TempDir Path tmp) throws Exception {
        try (RegistryReloadService svc = new RegistryReloadService()) {
            AtomicInteger skills = new AtomicInteger();
            AtomicInteger agents = new AtomicInteger();
            svc.registerReloader(RegistryReloadService.ReloadKind.SKILLS, skills::incrementAndGet);
            svc.registerReloader(RegistryReloadService.ReloadKind.AGENTS, agents::incrementAndGet);
            svc.refreshAll();
            assertEquals(1, skills.get());
            assertEquals(1, agents.get());
        }
    }

    @Test
    void fileChangeTriggersReload(@TempDir Path tmp) throws Exception {
        Path skillsDir = tmp.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"),
                "---\nname: test\ndescription: x\n---\nbody");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger reloadCount = new AtomicInteger();
        List<RegistryReloadService.ReloadEvent> events = new CopyOnWriteArrayList<>();
        try (RegistryReloadService svc = new RegistryReloadService()) {
            svc.registerReloader(RegistryReloadService.ReloadKind.SKILLS, reloadCount::incrementAndGet);
            svc.addListener(ev -> { events.add(ev); latch.countDown(); });
            svc.watch(skillsDir, RegistryReloadService.ReloadKind.SKILLS);
            // Modify a file in the watched dir.
            Files.writeString(skillsDir.resolve("SKILL.md"),
                    "---\nname: test\ndescription: updated\n---\nbody");
            assertTrue(latch.await(5, TimeUnit.SECONDS), "file change should trigger reload");
            assertEquals(1, reloadCount.get());
            assertEquals(1, events.size());
            assertEquals(RegistryReloadService.ReloadKind.SKILLS, events.get(0).kind());
        }
    }

    @Test
    void debounce_coalescesMultipleEvents(@TempDir Path tmp) throws Exception {
        // prior round design: a "save" fires 2-3 filesystem
        // events (write + chmod + close). The 250ms debounce
        // collapses them into a single reload call.
        Path skillsDir = tmp.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("SKILL.md"), "v1");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger reloadCount = new AtomicInteger();
        try (RegistryReloadService svc = new RegistryReloadService()) {
            svc.registerReloader(RegistryReloadService.ReloadKind.SKILLS, reloadCount::incrementAndGet);
            svc.addListener(ev -> latch.countDown());
            svc.watch(skillsDir, RegistryReloadService.ReloadKind.SKILLS);
            // Three rapid writes inside the 250ms debounce window.
            for (int i = 0; i < 3; i++) {
                Files.writeString(skillsDir.resolve("SKILL.md"), "v" + (i + 2));
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "first event should fire");
            // Wait a bit more for any straggler events.
            Thread.sleep(800);
            // All 3 writes should have collapsed to 1 reload.
            assertEquals(1, reloadCount.get(),
                    "3 rapid writes should coalesce to 1 reload, got " + reloadCount.get());
        }
    }

    @Test
    void brokenReloader_doesNotPropagate(@TempDir Path tmp) throws Exception {
        try (RegistryReloadService svc = new RegistryReloadService()) {
            svc.registerReloader(RegistryReloadService.ReloadKind.SKILLS, () -> {
                throw new RuntimeException("boom");
            });
            AtomicInteger listenerFired = new AtomicInteger();
            svc.addListener(ev -> listenerFired.incrementAndGet());
            // Should NOT throw — the broken reloader is caught
            // and logged; the listener still fires.
            svc.refresh(RegistryReloadService.ReloadKind.SKILLS);
            assertEquals(1, listenerFired.get());
        }
    }

    @Test
    void closeIsIdempotent(@TempDir Path tmp) throws Exception {
        RegistryReloadService svc = new RegistryReloadService();
        svc.close();
        // Second close is a no-op (no exception, no NPE).
        svc.close();
    }
}
