package fr.robie.paperdispatch.manager;

import com.google.common.base.Preconditions;
import fr.robie.paperdispatch.command.BaseCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Lifecycle contract for registering and unregistering top-level commands with
 * the Paper command framework.
 *
 * <p><b>Two-phase by design:</b> {@link #trackCommand(BaseCommand)} records a command,
 * {@link #flushRegistrations()} pushes the tracked set to the server. Commands tracked after
 * the flush are still registered, so the ordering of the two is not load-bearing.
 *
 * <pre>{@code
 * manager.trackCommand(new HealCommand(this));
 * manager.trackCommand(new BanCommand(this));
 * manager.flushRegistrations();
 * }</pre>
 *
 * <p>Commands are tracked per owning plugin ({@link BaseCommand#getPlugin()}), which is why
 * the mutators accept a command owned by any plugin rather than just {@code T} — a single
 * manager can host commands on behalf of other plugins (e.g. an addon registering into a
 * host plugin). {@code T} names the plugin that owns the manager itself.
 *
 * <p><b>Removal comes in five scopes</b>, deliberately named apart because they are not
 * interchangeable:
 * <ul>
 *   <li>{@link #unregisterCommand(BaseCommand)} — one command</li>
 *   <li>{@link #unregisterCommands(Collection)} — exactly the given commands</li>
 *   <li>{@link #unregisterReloadableCommands()} — only those marked
 *       {@link BaseCommand#setReloadable(boolean)}</li>
 *   <li>{@link #unregisterAll(Plugin)} — everything owned by one plugin</li>
 *   <li>{@link #unregisterAll()} — everything this manager tracks, whoever owns it</li>
 * </ul>
 *
 * @param <T> the plugin type owning this manager
 */
public interface ICommandManager<T extends Plugin> {
    /**
     * Removes a previously registered command from the server.
     *
     * @param command the command to unregister
     */
    <Y extends Plugin> void unregisterCommand(@NotNull BaseCommand<Y> command);

    /**
     * Records a command so that the next {@link #flushRegistrations()} pushes it to the
     * server. If the flush has already happened, the command is registered immediately.
     *
     * <p>Registering a command whose name or alias collides with one already tracked for the
     * same plugin replaces the existing command.
     *
     * @param command the command to track
     */
    <Y extends Plugin> void trackCommand(@NotNull BaseCommand<Y> command);

    /**
     * Builds a command from a {@link BaseCommand.BaseCommandBuilder} and tracks it.
     * <p>Equivalent to {@code trackCommand(builder.build())}.
     *
     * @param builder the builder for the command to track
     * @return the constructed {@link BaseCommand}
     */
    @NotNull
    default <U extends Plugin> BaseCommand<U> trackCommand(@NotNull BaseCommand.BaseCommandBuilder<U> builder) {
        Preconditions.checkNotNull(builder, "Command builder cannot be null");
        BaseCommand<U> command = builder.build();
        this.trackCommand(command);
        return command;
    }

    /**
     * Checks if a command with the given name (or alias) is already registered
     * by the specified plugin.
     *
     * @param plugin the plugin owning the command
     * @param name   the command name or alias
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean isRegistered(@NotNull Plugin plugin, @NotNull String name);

    /**
     * Checks if the given command instance is already registered.
     *
     * @param command the command to check
     * @return {@code true} if registered, {@code false} otherwise
     */
    boolean isRegistered(@NotNull BaseCommand<?> command);

    /**
     * Unregisters several commands as a single batch, syncing the command tree at most once.
     *
     * @param toRemove the commands to unregister
     */
    void unregisterCommands(@NotNull Collection<? extends BaseCommand<?>> toRemove);

    /**
     * Unregisters every command currently tracked for the given plugin, syncing once at the end.
     *
     * <p>Normally called as {@code unregisterAll(this)} from {@code onDisable()} to drop
     * everything the plugin owns.
     *
     * <p>The parameter is not redundant: commands are tracked by their <i>owning</i> plugin
     * ({@link BaseCommand#getPlugin()}), not by the plugin that owns this manager. A plugin that
     * hosts commands on behalf of others — an addon framework, or something that reloads other
     * plugins — can therefore drop exactly one plugin's commands and leave the rest registered.
     *
     * @param plugin the plugin whose commands should be removed
     * @see #unregisterAll() to drop every tracked command regardless of owner
     */
    void unregisterAll(@NotNull Plugin plugin);

    /**
     * Unregisters every command this manager tracks, whatever plugin owns it, syncing once at
     * the end.
     *
     * <p>This is the right call in a host plugin's {@code onDisable()} when it registers
     * commands on behalf of others: the {@code COMMANDS} lifecycle handler belongs to the
     * <i>manager's</i> plugin, so once that plugin unloads nothing will re-register the hosted
     * commands either. Dropping only your own ({@code unregisterAll(this)}) would leave the
     * hosted ones in the command tree with no owner able to restore or remove them.
     *
     * <p>For a single plugin's commands use {@link #unregisterAll(Plugin)}; for only the
     * reloadable ones use {@link #unregisterReloadableCommands()}.
     */
    void unregisterAll();

    /**
     * Returns an unmodifiable snapshot of the commands currently tracked for the given plugin.
     *
     * @param plugin the plugin whose commands to list
     * @return the tracked commands, or an empty list if none
     */
    @NotNull
    List<BaseCommand<?>> getCommands(@NotNull Plugin plugin);

    /**
     * Looks up a tracked command by name or alias for the given plugin.
     *
     * @param plugin the plugin owning the command
     * @param name   the command name or alias (matched case-insensitively)
     * @return the command, or {@code null} if none matches
     */
    @Nullable
    BaseCommand<?> getCommand(@NotNull Plugin plugin, @NotNull String name);

    /**
     * Pushes every tracked-but-unregistered command to the server (typically called during
     * plugin enable, after tracking them with {@link #trackCommand(BaseCommand)}).
     * <p>
     * Where Paper's lifecycle registration window is still open, the push happens when the
     * {@code COMMANDS} lifecycle event next fires; otherwise it happens immediately. Either
     * way, commands tracked after this call are still registered.
     */
    void flushRegistrations();

    /**
     * Removes every <i>reloadable</i> command from the server (see
     * {@link BaseCommand#setReloadable(boolean)}), leaving non-reloadable ones in place.
     * <p>Typically called during plugin disable or a config reload. To remove everything
     * regardless of the reloadable flag, use {@link #unregisterAll(Plugin)}.
     */
    void unregisterReloadableCommands();


    /**
     * @param command the command to track
     * @deprecated misleading name — this only <i>tracks</i> the command; nothing reaches the
     *         server until {@link #flushRegistrations()} runs. Use
     *         {@link #trackCommand(BaseCommand)}, whose name says so.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    default <Y extends Plugin> void registerCommand(@NotNull BaseCommand<Y> command) {
        this.trackCommand(command);
    }

    /**
     * @param builder the builder for the command to track
     * @return the constructed {@link BaseCommand}
     * @deprecated see {@link #registerCommand(BaseCommand)}; use
     *         {@link #trackCommand(BaseCommand.BaseCommandBuilder)}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    @NotNull
    default <U extends Plugin> BaseCommand<U> registerCommand(@NotNull BaseCommand.BaseCommandBuilder<U> builder) {
        return this.trackCommand(builder);
    }

    /**
     * @deprecated one letter away from {@code registerCommand} while doing something entirely
     *         different (flushing rather than tracking). Use {@link #flushRegistrations()}.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    default void registerCommands() {
        this.flushRegistrations();
    }

    /**
     * @deprecated the name implies "all commands", but this removes <i>only</i> reloadable
     *         ones — and it overloaded {@link #unregisterCommands(Collection)} with entirely
     *         different semantics. Use {@link #unregisterReloadableCommands()}, or
     *         {@link #unregisterAll(Plugin)} if you did mean all of them.
     */
    @Deprecated(forRemoval = true, since = "1.0.3")
    default void unregisterCommands() {
        this.unregisterReloadableCommands();
    }
}
