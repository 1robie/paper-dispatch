package fr.robie.paperdispatch.requirement;

import com.google.common.base.Preconditions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link CommandRequirement} that restricts a command to in-game players only;
 * console and command-block senders are denied.
 *
 * @param <T> the plugin type
 */
public class PlayerOnlyRequirement<T extends Plugin> implements CommandRequirement<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMet(@NotNull T plugin, @NotNull CommandSourceStack source) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        Preconditions.checkNotNull(source, "CommandSourceStack cannot be null");
        return source.getSender() instanceof Player;
    }
}
