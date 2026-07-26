package fr.robie.paperdispatch.manager;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import fr.robie.paperdispatch.command.BaseCommand;
import fr.robie.paperdispatch.logger.PluginLogger;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandManager<T extends Plugin> implements ICommandManager<T> {

    private final T plugin;

    private final PluginLogger logger;

    private final Map<Plugin, List<BaseCommand<?>>> commands = new ConcurrentHashMap<>();
    private final Set<BaseCommand<?>> registeredCommands = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean lifecycleHandlerAttached = new AtomicBoolean(false);

    private final Queue<CommandRemoval> pendingRemovals = new ConcurrentLinkedQueue<>();

    private volatile boolean dynamicRegistrationFlushed = false;

    private final AtomicBoolean missingLifecycleFieldWarned = new AtomicBoolean(false);

    /**
     * A command's identity in the Brigadier tree, snapshotted so a queued removal survives
     * the command being dropped from {@link #commands}.
     */
    private record CommandRemoval(String namespace, String name, Collection<String> aliases) {}

    public CommandManager(@NotNull T plugin) {
        this(plugin, PluginLogger.of(plugin.getLogger()));
    }

    public CommandManager(@NotNull T plugin, @NotNull PluginLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public <Y extends Plugin> void trackCommand(@NotNull BaseCommand<Y> command) {
        BaseCommand<?> existing = this.findRegisteredCommand(command.getPlugin(), command.getName());
        boolean needsSync = false;

        if (existing != null) {
            needsSync |= this.unregisterCommand(existing, false);
        }

        for (String alias : command.getAliases()) {
            BaseCommand<?> existingAlias = this.findRegisteredCommand(command.getPlugin(), alias);
            if (existingAlias != null && existingAlias != existing) {
                needsSync |= this.unregisterCommand(existingAlias, false);
            }
        }

        this.commands.computeIfAbsent(command.getPlugin(), k -> new CopyOnWriteArrayList<>()).add(command);

        if (this.dynamicRegistrationFlushed) {
            this.registerCommandsDynamically(List.of(command));
            return;
        }

        if (needsSync) {
            this.syncCommands();
        }
    }

    @Override
    public <Y extends Plugin> void unregisterCommand(@NotNull BaseCommand<Y> command) {
        this.unregisterCommand(command, true);
    }

    /**
     * Unregisters several commands as a single batch, syncing the command tree at most once
     * regardless of how many commands actually needed removal from the server-side tree.
     */
    @Override
    public void unregisterCommands(@NotNull Collection<? extends BaseCommand<?>> toRemove) {
        boolean needsSync = false;
        for (BaseCommand<?> command : toRemove) {
            needsSync |= this.unregisterCommand(command, false);
        }
        if (needsSync) {
            this.syncCommands();
        }
    }

    /**
     * Unregisters every command currently tracked for the given plugin, syncing once at the end.
     * Intended for use in {@code onDisable()}.
     */
    @Override
    public void unregisterAll(@NotNull Plugin plugin) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(plugin);
        if (pluginCommands == null || pluginCommands.isEmpty()) {
            return;
        }
        this.unregisterCommands(new ArrayList<>(pluginCommands));
    }

    /**
     * Unregisters every tracked command across all owning plugins, syncing once at the end.
     */
    @Override
    public void unregisterAll() {
        List<BaseCommand<?>> toRemove = this.allTrackedCommands();

        Set<String> foreignOwners = new TreeSet<>();
        for (BaseCommand<?> command : toRemove) {
            if (!command.getPlugin().equals(this.plugin)) {
                foreignOwners.add(command.getPlugin().getName());
            }
        }
        if (!foreignOwners.isEmpty()) {
            this.logger.info(
                    "unregisterAll() is also removing commands owned by " + String.join(", ", foreignOwners)
                            + ". Use unregisterAll(Plugin) if you only meant this plugin's own commands."
            );
        }

        this.unregisterCommands(toRemove);
    }

    /**
     * @param syncAfter whether to sync the command tree to clients immediately after removal.
     *                  Callers that batch multiple removals can pass {@code false} and sync once at the end.
     * @return true if the command was actually removed from the server-side Brigadier tree
     *         (i.e. a sync is warranted). Commands that were only ever tracked in the map but
     *         never reached the tree don't need one.
     */
    private <Y extends Plugin> boolean unregisterCommand(@NotNull BaseCommand<Y> command, boolean syncAfter) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(command.getPlugin());
        boolean removedFromServer = false;

        if (pluginCommands != null) {
            if (pluginCommands.remove(command)) {
                boolean wasRegisteredOnServer = this.registeredCommands.remove(command);
                if (wasRegisteredOnServer) {
                    removedFromServer = this.unregisterCommandFromServer(command, syncAfter);
                }
            }
            if (pluginCommands.isEmpty()) {
                this.commands.remove(command.getPlugin());
            }
        }
        return removedFromServer;
    }

    @Override
    public boolean isRegistered(@NotNull Plugin plugin, @NotNull String name) {
        return this.findRegisteredCommand(plugin, name) != null;
    }

    @Override
    public boolean isRegistered(@NotNull BaseCommand<?> command) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(command.getPlugin());
        return pluginCommands != null && pluginCommands.contains(command);
    }

    /**
     * Returns an unmodifiable snapshot of the commands currently tracked for the given plugin.
     */
    @Override
    @NotNull
    public List<BaseCommand<?>> getCommands(@NotNull Plugin plugin) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(plugin);
        return pluginCommands == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(pluginCommands));
    }

    /**
     * Looks up a tracked command by name or alias for the given plugin, or null if none matches.
     */
    @Override
    @Nullable
    public BaseCommand<?> getCommand(@NotNull Plugin plugin, @NotNull String name) {
        return this.findRegisteredCommand(plugin, name);
    }

    private BaseCommand<?> findRegisteredCommand(Plugin plugin, String name) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(plugin);
        if (pluginCommands != null) {
            for (BaseCommand<?> command : pluginCommands) {
                if (command.getName().equalsIgnoreCase(name)) {
                    return command;
                }
                for (String alias : command.getAliases()) {
                    if (alias.equalsIgnoreCase(name)) {
                        return command;
                    }
                }
            }
        }
        return null;
    }


    /**
         * Holds the reflective handles we need into {@code io.papermc.paper.command.brigadier.PaperCommands}.
         * Resolved lazily on first use and cached for the lifetime of the JVM/classloader.
         */
        private record PaperCommandsHandles(Object instance, Method getDispatcherInternal, Field invalidField,
                                            Method registerMethod) {
    }

    private static volatile PaperCommandsHandles cachedHandles;

    private static PaperCommandsHandles resolvePaperCommandsHandles() throws ReflectiveOperationException {
        PaperCommandsHandles handles = cachedHandles;
        if (handles != null) {
            return handles;
        }
        synchronized (CommandManager.class) {
            handles = cachedHandles;
            if (handles != null) {
                return handles;
            }

            Class<?> paperCommandsClass = Class.forName("io.papermc.paper.command.brigadier.PaperCommands");
            Object instance = paperCommandsClass.getField("INSTANCE").get(null);

            Method getDispatcherInternal = paperCommandsClass.getMethod("getDispatcherInternal");

            Field invalidField = paperCommandsClass.getDeclaredField("invalid");
            invalidField.setAccessible(true);

            Method registerMethod = paperCommandsClass.getMethod(
                    "register",
                    io.papermc.paper.plugin.configuration.PluginMeta.class,
                    com.mojang.brigadier.tree.LiteralCommandNode.class,
                    String.class,
                    java.util.Collection.class
            );

            handles = new PaperCommandsHandles(instance, getDispatcherInternal, invalidField, registerMethod);
            cachedHandles = handles;
            return handles;
        }
    }

    /**
     * Cached {@code removeCommand(String)} lookups, keyed by the concrete node class.
     * <p>
     * {@code removeCommand} is invoked 2-4 times per command per unregister, so resolving the
     * method (and calling {@code setAccessible}) on every call was pure overhead. Values are
     * wrapped in {@link Optional} so a negative result is cached too.
     */
    private static final Map<Class<?>, Optional<Method>> removeCommandMethods = new ConcurrentHashMap<>();

    @Nullable
    private static Method resolveRemoveCommandMethod(Class<?> nodeClass) {
        return removeCommandMethods.computeIfAbsent(nodeClass, cls -> {
            Class<?> current = cls;
            while (current != null && current != Object.class) {
                try {
                    Method method = current.getDeclaredMethod("removeCommand", String.class);
                    method.setAccessible(true);
                    return Optional.of(method);
                } catch (NoSuchMethodException e) {
                    current = current.getSuperclass();
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static RootCommandNode<CommandSourceStack> getDispatcherRoot(PaperCommandsHandles handles)
            throws ReflectiveOperationException {
        com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher =
                (com.mojang.brigadier.CommandDispatcher<CommandSourceStack>)
                        handles.getDispatcherInternal.invoke(handles.instance);
        return dispatcher.getRoot();
    }


    /**
     * Checks if the Paper lifecycle registration phase is still open.
     */
    private boolean isLifecycleRegistrationAllowed() {
        try {
            Field field = null;
            Class<?> current = this.plugin.getClass();
            while (current != null && current != Object.class) {
                try {
                    field = current.getDeclaredField("allowsLifecycleRegistration");
                    break;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            if (field != null) {
                field.setAccessible(true);
                return field.getBoolean(this.plugin);
            }
            if (this.missingLifecycleFieldWarned.compareAndSet(false, true)) {
                this.logger.warning(
                        "Could not find the 'allowsLifecycleRegistration' field on " + this.plugin.getClass().getName()
                                + " or its superclasses; assuming dynamic registration. If commands fail to register, "
                                + "this likely indicates a Paper API change."
                );
            }
        } catch (ReflectiveOperationException e) {
            this.logger.warning("Failed to determine lifecycle registration state, assuming dynamic registration: " + e);
        }
        return false;
    }

    @Override
    public void flushRegistrations() {
        if (this.isLifecycleRegistrationAllowed()) {
            int totalCommands = this.commands.values().stream().mapToInt(List::size).sum();
            this.logger.info("Registering " + totalCommands + " commands via lifecycle events...");
            this.attachLifecycleHandler();
        } else {
            this.registerCommandsDynamically(this.allTrackedCommands());
            this.dynamicRegistrationFlushed = true;
        }
    }

    /**
     * Attaches the single {@code LifecycleEvents.COMMANDS} handler, if not already attached.
     * <p>
     * The handler drains {@link #pendingRemovals} before registering, so that within one event
     * a queued removal is applied first and a command re-registered afterwards still wins.
     */
    private void attachLifecycleHandler() {
        if (!this.lifecycleHandlerAttached.compareAndSet(false, true)) {
            return;
        }
        this.plugin.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                event -> this.handleCommandsEvent(event.registrar())
        );
    }

    /**
     * Body of the {@code COMMANDS} lifecycle handler: applies any queued removals, then
     * registers every tracked command into the supplied registrar.
     * <p>
     * Package-private rather than inlined into the lambda so it can be driven directly in tests —
     * firing a real lifecycle event needs a running server.
     *
     * @param registrar the registrar for this event
     */
    void handleCommandsEvent(@NotNull Commands registrar) {
        RootCommandNode<CommandSourceStack> root = registrar.getDispatcher().getRoot();
        CommandRemoval removal;
        while ((removal = this.pendingRemovals.poll()) != null) {
            this.removeCommandAndAliases(root, removal);
        }

        this.registeredCommands.clear();

        for (List<BaseCommand<?>> pluginCommands : this.commands.values()) {
            for (BaseCommand<?> command : pluginCommands) {
                if (this.registeredCommands.contains(command)) {
                    continue;
                }
                try {
                    Set<String> registeredLabels = registrar.register(
                            command.getPlugin().getPluginMeta(),
                            command.build(),
                            command.getDescription(),
                            command.getAliases()
                    );
                    this.registeredCommands.add(command);
                    this.warnAboutRejectedAliases(command, registeredLabels);
                } catch (Exception e) {
                    this.logger.warning(
                            "Failed to register command '" + command.getName() + "': " + e
                    );
                }
            }
        }
    }

    /**
     * Narrows the reflective {@code register} return value to a set of labels, or {@code null}
     * if this Paper build returned something else (the signature is not part of the public API
     * on the dynamic path, so it is treated as best-effort).
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static Set<String> asLabelSet(@Nullable Object result) {
        if (!(result instanceof Set<?> set)) {
            return null;
        }
        for (Object element : set) {
            if (!(element instanceof String)) {
                return null;
            }
        }
        return (Set<String>) set;
    }

    /**
     * Reports any alias the server declined to register.
     * <p>
     * Paper's contract is that <i>aliases will not override already existing commands</i>, so an
     * alias claimed by another plugin is dropped silently — {@code register} returns only the
     * labels it actually took. Without this check the manager would report the command as fully
     * registered while some of its aliases quietly do nothing.
     *
     * @param command          the command that was registered
     * @param registeredLabels the labels the registrar reports it accepted, or {@code null} if
     *                         the registration path could not supply them
     */
    private void warnAboutRejectedAliases(@NotNull BaseCommand<?> command, @Nullable Set<String> registeredLabels) {
        if (registeredLabels == null) {
            return;
        }

        List<String> rejected = new ArrayList<>();
        for (String alias : command.getAliases()) {
            if (!registeredLabels.contains(alias)) {
                rejected.add(alias);
            }
        }

        if (!rejected.isEmpty()) {
            this.logger.warning(
                    "Command '" + command.getName() + "' registered, but the server declined these aliases: "
                            + String.join(", ", rejected)
                            + " (most likely already claimed by another command; the namespaced form still works)"
            );
        }
    }

    /**
     * @return a snapshot of every command tracked across all plugins.
     */
    @NotNull
    private List<BaseCommand<?>> allTrackedCommands() {
        List<BaseCommand<?>> all = new ArrayList<>();
        for (List<BaseCommand<?>> pluginCommands : this.commands.values()) {
            all.addAll(pluginCommands);
        }
        return all;
    }

    private void registerCommandsDynamically(@NotNull Collection<? extends BaseCommand<?>> toRegister) {
        if (toRegister.isEmpty()) {
            return;
        }
        this.logger.info("Registering " + toRegister.size() + " commands dynamically...");

        final PaperCommandsHandles handles;
        try {
            handles = resolvePaperCommandsHandles();
        } catch (ReflectiveOperationException e) {
            this.logger.warning("Failed to resolve PaperCommands internals for dynamic registration: " + e);
            return;
        }

        boolean wasInvalid;
        try {
            wasInvalid = handles.invalidField.getBoolean(handles.instance);
        } catch (ReflectiveOperationException e) {
            this.logger.warning("Failed to read PaperCommands 'invalid' state: " + e);
            return;
        }

        try {
            handles.invalidField.setBoolean(handles.instance, false);
        } catch (ReflectiveOperationException e) {
            this.logger.warning("Failed to flip PaperCommands 'invalid' state: " + e);
            return;
        }

        try {
            for (BaseCommand<?> command : toRegister) {
                if (this.registeredCommands.contains(command)) {
                    continue;
                }
                try {
                    Object result = handles.registerMethod.invoke(
                            handles.instance,
                            command.getPlugin().getPluginMeta(),
                            command.build(),
                            command.getDescription(),
                            command.getAliases()
                    );
                    this.registeredCommands.add(command);
                    this.warnAboutRejectedAliases(command, asLabelSet(result));
                } catch (Exception e) {
                    this.logger.warning(
                            "Failed to dynamically register command '" + command.getName() + "': " + e
                    );
                }
            }
            this.logger.info("Commands registered dynamically!");
        } finally {
            try {
                handles.invalidField.setBoolean(handles.instance, wasInvalid);
            } catch (ReflectiveOperationException e) {
                this.logger.warning("Failed to restore PaperCommands 'invalid' state: " + e);
            }
        }

        this.syncCommands();
    }

    @Override
    public void unregisterReloadableCommands() {
        if (this.isLifecycleRegistrationAllowed()) {
            this.logger.info("Queueing reloadable commands for removal on the next COMMANDS event...");

            int queued = 0;
            for (BaseCommand<?> command : this.allTrackedCommands()) {
                if (command.isReloadable()) {
                    this.pendingRemovals.add(this.toRemoval(command));
                    queued++;
                }
            }

            this.attachLifecycleHandler();
            this.purgeReloadableCommands();

            this.logger.info("Queued " + queued + " reloadable commands for removal.");
        } else {
            this.unregisterCommandsDynamically();
        }
    }

    /**
     * Snapshots a command's tree identity for later removal.
     */
    @NotNull
    private CommandRemoval toRemoval(@NotNull BaseCommand<?> command) {
        return new CommandRemoval(
                command.getPlugin().getPluginMeta().namespace(),
                command.getName(),
                List.copyOf(command.getAliases())
        );
    }

    /**
     * Removes a command, its aliases, and their namespaced variants from the given tree root.
     */
    private void removeCommandAndAliases(@NotNull CommandNode<CommandSourceStack> root, @NotNull CommandRemoval removal) {
        this.removeCommand(root, removal.name());
        this.removeCommand(root, removal.namespace() + ":" + removal.name());

        for (String alias : removal.aliases()) {
            this.removeCommand(root, alias);
            this.removeCommand(root, removal.namespace() + ":" + alias);
        }
    }

    private void unregisterCommandsDynamically() {
        this.logger.info("Unregistering commands dynamically...");

        final PaperCommandsHandles handles;
        final RootCommandNode<CommandSourceStack> root;
        try {
            handles = resolvePaperCommandsHandles();
            root = getDispatcherRoot(handles);
        } catch (ReflectiveOperationException e) {
            this.logger.warning("Failed to resolve PaperCommands internals for dynamic unregistration: " + e);
            this.purgeReloadableCommands();
            return;
        }

        for (BaseCommand<?> command : this.allTrackedCommands()) {
            if (command.isReloadable()) {
                this.removeCommandAndAliases(root, this.toRemoval(command));
            }
        }

        this.purgeReloadableCommands();

        this.logger.info("Commands unregistered dynamically!");

        this.syncCommands();
    }

    /**
     * @return true if the removal succeeded and a sync is warranted.
     */
    private boolean unregisterCommandFromServer(BaseCommand<?> command, boolean syncAfter) {
        final PaperCommandsHandles handles;
        final RootCommandNode<CommandSourceStack> root;
        try {
            handles = resolvePaperCommandsHandles();
            root = getDispatcherRoot(handles);
        } catch (ReflectiveOperationException e) {
            this.logger.warning(
                    "Failed to unregister command '" + command.getName() + "' from the server tree "
                            + "(this may indicate a Paper API change): " + e
            );
            return false;
        }

        this.removeCommandAndAliases(root, this.toRemoval(command));

        if (syncAfter) {
            this.syncCommands();
        }
        return true;
    }

    /**
     * Shared bookkeeping used by both the lifecycle and dynamic unregistration paths:
     * drops reloadable commands from the registered set and from the tracked map.
     */
    private void purgeReloadableCommands() {
        for (List<BaseCommand<?>> pluginCommands : this.commands.values()) {
            for (BaseCommand<?> command : pluginCommands) {
                if (command.isReloadable()) {
                    this.registeredCommands.remove(command);
                }
            }
            pluginCommands.removeIf(BaseCommand::isReloadable);
        }
        this.commands.values().removeIf(List::isEmpty);
    }

    /**
     * Compile-safe helper that removes a command from the tree at runtime.
     */
    private void removeCommand(CommandNode<CommandSourceStack> node, String name) {
        Method removeCommandMethod = resolveRemoveCommandMethod(node.getClass());

        if (removeCommandMethod != null) {
            try {
                removeCommandMethod.invoke(node, name);
                return;
            } catch (ReflectiveOperationException e) {
                this.logger.warning(
                        "removeCommand invocation failed for '" + name + "', falling back to field access: " + e
                );
            }
        }

        try {
            Class<?> commandNodeClass = Class.forName("com.mojang.brigadier.tree.CommandNode");

            Field childrenField = commandNodeClass.getDeclaredField("children");
            childrenField.setAccessible(true);
            Map<?, ?> children = (Map<?, ?>) childrenField.get(node);
            if (children != null) {
                children.remove(name);
            }

            Field literalsField = commandNodeClass.getDeclaredField("literals");
            literalsField.setAccessible(true);
            Map<?, ?> literals = (Map<?, ?>) literalsField.get(node);
            if (literals != null) {
                literals.remove(name);
            }

            Field argumentsField = commandNodeClass.getDeclaredField("arguments");
            argumentsField.setAccessible(true);
            Map<?, ?> arguments = (Map<?, ?>) argumentsField.get(node);
            if (arguments != null) {
                arguments.remove(name);
            }
        } catch (ReflectiveOperationException e) {
            this.logger.warning("Fallback field-based removal failed for '" + name + "': " + e);
        }
    }

    /**
     * Refreshes the command tree for all online players.
     * <p>
     * {@code CraftServer#syncCommands} mutates server-global state and must run on the main
     * thread, but this class is documented as usable from async plugin-load contexts — so
     * hop onto the global region scheduler when called off-thread rather than corrupting
     * the dispatcher from a worker.
     */
    private void syncCommands() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> this.syncCommandsNow());
            return;
        }
        this.syncCommandsNow();
    }

    private void syncCommandsNow() {
        try {
            Method syncCommands = Bukkit.getServer().getClass().getMethod("syncCommands");
            syncCommands.invoke(Bukkit.getServer());
        } catch (Exception e) {
            this.logger.warning("Failed to sync commands dynamically: " + e.getMessage());
        }
    }
}
