package fr.robie.paperdispatch.requirement;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class PlayerOnlyRequirement<T extends Plugin> implements CommandRequirement<T> {
    @Override
    public boolean isMet(@NotNull T plugin, @NotNull CommandSourceStack source) {
        return source.getSender() instanceof Player;
    }
}
