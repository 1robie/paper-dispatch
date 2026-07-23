package fr.robie.paperdispatch;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginBase;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public class DummyPlugin extends PluginBase {

    private final Server server;
    private boolean naggable = true;

    public DummyPlugin() {
        this(null);
    }

    public DummyPlugin(Server server) {
        super();
        this.server = server;
    }

    @Override public File getDataFolder() { return new File("."); }
    @Override public PluginDescriptionFile getDescription() { return new PluginDescriptionFile("DummyPlugin", "1.0", "main"); }
    @Override public PluginMeta getPluginMeta() { return this.getDescription(); }
    @Override public FileConfiguration getConfig() { return null; }
    @Override public InputStream getResource(String filename) { return null; }
    @Override public void saveConfig() {}
    @Override public void saveDefaultConfig() {}
    @Override public void saveResource(String resourcePath, boolean replace) {}
    @Override public void reloadConfig() {}
    @Override public PluginLoader getPluginLoader() { return null; }
    @Override public Server getServer() { return this.server; }
    @Override public boolean isEnabled() { return true; }
    @Override public void onDisable() {}
    @Override public void onLoad() {}
    @Override public void onEnable() {}
    @Override public boolean isNaggable() { return this.naggable; }
    @Override public void setNaggable(boolean naggable) { this.naggable = naggable; }
    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return null; }
    @Override public BiomeProvider getDefaultBiomeProvider(String worldName, String id) { return null; }
    @Override public Logger getLogger() { return Logger.getLogger("DummyPlugin"); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { return true; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
    @Override public LifecycleEventManager<Plugin> getLifecycleManager() { return null; }
}
