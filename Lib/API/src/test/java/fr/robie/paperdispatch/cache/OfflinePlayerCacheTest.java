package fr.robie.paperdispatch.cache;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class OfflinePlayerCacheTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("OfflinePlayerCache builder and addToCache should store and resolve UUIDs case-insensitively")
    void testCacheAddAndLookup() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .prePopulate(false)
                .maximumSize(500)
                .build();

        UUID id = UUID.randomUUID();
        cache.addToCache(id, "Notch");

        assertEquals("Notch", cache.getName(id));
        assertEquals(id, cache.getUUID("Notch"));
        assertEquals(id, cache.getUUID("notch"));
        assertEquals(id, cache.getUUID("NOTCH"));

        cache.clear();
        assertNull(cache.getName(id));
        assertNull(cache.getUUID("Notch"));
    }

    @Test
    @DisplayName("Install and uninstall should correctly set and clear global instance")
    void testInstallUninstall() {
        OfflinePlayerCache.install(this.plugin);
        assertNotNull(OfflinePlayerCache.getGlobalInstance());

        assertTrue(OfflinePlayerCache.uninstall(this.plugin));
        assertNull(OfflinePlayerCache.getGlobalInstance());
    }

    @Test
    @DisplayName("Concurrent reads during tab completion and writes should not cause ConcurrentModificationException")
    void testThreadSafety() throws Exception {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .prePopulate(false)
                .build();

        for (int i = 0; i < 100; i++) {
            cache.addToCache(UUID.randomUUID(), "Player" + i);
        }

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 500; i++) {
                        if (threadId % 2 == 0) {
                            // Concurrent tab completion read
                            SuggestionsBuilder builder = new SuggestionsBuilder("play", 0);
                            CompletableFuture<Suggestions> future = cache.suggestPlayerNames(builder);
                            assertNotNull(future.join());
                        } else {
                            // Concurrent write
                            cache.addToCache(UUID.randomUUID(), "NewPlayer_" + threadId + "_" + i);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
    }
}
