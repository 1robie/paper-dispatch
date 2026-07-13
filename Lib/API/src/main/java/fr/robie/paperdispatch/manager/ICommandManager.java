package fr.robie.paperdispatch.manager;

import com.google.common.base.Preconditions;
import fr.robie.paperdispatch.command.BaseCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Lifecycle contract for registering and unregistering top-level commands with
 * the Paper command framework.
 *
 * @param <T> the plugin type
 */
public interface ICommandManager<T extends Plugin> {
    /**
     * Removes a previously registered command from the server.
     *
     * @param command the command to unregister
     */
    <Y extends Plugin> void unregisterCommand(@NotNull BaseCommand<Y> command);

    /**
     * Registers a command with the server.
     *
     * @param command the command to register
     */
    <Y extends Plugin> void registerCommand(@NotNull BaseCommand<Y> command);

    /**
     * Registers a command built from a {@link BaseCommand.BaseCommandBuilder}.
     * <p>This is a convenience shortcut equivalent to:
     * {@code registerCommand(builder.build())}.
     *
     * @param builder the builder for the command to register
     * @return the constructed {@link BaseCommand}
     */
    @NotNull
    default <U extends Plugin> BaseCommand<U> registerCommand(@NotNull BaseCommand.BaseCommandBuilder<U> builder) {
        Preconditions.checkNotNull(builder, "Command builder cannot be null");
        BaseCommand<U> command = builder.build();
        this.registerCommand(command);
        return command;
    }

    /**
     * Checks if a command with the given name (or alias) is already registered
     * by the specified plugin.
     *
     * @param plugin the plugin owning the command
     * @param name   the command name or alias
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean isRegistered(@NotNull Plugin plugin, @NotNull String name);

    /**
     * Checks if the given command instance is already registered.
     *
     * @param command the command to check
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean isRegistered(@NotNull BaseCommand<?> command);

    /**
     * Bulk-registers all commands (typically called during plugin enable).
     */
    void registerCommands();

    void unregisterCommands();
}
