package fr.robie.paperdispatch.requirement.permissible;

import fr.robie.paperdispatch.requirement.CommandRequirement;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class PermissionRequirement<T extends Plugin> implements CommandRequirement<T> {
    private final String permission;

    public PermissionRequirement(@NotNull String permission) {
        this.permission = permission;
    }

    @Override
    public boolean isMet(@NotNull T plugin, @NotNull CommandSourceStack source) {
        return source.getSender().hasPermission(this.permission);
    }
}
