package fr.robie.paperdispatch.requirement;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public interface CommandRequirement<T extends Plugin> {
    boolean isMet(@NotNull T plugin, @NotNull CommandSourceStack source);
}
