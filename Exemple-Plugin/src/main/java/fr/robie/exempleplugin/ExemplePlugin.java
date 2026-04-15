package fr.robie.exempleplugin;

import fr.robie.exempleplugin.command.TestCommand;
import fr.robie.paperdispatch.CommandManager;
import fr.robie.paperdispatch.ICommandManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ExemplePlugin extends JavaPlugin {
    private final ICommandManager<ExemplePlugin> commandManager = new CommandManager<>(this);

    @Override
    public void onEnable() {
        this.getLogger().info("ExemplePlugin enabled!");

        this.commandManager.registerCommand(new TestCommand(this));
        this.commandManager.registerCommand(new fr.robie.exempleplugin.command.Test2(this));

        this.commandManager.registerCommands();
    }

    @Override
    public void onDisable() {
        this.getLogger().info("ExemplePlugin disabled!");
    }
}
