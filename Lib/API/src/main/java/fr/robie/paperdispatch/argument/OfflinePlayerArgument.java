package fr.robie.paperdispatch.argument;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fr.robie.paperdispatch.cache.OfflinePlayerCache;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A Brigadier {@link CustomArgumentType} that accepts a player name and
 * resolves it to a {@link UUID} via the {@link OfflinePlayerCache} global
 * instance.
 *
 * <p>This argument provides tab-completion of known player names and
 * validation against the cache's UUID ↔ name index. The cache must be
 * installed (via {@link OfflinePlayerCache#install} or
 * {@link OfflinePlayerCache#builder} + {@code build()}) for lookups and
 * suggestions to work.
 *
 * <p>If no cache is installed, {@link #convert} throws a distinct "no offline player cache is
 * installed" error and {@link #listSuggestions} returns an empty result — commands still
 * function, they simply won't complete or accept any player names.
 *
 * <p><b>Usage</b>
 * <pre>{@code
 * .addRequiredArgument("target", new OfflinePlayerArgument())
 *
 * // Custom error message
 * .addRequiredArgument("target",
 *     new OfflinePlayerArgument(new DynamicCommandExceptionType(
 *         name -> MessageComponentSerializer.message().serialize(
 *             Component.text("No player found: " + name).color(NamedTextColor.RED))
 *     )))
 * }</pre>
 *
 * @see OfflinePlayerCache
 */
public final class OfflinePlayerArgument implements CustomArgumentType.Converted<UUID, String> {

    /**
     * Raised when no {@link OfflinePlayerCache} is installed, so no name could ever resolve.
     * Shared: it carries no per-instance state.
     */
    private static final DynamicCommandExceptionType ERROR_NO_CACHE =
            new DynamicCommandExceptionType(name -> MessageComponentSerializer.message().serialize(
                    Component.text("Cannot look up '" + name + "': no offline player cache is installed.")
                            .color(NamedTextColor.DARK_RED)));

    private final DynamicCommandExceptionType errorUnknownPlayer;

    /**
     * Creates an argument with the default error message
     * {@code "Unknown player: <name>"}.
     */
    public OfflinePlayerArgument() {
        this.errorUnknownPlayer = new DynamicCommandExceptionType(name -> MessageComponentSerializer.message()
                .serialize(Component.text("Unknown player: " + name).color(NamedTextColor.DARK_RED)));
    }

    /**
     * Creates an argument with a custom error message.
     *
     * @param errorUnknownPlayer the exception type used when a name is not found
     */
    public OfflinePlayerArgument(@NotNull DynamicCommandExceptionType errorUnknownPlayer) {
        Preconditions.checkNotNull(errorUnknownPlayer, "Error message function cannot be null");
        this.errorUnknownPlayer = errorUnknownPlayer;
    }

    /**
     * Resolves a player name to its {@link UUID} using the global
     * {@link OfflinePlayerCache}.
     *
     * @param nativeType the raw player name from the command input
     * @return the corresponding UUID
     * @throws CommandSyntaxException if the name is not in the cache
     *         (or no cache is installed)
     */
    @Override
    public @NonNull UUID convert(@NonNull String nativeType) throws CommandSyntaxException {
        OfflinePlayerCache cache = OfflinePlayerCache.getGlobalInstance();
        if (cache == null) {
            throw ERROR_NO_CACHE.create(nativeType);
        }

        UUID playerId = cache.getUUID(nativeType);
        if (playerId == null) {
            throw this.errorUnknownPlayer.create(nativeType);
        }
        return playerId;
    }

    /**
     * Returns the native Brigadier type: a word string.
     *
     * @return {@link StringArgumentType#word()}
     */
    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    /**
     * Provides tab-completion suggestions from the global
     * {@link OfflinePlayerCache} name index. Returns an empty result if
     * no cache is installed.
     *
     * @param ctx     the command context
     * @param builder the suggestions builder
     * @return a future of matching player-name suggestions
     * @param <S> the command source type
     */
    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> ctx, @NonNull SuggestionsBuilder builder) {
        OfflinePlayerCache cache = OfflinePlayerCache.getGlobalInstance();
        if (cache != null) {
            return cache.suggestPlayerNames(builder);
        }
        return builder.buildFuture();
    }
}
