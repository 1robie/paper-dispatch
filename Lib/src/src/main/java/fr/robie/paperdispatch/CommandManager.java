package fr.robie.paperdispatch;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandManager<T extends Plugin> implements ICommandManager<T> {
    private final T plugin;

    private final List<VCommand<T>> commands = new ArrayList<>();

    public CommandManager(@NotNull T plugin) {
        this.plugin = plugin;
    }

    @Override
    public void registerCommand(@NotNull VCommand<T> command) {
        this.commands.add(command);
    }

    @Override
    public void unregisterCommand(@NotNull VCommand<T> command) {
        this.commands.remove(command);
    }

    @Override
    public void registerCommands() {
        this.plugin.getLogger().info("Registering " + this.commands.size() + " commands...");
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            for (VCommand<T> command : this.commands) {
                try {
                    this.registerCommand(registrar, command);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            this.commands.clear();
        });
        this.plugin.getLogger().info("Commands registered successfully!");
    }

    private void registerCommand(Commands registrar, VCommand<T> command) {
        LiteralCommandNode<CommandSourceStack> commandNode = command.build();
        registrar.register(commandNode, command.getDescription(), command.getAliases());
    }
}
