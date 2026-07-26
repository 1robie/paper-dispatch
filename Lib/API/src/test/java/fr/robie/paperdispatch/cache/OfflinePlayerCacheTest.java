package fr.robie.paperdispatch.cache;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OfflinePlayerCacheTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.plugin = PluginMock.builder().withPluginName("TestPlugin").build();
    }

    @AfterEach
    void tearDown() {
        OfflinePlayerCache leaked = OfflinePlayerCache.getGlobalInstance();
        if (leaked != null) {
            OfflinePlayerCache.uninstall(leaked.getPlugin());
        }
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
        assertNull(cache.getUUID("notch"), "lower-cased index must be cleared alongside the BiMap");
    }

    @Test
    @DisplayName("Renaming a player must not leave the old name resolvable case-insensitively")
    void testRenameEvictsOldNameFromLowercaseIndex() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).prePopulate(false).build();

        UUID id = UUID.randomUUID();
        cache.addToCache(id, "OldName");
        assertEquals(id, cache.getUUID("oldname"));

        cache.addToCache(id, "NewName");

        assertEquals("NewName", cache.getName(id));
        assertEquals(id, cache.getUUID("newname"));
        assertNull(cache.getUUID("oldname"), "the stale name must not resolve after a rename");
        assertNull(cache.getUUID("OldName"));
    }

    @Test
    @DisplayName("A name claimed by a new UUID should transfer to the new owner")
    void testNameTransferBetweenUuids() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).prePopulate(false).build();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cache.addToCache(first, "Shared");
        cache.addToCache(second, "Shared");

        assertEquals(second, cache.getUUID("Shared"));
        assertEquals(second, cache.getUUID("shared"));
        assertNull(cache.getName(first), "the previous owner loses the name in the BiMap");
    }

    @Test
    @DisplayName("suggestPlayerNames should respect the configured cap")
    void testSuggestionCap() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .prePopulate(false)
                .maxSuggestions(10)
                .build();

        for (int i = 0; i < 200; i++) {
            cache.addToCache(UUID.randomUUID(), "Player" + i);
        }

        Suggestions suggestions = cache.suggestPlayerNames(new SuggestionsBuilder("", 0)).join();
        assertEquals(10, suggestions.getList().size(),
                "an unbounded name index must not serialize every name into the completion packet");
    }

    @Test
    @DisplayName("suggestPlayerNames should filter by prefix case-insensitively")
    void testSuggestionPrefixFiltering() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).prePopulate(false).build();

        cache.addToCache(UUID.randomUUID(), "Alice");
        cache.addToCache(UUID.randomUUID(), "alfred");
        cache.addToCache(UUID.randomUUID(), "Bob");

        List<String> matches = cache.suggestPlayerNames(new SuggestionsBuilder("al", 0)).join()
                .getList().stream().map(s -> s.getText()).sorted().toList();

        assertEquals(List.of("Alice", "alfred"), matches);
    }

    @Test
    @DisplayName("prePopulate should still seed the name index now that it runs from build(), not the constructor")
    void testPrePopulateSeedsIndex() {
        MockBukkit.getMock().addPlayer("Notch");

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .prePopulate(true)
                .build();

        UUID resolved = null;
        for (int attempt = 0; attempt < 100 && resolved == null; attempt++) {
            resolved = cache.getUUID("Notch");
            if (resolved == null) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted while waiting for pre-population");
                }
            }
        }

        assertNotNull(resolved, "prePopulate(true) must seed the name index from getOfflinePlayers()");
        assertEquals("Notch", cache.getName(resolved));
        cache.close();
    }

    @Test
    @DisplayName("prePopulate(false) should leave the index empty")
    void testPrePopulateDisabled() {
        MockBukkit.getMock().addPlayer("Notch");

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .prePopulate(false)
                .build();

        assertNull(cache.getUUID("Notch"));
        cache.close();
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
    @DisplayName("A redundant install() must not replace or leak over the existing global instance")
    void testDoubleInstallKeepsFirstInstance() {
        OfflinePlayerCache.install(this.plugin);
        OfflinePlayerCache first = OfflinePlayerCache.getGlobalInstance();
        assertNotNull(first);

        OfflinePlayerCache.install(this.plugin);

        assertSame(first, OfflinePlayerCache.getGlobalInstance(),
                "the second install() must be a no-op, not a replacement");
    }

    @Test
    @DisplayName("close() should be idempotent and unregister the listener")
    void testCloseIsIdempotent() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .prePopulate(false)
                .buildAndRegister();

        assertTrue(cache.isRegistered());

        cache.close();
        assertFalse(cache.isRegistered());

        cache.close(); // must not throw
        assertFalse(cache.isRegistered());
    }

    @Test
    @DisplayName("uninstall() should shut the cache down, not merely unregister it")
    void testUninstallClosesCache() {
        OfflinePlayerCache.install(this.plugin);
        OfflinePlayerCache cache = OfflinePlayerCache.getGlobalInstance();
        assertNotNull(cache);
        cache.addToCache(UUID.randomUUID(), "Notch");

        assertTrue(OfflinePlayerCache.uninstall(this.plugin));

        assertFalse(cache.isRegistered());
        assertNull(cache.getUUID("Notch"), "close() clears the index");
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
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    for (int i = 0; i < 500; i++) {
                        if (threadId % 2 == 0) {
                            SuggestionsBuilder builder = new SuggestionsBuilder("play", 0);
                            CompletableFuture<Suggestions> future = cache.suggestPlayerNames(builder);
                            assertNotNull(future.join());
                            assertNotNull(cache.getUUID("Player1"));
                        } else {
                            cache.addToCache(UUID.randomUUID(), "NewPlayer_" + threadId + "_" + i);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }));
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "workers did not finish - possible deadlock");
        executor.shutdown();

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
    }
}
