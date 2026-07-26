package fr.robie.paperdispatch.cache;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.robie.paperdispatch.logger.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A two-tier offline player cache that provides fast UUID ↔ name resolution
 * and a bounded hot-path cache of {@link OfflinePlayer} objects.
 *
 * <p><b>Tiers</b>
 * <ol>
 *   <li><b>Bounded LRU cache</b> — holds {@link OfflinePlayer} objects (the
 *       heavier Bukkit type). Falls back to {@code Bukkit.getOfflinePlayer()}
 *       on miss, so eviction is safe. Configured via {@link Builder#maximumSize}.
 *   <li><b>Unbounded name index</b> — authoritative {@code UUID ↔ String}
 *       mapping used by commands to resolve any known player. Synchronized
 *       for thread-safe reads and writes.
 * </ol>
 *
 * <p><b>Stale-name detection</b>
 * When a name is claimed by a new UUID (player rename), the old UUID's entry
 * is marked stale and refreshed asynchronously via Mojang's session server.
 *
 * <p><b>Optional</b>
 * The cache is entirely optional. If you never {@link #install} or build one,
 * all static convenience methods degrade gracefully (return {@code null} or
 * empty results). Commands that depend on the cache simply won't tab-complete
 * names — they won't crash.
 *
 * <p><b>Lifecycle</b>
 * Each cache owns an {@link HttpClient} (with its own threads), so a cache you build
 * yourself must be shut down with {@link #close()} in {@code onDisable()}. Caches created
 * via {@link #install} are shut down for you by {@link #uninstall}.
 *
 * <p><b>Shading caveat</b>
 * The global instance is {@code static}. If two plugins shade this library without
 * relocating it, which of them owns the global cache depends on classloading. Relocate
 * when shading, or hold your own instance instead of relying on {@link #install}.
 *
 * <p><b>Usage examples</b>
 * <pre>{@code
 * // ── Simplest: defaults + auto-register listener + global instance ──
 * OfflinePlayerCache.install(this);
 *
 * // ── Custom builder, manual register, set as global ──
 * OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
 *     .maximumSize(5000)
 *     .expireAfterWrite(Duration.ofMinutes(10))
 *     .prePopulate(false)
 *     .build();
 * cache.register();
 *
 * // ── Builder with auto-register, no global instance ──
 * OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
 *     .maximumSize(2000)
 *     .buildAndRegister();
 *
 * // ── Fully custom, no listener (name index only) ──
 * OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
 *     .prePopulate(false)
 *     .build();
 * // cache is populated only via manual addToCache calls
 *
 * // ── Shutting down a cache you own (onDisable) ──
 * cache.close();          // unregisters, clears, and closes the HttpClient
 * }</pre>
 */
public final class OfflinePlayerCache implements Listener, AutoCloseable {

    private static final String MOJANG_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private static final AtomicReference<OfflinePlayerCache> globalInstance = new AtomicReference<>();

    private final Cache<UUID, OfflinePlayer> playerCache;

    private final BiMap<UUID, String> offlinePlayers = Maps.synchronizedBiMap(HashBiMap.create());

    /**
     * Lower-cased name to UUID index, kept in lock-step with {@link #offlinePlayers} so that
     * case-insensitive lookups are O(1) instead of a full scan of the (unbounded) name index.
     * Guarded by the {@link #offlinePlayers} monitor, like the BiMap itself.
     */
    private final Map<String, UUID> namesByLowercase = new HashMap<>();

    private final HttpClient httpClient;

    private final Plugin plugin;

    private final PluginLogger logger;

    private final Duration mojangTimeout;

    private final int maxSuggestions;

    /**
     * UUIDs with a Mojang name-refresh request currently in flight, so a burst of renames
     * cannot fan out duplicate requests for the same profile.
     */
    private final Set<UUID> refreshesInFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean registered = false;

    private volatile boolean closed = false;

    private OfflinePlayerCache(Builder builder) {
        this.plugin = builder.plugin;
        this.logger = builder.logger != null ? builder.logger : PluginLogger.of(this.plugin.getLogger());
        this.mojangTimeout = builder.mojangTimeout;
        this.maxSuggestions = builder.maxSuggestions;
        this.playerCache = builder.buildCache();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.mojangTimeout)
                .build();
    }

    /**
     * Populates the name index from {@code Bukkit.getOfflinePlayers()} on an async thread.
     * <p>
     * Deliberately <b>not</b> called from the constructor: handing {@code this} to the scheduler
     * before construction finishes publishes a partially-built object to another thread. The
     * {@link Builder} calls this once the instance is fully constructed.
     */
    private void prePopulateAsync() {
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            for (OfflinePlayer offlinePlayer : this.plugin.getServer().getOfflinePlayers()) {
                String name = offlinePlayer.getName();
                if (name != null) {
                    this.addToCache(offlinePlayer.getUniqueId(), name);
                }
            }
        });
    }

    /**
     * Associates {@code playerId} with {@code playerName} in both the BiMap and the
     * lower-cased index, returning the UUID that previously owned the name (or {@code null}).
     * <p>Callers must hold the {@link #offlinePlayers} monitor.
     */
    @Nullable
    private UUID putLocked(@NotNull UUID playerId, @NotNull String playerName) {
        UUID previousOwner = this.offlinePlayers.inverse().get(playerName);
        String previousName = this.offlinePlayers.get(playerId);

        this.offlinePlayers.forcePut(playerId, playerName);

        if (previousName != null) {
            this.namesByLowercase.remove(previousName.toLowerCase(Locale.ROOT));
        }
        this.namesByLowercase.put(playerName.toLowerCase(Locale.ROOT), playerId);
        return previousOwner;
    }

    /**
     * @return the plugin that owns this cache
     */
    @NotNull
    public Plugin getPlugin() {
        return this.plugin;
    }

    @NotNull
    public OfflinePlayer get(@NotNull UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        OfflinePlayer offlinePlayer = this.playerCache.getIfPresent(playerId);
        if (offlinePlayer == null) {
            offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            this.playerCache.put(playerId, offlinePlayer);
        }
        return offlinePlayer;
    }

    @Nullable
    public String getName(@NotNull UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        synchronized (this.offlinePlayers) {
            return this.offlinePlayers.get(playerId);
        }
    }

    /**
     * Resolves a player name to its UUID, case-insensitively.
     *
     * <p>Runs in constant time via the lower-cased index rather than scanning the whole
     * name index — this sits on the command-parsing hot path
     * ({@link fr.robie.paperdispatch.argument.OfflinePlayerArgument#convert}).
     *
     * <p>Minecraft names are unique case-insensitively, so at most one UUID can match. If
     * two names differing only in case were somehow indexed, the most recently added wins.
     *
     * @param playerName the player name
     * @return the UUID, or {@code null} if the name is unknown to this cache
     */
    @Nullable
    public UUID getUUID(@NotNull String playerName) {
        Preconditions.checkNotNull(playerName, "playerName cannot be null");
        synchronized (this.offlinePlayers) {
            UUID exact = this.offlinePlayers.inverse().get(playerName);
            return exact != null ? exact : this.namesByLowercase.get(playerName.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Suggests known player names matching the builder's current prefix.
     *
     * <p>The number of suggestions is capped (see {@link Builder#maxSuggestions}) because the
     * name index is unbounded: on an established server an empty prefix would otherwise
     * serialize tens of thousands of suggestions into the completion packet on every keystroke.
     * When the cap truncates the result, which subset survives is unspecified.
     *
     * @param builder the suggestions builder
     * @return a future of matching player-name suggestions
     */
    @NotNull
    public CompletableFuture<Suggestions> suggestPlayerNames(@NotNull SuggestionsBuilder builder) {
        Preconditions.checkNotNull(builder, "builder cannot be null");
        String remaining = builder.getRemainingLowerCase();
        synchronized (this.offlinePlayers) {
            int emitted = 0;
            for (String playerName : this.offlinePlayers.values()) {
                if (emitted >= this.maxSuggestions) break;
                if (playerName.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(playerName);
                    emitted++;
                }
            }
        }
        return builder.buildFuture();
    }

    public synchronized void register() {
        if (this.registered) return;
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.registered = true;
    }

    public synchronized void unregister() {
        if (!this.registered) return;
        HandlerList.unregisterAll(this);
        this.registered = false;
    }

    public boolean isRegistered() {
        return this.registered;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String cachedName;
        synchronized (this.offlinePlayers) {
            cachedName = this.offlinePlayers.get(player.getUniqueId());
        }
        if (!Objects.equals(cachedName, player.getName())) {
            this.addToCache(player.getUniqueId(), player.getName());
        }
    }

    public void addToCache(@NotNull UUID playerId, @NotNull String playerName) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        Preconditions.checkNotNull(playerName, "playerName cannot be null");

        UUID previousOwner;
        synchronized (this.offlinePlayers) {
            previousOwner = this.putLocked(playerId, playerName);
        }
        this.playerCache.invalidate(playerId);

        if (previousOwner != null && !previousOwner.equals(playerId)) {
            this.refreshStaleOwnerAsync(previousOwner);
        }
    }

    /**
     * Clears the bounded {@link OfflinePlayer} cache and the unbounded
     * UUID ↔ name index. No re-population is performed.
     */
    public void clear() {
        this.playerCache.invalidateAll();
        this.playerCache.cleanUp();
        synchronized (this.offlinePlayers) {
            this.offlinePlayers.clear();
            this.namesByLowercase.clear();
        }
    }

    /**
     * Fully resets the cache: clears all entries and re-populates the
     * name index from {@code Bukkit.getOfflinePlayers()}. The bounded
     * {@link OfflinePlayer} cache is left empty and will re-fill on
     * demand via {@link #get}.
     *
     * <p><b>Note:</b> re-population happens asynchronously. Names added via
     * {@link #addToCache} while it is in flight may be overwritten by the
     * on-disk data this reads, so avoid concurrent writes during a reset.
     */
    public void reset() {
        this.clear();
        this.prePopulateAsync();
    }

    /**
     * Releases the resources owned by this cache: clears both tiers and shuts down the
     * {@link HttpClient} used for Mojang name refreshes.
     *
     * <p>Each cache owns a dedicated {@code HttpClient}, which carries its own selector and
     * executor threads. Without this call those threads outlive the plugin, so a
     * reload-heavy server leaks a thread pool per reload. Also unregisters the event
     * listener if it is still registered.
     *
     * <p>Idempotent; safe to call more than once.
     */
    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.unregister();
        this.clear();
        this.httpClient.close();
    }

    /**
     * Returns statistics about the bounded {@link OfflinePlayer} cache,
     * such as hit rate, miss count, load times, etc.
     * <p>The returned object is a snapshot; statistics are collected only
     * when {@link Builder#recordStats} is enabled.
     *
     * @return the current cache stats
     */
    @NotNull
    public CacheStats stats() {
        return this.playerCache.stats();
    }

    private void refreshStaleOwnerAsync(@NotNull UUID staleId) {
        if (this.closed) return;

        if (!this.refreshesInFlight.add(staleId)) return;

        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            String trimmed = staleId.toString().replace("-", "");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MOJANG_PROFILE_URL + trimmed))
                    .timeout(this.mojangTimeout)
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String currentName = this.extractNameFromProfile(response.body());
                    if (currentName != null) {
                        synchronized (this.offlinePlayers) {
                            this.putLocked(staleId, currentName);
                        }
                    }
                } else if (response.statusCode() == 204 || response.statusCode() == 404) {
                    this.logger.fine("No Mojang profile found for " + staleId + " (status " + response.statusCode() + ")");
                } else if (response.statusCode() == 429) {
                    this.logger.warning("Mojang API rate-limited us while refreshing " + staleId + ", keeping stale name for now");
                } else {
                    this.logger.warning("Mojang API returned unexpected status " + response.statusCode() + " while refreshing " + staleId);
                }
            } catch (IOException e) {
                this.logger.warning("Failed to reach Mojang API to refresh name for " + staleId + " (Mojang may be down): " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.logger.warning("Interrupted while refreshing name for " + staleId);
            } catch (JsonSyntaxException e) {
                this.logger.warning("Malformed JSON from Mojang API for " + staleId + ": " + e.getMessage());
            } finally {
                this.refreshesInFlight.remove(staleId);
            }
        });
    }

    @Nullable
    private String extractNameFromProfile(@NotNull String jsonBody) {
        try {
            JsonObject obj = JsonParser.parseString(jsonBody).getAsJsonObject();
            JsonElement nameElement = obj.get("name");
            return nameElement != null ? nameElement.getAsString() : null;
        } catch (JsonSyntaxException | IllegalStateException e) {
            this.logger.warning("Unexpected Mojang profile JSON shape: " + e.getMessage());
            return null;
        }
    }

    /**
     * One-shot convenience: builds a cache with default settings, registers
     * the {@code PlayerJoinEvent} listener, and sets it as the global instance
     * used by the static convenience methods.
     *
     * <p>This is equivalent to:
     * <pre>{@code
     * OfflinePlayerCache cache = OfflinePlayerCache.builder(plugin).build();
     * cache.register();
     * }</pre>
     *
     * <p>Under the hood the constructor (called by {@link Builder#build})
     * pre-populates the name index from {@code Bukkit.getOfflinePlayers()}.
     * Then {@link #register} hooks the event listener. Calling {@code install}
     * is the simplest way to get everything running — use the {@link Builder}
     * directly if you need to customise settings or defer registration.
     *
     * @param plugin the owning plugin
     */
    public static void install(@NotNull Plugin plugin) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");

        OfflinePlayerCache existing = globalInstance.get();
        if (existing != null) {
            existing.logger.warning("OfflinePlayerCache global instance already set - install() called more than once.");
            return;
        }

        OfflinePlayerCache cache = builder(plugin).build();
        if (!globalInstance.compareAndSet(null, cache)) {
            cache.logger.warning("OfflinePlayerCache global instance already set - install() called more than once.");
            cache.close();
            return;
        }
        cache.register();
    }

    /**
     * Uninstalls the global cache instance if it belongs to the given plugin,
     * unregistering event listeners, clearing cached entries and shutting down its
     * {@link HttpClient}.
     *
     * @param plugin the owning plugin
     * @return {@code true} if uninstalled successfully, {@code false} otherwise
     */
    public static boolean uninstall(@NotNull Plugin plugin) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        OfflinePlayerCache current = globalInstance.get();
        if (current != null && current.plugin.equals(plugin)) {
            if (globalInstance.compareAndSet(current, null)) {
                current.close();
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the global instance previously set by {@link #install} or
     * {@code null} if no cache has been installed. Used by
     * {@link fr.robie.paperdispatch.argument.OfflinePlayerArgument} to resolve
     * player names without requiring consumers to hold a direct reference.
     *
     * @return the global cache instance, or {@code null}
     */
    @Nullable
    public static OfflinePlayerCache getGlobalInstance() {
        return globalInstance.get();
    }

    /**
     * Creates a new {@link Builder} for configuring an {@link OfflinePlayerCache}.
     *
     * @param plugin the owning plugin
     * @return a new builder
     */
    @NotNull
    public static Builder builder(@NotNull Plugin plugin) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        return new Builder(plugin);
    }

    /**
     * Fluent builder for {@link OfflinePlayerCache}.
     *
     * <p>Obtain via {@link OfflinePlayerCache#builder(Plugin)}.
     *
     * <p><b>Lifecycle</b>
     * <ol>
     *   <li>{@link #build()} — creates the instance and, if {@link #prePopulate} is
     *       enabled, kicks off the async name-index scan. The {@code PlayerJoinEvent}
     *       listener is <b>not</b> registered.
     *   <li>{@link #buildAndRegister()} — creates the instance and registers
     *       the event listener in one call.
     *   <li>{@link OfflinePlayerCache#register()} — register manually on an
     *       existing instance if you used {@link #build()} and later decide you
     *       need the listener.
     * </ol>
     *
     * <pre>{@code
     * OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
     *     .maximumSize(5000)
     *     .expireAfterWrite(Duration.ofMinutes(5))
     *     .expireAfterAccess(Duration.ofMinutes(1))
     *     .prePopulate(false)
     *     .mojangTimeout(Duration.ofSeconds(10))
     *     .build();
     * cache.register();
     * }</pre>
     */
    public static final class Builder {

        private final Plugin plugin;

        @Nullable
        private PluginLogger logger;

        private long maximumSize = 1000;

        @Nullable
        private Duration expireAfterWrite;

        @Nullable
        private Duration expireAfterAccess;

        private boolean prePopulate = true;

        @NotNull
        private Duration mojangTimeout = Duration.ofSeconds(5);

        private boolean recordStats = false;

        private int maxSuggestions = 50;

        private Builder(@NotNull Plugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Sets the maximum number of {@link OfflinePlayer} objects kept in the
         * bounded LRU cache. Default: {@code 1000}.
         * <p>This has no effect on the unbounded UUID ↔ name index.
         *
         * @param maximumSize positive maximum size
         * @return this builder
         */
        @NotNull
        public Builder maximumSize(long maximumSize) {
            Preconditions.checkArgument(maximumSize > 0, "maximumSize must be positive");
            this.maximumSize = maximumSize;
            return this;
        }

        /**
         * Specifies that each {@link OfflinePlayer} entry should be removed
         * from the bounded cache after the given duration from its creation
         * or last replacement. Default: no expiry.
         *
         * @param duration the write expiry duration, or {@code null} for no expiry
         * @return this builder
         */
        @NotNull
        public Builder expireAfterWrite(@Nullable Duration duration) {
            this.expireAfterWrite = duration;
            return this;
        }

        /**
         * Specifies that each {@link OfflinePlayer} entry should be removed
         * from the bounded cache after the given duration since its last read
         * or write access. Default: no expiry.
         *
         * @param duration the access expiry duration, or {@code null} for no expiry
         * @return this builder
         */
        @NotNull
        public Builder expireAfterAccess(@Nullable Duration duration) {
            this.expireAfterAccess = duration;
            return this;
        }

        /**
         * Sets the logger used for diagnostics (Mojang refresh failures, redundant
         * {@code install()} calls, and so on).
         *
         * @param logger the logger, or {@code null} to use the owning plugin's logger
         * @return this builder
         */
        @NotNull
        public Builder logger(@Nullable PluginLogger logger) {
            this.logger = logger;
            return this;
        }

        /**
         * Whether to pre-populate the UUID ↔ name index from
         * {@code Bukkit.getOfflinePlayers()} when the cache is built.
         * Default: {@code true}.
         * <p>Disable this if you only need the index populated reactively via
         * the {@code PlayerJoinEvent} listener. Note that
         * {@code Bukkit.getOfflinePlayers()} reads every stored player profile, so on a
         * large server this is a heavy (albeit async) scan.
         *
         * @param prePopulate {@code true} to pre-populate
         * @return this builder
         */
        @NotNull
        public Builder prePopulate(boolean prePopulate) {
            this.prePopulate = prePopulate;
            return this;
        }

        /**
         * Caps how many names {@link OfflinePlayerCache#suggestPlayerNames} may emit for a
         * single completion request. Default: {@code 50}.
         * <p>The name index is unbounded, so without a cap an empty prefix would serialize
         * every known player name into the completion packet on every keystroke.
         *
         * @param maxSuggestions positive suggestion cap
         * @return this builder
         */
        @NotNull
        public Builder maxSuggestions(int maxSuggestions) {
            Preconditions.checkArgument(maxSuggestions > 0, "maxSuggestions must be positive");
            this.maxSuggestions = maxSuggestions;
            return this;
        }

        /**
         * Sets the connection timeout for Mojang session-server requests used
         * when refreshing stale player names. Default: 5 seconds.
         *
         * @param mojangTimeout the timeout duration
         * @return this builder
         */
        @NotNull
        public Builder mojangTimeout(@NotNull Duration mojangTimeout) {
            Preconditions.checkNotNull(mojangTimeout, "mojangTimeout cannot be null");
            this.mojangTimeout = mojangTimeout;
            return this;
        }

        /**
         * Enables Guava cache statistics collection (hit rate, miss count,
         * load times, etc.). Default: {@code false}.
         * <p>Retrieve stats via {@link OfflinePlayerCache#stats()}.
         *
         * @param recordStats {@code true} to enable statistics
         * @return this builder
         */
        @NotNull
        public Builder recordStats(boolean recordStats) {
            this.recordStats = recordStats;
            return this;
        }

        /**
         * Builds the cache instance. The instance is <b>not</b> registered as
         * a Bukkit event listener — call {@link OfflinePlayerCache#register()}
         * on the returned instance if you want the {@code PlayerJoinEvent}
         * handler active.
         *
         * <p>If {@link #prePopulate} is enabled, an async name-index scan is kicked off
         * here rather than from the constructor, so the scheduler never sees a
         * partially-constructed instance.
         *
         * @return a new {@link OfflinePlayerCache}
         */
        @NotNull
        public OfflinePlayerCache build() {
            OfflinePlayerCache cache = new OfflinePlayerCache(this);
            if (this.prePopulate) {
                cache.prePopulateAsync();
            }
            return cache;
        }

        /**
         * Builds the cache instance and registers it as a Bukkit event listener
         * in one call. Equivalent to:
         * <pre>{@code
         * OfflinePlayerCache cache = builder.build();
         * cache.register();
         * }</pre>
         *
         * @return a new, registered {@link OfflinePlayerCache}
         */
        @NotNull
        public OfflinePlayerCache buildAndRegister() {
            OfflinePlayerCache cache = this.build();
            cache.register();
            return cache;
        }

        private Cache<UUID, OfflinePlayer> buildCache() {
            CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder()
                    .maximumSize(this.maximumSize);

            if (this.recordStats) {
                builder.recordStats();
            }
            if (this.expireAfterWrite != null) {
                builder.expireAfterWrite(this.expireAfterWrite);
            }
            if (this.expireAfterAccess != null) {
                builder.expireAfterAccess(this.expireAfterAccess);
            }

            return builder.build();
        }
    }
}
