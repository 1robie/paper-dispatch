package fr.robie.exempleplugin.command;

import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.argument.OfflinePlayerArgument;
import fr.robie.paperdispatch.cache.OfflinePlayerCache;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class PlayerLookupSubCommand extends SubCommand<ExemplePlugin> {

    public PlayerLookupSubCommand(@NotNull ExemplePlugin plugin) {
        super(plugin, "lookup");

        this.addRequiredArgument("player", new OfflinePlayerArgument());
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<ExemplePlugin> dispatch) {
        UUID playerId = dispatch.getArgument("player", UUID.class);

        OfflinePlayerCache cache = OfflinePlayerCache.getGlobalInstance();
        if (cache == null) {
            dispatch.getSender().sendMessage("Offline player cache is not installed!");
            return CommandResultType.FAILURE;
        }

        OfflinePlayer offlinePlayer = cache.get(playerId);
        String name = cache.getName(playerId);

        dispatch.getSender().sendMessage("Resolved UUID: " + playerId);
        dispatch.getSender().sendMessage("Name from Cache: " + name);
        dispatch.getSender().sendMessage("Has played before: " + offlinePlayer.hasPlayedBefore());

        return CommandResultType.SUCCESS;
    }
}
