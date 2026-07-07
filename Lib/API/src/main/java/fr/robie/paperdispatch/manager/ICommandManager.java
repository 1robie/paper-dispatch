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
    void unregisterCommand(@NotNull BaseCommand<T> command);

    /**
     * Registers a command with the server.
     *
     * @param command the command to register
     */
    void registerCommand(@NotNull BaseCommand<T> command);

    /**
     * Registers a command built from a {@link BaseCommand.BaseCommandBuilder}.
     * <p>This is a convenience shortcut equivalent to:
     * {@code registerCommand(builder.build())}.
     *
     * @param builder the builder for the command to register
     * @return the constructed {@link BaseCommand}
     */
    @NotNull
    default BaseCommand<T> registerCommand(@NotNull BaseCommand.BaseCommandBuilder<T> builder) {
        Preconditions.checkNotNull(builder, "Command builder cannot be null");
        BaseCommand<T> command = builder.build();
        this.registerCommand(command);
        return command;
    }

    /**
     * Bulk-registers all commands (typically called during plugin enable).
     */
    void registerCommands();
}
