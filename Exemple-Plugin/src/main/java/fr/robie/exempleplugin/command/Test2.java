package fr.robie.exempleplugin.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.CommandResultType;
import fr.robie.paperdispatch.command.BaseCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.jspecify.annotations.NonNull;

public class Test2 extends BaseCommand<ExemplePlugin> {

    public Test2(@NonNull ExemplePlugin plugin) {
        super(plugin, "test2", "t2");

        this.addOptionalArgument(Commands.argument("optionalArg", BoolArgumentType.bool()));
    }

    @Override
    protected CommandResultType perform(@NonNull ExemplePlugin plugin, CommandContext<CommandSourceStack> context) throws Exception {
        context.getSource().getSender().sendMessage("Test2 command executed!");

        try {
            Boolean optionalArg = context.getArgument("optionalArg", Boolean.class);
            context.getSource().getSender().sendMessage("Optional argument: " + optionalArg);
        } catch (IllegalArgumentException e) {
            context.getSource().getSender().sendMessage("No optional argument provided.");
        }

        return CommandResultType.SUCCESS;
    }
}
