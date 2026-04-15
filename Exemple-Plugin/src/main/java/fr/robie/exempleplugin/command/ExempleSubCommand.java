package fr.robie.exempleplugin.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

public class ExempleSubCommand extends SubCommand<ExemplePlugin> {
    protected ExempleSubCommand(@NonNull ExemplePlugin plugin) {
        super(plugin, "sub", "s");

        // Only allow players to execute this command
        this.setPlayerOnly();

        // Only allow players with the "exempleplugin.subcommand" permission to execute this command
        this.setPermission("exempleplugin.subcommand");

        // Add an argument chain to this command (e.g. /exemple sub <arg1> [arg2])
        // arg1 is required, arg2 is optional
        this.addRequiredArgument(Commands.argument("arg1", StringArgumentType.word()));
        this.addOptionalArgument(Commands.argument("arg2", StringArgumentType.word()));
    }

    @Override
    protected @NotNull CommandResultType perform(@NonNull ExemplePlugin plugin, CommandContext<CommandSourceStack> context) {
        Player player = (Player) context.getSource().getSender();

        String arg1 = context.getArgument("arg1", String.class);// Get the value of the required argument "arg1"
        player.sendMessage("You entered the required argument: " + arg1);
        this.getOptionalArgumentValue(context, "arg2", String.class).ifPresent(arg2 -> {
            // Do something with the optional argument "arg2" if it is present
            player.sendMessage("You entered the optional argument: " + arg2);
        });

        return CommandResultType.SUCCESS;
    }
}
