package fr.robie.exempleplugin.command;

import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.BaseCommand;
import fr.robie.paperdispatch.command.CommandDispatch;
import org.jetbrains.annotations.NotNull;

public class ExempleCommand extends BaseCommand<ExemplePlugin> {

    public ExempleCommand(@NotNull ExemplePlugin plugin) {
        super(plugin, "exemple", "ex");
        this.setDescription("An example command");

        this.addSubCommand(new ExempleSubCommand(plugin));
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<ExemplePlugin> dispatch) {
        dispatch.getSender().sendMessage("Hello, this is an example command!");
        return CommandResultType.SUCCESS;
    }
}
