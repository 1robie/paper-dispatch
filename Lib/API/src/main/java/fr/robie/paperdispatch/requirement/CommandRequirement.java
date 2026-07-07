package fr.robie.paperdispatch.requirement;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * A predicate checked at command-build time to determine whether a
 * {@link io.papermc.paper.command.brigadier.CommandSourceStack} is allowed to
 * execute a command or sub-command.
 * <p>
 * Multiple requirements are combined with AND logic. Built-in implementations
 * include {@link PermissionRequirement} and {@link PlayerOnlyRequirement}.
 *
 * @param <T> the plugin type
 */
public interface CommandRequirement<T extends Plugin> {
    /**
     * Checks whether the given source satisfies this requirement.
     *
     * @param plugin the plugin instance
     * @param source the command source to check
     * @return {@code true} if the source is allowed
     */
    boolean isMet(@NotNull T plugin, @NotNull CommandSourceStack source);
}
