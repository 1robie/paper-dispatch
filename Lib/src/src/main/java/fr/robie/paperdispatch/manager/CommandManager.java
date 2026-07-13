package fr.robie.paperdispatch.manager;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import fr.robie.paperdispatch.command.BaseCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandManager<T extends Plugin> implements ICommandManager<T> {

    private final T plugin;

    // Thread-safe: registration can be triggered from async plugin-load contexts.
    // CopyOnWriteArrayList so that concurrent iteration (e.g. during registerCommandsDynamically)
    // never races against a concurrent registerCommand/unregisterCommand call.
    private final Map<Plugin, List<BaseCommand<?>>> commands = new ConcurrentHashMap<>();
    private final Set<BaseCommand<?>> registeredCommands = ConcurrentHashMap.newKeySet();

    // Guards against attaching the same LifecycleEvents.COMMANDS handler more than once if
    // registerCommands()/unregisterCommands() are ever called multiple times (e.g. on reload).
    private final AtomicBoolean registerLifecycleHandlerAttached = new AtomicBoolean(false);
    private final AtomicBoolean unregisterLifecycleHandlerAttached = new AtomicBoolean(false);

    public CommandManager(@NotNull T plugin) {
        this.plugin = plugin;
    }

    @Override
    public <Y extends Plugin> void registerCommand(@NotNull BaseCommand<Y> command) {
        // Find if a command with the same name is already registered by this plugin, and if so, override/replace it.
        // We defer the server-side sync until the very end so a replace only triggers ONE sync instead of two,
        // and we only touch the Brigadier tree if the old command actually made it there.
        BaseCommand<?> existing = this.findRegisteredCommand(command.getPlugin(), command.getName());
        boolean needsSync = false;

        if (existing != null) {
            needsSync |= this.unregisterCommand(existing, false);
        }

        // Check if any of the new command's aliases are already registered by this plugin
        for (String alias : command.getAliases()) {
            BaseCommand<?> existingAlias = this.findRegisteredCommand(command.getPlugin(), alias);
            if (existingAlias != null && existingAlias != existing) {
                needsSync |= this.unregisterCommand(existingAlias, false);
            }
        }

        this.commands.computeIfAbsent(command.getPlugin(), k -> new CopyOnWriteArrayList<>()).add(command);

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
    public void unregisterAll(@NotNull Plugin plugin) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(plugin);
        if (pluginCommands == null || pluginCommands.isEmpty()) {
            return;
        }
        // Snapshot first: unregisterCommand mutates pluginCommands as it goes.
        this.unregisterCommands(new ArrayList<>(pluginCommands));
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
    @NotNull
    public List<BaseCommand<?>> getCommands(@NotNull Plugin plugin) {
        List<BaseCommand<?>> pluginCommands = this.commands.get(plugin);
        // CopyOnWriteArrayList's own copy constructor is safe against concurrent mutation.
        return pluginCommands == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(pluginCommands));
    }

    /**
     * Looks up a tracked command by name or alias for the given plugin, or null if none matches.
     */
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

    // ---------------------------------------------------------------------
    // Cached reflection handles into Paper's internal PaperCommands class.
    // Resolved once and reused, instead of re-resolving Class/Field/Method
    // objects on every single register/unregister call.
    // ---------------------------------------------------------------------

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

    @SuppressWarnings("unchecked")
    private static RootCommandNode<CommandSourceStack> getDispatcherRoot(PaperCommandsHandles handles)
            throws ReflectiveOperationException {
        com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher =
                (com.mojang.brigadier.CommandDispatcher<CommandSourceStack>)
                        handles.getDispatcherInternal.invoke(handles.instance);
        return dispatcher.getRoot();
    }

    // ---------------------------------------------------------------------

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
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().warning("Failed to determine lifecycle registration state, assuming dynamic registration: " + e);
        }
        return false;
    }

    @Override
    public void registerCommands() {
        if (this.isLifecycleRegistrationAllowed()) {
            int totalCommands = this.commands.values().stream().mapToInt(List::size).sum();
            this.plugin.getLogger().info("Registering " + totalCommands + " commands via lifecycle events...");

            if (this.registerLifecycleHandlerAttached.compareAndSet(false, true)) {
                this.plugin.getLifecycleManager().registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            Commands registrar = event.registrar();
                            for (List<BaseCommand<?>> pluginCommands : this.commands.values()) {
                                for (BaseCommand<?> command : pluginCommands) {
                                    if (this.registeredCommands.contains(command)) {
                                        continue;
                                    }
                                    try {
                                        registrar.register(
                                                command.build(),
                                                command.getDescription(),
                                                command.getAliases()
                                        );
                                        this.registeredCommands.add(command);
                                    } catch (Exception e) {
                                        this.plugin.getLogger().warning(
                                                "Failed to register command '" + command.getName() + "': " + e
                                        );
                                    }
                                }
                            }
                        }
                );
            }
        } else {
            // Dynamic registration at runtime
            this.registerCommandsDynamically();
        }
    }

    private void registerCommandsDynamically() {
        int totalCommands = this.commands.values().stream().mapToInt(List::size).sum();
        this.plugin.getLogger().info("Registering " + totalCommands + " commands dynamically...");

        final PaperCommandsHandles handles;
        try {
            handles = resolvePaperCommandsHandles();
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().warning("Failed to resolve PaperCommands internals for dynamic registration: " + e);
            return;
        }

        boolean wasInvalid;
        try {
            wasInvalid = handles.invalidField.getBoolean(handles.instance);
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().warning("Failed to read PaperCommands 'invalid' state: " + e);
            return;
        }

        // Everything that touches the temporarily-flipped 'invalid' flag must live inside this
        // try/finally so the flag is guaranteed to be restored even if something fails mid-way.
        try {
            handles.invalidField.setBoolean(handles.instance, false);
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().warning("Failed to flip PaperCommands 'invalid' state: " + e);
            return;
        }

        try {
            for (Map.Entry<Plugin, List<BaseCommand<?>>> entry : this.commands.entrySet()) {
                Plugin cmdPlugin = entry.getKey();
                for (BaseCommand<?> command : entry.getValue()) {
                    if (this.registeredCommands.contains(command)) {
                        continue;
                    }
                    try {
                        handles.registerMethod.invoke(
                                handles.instance,
                                cmdPlugin.getPluginMeta(),
                                command.build(),
                                command.getDescription(),
                                command.getAliases()
                        );
                        this.registeredCommands.add(command);
                    } catch (Exception e) {
                        this.plugin.getLogger().warning(
                                "Failed to dynamically register command '" + command.getName() + "': " + e
                        );
                    }
                }
            }
            this.plugin.getLogger().info("Commands registered dynamically!");
        } finally {
            // Restore the original invalid state no matter what happened above.
            try {
                handles.invalidField.setBoolean(handles.instance, wasInvalid);
            } catch (ReflectiveOperationException e) {
                this.plugin.getLogger().warning("Failed to restore PaperCommands 'invalid' state: " + e);
            }
        }

        // Sync commands to all players so client autocomplete updates
        this.syncCommands();
    }

    @Override
    public void unregisterCommands() {
        if (this.isLifecycleRegistrationAllowed()) {
            this.plugin.getLogger().info("Unregistering commands via lifecycle events...");

            if (this.unregisterLifecycleHandlerAttached.compareAndSet(false, true)) {
                this.plugin.getLifecycleManager().registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            RootCommandNode<CommandSourceStack> root = event.registrar().getDispatcher().getRoot();
                            this.forEachReloadableCommand(command -> {
                                this.removeCommand(root, command.getName());
                                for (String alias : command.getAliases()) {
                                    this.removeCommand(root, alias);
                                }
                            });
                        }
                );
            }
            this.purgeReloadableCommands();
        } else {
            // Dynamic unregistration at runtime
            this.unregisterCommandsDynamically();
        }
    }

    private void unregisterCommandsDynamically() {
        this.plugin.getLogger().info("Unregistering commands dynamically...");

        final PaperCommandsHandles handles;
        final RootCommandNode<CommandSourceStack> root;
        try {
            handles = resolvePaperCommandsHandles();
            root = getDispatcherRoot(handles);
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().warning("Failed to resolve PaperCommands internals for dynamic unregistration: " + e);
            return;
        }

        for (Map.Entry<Plugin, List<BaseCommand<?>>> entry : this.commands.entrySet()) {
            Plugin cmdPlugin = entry.getKey();
            String namespace = cmdPlugin.getPluginMeta().namespace();
            for (BaseCommand<?> command : entry.getValue()) {
                if (!command.isReloadable()) {
                    continue;
                }
                String name = command.getName();

                // Remove base command and namespaced variant (e.g. /myplugin:mycommand)
                this.removeCommand(root, name);
                this.removeCommand(root, namespace + ":" + name);

                // Remove aliases and namespaced variants
                for (String alias : command.getAliases()) {
                    this.removeCommand(root, alias);
                    this.removeCommand(root, namespace + ":" + alias);
                }
            }
        }

        this.purgeReloadableCommands();

        this.plugin.getLogger().info("Commands unregistered dynamically!");

        // Sync commands to all players so client autocomplete updates
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
            this.plugin.getLogger().warning(
                    "Failed to unregister command '" + command.getName() + "' from the server tree "
                            + "(this may indicate a Paper API change): " + e
            );
            return false;
        }

        String namespace = command.getPlugin().getPluginMeta().namespace();
        String name = command.getName();
        this.removeCommand(root, name);
        this.removeCommand(root, namespace + ":" + name);

        for (String alias : command.getAliases()) {
            this.removeCommand(root, alias);
            this.removeCommand(root, namespace + ":" + alias);
        }

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

    private interface ReloadableCommandAction {
        void accept(BaseCommand<?> command);
    }

    private void forEachReloadableCommand(ReloadableCommandAction action) {
        for (List<BaseCommand<?>> pluginCommands : this.commands.values()) {
            for (BaseCommand<?> command : pluginCommands) {
                if (command.isReloadable()) {
                    action.accept(command);
                }
            }
        }
    }

    /**
     * Compile-safe helper that removes a command from the tree at runtime.
     */
    private void removeCommand(CommandNode<CommandSourceStack> node, String name) {
        Method removeCommandMethod = null;
        try {
            Class<?> current = node.getClass();
            while (current != null && current != Object.class) {
                try {
                    removeCommandMethod = current.getDeclaredMethod("removeCommand", String.class);
                    break;
                } catch (NoSuchMethodException e) {
                    current = current.getSuperclass();
                }
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning("Failed to look up removeCommand method for '" + name + "': " + e);
        }

        if (removeCommandMethod != null) {
            try {
                removeCommandMethod.setAccessible(true);
                removeCommandMethod.invoke(node, name);
                return;
            } catch (ReflectiveOperationException e) {
                this.plugin.getLogger().warning(
                        "removeCommand invocation failed for '" + name + "', falling back to field access: " + e
                );
                // fall through to the field-based fallback below
            }
        }

        // Safe fallback: manually modify the children, literals, and arguments maps in CommandNode
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
            this.plugin.getLogger().warning("Fallback field-based removal failed for '" + name + "': " + e);
        }
    }

    /**
     * Refreshes the command tree for all online players.
     */
    private void syncCommands() {
        try {
            Method syncCommands = org.bukkit.Bukkit.getServer().getClass().getMethod("syncCommands");
            syncCommands.invoke(org.bukkit.Bukkit.getServer());
        } catch (Exception e) {
            this.plugin.getLogger().warning("Failed to sync commands dynamically: " + e.getMessage());
        }
    }
}