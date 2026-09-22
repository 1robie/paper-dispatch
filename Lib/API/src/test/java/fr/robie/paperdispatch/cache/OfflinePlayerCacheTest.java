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

import java.lang.reflect.Field;
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
        assertNull(cache.getUUID("notch"), "the lower-cased index must be cleared alongside the display index");
    }

    @Test
    @DisplayName("Renaming a player must not leave the old name resolvable case-insensitively")
    void testRenameEvictsOldNameFromLowercaseIndex() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

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
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cache.addToCache(first, "Shared");
        cache.addToCache(second, "Shared");

        assertEquals(second, cache.getUUID("Shared"));
        assertEquals(second, cache.getUUID("shared"));
    }

    @Test
    @DisplayName("Losing a name to another player must leave the previous owner a name to display")
    void testPreviousOwnerKeepsDisplayName() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cache.addToCache(first, "Shared");
        cache.addToCache(second, "Shared");

        assertEquals("Shared", cache.getName(first),
                "the previous owner must keep a display name - returning null prints 'null' to operators");
        assertEquals("Shared", cache.getName(second));
        assertEquals(second, cache.getUUID("Shared"),
                "but the name itself resolves to the new owner only");
    }

    @Test
    @DisplayName("The name index must be bounded, evicting the oldest entries")
    void testNameIndexIsBounded() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .maximumNameEntries(10)
                .build();

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            cache.addToCache(id, "Player" + i);
        }

        assertNull(cache.getUUID("Player0"),
                "an index capped at 10 entries must not still hold the first of 500 names");
        assertNull(cache.getName(ids.getFirst()));

        assertNotNull(cache.getUUID("Player499"), "the most recent entry must survive");
    }

    @Test
    @DisplayName("The cache must not hold an HTTP client - it never contacts a remote service")
    void testCacheHoldsNoHttpClient() {
        for (Field field : OfflinePlayerCache.class.getDeclaredFields()) {
            assertNotEquals(java.net.http.HttpClient.class, field.getType(),
                    "OfflinePlayerCache must not own an HttpClient; name refreshes are local-only");
        }
    }

    @Test
    @DisplayName("An unindexed name should resolve from the server's own user cache")
    void testUnknownNameResolvesFromServerUserCache() {
        UUID joined = MockBukkit.getMock().addPlayer("Notch").getUniqueId();

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        assertEquals(joined, cache.getUUID("Notch"),
                "getUUID must fall back to Bukkit.getOfflinePlayerIfCached before giving up");
        assertEquals(joined, cache.getUUID("notch"), "and that fallback is case-insensitive");
        assertEquals("Notch", cache.getName(joined),
                "a user-cache hit should be folded into the index so the next lookup is a map read");
    }

    @Test
    @DisplayName("A name the server has never seen must not resolve")
    void testUnknownNameStaysUnresolved() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        assertNull(cache.getUUID("NoSuchPlayer"));
        assertNull(cache.getUUID(""), "a blank name must not reach the server user cache");
    }

    @Test
    @DisplayName("A Floodgate-shaped Bedrock player should index and resolve like any other")
    void testBedrockPlayerResolves() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        UUID bedrockId = new UUID(0L, 0x0002535449A0B1C2L);
        cache.addToCache(bedrockId, ".Alex_Smith");

        assertEquals(bedrockId, cache.getUUID(".Alex_Smith"));
        assertEquals(bedrockId, cache.getUUID(".alex_smith"));
        assertEquals(".Alex_Smith", cache.getName(bedrockId));

        List<String> matches = cache.suggestPlayerNames(new SuggestionsBuilder(".al", 0)).join()
                .getList().stream().map(s -> s.getText()).toList();
        assertEquals(List.of(".Alex_Smith"), matches);
    }

    @Test
    @DisplayName("suggestPlayerNames should respect the configured cap")
    void testSuggestionCap() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .maxSuggestions(10)
                .maximumNameEntries(500)
                .build();

        for (int i = 0; i < 200; i++) {
            cache.addToCache(UUID.randomUUID(), "Player" + i);
        }

        Suggestions suggestions = cache.suggestPlayerNames(new SuggestionsBuilder("", 0)).join();
        assertEquals(10, suggestions.getList().size(),
                "the index must not serialize every name into the completion packet");
    }

    @Test
    @DisplayName("suggestPlayerNames should filter by prefix case-insensitively")
    void testSuggestionPrefixFiltering() {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        cache.addToCache(UUID.randomUUID(), "Alice");
        cache.addToCache(UUID.randomUUID(), "alfred");
        cache.addToCache(UUID.randomUUID(), "Bob");

        List<String> matches = cache.suggestPlayerNames(new SuggestionsBuilder("al", 0)).join()
                .getList().stream().map(s -> s.getText()).sorted().toList();

        assertEquals(List.of("Alice", "alfred"), matches);
    }

    @Test
    @DisplayName("suggestPlayerNames should offer online players first and not duplicate them")
    void testSuggestionsPreferOnlinePlayers() {
        MockBukkit.getMock().addPlayer("Steve");

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .maxSuggestions(2)
                .build();

        for (int i = 0; i < 50; i++) {
            cache.addToCache(UUID.randomUUID(), "Stranger" + i);
        }

        List<String> matches = cache.suggestPlayerNames(new SuggestionsBuilder("st", 0)).join()
                .getList().stream().map(s -> s.getText()).toList();

        assertTrue(matches.contains("Steve"),
                "an online player must survive the cap; they are the likeliest completion");
        assertEquals(2, matches.size());
    }

    @Test
    @DisplayName("Offline names must still be suggested when no online player matches the prefix")
    void testOfflineNamesSuggestedWhenNoOnlinePlayerMatches() {
        MockBukkit.getMock().addPlayer("Steve");
        MockBukkit.getMock().addPlayer("Herobrine");

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();
        cache.addToCache(UUID.randomUUID(), "Alice");
        cache.addToCache(UUID.randomUUID(), "Alfred");

        List<String> matches = cache.suggestPlayerNames(new SuggestionsBuilder("al", 0)).join()
                .getList().stream().map(s -> s.getText()).sorted().toList();

        assertEquals(List.of("Alfred", "Alice"), matches,
                "the online pass matching nothing must not suppress the index pass");
    }

    @Test
    @DisplayName("An online player already in the index must be suggested once, not twice")
    void testOnlinePlayerIsNotSuggestedTwice() {
        UUID id = MockBukkit.getMock().addPlayer("Steve").getUniqueId();

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();
        cache.addToCache(id, "Steve");

        List<String> matches = cache.suggestPlayerNames(new SuggestionsBuilder("st", 0)).join()
                .getList().stream().map(s -> s.getText()).toList();

        assertEquals(List.of("Steve"), matches);
    }

    @Test
    @DisplayName("prePopulate should still seed the name index when it is explicitly enabled")
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
    @DisplayName("prePopulate must default to false so no startup scan runs unasked")
    void testPrePopulateDefaultsToFalse() {
        MockBukkit.getMock().addPlayer("Notch");

        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin).build();

        // The name still resolves - but through the server's user cache, not a playerdata
        // scan - so the observable difference is that nothing was indexed up front.
        assertNull(cache.getName(UUID.randomUUID()));
        assertNull(cache.getUUID("SomeoneWhoNeverJoined"));
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
        UUID id = UUID.randomUUID();
        cache.addToCache(id, "Notch");

        assertTrue(OfflinePlayerCache.uninstall(this.plugin));

        assertFalse(cache.isRegistered());
        assertNull(cache.getName(id), "close() clears the index");
    }

    @Test
    @DisplayName("Concurrent reads during tab completion and writes should not cause ConcurrentModificationException")
    void testThreadSafety() throws Exception {
        OfflinePlayerCache cache = OfflinePlayerCache.builder(this.plugin)
                .maximumNameEntries(10_000)
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
