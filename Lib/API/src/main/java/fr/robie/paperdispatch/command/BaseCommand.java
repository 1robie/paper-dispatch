package fr.robie.paperdispatch.command;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.arguments.ArgumentType;
import fr.robie.paperdispatch.flag.Flag;
import fr.robie.paperdispatch.requirement.CommandRequirement;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Convenience base for a top-level (root) command. Unlike {@link SubCommand},
 * this class adds a human-readable {@code description} that can be shown in
 * command listings or help messages.
 *
 * @param <T> the plugin type
 */
public abstract class BaseCommand<T extends Plugin> extends SubCommand<T> {
    private boolean reloadable = false;

    @Nullable
    private String description = null;

    /**
     * @param plugin the owning plugin
     * @param name   the command name
     */
    public BaseCommand(@NotNull T plugin, @NotNull String name) {
        super(plugin, name);
    }

    /**
     * @param plugin  the owning plugin
     * @param name    the command name
     * @param aliases alternative names for this command
     */
    public BaseCommand(@NotNull T plugin, @NotNull String name, @NotNull String... aliases) {
        super(plugin, name, aliases);
    }

    /**
     * Returns the command description.
     *
     * @return the description, or {@code null} if not set
     */
    @Nullable
    public String getDescription() {
        return this.description;
    }

    /**
     * Sets the human-readable description for this command.
     *
     * @param description the description (may be {@code null} to clear)
     * @return this instance for chaining
     */
    public BaseCommand<T> setDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Checks if this command is reloadable. Reloadable commands are removed
     * when the command manager unregisters commands.
     *
     * @return {@code true} if reloadable, {@code false} otherwise
     */
    public boolean isReloadable() {
        return this.reloadable;
    }

    /**
     * Sets whether this command is reloadable.
     *
     * @param reloadable {@code true} if reloadable, {@code false} otherwise
     * @return this instance for chaining
     */
    public BaseCommand<T> setReloadable(boolean reloadable) {
        this.reloadable = reloadable;
        return this;
    }

    /**
     * Creates a new {@link BaseCommandBuilder} for constructing a
     * {@link BaseCommand} with a description, without subclassing.
     *
     * @param plugin the owning plugin
     * @param name   the command name
     * @return a new builder
     * @param <T> the plugin type
     */
    @NotNull
    public static <T extends Plugin> BaseCommandBuilder<T> builder(@NotNull T plugin, @NotNull String name) {
        Preconditions.checkNotNull(plugin, "Plugin cannot be null");
        Preconditions.checkNotNull(name, "Command name cannot be null");
        return new BaseCommandBuilder<>(plugin, name);
    }

    /**
     * Builder for creating a {@link BaseCommand} (a top-level command with a
     * description) without subclassing. Obtain via
     * {@link BaseCommand#builder(Plugin, String)}.
     *
     * @param <T> the plugin type
     */
    public static final class BaseCommandBuilder<T extends Plugin> extends SubCommandBuilder<T> {

        @Nullable
        private String description;

        private boolean reloadable = false;

        BaseCommandBuilder(@NotNull T plugin, @NotNull String name) {
            super(plugin, name);
        }

        /**
         * Sets a human-readable description for this command.
         *
         * @return this builder
         */
        @NotNull
        public BaseCommandBuilder<T> description(@Nullable String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets whether the command should be reloadable.
         *
         * @param reloadable {@code true} if reloadable, {@code false} otherwise
         * @return this builder
         */
        @NotNull
        public BaseCommandBuilder<T> reloadable(boolean reloadable) {
            this.reloadable = reloadable;
            return this;
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> alias(@NotNull String... aliases) {
            return (BaseCommandBuilder<T>) super.alias(aliases);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> flagValuePrefix(@Nullable String flagValuePrefix) {
            return (BaseCommandBuilder<T>) super.flagValuePrefix(flagValuePrefix);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> addSubCommand(@NotNull SubCommand<T> subCommand) {
            return (BaseCommandBuilder<T>) super.addSubCommand(subCommand);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> addRequirement(@NotNull CommandRequirement<T> requirement) {
            return (BaseCommandBuilder<T>) super.addRequirement(requirement);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> playerOnly() {
            return (BaseCommandBuilder<T>) super.playerOnly();
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> permission(@NotNull String permission) {
            return (BaseCommandBuilder<T>) super.permission(permission);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> requiresConfirmation(boolean requiresConfirmation) {
            return (BaseCommandBuilder<T>) super.requiresConfirmation(requiresConfirmation);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> addFlag(@NotNull Flag<?> flag) {
            return (BaseCommandBuilder<T>) super.addFlag(flag);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> addFlags(@NotNull Flag<?>... flags) {
            return (BaseCommandBuilder<T>) super.addFlags(flags);
        }

        @Override
        @NotNull
        public <U> BaseCommandBuilder<T> addRequiredArgument(@NotNull String name, @NotNull ArgumentType<U> type) {
            return (BaseCommandBuilder<T>) super.addRequiredArgument(name, type);
        }

        @Override
        @NotNull
        public <U> BaseCommandBuilder<T> addOptionalArgument(@NotNull String name, @NotNull ArgumentType<U> type) {
            return (BaseCommandBuilder<T>) super.addOptionalArgument(name, type);
        }

        @Override
        @NotNull
        public BaseCommandBuilder<T> executes(@NotNull ArgumentExecutor<T> executor) {
            return (BaseCommandBuilder<T>) super.executes(executor);
        }

        @Override
        @NotNull
        public BaseCommand<T> build() {
            return new BuiltBaseCommand<>(
                    this.plugin, this.name, this.aliases, this.description, this.flagValuePrefix,
                    this.subCommands, this.requirements, this.requiresConfirmation,
                    this.flags, this.requiredArgs, this.optionalArgs, this.executor, this.reloadable
            );
        }
    }

    private static final class BuiltBaseCommand<T extends Plugin> extends BaseCommand<T> {

        private final SubCommand.ArgumentExecutor<T> executor;

        BuiltBaseCommand(
                T plugin, String name, List<String> aliases, @Nullable String description,
                @Nullable String flagValuePrefix,
                List<SubCommand<T>> subCommands, List<CommandRequirement<T>> requirements,
                boolean requiresConfirmation, List<Flag<?>> flags,
                List<SubCommandBuilder.ArgumentDefinition> requiredArgs,
                List<SubCommandBuilder.ArgumentDefinition> optionalArgs,
                ArgumentExecutor<T> executor, boolean reloadable) {

            super(plugin, name, aliases.toArray(new String[0]));
            this.executor = executor;

            if (description != null) {
                this.setDescription(description);
            }
            if (flagValuePrefix != null) {
                this.setFlagValuePrefix(flagValuePrefix);
            }

            this.setReloadable(reloadable);

            SubCommand.initializeBuilt(this, subCommands, requirements, requiresConfirmation, flags, requiredArgs, optionalArgs);
        }

        @Override
        @NotNull
        protected CommandResultType perform(@NotNull CommandDispatch<T> dispatch) {
            try {
                return this.executor.execute(dispatch);
            } catch (Exception e) {
                this.plugin.getLogger().severe("Error executing built command '" + this.getName() + "': " + e.getMessage());
                return CommandResultType.FAILURE;
            }
        }
    }
}
