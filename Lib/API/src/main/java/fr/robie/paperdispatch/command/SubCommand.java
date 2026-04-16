package fr.robie.paperdispatch.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.paperdispatch.CommandResultType;
import fr.robie.paperdispatch.requirement.CommandRequirement;
import fr.robie.paperdispatch.requirement.PlayerOnlyRequirement;
import fr.robie.paperdispatch.requirement.permissible.PermissionRequirement;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class SubCommand<T extends Plugin> {

    protected final T plugin;
    private final String name;
    private final Set<String> aliases = new HashSet<>();

    private final List<SubCommand<T>> subCommands = new ArrayList<>();
    private final List<CommandRequirement<T>> requirements = new ArrayList<>();
    private boolean requiresConfirmation = false;

    @Nullable
    private ArgumentBuilder<CommandSourceStack, ?> argumentChain = null;
    private final List<ArgumentBuilder<CommandSourceStack, ?>> optionalArguments = new ArrayList<>();

    protected SubCommand(@NotNull T plugin, @NotNull String name) {
        this.plugin = plugin;
        this.name = name;
    }

    protected SubCommand(@NotNull T plugin, @NotNull String name, @NotNull String... aliases) {
        this(plugin, name);
        this.aliases.addAll(Arrays.asList(aliases));
    }

    @NotNull
    public String getName() {
        return this.name;
    }

    @NotNull
    public Collection<String> getAliases() {
        return Collections.unmodifiableSet(this.aliases);
    }

    @NotNull
    public List<SubCommand<T>> getSubCommands() {
        return Collections.unmodifiableList(this.subCommands);
    }

    @NotNull
    public List<CommandRequirement<T>> getRequirements() {
        return Collections.unmodifiableList(this.requirements);
    }

    protected SubCommand<T> addSubCommand(@NotNull SubCommand<T> subCommand) {
        this.subCommands.add(subCommand);
        return this;
    }

    protected SubCommand<T> addRequirement(@NotNull CommandRequirement<T> requirement) {
        this.requirements.add(requirement);
        return this;
    }

    protected SubCommand<T> setPlayerOnly() {
        return this.addRequirement(new PlayerOnlyRequirement<>());
    }

    protected SubCommand<T> setPermission(@NotNull String permission) {
        return this.addRequirement(new PermissionRequirement<>(permission));
    }

    protected SubCommand<T> setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
        return this;
    }

    protected <U> void addRequiredArgument(final @NotNull String name, final @NotNull ArgumentType<U> argumentType) {
        this.addRequiredArgument(Commands.argument(name, argumentType));
    }

    protected void addRequiredArgument(@NotNull ArgumentBuilder<CommandSourceStack, ?> argument) {
        this.addRequiredArgument(argument, this::perform);
    }

    protected void addRequiredArgument(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> argument,
            @NotNull ArgumentExecutor<T> executor) {

        argument.executes(ctx -> this.wrapExecution(executor, ctx));

        if (this.argumentChain == null) {
            this.argumentChain = argument;
        } else {
            this.argumentChain.then(argument);
        }
    }

    protected <U> void addOptionalArgument(final @NotNull String name, final @NotNull ArgumentType<U> argumentType) {
        this.addOptionalArgument(Commands.argument(name, argumentType));
    }

    protected void addOptionalArgument(@NotNull ArgumentBuilder<CommandSourceStack, ?> argument) {
        this.addOptionalArgument(argument, this::perform);
    }

    protected void addOptionalArgument(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> argument,
            @NotNull ArgumentExecutor<T> executor) {

        argument.executes(ctx -> this.wrapExecution(executor, ctx));
        this.optionalArguments.add(argument);
    }

    private int wrapExecution(ArgumentExecutor<T> executor, CommandContext<CommandSourceStack> context) {
        try {
            CommandResultType result = executor.execute(this.plugin, context);
            return result == CommandResultType.FAILURE ? 0 : Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            this.plugin.getLogger().severe("Error executing command '" + this.name + "': " + e.getMessage());
            e.printStackTrace();
            return Command.SINGLE_SUCCESS;
        }
    }

    @NotNull
    protected abstract CommandResultType perform(@NotNull T plugin, CommandContext<CommandSourceStack> context);


    @NotNull
    public <U> Optional<U> getOptionalArgumentValue(@NotNull CommandContext<CommandSourceStack> context, @NotNull String argumentName, @NotNull Class<U> type) {
        try {
            return Optional.ofNullable(context.getArgument(argumentName, type));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @NotNull
    public <U> U getRequiredArgumentValue(@NotNull CommandContext<CommandSourceStack> context, @NotNull String argumentName, @NotNull Class<U> type, @NotNull U defaultValue) {
        try {
            return context.getArgument(argumentName, type);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }


    public LiteralCommandNode<CommandSourceStack> build() {
        return this.buildCommandNode(this.name, true);
    }

    public List<LiteralCommandNode<CommandSourceStack>> buildAliases() {
        return this.aliases.stream()
                .map(alias -> this.buildCommandNode(alias, false))
                .toList();
    }

    private LiteralCommandNode<CommandSourceStack> buildCommandNode(String literal, boolean includeSubCommandAliases) {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(literal);

        if (!this.requirements.isEmpty()) {
            if (this.requiresConfirmation) {
                builder.requires(Commands.restricted(source -> this.requirements.stream().anyMatch(req -> req.isMet(this.plugin, source))));
            } else {
                builder.requires(source -> this.requirements.stream().anyMatch(req -> req.isMet(this.plugin, source)));
            }
        }

        for (SubCommand<T> sub : this.subCommands) {
            builder.then(sub.build());
            if (includeSubCommandAliases) {
                sub.buildAliases().forEach(builder::then);
            }
        }

        if (this.argumentChain != null) {
            this.optionalArguments.forEach(this.argumentChain::then);
            builder.then(this.argumentChain);
        } else {
            builder.executes(ctx -> this.wrapExecution(this::perform, ctx));
            this.optionalArguments.forEach(builder::then);
        }

        return builder.build();
    }

    @FunctionalInterface
    public interface ArgumentExecutor<T extends Plugin> {
        @NotNull
        CommandResultType execute(T plugin, CommandContext<CommandSourceStack> context) throws Exception;
    }
}
