package fr.robie.exempleplugin.command;

import fr.robie.exempleplugin.ExemplePlugin;
import fr.robie.paperdispatch.argument.EnumArgument;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import org.jetbrains.annotations.NotNull;

public class EnumSubCommand extends SubCommand<ExemplePlugin> {

    public enum ColorMode {
        RAINBOW,
        GRADIENT,
        MONOCHROME
    }

    public EnumSubCommand(@NotNull ExemplePlugin plugin) {
        super(plugin, "color");

        this.addRequiredArgument("mode", new EnumArgument<>(ColorMode.class));
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<ExemplePlugin> dispatch) {
        ColorMode mode = dispatch.getArgument("mode", ColorMode.class);
        dispatch.getSender().sendMessage("Successfully set color mode to: " + mode.name());
        return CommandResultType.SUCCESS;
    }
}
