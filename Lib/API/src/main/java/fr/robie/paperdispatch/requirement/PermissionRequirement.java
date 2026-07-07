package fr.robie.paperdispatch.requirement;

import com.google.common.base.Preconditions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link CommandRequirement} that checks whether the command sender has a
 * specific Bukkit permission node.
 *
 * @param <T> the plugin type
 */
public class PermissionRequirement<T extends Plugin> implements CommandRequirement<T> {
    private final String permission;

    /**
     * @param permission the Bukkit permission node to check
     */
    public PermissionRequirement(@NotNull String permission) {
        Preconditions.checkNotNull(permission, "Permission cannot be null");
        this.permission = permission;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMet(@NotNull T plugin, @NotNull CommandSourceStack source) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        Preconditions.checkNotNull(source, "CommandSourceStack cannot be null");
        return source.getSender().hasPermission(this.permission);
    }
}
