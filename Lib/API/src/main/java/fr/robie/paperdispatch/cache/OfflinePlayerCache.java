package fr.robie.paperdispatch.cache;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded name to {@link UUID} resolver for command arguments.
 *
 * <p>This is <b>not</b> a player database. It exists so that a command can accept the
 * name of a player who is not currently connected, resolve it to a UUID, and show that
 * name back in a message. Everything it holds is best-effort and evictable.
 *
 * <p><b>What it holds</b>
 * <ol>
 *   <li><b>name to UUID</b> — authoritative and unique: a name resolves to exactly one
 *       player, the most recent owner. Backs {@link #getUUID} and tab completion.
 *   <li><b>UUID to name</b> — display only, and deliberately <b>not</b> unique. A player
 *       whose name was taken over by someone else keeps their last known name here, so
 *       {@link #getName} still has something to show instead of {@code null}.
 *   <li><b>{@link OfflinePlayer} objects</b> — a hot-path cache for {@link #get(UUID)},
 *       refilled from {@code Bukkit.getOfflinePlayer(UUID)} on miss.
 * </ol>
 *
 * <p>All three are bounded (see {@link Builder#maximumSize} and
 * {@link Builder#maximumNameEntries}). Nothing here grows without limit.
 *
 * <p><b>No network access</b>
 * This class never contacts Mojang or any other remote service. A name that is not
 * indexed locally is looked up once via {@code Bukkit.getOfflinePlayerIfCached(String)},
 * which reads the server's own user cache and does no I/O of its own; if that misses,
 * the name simply does not resolve. Resolving a name any further would mean a blocking
 * profile lookup on the command path, which is what this class exists to avoid.
 *
 * <p><b>Renames</b>
 * When a player joins under a new name, the old name stops resolving and the new one
 * takes over. When a name is claimed by a <i>different</i> UUID, the new owner wins the
 * name and the previous owner keeps their last known name for display. No refresh, no
 * remote call, and no way for the two directions to disagree.
 *
 * <p><b>Optional</b>
 * The cache is entirely optional. If you never {@link #install} or build one, all static
 * convenience methods degrade gracefully (return {@code null} or empty results). Commands
 * that depend on the cache simply won't tab-complete names — they won't crash.
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
 *     .maximumNameEntries(5000)
 *     .expireAfterWrite(Duration.ofMinutes(10))
 *     .build();
 * cache.register();
 *
 * // ── Builder with auto-register, no global instance ──
 * OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
 *     .maximumSize(2000)
 *     .buildAndRegister();
 *
 * // ── Opt in to the startup scan (see prePopulate for the cost) ──
 * OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
 *     .prePopulate(true)
 *     .build();
 *
 * // ── Releasing a cache you own (onDisable) ──
 * cache.close();          // unregisters the listener and drops every entry
 * }</pre>
 */
public final class OfflinePlayerCache implements Listener, AutoCloseable {

    private static final AtomicReference<OfflinePlayerCache> globalInstance = new AtomicReference<>();

    private final Cache<UUID, OfflinePlayer> playerCache;

    /**
     * Lower-cased name to owner. Authoritative and unique: exactly one UUID owns a name.
     * The display spelling rides along so suggestions keep the player's real casing.
     */
    private final Cache<String, NameEntry> ownersByLowercaseName;

    /**
     * UUID to last known name, for display. Unlike {@link #ownersByLowercaseName} this is
     * not unique and is not invalidated when someone else takes the name, so a player who
     * lost their name to a new owner still has something to show.
     */
    private final Cache<UUID, String> namesById;

    private final ReentrantLock indexLock = new ReentrantLock();

    private final Plugin plugin;

    private final PluginLogger logger;

    private final int maxSuggestions;

    private volatile boolean registered = false;

    private volatile boolean closed = false;

    private OfflinePlayerCache(Builder builder) {
        this.plugin = builder.plugin;
        this.logger = builder.logger != null ? builder.logger : PluginLogger.of(this.plugin.getLogger());
        this.maxSuggestions = builder.maxSuggestions;
        this.playerCache = builder.buildPlayerCache();
        this.ownersByLowercaseName = builder.buildNameCache();
        this.namesById = builder.buildIdCache();
    }

    /**
     * A name's current owner, plus the spelling to show for it.
     *
     * @param playerId    the owning player
     * @param displayName the name as the player spells it
     */
    private record NameEntry(@NotNull UUID playerId, @NotNull String displayName) {
    }

    /**
     * Populates the name index from {@code Bukkit.getOfflinePlayers()} on an async thread.
     * <p>
     * Deliberately <b>not</b> called from the constructor: handing {@code this} to the scheduler
     * before construction finishes publishes a partially-built object to another thread. The
     * {@link Builder} calls this once the instance is fully constructed, and only when
     * {@link Builder#prePopulate} was explicitly enabled.
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

    /**
     * Returns the last name this cache saw for a player.
     *
     * <p>The answer may be stale: if someone else has since taken the name, that name now
     * resolves to <i>them</i> via {@link #getUUID}, while this still reports it here. That
     * is deliberate — a slightly out-of-date name is a better thing to print than
     * {@code null}, and correcting it would mean a remote profile lookup.
     *
     * @param playerId the player
     * @return the last known name, or {@code null} if this cache has never seen the player
     */
    @Nullable
    public String getName(@NotNull UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        return this.namesById.getIfPresent(playerId);
    }

    /**
     * Resolves a player name to its UUID, case-insensitively.
     *
     * <p>Checks the local index first, then falls back to
     * {@code Bukkit.getOfflinePlayerIfCached(String)} — the server's own user cache, which
     * is local and does no I/O. A hit there is folded into this cache so the next lookup
     * is a straight map read. Nothing beyond that is attempted: resolving an unknown name
     * for real means a blocking profile lookup on the command path.
     *
     * <p>Minecraft names are unique case-insensitively, so at most one UUID can match.
     *
     * @param playerName the player name
     * @return the UUID, or {@code null} if the name cannot be resolved locally
     */
    @Nullable
    public UUID getUUID(@NotNull String playerName) {
        Preconditions.checkNotNull(playerName, "playerName cannot be null");
        if (playerName.isBlank()) return null;

        NameEntry entry = this.ownersByLowercaseName.getIfPresent(playerName.toLowerCase(Locale.ROOT));
        if (entry != null) return entry.playerId();

        return this.resolveFromServerUserCache(playerName);
    }

    @Nullable
    private UUID resolveFromServerUserCache(@NotNull String playerName) {
        OfflinePlayer offlinePlayer;
        try {
            offlinePlayer = Bukkit.getOfflinePlayerIfCached(playerName);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (offlinePlayer == null) return null;

        UUID playerId = offlinePlayer.getUniqueId();
        String name = offlinePlayer.getName();
        if (name != null) {
            this.addToCache(playerId, name);
        }
        return playerId;
    }

    /**
     * Suggests known player names matching the builder's current prefix.
     *
     * <p>Online players are offered first: they are both the cheapest to enumerate and the
     * likeliest thing an operator is typing. The local index fills the rest.
     *
     * <p>The number of suggestions is capped (see {@link Builder#maxSuggestions}) because an
     * empty prefix would otherwise serialize the whole index into the completion packet on
     * every keystroke. When the cap truncates the result, which subset survives is
     * unspecified.
     *
     * @param builder the suggestions builder
     * @return a future of matching player-name suggestions
     */
    @NotNull
    public CompletableFuture<Suggestions> suggestPlayerNames(@NotNull SuggestionsBuilder builder) {
        Preconditions.checkNotNull(builder, "builder cannot be null");
        String remaining = builder.getRemainingLowerCase();

        Set<String> emittedOnline = null;
        int emitted = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (emitted >= this.maxSuggestions) break;
            String name = player.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(remaining)) continue;

            if (emittedOnline == null) emittedOnline = new HashSet<>();
            emittedOnline.add(lower);
            builder.suggest(name);
            emitted++;
        }

        for (NameEntry entry : this.ownersByLowercaseName.asMap().values()) {
            if (emitted >= this.maxSuggestions) break;
            String name = entry.displayName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(remaining)) continue;
            if (emittedOnline != null && emittedOnline.contains(lower)) continue;

            builder.suggest(name);
            emitted++;
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
        String cachedName = this.namesById.getIfPresent(player.getUniqueId());
        if (!Objects.equals(cachedName, player.getName())) {
            this.addToCache(player.getUniqueId(), player.getName());
        }
    }

    /**
     * Indexes a player under a name, making that name resolve to them from now on.
     *
     * <p>If this player previously held a different name, the old name stops resolving. If
     * the name previously belonged to a different player, this player takes it over and the
     * previous owner keeps their last known name for display.
     *
     * @param playerId   the player
     * @param playerName the name to index them under
     */
    public void addToCache(@NotNull UUID playerId, @NotNull String playerName) {
        Preconditions.checkNotNull(playerId, "playerId cannot be null");
        Preconditions.checkNotNull(playerName, "playerName cannot be null");

        this.indexLock.lock();
        try {
            String previousName = this.namesById.getIfPresent(playerId);
            if (previousName != null && !previousName.equalsIgnoreCase(playerName)) {
                this.ownersByLowercaseName.invalidate(previousName.toLowerCase(Locale.ROOT));
            }
            this.ownersByLowercaseName.put(playerName.toLowerCase(Locale.ROOT), new NameEntry(playerId, playerName));
            this.namesById.put(playerId, playerName);
        } finally {
            this.indexLock.unlock();
        }

        this.playerCache.invalidate(playerId);
    }

    /**
     * Drops every entry: both directions of the name index and the
     * {@link OfflinePlayer} cache. No re-population is performed.
     */
    public void clear() {
        this.playerCache.invalidateAll();
        this.playerCache.cleanUp();
        this.indexLock.lock();
        try {
            this.ownersByLowercaseName.invalidateAll();
            this.namesById.invalidateAll();
        } finally {
            this.indexLock.unlock();
        }
    }

    /**
     * Fully resets the cache: clears all entries and re-populates the
     * name index from {@code Bukkit.getOfflinePlayers()}. The
     * {@link OfflinePlayer} cache is left empty and will re-fill on
     * demand via {@link #get}.
     *
     * <p><b>Note:</b> re-population happens asynchronously and reads every stored player
     * profile — see {@link Builder#prePopulate} for what that costs. Names added via
     * {@link #addToCache} while it is in flight may be overwritten by the on-disk data it
     * reads, so avoid concurrent writes during a reset.
     */
    public void reset() {
        this.clear();
        this.prePopulateAsync();
    }

    /**
     * Releases this cache: unregisters the event listener if it is still registered and
     * drops every entry.
     *
     * <p>Idempotent; safe to call more than once.
     */
    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.unregister();
        this.clear();
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
     * <p>The index starts empty and fills as players join, plus whatever the server's own
     * user cache can answer. Use the {@link Builder} directly if you need to customise
     * settings, defer registration, or opt in to {@link Builder#prePopulate}.
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
     * unregistering event listeners and clearing cached entries.
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
     *   <li>{@link #build()} — creates the instance. The {@code PlayerJoinEvent}
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
     *     .maximumNameEntries(5000)
     *     .expireAfterWrite(Duration.ofMinutes(5))
     *     .expireAfterAccess(Duration.ofMinutes(1))
     *     .build();
     * cache.register();
     * }</pre>
     */
    public static final class Builder {

        private final Plugin plugin;

        @Nullable
        private PluginLogger logger;

        private long maximumSize = 1000;

        private long maximumNameEntries = 1000;

        @Nullable
        private Duration expireAfterWrite;

        @Nullable
        private Duration expireAfterAccess;

        private boolean prePopulate = false;

        private boolean recordStats = false;

        private int maxSuggestions = 50;

        private Builder(@NotNull Plugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Sets the maximum number of {@link OfflinePlayer} objects kept in the
         * bounded LRU cache. Default: {@code 1000}.
         * <p>This has no effect on the name index — see {@link #maximumNameEntries}.
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
         * Sets the maximum number of entries kept in each direction of the name index.
         * Default: {@code 1000}, matching the server's own {@code settings.user-cache-size}.
         *
         * <p>Raising this trades memory for the chance that a long-absent player's name
         * still resolves. It is a cache, not a record: an evicted name falls back to the
         * server's user cache, and failing that simply stops resolving.
         *
         * @param maximumNameEntries positive maximum number of indexed names
         * @return this builder
         */
        @NotNull
        public Builder maximumNameEntries(long maximumNameEntries) {
            Preconditions.checkArgument(maximumNameEntries > 0, "maximumNameEntries must be positive");
            this.maximumNameEntries = maximumNameEntries;
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
         * Sets the logger used for diagnostics (redundant {@code install()} calls, and so on).
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
         * Whether to scan {@code Bukkit.getOfflinePlayers()} into the name index when the
         * cache is built. Default: {@code false}.
         *
         * <p><b>This is expensive and most servers should leave it off.</b>
         * {@code getOfflinePlayers()} lists the {@code playerdata} directory, and reading
         * each result's name loads and parses that player's {@code .dat} file — one disk
         * read per player who has ever joined. The scan is async, but on an established
         * server it is thousands of file reads at startup for data the server's own user
         * cache already answers for anyone seen recently.
         *
         * <p>Left off, the index fills from {@code PlayerJoinEvent} and from
         * {@code Bukkit.getOfflinePlayerIfCached(String)} on lookup. Turn it on only if you
         * genuinely need every historical player to tab-complete from a cold start.
         *
         * @param prePopulate {@code true} to scan at build time
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
         * <p>Without a cap an empty prefix would serialize the whole index into the
         * completion packet on every keystroke.
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
         * No longer has any effect.
         *
         * <p>This cache used to refresh a stale name against Mojang's session server. It no
         * longer contacts Mojang — or anything else — so there is no timeout to configure.
         * See the class documentation for how renames are handled now.
         *
         * @param mojangTimeout ignored
         * @return this builder
         * @deprecated no replacement; remove the call
         */
        @Deprecated(forRemoval = true, since = "1.1.0")
        @NotNull
        public Builder mojangTimeout(@Nullable Duration mojangTimeout) {
            this.plugin.getLogger().warning(
                    "OfflinePlayerCache.Builder#mojangTimeout no longer does anything and will be removed; "
                            + "this cache never contacts Mojang. Remove the call.");
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
         * <p>If {@link #prePopulate} was enabled, the async scan is kicked off here rather
         * than from the constructor, so the scheduler never sees a partially-constructed
         * instance.
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

        private Cache<UUID, OfflinePlayer> buildPlayerCache() {
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

        private Cache<String, NameEntry> buildNameCache() {
            return CacheBuilder.newBuilder().maximumSize(this.maximumNameEntries).build();
        }

        private Cache<UUID, String> buildIdCache() {
            return CacheBuilder.newBuilder().maximumSize(this.maximumNameEntries).build();
        }
    }
}
