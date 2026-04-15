package fr.robie.exempleplugin.command;

import com.mojang.brigadier.context.CommandContext;
import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.CommandResultType;
import fr.robie.paperdispatch.command.BaseCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

public class ExempleCommand extends BaseCommand<ExemplePlugin> {

    public ExempleCommand(@NonNull ExemplePlugin plugin) {
        super(plugin, "exemple", "ex");
        this.setDescription("An example command");

        this.addSubCommand(new ExempleSubCommand(plugin));
    }

    @Override
    protected @NotNull CommandResultType perform(@NonNull ExemplePlugin plugin, CommandContext<CommandSourceStack> context) {

        context.getSource().getSender().sendMessage("Hello, this is an example command!");

        return CommandResultType.SUCCESS;
    }
}
