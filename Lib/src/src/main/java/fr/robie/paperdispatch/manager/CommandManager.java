package fr.robie.paperdispatch.manager;

import fr.robie.paperdispatch.command.BaseCommand;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandManager<T extends Plugin> implements ICommandManager<T> {
    private final T plugin;

    private final List<BaseCommand<T>> commands = new ArrayList<>();

    public CommandManager(@NotNull T plugin) {
        this.plugin = plugin;
    }

    @Override
    public void registerCommand(@NotNull BaseCommand<T> command) {
        this.commands.add(command);
    }

    @Override
    public void unregisterCommand(@NotNull BaseCommand<T> command) {
        this.commands.remove(command);
    }

    @Override
    public void registerCommands() {
        this.plugin.getLogger().info("Registering " + this.commands.size() + " commands...");
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            for (BaseCommand<T> command : this.commands) {
                try {
                    registrar.register(command.build(), command.getDescription(), command.getAliases());
                } catch (Exception e) {
                    this.plugin.getLogger().severe("Failed to register command: " + command.getName());
                    e.printStackTrace();
                }
            }
            this.commands.clear();
        });
        this.plugin.getLogger().info("Commands registered successfully!");
    }
}
