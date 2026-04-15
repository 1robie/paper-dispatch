package fr.robie.paperdispatch;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.paperdispatch.requirement.CommandRequirement;
import fr.robie.paperdispatch.requirement.PlayerOnlyRequirement;
import fr.robie.paperdispatch.requirement.permissible.PermissionRequirement;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class VCommand<T extends Plugin> {

    private final T plugin;
    private final String name;
    private final Set<String> aliases = new HashSet<>();

    @Nullable
    private String description = null;

    private final List<@NotNull VCommand<T>> subCommands = new ArrayList<>();
    private final List<@NotNull CommandRequirement<T>> requirements = new ArrayList<>();

    @Nullable
    private ArgumentBuilder<CommandSourceStack, ?> argumentChain = null;

    private final List<ArgumentBuilder<CommandSourceStack, ?>> optionalArguments = new ArrayList<>();

    protected VCommand(@NotNull T plugin, @NotNull String name) {
        this.plugin = plugin;
        this.name = name;
    }

    protected VCommand(@NotNull T plugin, @NotNull String name, @NotNull String... aliases) {
        this.plugin = plugin;
        this.name = name;
        this.aliases.addAll(Arrays.asList(aliases));
    }


    @NotNull
    public String getName() {
        return this.name;
    }

    @Nullable
    public String getDescription() {
        return this.description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    @NotNull
    public List<VCommand<T>> getSubCommands() {
        return Collections.unmodifiableList(this.subCommands);
    }

    @NotNull
    public List<CommandRequirement<T>> getRequirements() {
        return Collections.unmodifiableList(this.requirements);
    }


    protected void addSubCommand(@NotNull VCommand<T> subCommand) {
        this.subCommands.add(subCommand);
    }

    protected void addRequirement(@NotNull CommandRequirement<T> requirement) {
        this.requirements.add(requirement);
    }

    protected void setPlayerOnly() {
        this.addRequirement(new PlayerOnlyRequirement<>());
    }

    protected void setPermissionRequired(@NotNull String permission) {
        this.addRequirement(new PermissionRequirement<>(permission));
    }

    protected void addRequiredArgument(@NotNull ArgumentBuilder<CommandSourceStack, ?> argument) {
        this.addRequiredArgument(argument, this::perform);
    }

    protected void addRequiredArgument(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> argument,
            @NotNull ArgumentExecutor<T> executor) {

        argument.executes(context -> {
            CommandResultType result;
            try {
                result = executor.execute(this.plugin, context);
            } catch (Exception e) {
                this.plugin.getLogger().severe(
                        "Error in argument executor for '" + this.name + "': " + e.getMessage());
                e.printStackTrace();
                return Command.SINGLE_SUCCESS;
            }

            switch (result) {
                case SUCCESS:
                    return Command.SINGLE_SUCCESS;
                case FAILURE:
                    return 0;
                default:
                    return Command.SINGLE_SUCCESS;
            }
        });

        if (this.argumentChain == null) {
            this.argumentChain = argument;
        } else {
            this.argumentChain.then(argument);
        }
    }


    protected void addOptionalArgument(@NotNull ArgumentBuilder<CommandSourceStack, ?> argument) {
        this.addOptionalArgument(argument, this::perform);
    }

    protected void addOptionalArgument(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> argument,
            @NotNull ArgumentExecutor<T> executor) {
        argument.executes(context -> {
            CommandResultType result;
            try {
                result = executor.execute(this.plugin, context);
            } catch (Exception e) {
                this.plugin.getLogger().severe(
                        "Error in optional argument executor for '" + this.name + "': " + e.getMessage());
                e.printStackTrace();
                return Command.SINGLE_SUCCESS;
            }

            switch (result) {
                case SUCCESS:
                    return Command.SINGLE_SUCCESS;
                case FAILURE:
                    return 0;
                default:
                    return Command.SINGLE_SUCCESS;
            }
        });

        this.optionalArguments.add(argument);
    }

    protected abstract CommandResultType perform(@NotNull T plugin, CommandContext<CommandSourceStack> context);

    public LiteralCommandNode<CommandSourceStack> build() {
        return this.buildCommandNode(this.name, true);
    }

    public List<LiteralCommandNode<CommandSourceStack>> buildAliases() {
        if (this.aliases.isEmpty()) {
            return Collections.emptyList();
        }
        List<LiteralCommandNode<CommandSourceStack>> nodes = new ArrayList<>();
        for (String alias : this.aliases) {
            nodes.add(this.buildCommandNode(alias, false));
        }
        return nodes;
    }

    private LiteralCommandNode<CommandSourceStack> buildCommandNode(
            String literal,
            boolean includeSubCommandAliases) {

        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(literal);

        if (!this.requirements.isEmpty()) {
            builder.requires(source -> {
                for (CommandRequirement<T> req : this.requirements) {
                    if (!req.isMet(this.plugin, source)) {
                        return false;
                    }
                }
                return true;
            });
        }


        for (VCommand<T> sub : this.subCommands) {
            if (includeSubCommandAliases) {
                sub.buildAliases().forEach(builder::then);
            }
            builder.then(sub.build());
        }

        if (this.argumentChain != null) {
            for (ArgumentBuilder<CommandSourceStack, ?> optionalArg : this.optionalArguments) {
                this.argumentChain.then(optionalArg);
            }
            builder.then(this.argumentChain);
        } else {
            builder.executes(context -> {
                CommandResultType result;
                try {
                    result = this.perform(this.plugin, context);
                } catch (Exception e) {
                    this.plugin.getLogger().severe("Error executing command '" + this.name + "': " + e.getMessage());
                    e.printStackTrace();
                    return Command.SINGLE_SUCCESS;
                }

                switch (result) {
                    case SUCCESS:
                        return Command.SINGLE_SUCCESS;
                    case FAILURE:
                        return 0;
                    default:
                        return Command.SINGLE_SUCCESS;
                }
            });
            for (ArgumentBuilder<CommandSourceStack, ?> optionalArg : this.optionalArguments) {
                builder.then(optionalArg);
            }
        }

        return builder.build();
    }

    public Collection<String> getAliases() {
        return Collections.unmodifiableSet(this.aliases);
    }


    @FunctionalInterface
    public interface ArgumentExecutor<T extends Plugin> {
        CommandResultType execute(T plugin, CommandContext<CommandSourceStack> context);
    }
}