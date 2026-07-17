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
     * @return the sender as a player, or {@code null} if the sender is not a player
     */
    @Nullable
    public Player getPlayer() {
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
     * Delegates to {@link FlagContext#getValue(String, Class)}.
     */
    @NotNull
    public <V> V getFlagValue(@NotNull String name, @NotNull Class<V> type) {
        return this.flags.getValue(name, type);
    }

    /**
     * Delegates to {@link FlagContext#getValue(String, Class, Object)}.
     */
    @NotNull
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
     * Retrieves and resolves a single-player argument.
     * Empty if the argument is absent, or if the selector resolved to zero players.
     *
     * @param name the argument name
     * @return optional resolved player
     */
    @NotNull
    public Optional<Player> getOptionalPlayer(@NotNull String name) {
        return this.tryResolve(name, () -> {
            List<Player> resolved = this.context.getArgument(name, PlayerSelectorArgumentResolver.class)
                    .resolve(this.getSource());
            return resolved.isEmpty() ? null : resolved.getFirst();
        });
    }

    /**
     * Retrieves and resolves a multi-player argument.
     *
     * @param name the argument name
     * @return optional resolved list of players
     */
    @NotNull
    public Optional<List<Player>> getOptionalPlayers(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(this.getSource()));
    }

    /**
     * Retrieves and resolves a single-entity argument.
     * Empty if the argument is absent, or if the selector resolved to zero entities.
     *
     * @param name the argument name
     * @return optional resolved entity
     */
    @NotNull
    public Optional<Entity> getOptionalEntity(@NotNull String name) {
        return this.tryResolve(name, () -> {
            List<Entity> resolved = this.context.getArgument(name, EntitySelectorArgumentResolver.class)
                    .resolve(this.getSource());
            return resolved.isEmpty() ? null : resolved.getFirst();
        });
    }

    /**
     * Retrieves and resolves a multi-entity argument.
     *
     * @param name the argument name
     * @return optional resolved list of entities
     */
    @NotNull
    public Optional<List<Entity>> getOptionalEntities(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, EntitySelectorArgumentResolver.class).resolve(this.getSource()));
    }

    /**
     * Retrieves and resolves a player-profiles argument.
     *
     * @param name the argument name
     * @return optional resolved collection of player profiles
     */
    @NotNull
    public Optional<Collection<PlayerProfile>> getOptionalPlayerProfiles(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, PlayerProfileListResolver.class).resolve(this.getSource()));
    }

    /**
     * Retrieves and resolves a block-position argument.
     *
     * @param name the argument name
     * @return optional resolved block position (as a {@link io.papermc.paper.math.BlockPosition})
     */
    @NotNull
    public Optional<BlockPosition> getOptionalBlockPosition(@NotNull String name) {
        return this.tryResolve(name, () ->
                this.context.getArgument(name, BlockPositionResolver.class).resolve(this.getSource()));
    }

    /**
     * Retrieves and resolves a fine-position argument.
     *
     * @param name the argument name
     * @return optional resolved fine position (as a {@link io.papermc.paper.math.FinePosition})
     */
    @NotNull
    public Optional<FinePosition> getOptionalFinePosition(@NotNull String name) {
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
     * Retrieves an optional command argument. Returns an empty {@link Optional}
     * if the argument was not present or its value matches a registered flag name.
     *
     * @param name the argument name
     * @param type the expected type
     * @return optional argument value
     */
    @NotNull
    public <U> Optional<U> getOptionalArgument(@NotNull String name, @NotNull Class<U> type) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");
        try {
            U value = this.context.getArgument(name, type);
            return this.isFlagLikeValue(value) ? Optional.empty() : Optional.ofNullable(value);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Retrieves a command argument with a fallback default. Returns
     * {@code defaultValue} if the argument was not present or its value
     * matches a registered flag name.
     *
     * <p>Note: this only catches {@link IllegalArgumentException} (argument absent).
     * A {@link ClassCastException} from a genuine type mismatch is intentionally
     * allowed to propagate rather than being masked by the default value.
     *
     * @param name         the argument name
     * @param type         the expected type
     * @param defaultValue the fallback value
     * @return the argument value or {@code defaultValue}
     */
    @Contract("_, _, !null -> !null")
    public <U> U getArgument(@NotNull String name, @NotNull Class<U> type, U defaultValue) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");
        Preconditions.checkNotNull(defaultValue, "Default value cannot be null");
        try {
            U value = this.context.getArgument(name, type);
            if (this.isFlagLikeValue(value)) {
                return defaultValue;
            }
            return value != null ? value : defaultValue;
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
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
        return value instanceof String str && this.isMatchingFlag(str);
    }

    private boolean isMatchingFlag(@NotNull String input) {
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
}