package fr.robie.exempleplugin.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.BaseCommand;
import fr.robie.paperdispatch.CommandResultType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.jspecify.annotations.NonNull;

public class TestCommand extends BaseCommand<ExemplePlugin> {

    public TestCommand(@NonNull ExemplePlugin plugin) {
        super(plugin, "test", "t");

        this.addRequiredArgument(Commands.argument("args", BoolArgumentType.bool()));
        this.addOptionalArgument(Commands.argument("optionalArg", BoolArgumentType.bool()));
    }

    @Override
    protected CommandResultType perform(@NonNull ExemplePlugin plugin, CommandContext<CommandSourceStack> context) throws Exception {
//        context.getSource().getSender().sendMessage("Test command executed!");
        Boolean args = context.getArgument("args", Boolean.class);

        context.getSource().getSender().sendMessage("Test command executed with argument: " + args);

        try {
            Boolean optionalArg = context.getArgument("optionalArg", Boolean.class);
            context.getSource().getSender().sendMessage("Optional argument: " + optionalArg);
        } catch (IllegalArgumentException e) {
            context.getSource().getSender().sendMessage("No optional argument provided.");
        }

        return CommandResultType.SUCCESS;
    }
}
