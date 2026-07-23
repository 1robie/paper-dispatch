package fr.robie.paperdispatch.command;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.paperdispatch.flag.Flag;
import fr.robie.paperdispatch.flag.FlagContext;
import fr.robie.paperdispatch.flag.Flags;
import fr.robie.paperdispatch.requirement.CommandRequirement;
import fr.robie.paperdispatch.requirement.PermissionRequirement;
import fr.robie.paperdispatch.requirement.PlayerOnlyRequirement;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Abstract base for a command or sub-command. Supports:
 * <ul>
 *   <li>Nested sub-commands via {@link #addSubCommand}</li>
 *   <li>Named arguments (required / optional) via {@link #addRequiredArgument}
 *       and {@link #addOptionalArgument}</li>
 *   <li>Flags ({@code --option}) via {@link #addFlag} / {@link #addFlags}</li>
 *   <li>Requirements ({@link #addRequirement}, {@link #setPermission},
 *       {@link #setPlayerOnly})</li>
 * </ul>
 * Call {@link #build()} to produce the Brigadier tree.
 * <p>
 * <b>Warning — flag complexity:</b> The flag-tree builder generates a full
 * permutation of all flag orderings via recursive branching, producing
 * <b>O(n!)</b> nodes in the Brigadier tree where {@code n} is the number of
 * flags (aliases increase the constant factor). Because this happens at every
 * nesting level, and independently again for every alias of every command in
 * the tree (each alias rebuilds its own subtree), the real blow-up across a
 * deeply nested command with many aliases is closer to
 * {@code O(n! × Π aliasCounts)} summed over the whole tree. This is negligible
 * for small flag sets (n &le; 5) with few aliases, but adding a couple of
 * aliases to a command with 4-5 flags multiplies cost noticeably. Prefer few
 * flags, few aliases on flag-heavy commands, or use the generic
 * {@link fr.robie.paperdispatch.flag.Flags#argFlag(String, com.mojang.brigadier.arguments.ArgumentType)}
 * to keep the flag count low.
 * <p>
 * <b>Thread-safety:</b> instances of this class are not thread-safe. All
 * mutator methods ({@link #addFlag}, {@link #addFlags}, {@link #addSubCommand},
 * {@link #addRequirement}, {@link #addRequiredArgument}, {@link #addOptionalArgument},
 * etc.) are intended to be called once, single-threaded, during plugin
 * initialization, before {@link #build()} is ever invoked. Calling them
 * concurrently, or after the command has been registered, is not supported.
 *
 * @param <T> the plugin type
 */
public abstract class SubCommand<T extends Plugin> {

    @NotNull
    protected String flagValuePrefix = "flag$";

    protected final T plugin;
    private final String name;
    private final Set<String> aliases = new HashSet<>();

    private final List<SubCommand<T>> subCommands = new ArrayList<>();
    private final List<CommandRequirement<T>> requirements = new ArrayList<>();
    private boolean requiresConfirmation = false;

    private final List<Flag<?>> flags = new ArrayList<>();
    private final List<ExecutableNode<T>> executableNodes = new ArrayList<>();

    private final List<ArgumentBuilder<CommandSourceStack, ?>> requiredArguments = new ArrayList<>();
    private final List<ArgumentBuilder<CommandSourceStack, ?>> optionalArguments = new ArrayList<>();

    /**
     * @param plugin the owning plugin
     * @param name   the command name
     */
    protected SubCommand(@NotNull T plugin, @NotNull String name) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        Preconditions.checkNotNull(name, "Command name cannot be null");
        this.plugin = plugin;
        this.name = name;
    }

    /**
     * @param plugin  the owning plugin
     * @param name    the command name
     * @param aliases alternative names for this command
     */
    protected SubCommand(@NotNull T plugin, @NotNull String name, @NotNull String... aliases) {
        this(plugin, name);
        Preconditions.checkNotNull(aliases, "Aliases cannot be null");
        this.aliases.addAll(Arrays.asList(aliases));
    }

    /**
     * @return the owning plugin
     */
    @NotNull
    public T getPlugin() {
        return this.plugin;
    }

    /**
     * @return the command name
     */
    @NotNull
    public String getName() {
        return this.name;
    }

    /**
     * @return an immutable snapshot of the aliases at the time of calling
     */
    @NotNull
    public Collection<String> getAliases() {
        return Set.copyOf(this.aliases);
    }

    /**
     * @return an immutable snapshot of nested sub-commands at the time of calling
     */
    @NotNull
    public List<SubCommand<T>> getSubCommands() {
        return List.copyOf(this.subCommands);
    }

    /**
     * @return an immutable snapshot of requirements at the time of calling
     */
    @NotNull
    public List<CommandRequirement<T>> getRequirements() {
        return List.copyOf(this.requirements);
    }

    /**
     * @return an immutable snapshot of registered flags at the time of calling
     */
    @NotNull
    public List<Flag<?>> getFlags() {
        return List.copyOf(this.flags);
    }

    /**
     * Registers a nested sub-command.
     *
     * @param subCommand the sub-command to add
     * @return this instance for chaining
     */
    protected SubCommand<T> addSubCommand(@NotNull SubCommand<T> subCommand) {
        Preconditions.checkNotNull(subCommand, "SubCommand cannot be null");
        this.subCommands.add(subCommand);
        return this;
    }

    /**
     * Registers a requirement that must be met for this command to be executable.
     *
     * @param requirement the requirement to add
     * @return this instance for chaining
     */
    protected SubCommand<T> addRequirement(@NotNull CommandRequirement<T> requirement) {
        Preconditions.checkNotNull(requirement, "CommandRequirement cannot be null");
        this.requirements.add(requirement);
        return this;
    }

    /**
     * Convenience shortcut to restrict this command to in-game players only.
     *
     * @return this instance for chaining
     */
    protected SubCommand<T> setPlayerOnly() {
        return this.addRequirement(new PlayerOnlyRequirement<>());
    }

    /**
     * Convenience shortcut to require a specific Bukkit permission node.
     *
     * @param permission the permission node
     * @return this instance for chaining
     */
    protected SubCommand<T> setPermission(@NotNull String permission) {
        Preconditions.checkNotNull(permission, "Permission cannot be null");
        return this.addRequirement(new PermissionRequirement<>(permission));
    }

    /**
     * Sets whether this command requires explicit confirmation before execution.
     *
     * @param requiresConfirmation {@code true} if confirmation is required
     * @return this instance for chaining
     */
    protected SubCommand<T> setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
        return this;
    }

    /**
     * Registers a pre-built flag.
     *
     * @param flag the flag to add
     * @return this instance for chaining
     * @throws IllegalArgumentException if the flag name or alias collides with an existing flag
     */
    protected SubCommand<T> addFlag(@NotNull Flag<?> flag) {
        Preconditions.checkNotNull(flag, "Flag cannot be null");
        this.checkNoCollision(flag);
        this.flags.add(flag);
        return this;
    }

    /**
     * Creates and registers a boolean flag with the given name. Delegates to
     * {@link #addFlag(Flag)} so subclasses only need to override that one method
     * to intercept every flag-registration path (collision checks, logging, etc.).
     *
     * @param name the flag name
     * @return this instance for chaining
     * @throws IllegalArgumentException if the flag name collides with an existing flag
     */
    protected SubCommand<T> addFlag(@NotNull String name) {
        Preconditions.checkNotNull(name, "Flag name cannot be null");
        return this.addFlag(Flags.boolFlag(name));
    }

    /**
     * Registers multiple flags at once.
     *
     * @param flags the flags to add
     * @return this instance for chaining
     */
    protected SubCommand<T> addFlags(@NotNull Flag<?>... flags) {
        Preconditions.checkNotNull(flags, "Flags cannot be null");
        for (Flag<?> flag : flags) {
            this.addFlag(flag);
        }
        return this;
    }

    private void checkNoCollision(@NotNull Flag<?> flag) {
        for (Flag<?> existing : this.flags) {
            if (existing.getName().equals(flag.getName())) {
                throw new IllegalArgumentException("Flag name '" + Flag.toFlagToken(flag.getName()) + "' conflicts with existing flag '" + Flag.toFlagToken(existing.getName()) + "'");
            }
            for (String newAlias : flag.getAliases()) {
                if (existing.getName().equals(newAlias) || existing.getAliases().contains(newAlias)) {
                    throw new IllegalArgumentException("Flag alias '" + Flag.toFlagToken(newAlias) + "' conflicts with existing flag '" + Flag.toFlagToken(existing.getName()) + "'");
                }
            }
            for (String existingAlias : existing.getAliases()) {
                if (flag.getName().equals(existingAlias)) {
                    throw new IllegalArgumentException("Flag name '" + Flag.toFlagToken(flag.getName()) + "' conflicts with existing alias '" + Flag.toFlagToken(existingAlias) + "'");
                }
            }
        }
    }

    /**
     * Adds a required (positional) argument with the given name and type.
     *
     * @param name         the argument name
     * @param argumentType the Brigadier argument type
     * @param <U>          the argument value type
     * @throws IllegalArgumentException if {@code name} starts with the reserved
     *                                   {@link #flagValuePrefix}, which would collide with the
     *                                   synthetic argument names generated internally for value flags
     */
    protected <U> void addRequiredArgument(final @NotNull String name, final @NotNull ArgumentType<U> argumentType) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(argumentType, "Argument type cannot be null");
        this.checkNotReservedName(name);
        this.addRequiredArgument(Commands.argument(name, this.wrapIfFlagAware(argumentType)));
    }

    /**
     * Adds a required argument from a pre-built Brigadier builder.
     *
     * @param argument the argument builder
     */
    protected void addRequiredArgument(@NotNull ArgumentBuilder<CommandSourceStack, ?> argument) {
        Preconditions.checkNotNull(argument, "Argument cannot be null");
        argument = this.wrapBuilderType(argument);
        this.addRequiredArgument(argument, this::perform);
    }

    /**
     * Adds a required argument with a custom executor.
     *
     * @param argument the argument builder
     * @param executor the executor to run when this argument path is matched
     */
    protected void addRequiredArgument(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> argument,
            @NotNull ArgumentExecutor<T> executor) {

        argument.executes(ctx -> this.executeWithFlags(executor, ctx));
        this.executableNodes.add(new ExecutableNode<>(argument, executor));
        this.requiredArguments.add(argument);
    }

    /**
     * Adds an optional argument with the given name and type.
     *
     * @param name         the argument name
     * @param argumentType the Brigadier argument type
     * @param <U>          the argument value type
     * @throws IllegalArgumentException if {@code name} starts with the reserved
     *                                   {@link #flagValuePrefix}, which would collide with the
     *                                   synthetic argument names generated internally for value flags
     */
    protected <U> void addOptionalArgument(final @NotNull String name, final @NotNull ArgumentType<U> argumentType) {
        Preconditions.checkNotNull(name, "Argument name cannot be null");
        Preconditions.checkNotNull(argumentType, "Argument type cannot be null");
        this.checkNotReservedName(name);
        this.addOptionalArgument(Commands.argument(name, this.wrapIfFlagAware(argumentType)));
    }

    /**
     * Adds an optional argument from a pre-built Brigadier builder.
     *
     * @param argument the argument builder
     */
    protected void addOptionalArgument(@NotNull ArgumentBuilder<CommandSourceStack, ?> argument) {
        Preconditions.checkNotNull(argument, "Argument cannot be null");
        argument = this.wrapBuilderType(argument);
        this.addOptionalArgument(argument, this::perform);
    }

    /**
     * Adds an optional argument with a custom executor.
     *
     * @param argument the argument builder
     * @param executor the executor to run when this argument path is matched
     */
    protected void addOptionalArgument(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> argument,
            @NotNull ArgumentExecutor<T> executor) {

        argument.executes(ctx -> this.executeWithFlags(executor, ctx));
        this.executableNodes.add(new ExecutableNode<>(argument, executor));
        this.optionalArguments.add(argument);
    }

    private void checkNotReservedName(@NotNull String name) {
        Preconditions.checkArgument(
                !name.startsWith(this.flagValuePrefix),
                "Argument name '%s' collides with the reserved flag-value prefix '%s' "
                        + "used internally for value-flag arguments; choose a different name "
                        + "or change the prefix via setFlagValuePrefix(...)",
                name, this.flagValuePrefix
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentBuilder<CommandSourceStack, ?> wrapBuilderType(ArgumentBuilder<CommandSourceStack, ?> builder) {
        if (!(builder instanceof RequiredArgumentBuilder argBuilder)) return builder;

        ArgumentType<?> type = argBuilder.getType();
        if (!(type instanceof StringArgumentType stringType)) return builder;

        RequiredArgumentBuilder<CommandSourceStack, Object> newBuilder = (RequiredArgumentBuilder) Commands.argument(
                argBuilder.getName(), new FlagAwareStringType(stringType, this.flags)
        );
        if (argBuilder.getCommand() != null) {
            newBuilder.executes(argBuilder.getCommand());
        }
        if (argBuilder.getRequirement() != null) {
            newBuilder.requires(argBuilder.getRequirement());
        }
        if (argBuilder.getSuggestionsProvider() != null) {
            newBuilder.suggests(argBuilder.getSuggestionsProvider());
        }
        return newBuilder;
    }

    @SuppressWarnings("unchecked")
    private <U> ArgumentType<U> wrapIfFlagAware(@NotNull ArgumentType<U> type) {
        if (type instanceof StringArgumentType stringType) {
            return (ArgumentType<U>) new FlagAwareStringType(stringType, this.flags);
        }
        return type;
    }

    /**
     * Implement this method to define the command's behavior. The returned
     * {@link CommandResultType} determines the exit code sent to the caller.
     *
     * @param dispatch the command dispatch context
     * @return the result type
     */
    @NotNull
    protected abstract CommandResultType perform(@NotNull CommandDispatch<T> dispatch);

    private int executeWithFlags(ArgumentExecutor<T> executor, CommandContext<CommandSourceStack> context) {
        try {
            FlagContext flagCtx = this.buildFlagContext(context);
            CommandDispatch<T> dispatch = new CommandDispatch<>(this.plugin, context, flagCtx, this.flags);
            CommandResultType result = executor.execute(dispatch);
            return result == CommandResultType.FAILURE ? 0 : Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error executing command '" + this.name + "'", e);
            return 0;
        }
    }

    private FlagContext buildFlagContext(CommandContext<CommandSourceStack> context) {
        if (this.flags.isEmpty()) {
            return FlagContext.empty();
        }

        Set<String> matchedNodes = context.getNodes().stream()
                .map(node -> node.getNode().getName())
                .collect(Collectors.toSet());

        Set<String> explicit = new HashSet<>();
        Map<String, Object> values = new HashMap<>();

        for (Flag<?> flag : this.flags) {
            boolean present = this.flagTokens(flag).stream().anyMatch(matchedNodes::contains);
            if (present) {
                explicit.add(flag.getName());
                values.put(flag.getName(), flag.isBoolFlag()
                        ? true
                        : context.getArgument(this.flagValuePrefix + flag.getName(), Object.class));
            } else if (flag.hasDefaultValue()) {
                values.put(flag.getName(), flag.getDefaultValue());
            }
        }

        return new FlagContext(values, explicit);
    }

    /**
     * Returns every literal token (primary name + aliases) that can trigger this flag,
     * consistently formatted via {@link Flag#toFlagToken(String)} for both the name and
     * its aliases so the actual registered tokens always match what collision-error
     * messages describe.
     */
    @NotNull
    private List<String> flagTokens(@NotNull Flag<?> flag) {
        List<String> tokens = new ArrayList<>();
        tokens.add(Flag.toFlagToken(flag.getName()));
        for (String alias : flag.getAliases()) {
            tokens.add(Flag.toFlagToken(alias));
        }
        return tokens;
    }

    /**
     * Retrieves an optional argument value from the command context.
     *
     * @param context      the Brigadier command context
     * @param argumentName the argument name
     * @param type         the expected type
     * @return optional argument value
     * @param <U> the argument value type
     * @deprecated Use {@link CommandDispatch#getOptionalArgument(String, Class)} instead.
     */
    @Deprecated
    @NotNull
    public <U> Optional<U> getOptionalArgumentValue(@NotNull CommandContext<CommandSourceStack> context, @NotNull String argumentName, @NotNull Class<U> type) {
        Preconditions.checkNotNull(context, "CommandContext cannot be null");
        Preconditions.checkNotNull(argumentName, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");

        try {
            return Optional.ofNullable(context.getArgument(argumentName, type));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Retrieves a required argument value from the command context, falling back
     * to a default if the argument is not present.
     *
     * @param context      the Brigadier command context
     * @param argumentName the argument name
     * @param type         the expected type
     * @param defaultValue the fallback value
     * @return the argument value or {@code defaultValue}
     * @param <U> the argument value type
     * @deprecated Use {@link CommandDispatch#getArgument(String, Class, Object)} instead.
     */
    @NotNull
    @Deprecated
    public <U> U getRequiredArgumentValue(@NotNull CommandContext<CommandSourceStack> context, @NotNull String argumentName, @NotNull Class<U> type, @NotNull U defaultValue) {
        Preconditions.checkNotNull(context, "CommandContext cannot be null");
        Preconditions.checkNotNull(argumentName, "Argument name cannot be null");
        Preconditions.checkNotNull(type, "Argument type cannot be null");
        Preconditions.checkNotNull(defaultValue, "Default value cannot be null");

        try {
            return context.getArgument(argumentName, type);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    /**
     * Builds the Brigadier command tree rooted at this sub-command's literal node.
     *
     * @return the built command node
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return this.buildCommandNode(this.name, true);
    }

    private List<LiteralCommandNode<CommandSourceStack>> buildAliases() {
        return this.aliases.stream()
                .map(alias -> this.buildCommandNode(alias, false))
                .toList();
    }

    private LiteralCommandNode<CommandSourceStack> buildCommandNode(String literal, boolean includeSubCommandAliases) {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(literal);

        if (!this.requirements.isEmpty()) {
            if (this.requiresConfirmation) {
                builder.requires(Commands.restricted(source -> this.requirements.stream().allMatch(req -> req.isMet(this.plugin, source))));
            } else {
                builder.requires(source -> this.requirements.stream().allMatch(req -> req.isMet(this.plugin, source)));
            }
        }

        for (SubCommand<T> sub : this.subCommands) {
            builder.then(sub.build());
            if (includeSubCommandAliases) {
                sub.buildAliases().forEach(builder::then);
            }
        }

        if (this.requiredArguments.isEmpty() && this.optionalArguments.isEmpty()) {
            builder.executes(ctx -> this.executeWithFlags(this::perform, ctx));
            this.attachFlagNodesToBuilder(builder);
            return builder.build();
        }

        // Attach flag branches to every argument-level builder BEFORE any of them are
        // chained together via `.then()`. Brigadier's ArgumentBuilder#then(child) snapshots
        // the child immediately into an immutable CommandNode, so flags (or further children)
        // added to a builder *after* it has already been `.then()`'d onto something else would
        // silently be lost.
        this.attachFlagNodes();

        // Build the optional-argument chain tail-first, so each optional argument is sequential.
        ArgumentBuilder<CommandSourceStack, ?> optionalChain = null;
        if (!this.optionalArguments.isEmpty()) {
            for (int i = this.optionalArguments.size() - 1; i >= 0; i--) {
                ArgumentBuilder<CommandSourceStack, ?> current = this.optionalArguments.get(i);
                if (optionalChain != null) {
                    current.then(optionalChain);
                }
                optionalChain = current;
            }
        }

        if (this.requiredArguments.isEmpty()) {
            // No required arguments: the base literal itself is executable and flag-capable
            // (e.g. `/cmd --flag`), with optional arguments - each already flag-capable in
            // their own right - branching directly off it.
            builder.executes(ctx -> this.executeWithFlags(this::perform, ctx));
            this.attachFlagNodesToBuilder(builder);
            if (optionalChain != null) {
                builder.then(optionalChain);
            }
            return builder.build();
        }

        // Build the required-argument chain tail-first, so each node's own children (its flag
        // branches and, at the very tail, the optional arguments) are fully attached before that
        // node itself gets snapshot as a child of the previous node in the chain.
        ArgumentBuilder<CommandSourceStack, ?> tail = null;
        for (int i = this.requiredArguments.size() - 1; i >= 0; i--) {
            ArgumentBuilder<CommandSourceStack, ?> current = this.requiredArguments.get(i);
            if (tail == null) {
                if (optionalChain != null) {
                    current.then(optionalChain);
                }
            } else {
                current.then(tail);
            }
            tail = current;
        }

        builder.then(tail);
        return builder.build();
    }

    private void attachFlagNodes() {
        if (this.flags.isEmpty()) return;

        for (ExecutableNode<T> execNode : this.executableNodes) {
            this.attachFlagBranches(execNode.builder, execNode.executor);
        }
    }

    private void attachFlagNodesToBuilder(LiteralArgumentBuilder<CommandSourceStack> builder) {
        if (this.flags.isEmpty()) return;
        this.attachFlagBranches(builder, this::perform);
    }

    /**
     * Attaches one real brigadier literal node per flag (name + aliases) as a child of
     * {@code parent}, each branching recursively into every other not-yet-used flag so that
     * flags can be supplied in any order/subset. Value flags get a genuine typed argument
     * node (e.g. {@code IntegerArgumentType}) so the client shows native type hints,
     * live validation, and suggestions - exactly like vanilla Minecraft commands.
     */
    private void attachFlagBranches(@NotNull ArgumentBuilder<CommandSourceStack, ?> parent, @NotNull ArgumentExecutor<T> executor) {
        if (this.flags.isEmpty()) return;

        for (Flag<?> flag : this.flags) {
            List<Flag<?>> remaining = new ArrayList<>(this.flags);
            remaining.remove(flag);
            for (String token : this.flagTokens(flag)) {
                parent.then(this.buildFlagNode(token, flag, remaining, executor));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentBuilder<CommandSourceStack, ?> buildFlagNode(
            @NotNull String token,
            @NotNull Flag<?> flag,
            @NotNull List<Flag<?>> remaining,
            @NotNull ArgumentExecutor<T> executor) {

        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(token);

        if (flag.isBoolFlag()) {
            literal.executes(ctx -> this.executeWithFlags(executor, ctx));
            this.attachRemainingFlags(literal, remaining, executor);
            return literal;
        }

        RequiredArgumentBuilder valueArg = Commands.argument(
                this.flagValuePrefix + flag.getName(), flag.getArgumentType()
        );
        valueArg.executes(ctx -> this.executeWithFlags(executor, ctx));
        if (flag.hasSuggestions()) {
            valueArg.suggests(flag.getSuggestionProvider());
        }

        // IMPORTANT: ArgumentBuilder#then() calls build() immediately and stores an
        // immutable snapshot of the child. So valueArg's own children MUST be attached
        // before it is handed to literal.then(valueArg) below - otherwise they'd silently
        // be dropped and no flag could ever follow a value flag (e.g. "--count 5 --verbose").
        this.attachRemainingFlags(valueArg, remaining, executor);
        literal.then(valueArg);

        return literal;
    }

    private void attachRemainingFlags(
            @NotNull ArgumentBuilder<CommandSourceStack, ?> tail,
            @NotNull List<Flag<?>> remaining,
            @NotNull ArgumentExecutor<T> executor) {

        for (Flag<?> next : remaining) {
            List<Flag<?>> nextRemaining = new ArrayList<>(remaining);
            nextRemaining.remove(next);
            for (String nextToken : this.flagTokens(next)) {
                tail.then(this.buildFlagNode(nextToken, next, nextRemaining, executor));
            }
        }
    }

    /**
     * Functional interface for command execution.
     *
     * @param <T> the plugin type
     */
    @FunctionalInterface
    public interface ArgumentExecutor<T extends Plugin> {
        /**
         * Executes the command.
         *
         * @param dispatch the command dispatch context
         * @return the result type
         * @throws Exception if execution fails
         */
        @NotNull
        CommandResultType execute(CommandDispatch<T> dispatch) throws Exception;
    }

    private record ExecutableNode<T extends Plugin>(
            ArgumentBuilder<CommandSourceStack, ?> builder,
            ArgumentExecutor<T> executor
    ) {}

    private record FlagAwareStringType(ArgumentType<String> delegate,
                                       List<Flag<?>> flags) implements CustomArgumentType.Converted<String, String> {

        /**
         * Rejects input that matches a registered flag name, allowing the remaining
         * arguments to fall through to the flag-parsing tree.
         */
        @Override
        public @NotNull String convert(@NotNull String nativeValue) throws CommandSyntaxException {
            for (Flag<?> flag : this.flags) {
                if (flag.matches(nativeValue)) {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
                }
            }
            return nativeValue;
        }

        @Override
        public @NotNull ArgumentType<String> getNativeType() {
            return this.delegate;
        }

        @Override
        public <S> @NotNull CompletableFuture<Suggestions> listSuggestions(@NotNull CommandContext<S> context, @NotNull SuggestionsBuilder builder) {
            return this.delegate.listSuggestions(context, builder);
        }
    }

    /**
     * Returns the prefix used internally for flag-value argument names in the
     * Brigadier tree. The default is {@code "flag$"}.
     */
    @NotNull
    public String getFlagValuePrefix() {
        return this.flagValuePrefix;
    }

    /**
     * Sets the prefix used internally for flag-value argument names.
     *
     * @param flagValuePrefix the new prefix (must not be null)
     */
    protected void setFlagValuePrefix(@NotNull String flagValuePrefix) {
        Preconditions.checkNotNull(flagValuePrefix, "flagValuePrefix cannot be null");
        this.flagValuePrefix = flagValuePrefix;
    }

    /**
     * Creates a new {@link SubCommandBuilder} for constructing a {@link SubCommand}
     * without subclassing.
     *
     * @param plugin the owning plugin
     * @param name   the command name
     * @return a new builder
     * @param <T> the plugin type
     */
    public static <T extends Plugin> SubCommandBuilder<T> builder(@NotNull T plugin, @NotNull String name) {
        return new SubCommandBuilder<>(plugin, name);
    }

    /**
     * Builder for creating a {@link SubCommand} without subclassing.
     * Use {@link SubCommand#builder(Plugin, String)} to obtain an instance.
     * For commands that need a description, use
     * {@link BaseCommand#builder(Plugin, String)} which returns a
     * {@link fr.robie.paperdispatch.command.BaseCommand.BaseCommandBuilder}.
     *
     * @param <T> the plugin type
     */
    public static class SubCommandBuilder<T extends Plugin> {

        protected final T plugin;
        protected final String name;
        protected final List<String> aliases = new ArrayList<>();
        @Nullable
        protected String flagValuePrefix;
        protected final List<SubCommand<T>> subCommands = new ArrayList<>();
        protected final List<CommandRequirement<T>> requirements = new ArrayList<>();
        protected boolean requiresConfirmation;
        protected final List<Flag<?>> flags = new ArrayList<>();
        protected final List<ArgumentDefinition> requiredArgs = new ArrayList<>();
        protected final List<ArgumentDefinition> optionalArgs = new ArrayList<>();
        @NotNull
        protected ArgumentExecutor<T> executor = dispatch -> CommandResultType.SUCCESS;

        record ArgumentDefinition(String name, ArgumentType<?> type) {}

        protected SubCommandBuilder(@NotNull T plugin, @NotNull String name) {
            Preconditions.checkNotNull(plugin, "Plugin cannot be null");
            Preconditions.checkNotNull(name, "Command name cannot be null");
            this.plugin = plugin;
            this.name = name;
        }

        /**
         * Adds an alias for the command.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> alias(@NotNull String... aliases) {
            Preconditions.checkNotNull(aliases, "Aliases array cannot be null");
            for (String alias : aliases) {
                Preconditions.checkNotNull(alias, "Alias entry cannot be null");
            }
            this.aliases.addAll(Arrays.asList(aliases));
            return this;
        }

        /**
         * Overrides the internal flag-value prefix (default {@code "flag$"}).
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> flagValuePrefix(@Nullable String flagValuePrefix) {
            this.flagValuePrefix = flagValuePrefix;
            return this;
        }

        /**
         * Registers a nested sub-command.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> addSubCommand(@NotNull SubCommand<T> subCommand) {
            Preconditions.checkNotNull(subCommand, "SubCommand cannot be null");
            this.subCommands.add(subCommand);
            return this;
        }

        /**
         * Registers a requirement.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> addRequirement(@NotNull CommandRequirement<T> requirement) {
            Preconditions.checkNotNull(requirement, "CommandRequirement cannot be null");
            this.requirements.add(requirement);
            return this;
        }

        /**
         * Restricts the command to in-game players only.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> playerOnly() {
            this.requirements.add(new PlayerOnlyRequirement<>());
            return this;
        }

        /**
         * Requires a specific Bukkit permission node.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> permission(@NotNull String permission) {
            Preconditions.checkNotNull(permission, "Permission cannot be null");
            this.requirements.add(new PermissionRequirement<>(permission));
            return this;
        }

        /**
         * Sets whether execution requires explicit confirmation.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> requiresConfirmation(boolean requiresConfirmation) {
            this.requiresConfirmation = requiresConfirmation;
            return this;
        }

        /**
         * Registers a pre-built flag.
         *
         * @return this builder
         * @throws IllegalArgumentException on name/alias collision at build time
         */
        @NotNull
        public SubCommandBuilder<T> addFlag(@NotNull Flag<?> flag) {
            Preconditions.checkNotNull(flag, "Flag cannot be null");
            this.flags.add(flag);
            return this;
        }

        /**
         * Registers multiple flags at once.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> addFlags(@NotNull Flag<?>... flags) {
            Preconditions.checkNotNull(flags, "Flags cannot be null");
            for (Flag<?> flag : flags) {
                Preconditions.checkNotNull(flag, "Flag entry cannot be null");
            }
            this.flags.addAll(Arrays.asList(flags));
            return this;
        }

        /**
         * Adds a required (positional) argument.
         *
         * @param <U> the argument value type
         * @return this builder
         */
        @NotNull
        public <U> SubCommandBuilder<T> addRequiredArgument(@NotNull String name, @NotNull ArgumentType<U> type) {
            Preconditions.checkNotNull(name, "Argument name cannot be null");
            Preconditions.checkNotNull(type, "Argument type cannot be null");
            this.requiredArgs.add(new ArgumentDefinition(name, type));
            return this;
        }

        /**
         * Adds an optional argument.
         *
         * @param <U> the argument value type
         * @return this builder
         */
        @NotNull
        public <U> SubCommandBuilder<T> addOptionalArgument(@NotNull String name, @NotNull ArgumentType<U> type) {
            Preconditions.checkNotNull(name, "Argument name cannot be null");
            Preconditions.checkNotNull(type, "Argument type cannot be null");
            this.optionalArgs.add(new ArgumentDefinition(name, type));
            return this;
        }

        /**
         * Sets the execution handler for this command.
         *
         * @return this builder
         */
        @NotNull
        public SubCommandBuilder<T> executes(@NotNull ArgumentExecutor<T> executor) {
            Preconditions.checkNotNull(executor, "Executor cannot be null");
            this.executor = executor;
            return this;
        }

        /**
         * Builds the command.
         *
         * @return the constructed {@link SubCommand}
         */
        @NotNull
        public SubCommand<T> build() {
            return new BuiltSubCommand<>(
                    this.plugin, this.name, this.aliases, this.flagValuePrefix,
                    this.subCommands, this.requirements, this.requiresConfirmation,
                    this.flags, this.requiredArgs, this.optionalArgs, this.executor
            );
        }
    }

    static <T extends Plugin> void initializeBuilt(
            @NotNull SubCommand<T> cmd,
            @NotNull List<SubCommand<T>> subCommands,
            @NotNull List<CommandRequirement<T>> requirements,
            boolean requiresConfirmation,
            @NotNull List<Flag<?>> flags,
            @NotNull List<SubCommandBuilder.ArgumentDefinition> requiredArgs,
            @NotNull List<SubCommandBuilder.ArgumentDefinition> optionalArgs) {

        for (SubCommand<T> sub : subCommands) {
            cmd.addSubCommand(sub);
        }
        for (CommandRequirement<T> req : requirements) {
            cmd.addRequirement(req);
        }
        cmd.setRequiresConfirmation(requiresConfirmation);
        for (Flag<?> flag : flags) {
            cmd.addFlag(flag);
        }
        for (SubCommandBuilder.ArgumentDefinition arg : requiredArgs) {
            cmd.addRequiredArgument(arg.name(), arg.type());
        }
        for (SubCommandBuilder.ArgumentDefinition arg : optionalArgs) {
            cmd.addOptionalArgument(arg.name(), arg.type());
        }
    }

    private static final class BuiltSubCommand<T extends Plugin> extends SubCommand<T> {

        private final ArgumentExecutor<T> executor;

        BuiltSubCommand(
                T plugin, String name, List<String> aliases, @Nullable String flagValuePrefix,
                List<SubCommand<T>> subCommands, List<CommandRequirement<T>> requirements,
                boolean requiresConfirmation, List<Flag<?>> flags,
                List<SubCommandBuilder.ArgumentDefinition> requiredArgs,
                List<SubCommandBuilder.ArgumentDefinition> optionalArgs,
                ArgumentExecutor<T> executor) {

            super(plugin, name, aliases.toArray(new String[0]));
            this.executor = executor;

            if (flagValuePrefix != null) {
                this.setFlagValuePrefix(flagValuePrefix);
            }

            initializeBuilt(this, subCommands, requirements, requiresConfirmation, flags, requiredArgs, optionalArgs);
        }

        @Override
        @NotNull
        protected CommandResultType perform(@NotNull CommandDispatch<T> dispatch) {
            try {
                return this.executor.execute(dispatch);
            } catch (Exception e) {
                this.plugin.getLogger().log(Level.SEVERE, "Error executing built command '" + this.getName() + "'", e);
                return CommandResultType.FAILURE;
            }
        }
    }

}