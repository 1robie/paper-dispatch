package fr.robie.paperdispatch.manager;

import fr.robie.paperdispatch.command.BaseCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public interface ICommandManager<T extends Plugin> {
    void unregisterCommand(@NotNull BaseCommand<T> command);

    void registerCommand(@NotNull BaseCommand<T> command);

    void registerCommands();
}
