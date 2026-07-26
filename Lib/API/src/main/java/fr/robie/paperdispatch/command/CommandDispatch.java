package fr.robie.paperdispatch.command;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.common.base.Preconditions;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.robie.paperdispatch.flag.Flag;
import fr.robie.paperdispatch.flag.FlagContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.FinePosition;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Provides convenient access to the current command execution context:
 * the plugin, the Brigadier {@link CommandContext}, the parsed {@link FlagContext},
 * and typed helpers for retrieving command arguments and flag values.
 *
 * @param <T> the plugin type
 */
public final class CommandDispatch<T extends Plugin> {

    private final T plugin;
    private final CommandContext<CommandSourceStack> context;
    private final FlagContext flags;
    private final List<Flag<?>> registeredFlags;

    /**
     * @param plugin          the owning plugin
     * @param context         the Brigadier command context
     * @param flags           the parsed flag context
     * @param registeredFlags the list of flags available to this command
     */
    public CommandDispatch(@NotNull T plugin, @NotNull CommandContext<CommandSourceStack> context, @NotNull FlagContext flags, @NotNull List<Flag<?>> registeredFlags) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        Preconditions.checkNotNull(context, "CommandContext cannot be null");
        Preconditions.checkNotNull(flags, "FlagContext cannot be null");
        Preconditions.checkNotNull(registeredFlags, "Registered flags list cannot be null");
        this.plugin = plugin;
        this.context = context;
        this.flags = flags;
        this.registeredFlags = registeredFlags;
    }

    /**
     * @return the plugin that owns this command
     */
    @NotNull
    public T getPlugin() {
        return this.plugin;
    }

    /**
     * @return the raw Brigadier command context
     */
    @NotNull
    public CommandContext<CommandSourceStack> getContext() {
        return this.context;
    }

    /**
     * @return the parsed flag context
     */
    @NotNull
    public FlagContext getFlags() {
        return this.flags;
    }

    /**
     * @return the command source
     */
    @NotNull
    public CommandSourceStack getSource() {
        return this.context.getSource();
    }

    /**
     * @return the command sender
     */
    @NotNull
    public CommandSender getSender() {
        return this.context.getSource().getSender();
    }

    /**
     * Returns the entity actually executing this command, which is <b>not</b> always
     * {@link #getSender()}.
     *
     * <p>Vanilla's {@code /execute as &lt;entity&gt; run &lt;command&gt;} keeps the original
     * sender (whoever typed it) while swapping the executor to the targeted entity. Use this
     * when the command should act on behalf of that entity; use {@link #getSender()} when you
     * need whoever triggered it - for permission messages, feedback, and so on.
     *
     * @return the executing entity, or {@code null} if the source has no entity (e.g. console)
     */
    @Nullable
    public Entity getExecutor() {
        return this.getSource().getExecutor();
    }

    /**
     * Returns the location this command is executing at.
     *
     * <p>Affected by {@code /execute positioned ...}, so this is not necessarily the sender's
     * own location. The returned instance is a clone and safe to mutate.
     *
     * @return the execution location
     */
    @NotNull
    public Location getLocation() {
        return this.getSource().getLocation();
    }

    /**
     * Returns the <b>sender</b> as a player.
     *
     * <p>Not to be confused with {@link #resolvePlayer(String)}, which resolves a named
     * <i>argument</i>. This method never looks at the command's arguments.
     *
     * @return the sender as a player, or {@code null} if the sender is not a player
     */
    @Nullable
    public Player getSenderAsPlayer() {
        CommandSender sender = this.getSender();
        return sender instanceof Player ? (Player) sender : null;
    }

    /**
     * Delegates to {@link FlagContext#hasFlag(String)}.
     */
    public boolean hasFlag(@NotNull String name) {
        return this.flags.hasFlag(name);
    }

    /**
     * Delegates to {@link FlagContext#getValue(String, Class)}. May return {@code null}
     * for a flag explicitly declared with a null default.
     */
    @Nullable
    public <V> V getFlagValue(@NotNull String name, @NotNull Class<V> type) {
        return this.flags.getValue(name, type);
    }

    /**
     * Delegates to {@link FlagContext#getValue(String, Class, Object)}. May return {@code null}
     * for a flag explicitly declared with a null default.
     */
    @Nullable
    public <V> V getFlagValue(@NotNull String name, @NotNull Class<V> type, @NotNull V fallback) {
        return this.flags.getValue(name, type, fallback);
    }

    /**
     * Delegates to {@link FlagContext#getOptionalValue(String, Class)}.
     */
    @NotNull
    public <V> Optional<V> getOptionalFlagValue(@NotNull String name, @NotNull Class<V> type) {
        return this.flags.getOptionalValue(name, type);
    }

    /**
     * Resolves a single-player selector argument, returning the <b>first</b> match.
     *
     * <p>Empty if the argument is absent, if the selector matched nothing, or if resolution
     * failed. Note this resolves an <i>argument</i> - for the command's sender, use
     * {@link #getSenderAsPlayer()}.
     *
     * @param name the argument name
     * @return the first resolved player, if any
     */
    @NotNull
    public Optional<Player> resolvePlayer(@NotNull String name) {
        return this.tryResolve(name, () -> {
            List<Player> resolved = this.context.getArgument(name, PlayerSelectorArgumentResolver.class)
                    .resolve(this.getSource());
            return resolved.isEmpty() ? null : resolved.getFirst();
        });
    }

    /**
     * Resolves a multi-player selector argument.
     *
     * @param name the argument name
     * @return the resolved players, if the argument was present and resolvable
     */
    @NotNull
    public Optional<List<Player>> resolvePlayers(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(this.getSource()));
    }

    /**
     * Resolves a single-entity selector argument, returning the <b>first</b> match.
     * Empty if the argument is absent, or if the selector matched nothing.
     *
     * @param name the argument name
     * @return the first resolved entity, if any
     */
    @NotNull
    public Optional<Entity> resolveEntity(@NotNull String name) {
        return this.tryResolve(name, () -> {
            List<Entity> resolved = this.context.getArgument(name, EntitySelectorArgumentResolver.class)
                    .resolve(this.getSource());
            return resolved.isEmpty() ? null : resolved.getFirst();
        });
    }

    /**
     * Resolves a multi-entity selector argument.
     *
     * @param name the argument name
     * @return the resolved entities, if the argument was present and resolvable
     */
    @NotNull
    public Optional<List<Entity>> resolveEntities(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, EntitySelectorArgumentResolver.class).resolve(this.getSource()));
    }

    /**
     * Resolves a player-profiles argument.
     *
     * @param name the argument name
     * @return the resolved player profiles, if the argument was present and resolvable
     */
    @NotNull
    public Optional<Collection<PlayerProfile>> resolvePlayerProfiles(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, PlayerProfileListResolver.class).resolve(this.getSource()));
    }

    /**
     * Resolves a block-position argument.
     *
     * @param name the argument name
     * @return the resolved {@link BlockPosition}, if the argument was present and resolvable
     */
    @NotNull
    public Optional<BlockPosition> resolveBlockPosition(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, BlockPositionResolver.class).resolve(this.getSource()));
    }

    /**
     * Resolves a fine-position argument.
     *
     * @param name the argument name
     * @return the resolved {@link FinePosition}, if the argument was present and resolvable
     */
    @NotNull
    public Optional<FinePosition> resolveFinePosition(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, FinePositionResolver.class).resolve(this.getSource()));
    }

    /**
     * Retrieves a command argument from the Brigadier context.
     *
     * @param name the argument name
     * @param type the expected type
     * @return the argument value
     */
    @NotNull
    public <U> U getArgument(@NotNull String name, @NotNull Class<U> type) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");
        return this.context.getArgument(name, type);
    }

    /**
     * Checks whether an argument with the given name was parsed into this context.
     *
     * <p>Brigadier signals both "no such argument" and "argument is of a different type"
     * with the same {@link IllegalArgumentException}, so catching it around a typed lookup
     * cannot tell an absent argument from a caller's type mistake. Probing with
     * {@code Object.class} disambiguate: {@link Class#isAssignableFrom} always succeeds for
     * {@code Object}, so the call can only fail when the argument genuinely is not present.
     *
     * @param name the argument name
     * @return {@code true} if the argument is present in this context
     */
    public boolean hasArgument(@NotNull String name) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        try {
            this.context.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Retrieves an optional command argument. Returns an empty {@link Optional}
     * if the argument was not present or its value matches a registered flag name.
     *
     * @param name the argument name
     * @param type the expected type
     * @return optional argument value
     * @throws IllegalArgumentException if the argument exists but is of a different type
     */
    @NotNull
    public <U> Optional<U> getOptionalArgument(@NotNull String name, @NotNull Class<U> type) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");
        if (!this.hasArgument(name)) {
            return Optional.empty();
        }
        U value = this.context.getArgument(name, type);
        return this.isFlagLikeValue(value) ? Optional.empty() : Optional.ofNullable(value);
    }

    /**
     * Retrieves a command argument with a fallback default. Returns
     * {@code defaultValue} if the argument was not present or its value
     * matches a registered flag name.
     *
     * @param name         the argument name
     * @param type         the expected type
     * @param defaultValue the fallback value
     * @return the argument value or {@code defaultValue}
     * @throws IllegalArgumentException if the argument exists but is of a different type
     */
    @Contract("_, _, !null -> !null")
    public <U> U getArgument(@NotNull String name, @NotNull Class<U> type, U defaultValue) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");
        Preconditions.checkNotNull(defaultValue, "Default value cannot be null");
        if (!this.hasArgument(name)) {
            return defaultValue;
        }
        U value = this.context.getArgument(name, type);
        if (this.isFlagLikeValue(value)) {
            return defaultValue;
        }
        return value != null ? value : defaultValue;
    }


    @NotNull
    private <R> Optional<R> tryResolve(@NotNull String name, @NotNull Resolver<R> resolver) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        try {
            return Optional.ofNullable(resolver.resolve());
        } catch (IllegalArgumentException | CommandSyntaxException
                 | NoSuchElementException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private boolean isFlagLikeValue(@Nullable Object value) {
        return value instanceof String str && this.matchesRegisteredFlag(str);
    }

    /**
     * @return {@code true} if {@code input} is a flag token (e.g. {@code --verbose}, {@code -v})
     *         belonging to one of this command's registered flags
     */
    private boolean matchesRegisteredFlag(@NotNull String input) {
        for (Flag<?> flag : this.registeredFlags) {
            if (flag.matches(input)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface Resolver<R> {
        R resolve() throws CommandSyntaxException;
    }

    /**
     * @return the sender as a player, or {@code null}
     * @deprecated confusable with {@link #resolvePlayer(String)}, which resolves an argument
     *         rather than the sender. Use {@link #getSenderAsPlayer()}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @Nullable
    public Player getPlayer() {
        return this.getSenderAsPlayer();
    }

    /**
     * @param name the argument name
     * @return the first resolved player, if any
     * @deprecated use {@link #resolvePlayer(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<Player> getOptionalPlayer(@NotNull String name) {
        return this.resolvePlayer(name);
    }

    /**
     * @param name the argument name
     * @return the resolved players, if any
     * @deprecated use {@link #resolvePlayers(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<List<Player>> getOptionalPlayers(@NotNull String name) {
        return this.resolvePlayers(name);
    }

    /**
     * @param name the argument name
     * @return the first resolved entity, if any
     * @deprecated use {@link #resolveEntity(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<Entity> getOptionalEntity(@NotNull String name) {
        return this.resolveEntity(name);
    }

    /**
     * @param name the argument name
     * @return the resolved entities, if any
     * @deprecated use {@link #resolveEntities(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<List<Entity>> getOptionalEntities(@NotNull String name) {
        return this.resolveEntities(name);
    }

    /**
     * @param name the argument name
     * @return the resolved player profiles, if any
     * @deprecated use {@link #resolvePlayerProfiles(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<Collection<PlayerProfile>> getOptionalPlayerProfiles(@NotNull String name) {
        return this.resolvePlayerProfiles(name);
    }

    /**
     * @param name the argument name
     * @return the resolved block position, if any
     * @deprecated use {@link #resolveBlockPosition(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<BlockPosition> getOptionalBlockPosition(@NotNull String name) {
        return this.resolveBlockPosition(name);
    }

    /**
     * @param name the argument name
     * @return the resolved fine position, if any
     * @deprecated use {@link #resolveFinePosition(String)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    public Optional<FinePosition> getOptionalFinePosition(@NotNull String name) {
        return this.resolveFinePosition(name);
    }
}