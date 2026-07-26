package fr.robie.paperdispatch.manager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.paperdispatch.command.BaseCommand;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.logger.PluginLogger;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CommandManager}'s bookkeeping: what it tracks, what it replaces, and what it
 * forgets. This module previously had no tests at all.
 *
 * <p><b>Scope note:</b> the reflective paths into {@code PaperCommands} cannot run under
 * MockBukkit — that class only exists on a real server. These tests therefore assert the
 * tracking logic plus graceful degradation when those internals are unavailable, which is
 * exactly what the manager promises to do.
 */
class CommandManagerTest {

    private Plugin plugin;
    private CommandManager<Plugin> manager;
    /** WARNING and above. */
    private List<String> warnings;
    /** Every level, for assertions about informational output. */
    private List<String> messages;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.plugin = PluginMock.builder().withPluginName("TestPlugin").build();
        this.warnings = new ArrayList<>();
        this.messages = new ArrayList<>();
        PluginLogger capturing = (level, message) -> {
            this.messages.add(message);
            if (level.intValue() >= Level.WARNING.intValue()) {
                this.warnings.add(message);
            }
        };
        this.manager = new CommandManager<>(this.plugin, capturing);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BaseCommand<Plugin> command(String name, String... aliases) {
        return BaseCommand.builder(this.plugin, name)
                .alias(aliases)
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();
    }

    private BaseCommand<Plugin> reloadableCommand(String name) {
        return BaseCommand.builder(this.plugin, name)
                .reloadable(true)
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();
    }

    // ------------------------------------------------------------------
    // Tracking
    // ------------------------------------------------------------------

    @Test
    @DisplayName("trackCommand should track the command for its owning plugin")
    void testRegisterTracksCommand() {
        BaseCommand<Plugin> cmd = this.command("test");
        this.manager.trackCommand(cmd);

        assertTrue(this.manager.isRegistered(cmd));
        assertTrue(this.manager.isRegistered(this.plugin, "test"));
        assertEquals(List.of(cmd), this.manager.getCommands(this.plugin));
        assertSame(cmd, this.manager.getCommand(this.plugin, "test"));
    }

    @Test
    @DisplayName("Lookups by name and alias should be case-insensitive")
    void testLookupIsCaseInsensitive() {
        BaseCommand<Plugin> cmd = this.command("test", "tst");
        this.manager.trackCommand(cmd);

        assertTrue(this.manager.isRegistered(this.plugin, "TEST"));
        assertTrue(this.manager.isRegistered(this.plugin, "TsT"));
        assertSame(cmd, this.manager.getCommand(this.plugin, "tEsT"));
        assertSame(cmd, this.manager.getCommand(this.plugin, "TST"));
    }

    @Test
    @DisplayName("Unknown names should not resolve")
    void testUnknownNameDoesNotResolve() {
        this.manager.trackCommand(this.command("test"));

        assertFalse(this.manager.isRegistered(this.plugin, "nope"));
        assertNull(this.manager.getCommand(this.plugin, "nope"));
    }

    @Test
    @DisplayName("getCommands should return an empty list for an unknown plugin")
    void testGetCommandsForUnknownPlugin() {
        Plugin other = PluginMock.builder().withPluginName("Other").build();
        assertTrue(this.manager.getCommands(other).isEmpty());
    }

    @Test
    @DisplayName("getCommands should return an unmodifiable snapshot")
    void testGetCommandsIsUnmodifiableSnapshot() {
        BaseCommand<Plugin> cmd = this.command("test");
        this.manager.trackCommand(cmd);

        List<BaseCommand<?>> snapshot = this.manager.getCommands(this.plugin);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(this.command("other")));

        this.manager.trackCommand(this.command("second"));
        assertEquals(1, snapshot.size());
    }

    // ------------------------------------------------------------------
    // Replacement semantics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Re-registering the same name should replace the previous command")
    void testReRegisterReplacesByName() {
        BaseCommand<Plugin> first = this.command("dup");
        BaseCommand<Plugin> second = this.command("dup");

        this.manager.trackCommand(first);
        this.manager.trackCommand(second);

        assertFalse(this.manager.isRegistered(first), "the replaced command must be dropped");
        assertTrue(this.manager.isRegistered(second));
        assertEquals(List.of(second), this.manager.getCommands(this.plugin));
    }

    @Test
    @DisplayName("A new command whose alias collides with an existing command replaces it")
    void testAliasCollisionReplacesExisting() {
        BaseCommand<Plugin> existing = this.command("alpha");
        BaseCommand<Plugin> colliding = this.command("beta", "alpha");

        this.manager.trackCommand(existing);
        this.manager.trackCommand(colliding);

        assertFalse(this.manager.isRegistered(existing));
        assertTrue(this.manager.isRegistered(colliding));
    }

    // ------------------------------------------------------------------
    // Removal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unregisterCommand should drop the command from tracking")
    void testUnregisterCommand() {
        BaseCommand<Plugin> cmd = this.command("test");
        this.manager.trackCommand(cmd);
        this.manager.unregisterCommand(cmd);

        assertFalse(this.manager.isRegistered(cmd));
        assertFalse(this.manager.isRegistered(this.plugin, "test"));
        assertTrue(this.manager.getCommands(this.plugin).isEmpty());
    }

    @Test
    @DisplayName("unregisterCommand on an untracked command should be a harmless no-op")
    void testUnregisterUntrackedCommand() {
        BaseCommand<Plugin> tracked = this.command("tracked");
        this.manager.trackCommand(tracked);

        assertDoesNotThrow(() -> this.manager.unregisterCommand(this.command("ghost")));
        assertTrue(this.manager.isRegistered(tracked), "the tracked command must be untouched");
    }

    @Test
    @DisplayName("unregisterCommands should batch-remove every given command")
    void testUnregisterCommandsBatch() {
        BaseCommand<Plugin> a = this.command("a");
        BaseCommand<Plugin> b = this.command("b");
        BaseCommand<Plugin> c = this.command("c");
        this.manager.trackCommand(a);
        this.manager.trackCommand(b);
        this.manager.trackCommand(c);

        this.manager.unregisterCommands(List.of(a, b));

        assertFalse(this.manager.isRegistered(a));
        assertFalse(this.manager.isRegistered(b));
        assertTrue(this.manager.isRegistered(c));
    }

    @Test
    @DisplayName("unregisterAll should clear every command for the plugin")
    void testUnregisterAll() {
        this.manager.trackCommand(this.command("a"));
        this.manager.trackCommand(this.command("b"));

        this.manager.unregisterAll(this.plugin);

        assertTrue(this.manager.getCommands(this.plugin).isEmpty());
    }

    @Test
    @DisplayName("unregisterAll should not disturb another plugin's commands")
    void testUnregisterAllIsScopedToPlugin() {
        Plugin other = PluginMock.builder().withPluginName("Other").build();
        BaseCommand<Plugin> mine = this.command("mine");
        BaseCommand<Plugin> theirs = BaseCommand.builder(other, "theirs")
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();

        this.manager.trackCommand(mine);
        this.manager.trackCommand(theirs);

        this.manager.unregisterAll(this.plugin);

        assertTrue(this.manager.getCommands(this.plugin).isEmpty());
        assertEquals(List.of(theirs), this.manager.getCommands(other));
    }

    @Test
    @DisplayName("unregisterAll on a plugin with no commands should be a no-op")
    void testUnregisterAllWithNoCommands() {
        assertDoesNotThrow(() -> this.manager.unregisterAll(this.plugin));
    }

    @Test
    @DisplayName("No-arg unregisterAll should drop every tracked command whatever plugin owns it")
    void testUnregisterAllAcrossPlugins() {
        Plugin other = PluginMock.builder().withPluginName("Other").build();
        BaseCommand<Plugin> mine = this.command("mine");
        BaseCommand<Plugin> theirs = BaseCommand.builder(other, "theirs")
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();

        this.manager.trackCommand(mine);
        this.manager.trackCommand(theirs);

        this.manager.unregisterAll();

        assertFalse(this.manager.isRegistered(mine));
        assertFalse(this.manager.isRegistered(theirs));
        assertTrue(this.manager.getCommands(this.plugin).isEmpty());
        assertTrue(this.manager.getCommands(other).isEmpty());
    }

    @Test
    @DisplayName("No-arg unregisterAll should report when it removes another plugin's commands")
    void testUnregisterAllReportsForeignOwners() {
        Plugin other = PluginMock.builder().withPluginName("Other").build();
        this.manager.trackCommand(this.command("mine"));
        this.manager.trackCommand(BaseCommand.builder(other, "theirs")
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build());

        this.manager.unregisterAll();

        assertTrue(this.messages.stream().anyMatch(m -> m.contains("Other") && m.contains("unregisterAll(Plugin)")),
                "removing a foreign plugin's commands should be stated, got: " + this.messages);
    }

    @Test
    @DisplayName("No-arg unregisterAll should stay quiet when every command is our own")
    void testUnregisterAllQuietForOwnCommands() {
        this.manager.trackCommand(this.command("mine"));
        this.manager.trackCommand(this.command("also-mine"));

        this.manager.unregisterAll();

        assertTrue(this.messages.stream().noneMatch(m -> m.contains("unregisterAll(Plugin)")),
                "the ordinary case must not be chatty: " + this.messages);
    }

    @Test
    @DisplayName("No-arg unregisterAll should ignore the reloadable flag")
    void testUnregisterAllIgnoresReloadableFlag() {
        BaseCommand<Plugin> permanent = this.command("permanent");
        BaseCommand<Plugin> reloadable = this.reloadableCommand("reloadable");
        this.manager.trackCommand(permanent);
        this.manager.trackCommand(reloadable);

        this.manager.unregisterAll();

        assertFalse(this.manager.isRegistered(permanent),
                "unlike unregisterReloadableCommands(), this must not spare non-reloadable commands");
        assertFalse(this.manager.isRegistered(reloadable));
    }

    @Test
    @DisplayName("No-arg unregisterAll should be a harmless no-op when nothing is tracked")
    void testUnregisterAllEmpty() {
        assertDoesNotThrow(() -> this.manager.unregisterAll());
        assertDoesNotThrow(() -> this.manager.unregisterAll());
    }

    // ------------------------------------------------------------------
    // Reloadable purging
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unregisterReloadableCommands() should purge reloadable commands and keep the rest")
    void testUnregisterCommandsPurgesOnlyReloadable() {
        BaseCommand<Plugin> permanent = this.command("permanent");
        BaseCommand<Plugin> reloadable = this.reloadableCommand("reloadable");
        this.manager.trackCommand(permanent);
        this.manager.trackCommand(reloadable);

        this.manager.unregisterReloadableCommands();

        assertFalse(this.manager.isRegistered(reloadable), "reloadable commands must be dropped");
        assertTrue(this.manager.isRegistered(permanent), "non-reloadable commands must survive");
    }

    @Test
    @DisplayName("unregisterReloadableCommands() should be safe to call repeatedly")
    void testUnregisterCommandsIsRepeatable() {
        this.manager.trackCommand(this.reloadableCommand("r1"));

        assertDoesNotThrow(() -> this.manager.unregisterReloadableCommands());
        assertDoesNotThrow(() -> this.manager.unregisterReloadableCommands());

        assertTrue(this.manager.getCommands(this.plugin).isEmpty());
    }

    // ------------------------------------------------------------------
    // Graceful degradation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("flushRegistrations() should degrade gracefully when Paper internals are absent")
    void testRegisterCommandsDegradesGracefully() {
        this.manager.trackCommand(this.command("test"));

        assertDoesNotThrow(() -> this.manager.flushRegistrations());
        assertTrue(
                this.warnings.stream().anyMatch(w -> w.contains("PaperCommands")
                        || w.contains("allowsLifecycleRegistration")),
                "expected a diagnostic about the unavailable internals, got: " + this.warnings
        );
    }

    @Test
    @DisplayName("A normal JavaPlugin must NOT trigger the missing-field warning")
    void testLifecycleFieldFoundOnJavaPlugin() {
        this.manager.trackCommand(this.command("test"));
        this.manager.flushRegistrations();

        assertFalse(
                this.warnings.stream().anyMatch(w -> w.contains("allowsLifecycleRegistration")),
                "the field is present on JavaPlugin; warning about it would cry wolf: " + this.warnings
        );
    }

    @Test
    @DisplayName("A Plugin without allowsLifecycleRegistration should be reported, not swallowed")
    void testMissingLifecycleFieldIsReported() {
        Plugin fieldless = Mockito.mock(Plugin.class);
        when(fieldless.getLogger()).thenReturn(java.util.logging.Logger.getLogger("fieldless"));

        List<String> captured = new ArrayList<>();
        CommandManager<Plugin> bare = new CommandManager<>(fieldless, (level, message) -> {
            if (level.intValue() >= Level.WARNING.intValue()) {
                captured.add(message);
            }
        });

        bare.flushRegistrations();

        assertTrue(
                captured.stream().anyMatch(w -> w.contains("allowsLifecycleRegistration")),
                "expected a warning naming the missing field, got: " + captured
        );
    }

    @Test
    @DisplayName("The missing-field warning should be logged once, not on every call")
    void testMissingLifecycleFieldWarnsOnce() {
        Plugin fieldless = Mockito.mock(Plugin.class);
        when(fieldless.getLogger()).thenReturn(java.util.logging.Logger.getLogger("fieldless"));

        List<String> captured = new ArrayList<>();
        CommandManager<Plugin> bare = new CommandManager<>(fieldless, (level, message) -> {
            if (level.intValue() >= Level.WARNING.intValue()) {
                captured.add(message);
            }
        });

        bare.flushRegistrations();
        bare.flushRegistrations();
        bare.unregisterReloadableCommands();

        assertEquals(1, captured.stream().filter(w -> w.contains("allowsLifecycleRegistration")).count(),
                "the diagnostic must not spam the console: " + captured);
    }

    // ------------------------------------------------------------------
    // COMMANDS lifecycle event handling
    // ------------------------------------------------------------------

    /** A registrar that records what was registered and reports every label as accepted. */
    private static Commands recordingRegistrar(List<String> registeredNames) {
        Commands registrar = Mockito.mock(Commands.class);
        when(registrar.getDispatcher()).thenReturn(new CommandDispatcher<>());
        when(registrar.register(anyMeta(), anyNode(), nullable(String.class), anyAliases())).thenAnswer(invocation -> {
            LiteralCommandNode<CommandSourceStack> node = invocation.getArgument(1);
            Collection<String> aliases = invocation.getArgument(3);
            registeredNames.add(node.getName());
            Set<String> accepted = new HashSet<>(aliases);
            accepted.add(node.getName());
            return accepted;
        });
        return registrar;
    }

    private static PluginMeta anyMeta() {
        return ArgumentMatchers.any();
    }

    private static LiteralCommandNode<CommandSourceStack> anyNode() {
        return ArgumentMatchers.any();
    }

    private static Collection<String> anyAliases() {
        return ArgumentMatchers.any();
    }

    @Test
    @DisplayName("A second COMMANDS event must re-register every command into the fresh dispatcher")
    void testCommandsEventReRegisters() {
        this.manager.trackCommand(this.command("alpha"));
        this.manager.trackCommand(this.command("beta"));

        List<String> firstPass = new ArrayList<>();
        this.manager.handleCommandsEvent(recordingRegistrar(firstPass));
        assertEquals(List.of("alpha", "beta"), firstPass.stream().sorted().toList());

        List<String> secondPass = new ArrayList<>();
        this.manager.handleCommandsEvent(recordingRegistrar(secondPass));
        assertEquals(List.of("alpha", "beta"), secondPass.stream().sorted().toList(),
                "commands must be re-registered on every COMMANDS event, not just the first");
    }

    @Test
    @DisplayName("Each command registers under its OWNING plugin's meta, not the manager's")
    void testRegistrationUsesOwningPluginMeta() {
        Plugin other = PluginMock.builder().withPluginName("Other").build();
        BaseCommand<Plugin> mine = this.command("mine");
        BaseCommand<Plugin> theirs = BaseCommand.builder(other, "theirs")
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();

        this.manager.trackCommand(mine);
        this.manager.trackCommand(theirs);

        Map<String, PluginMeta> metaByCommand = new HashMap<>();
        Commands registrar = Mockito.mock(Commands.class);
        when(registrar.getDispatcher()).thenReturn(new CommandDispatcher<>());
        when(registrar.register(anyMeta(), anyNode(), nullable(String.class), anyAliases()))
                .thenAnswer(invocation -> {
                    PluginMeta meta = invocation.getArgument(0);
                    LiteralCommandNode<CommandSourceStack> node = invocation.getArgument(1);
                    metaByCommand.put(node.getName(), meta);
                    return Set.of(node.getName());
                });

        this.manager.handleCommandsEvent(registrar);

        assertEquals(this.plugin.getPluginMeta(), metaByCommand.get("mine"));
        assertEquals(other.getPluginMeta(), metaByCommand.get("theirs"),
                "a hosted command must be registered under its own plugin's meta");
    }

    @Test
    @DisplayName("Aliases the server refuses should be reported, not assumed successful")
    void testRejectedAliasesAreLogged() {
        this.manager.trackCommand(this.command("cmd", "good", "taken"));

        Commands registrar = Mockito.mock(Commands.class);
        when(registrar.getDispatcher()).thenReturn(new CommandDispatcher<>());
        when(registrar.register(anyMeta(), anyNode(), nullable(String.class), anyAliases())).thenReturn(Set.of("cmd", "good"));

        this.manager.handleCommandsEvent(registrar);

        assertTrue(this.warnings.stream().anyMatch(w -> w.contains("declined these aliases") && w.contains("taken")),
                "expected a warning naming the refused alias, got: " + this.warnings);
    }

    @Test
    @DisplayName("An alias available only under its namespace is still reported as declined")
    void testNamespacedOnlyAliasIsReported() {
        this.manager.trackCommand(this.command("cmd", "alias"));

        Commands registrar = Mockito.mock(Commands.class);
        when(registrar.getDispatcher()).thenReturn(new CommandDispatcher<>());
        when(registrar.register(anyMeta(), anyNode(), nullable(String.class), anyAliases()))
                .thenReturn(Set.of("cmd", "testplugin:cmd", "testplugin:alias"));

        this.manager.handleCommandsEvent(registrar);

        assertTrue(this.warnings.stream().anyMatch(w -> w.contains("declined these aliases") && w.contains("alias")),
                "expected the bare alias to be reported as declined, got: " + this.warnings);
    }

    @Test
    @DisplayName("Aliases the server accepted should produce no warning")
    void testAcceptedAliasesAreSilent() {
        this.manager.trackCommand(this.command("cmd", "a", "b"));

        Commands registrar = Mockito.mock(Commands.class);
        when(registrar.getDispatcher()).thenReturn(new CommandDispatcher<>());
        when(registrar.register(anyMeta(), anyNode(), nullable(String.class), anyAliases()))
                .thenReturn(Set.of("cmd", "a", "b", "testplugin:cmd"));

        this.manager.handleCommandsEvent(registrar);

        assertTrue(this.warnings.stream().noneMatch(w -> w.contains("declined these aliases")),
                "fully accepted aliases must not warn: " + this.warnings);
    }

    // ------------------------------------------------------------------
    // Deprecated aliases
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    @DisplayName("Deprecated registerCommand should still track, exactly like trackCommand")
    void testDeprecatedRegisterCommandDelegates() {
        BaseCommand<Plugin> cmd = this.command("legacy");
        this.manager.registerCommand(cmd);

        assertTrue(this.manager.isRegistered(cmd));
        assertSame(cmd, this.manager.getCommand(this.plugin, "legacy"));
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    @DisplayName("Deprecated registerCommands/unregisterCommands should delegate to the new names")
    void testDeprecatedLifecycleAliasesDelegate() {
        BaseCommand<Plugin> permanent = this.command("permanent");
        BaseCommand<Plugin> reloadable = this.reloadableCommand("reloadable");
        this.manager.registerCommand(permanent);
        this.manager.registerCommand(reloadable);

        assertDoesNotThrow(() -> this.manager.registerCommands());

        this.manager.unregisterCommands();

        assertFalse(this.manager.isRegistered(reloadable));
        assertTrue(this.manager.isRegistered(permanent));
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    @DisplayName("Deprecated builder overload should build, track and return the command")
    void testDeprecatedBuilderOverloadDelegates() {
        BaseCommand<Plugin> built = this.manager.registerCommand(
                BaseCommand.builder(this.plugin, "built").executes(d -> CommandResultType.SUCCESS));

        assertNotNull(built);
        assertEquals("built", built.getName());
        assertTrue(this.manager.isRegistered(built));
    }

    @Test
    @DisplayName("trackCommand builder overload should build, track and return the command")
    void testTrackCommandBuilderOverload() {
        BaseCommand<Plugin> built = this.manager.trackCommand(
                BaseCommand.builder(this.plugin, "built").executes(d -> CommandResultType.SUCCESS));

        assertNotNull(built);
        assertEquals("built", built.getName());
        assertTrue(this.manager.isRegistered(built));
    }

    @Test
    @DisplayName("Commands tracked after flushRegistrations() must not be silently forgotten")
    void testLateRegistrationIsNotSilentlyDropped() {
        this.manager.flushRegistrations();

        BaseCommand<Plugin> late = this.command("late");
        assertDoesNotThrow(() -> this.manager.trackCommand(late));

        assertTrue(this.manager.isRegistered(late));
        assertSame(late, this.manager.getCommand(this.plugin, "late"));
    }
}
