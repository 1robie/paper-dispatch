package fr.robie.exempleplugin;

import fr.robie.exempleplugin.command.ExempleCommand;
import fr.robie.paperdispatch.cache.OfflinePlayerCache;
import fr.robie.paperdispatch.manager.CommandManager;
import fr.robie.paperdispatch.manager.ICommandManager;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public class ExemplePlugin extends JavaPlugin implements Listener {
    private final ICommandManager<ExemplePlugin> commandManager = new CommandManager<>(this);

    @Override
    public void onEnable() {
        this.getLogger().info("ExemplePlugin enabled!");

        OfflinePlayerCache.install(this);

        this.commandManager.registerCommand(new ExempleCommand(this));

        this.commandManager.registerCommands();
    }

    @Override
    public void onDisable() {
        this.getLogger().info("ExemplePlugin disabled!");
    }
}
