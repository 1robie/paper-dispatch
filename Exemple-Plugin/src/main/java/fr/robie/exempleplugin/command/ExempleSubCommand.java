package fr.robie.exempleplugin.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import fr.robie.paperdispatch.flag.Flags;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ExempleSubCommand extends SubCommand<ExemplePlugin> {
    protected ExempleSubCommand(@NotNull ExemplePlugin plugin) {
        super(plugin, "sub", "s");

        this.setPlayerOnly();
        this.setPermission("exempleplugin.subcommand");


        this.addRequiredArgument(Commands.argument("arg1", StringArgumentType.word()));
        this.addOptionalArgument(Commands.argument("arg2", StringArgumentType.word()).suggests((ctx, builder) -> builder.suggest("option1").suggest("option2").buildFuture()));

        this.addFlag("silent");
        this.addFlag(Flags.boolFlag("verbose").alias("v"));
        this.addFlag(Flags.intFlag("count").defaultTo(1).suggests("1", "16", "64"));
        this.addFlag(Flags.greedyStringFlag("message"));
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<ExemplePlugin> dispatch) {
        Player player = dispatch.getSenderAsPlayer();
        if (player == null) {
            dispatch.getSender().sendMessage("This command can only be run by a player.");
            return CommandResultType.FAILURE;
        }

        String arg1 = dispatch.getArgument("arg1", String.class);
        player.sendMessage("You entered the required argument: " + arg1);

        dispatch.getOptionalArgument("arg2", String.class).ifPresent(arg2 ->
                player.sendMessage("You entered the optional argument: " + arg2)
        );

        if (dispatch.hasFlag("verbose")) {
            player.sendMessage("Verbose mode enabled");
        }

        if (!dispatch.hasFlag("silent")) {
            int count = dispatch.getFlagValue("count", Integer.class, 1);
            String message = dispatch.getFlagValue("message", String.class, "default message");
            player.sendMessage("Count: " + count + ", Message: " + message);
        }

        return CommandResultType.SUCCESS;
    }
}
