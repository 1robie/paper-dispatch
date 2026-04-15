package fr.robie.paperdispatch;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public interface ICommandManager<T extends Plugin> {
    void unregisterCommand(@NotNull VCommand<T> command);

    void registerCommand(@NotNull VCommand<T> command);

    void registerCommands();
}
