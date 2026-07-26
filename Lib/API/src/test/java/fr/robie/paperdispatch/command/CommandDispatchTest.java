package fr.robie.paperdispatch.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.StringRange;
import fr.robie.paperdispatch.flag.Flag;
import fr.robie.paperdispatch.flag.FlagContext;
import fr.robie.paperdispatch.flag.Flags;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Covers {@link CommandDispatch}'s argument accessors.
 *
 * <p>The key behaviour under test: Brigadier reports "no such argument" and "argument is of a
 * different type" with the <i>same</i> {@link IllegalArgumentException}. Catching it blindly
 * turns a caller's type mistake into a silently-swallowed empty/default result, so absence and
 * type mismatch must be distinguished.
 */
class CommandDispatchTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.plugin = PluginMock.builder().withPluginName("TestPlugin").build();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Builds a dispatch whose context carries exactly the given parsed arguments. */
    private CommandDispatch<Plugin> dispatchWith(Map<String, Object> arguments, List<Flag<?>> flags) {
        Map<String, ParsedArgument<CommandSourceStack, ?>> parsed = new HashMap<>();
        arguments.forEach((name, value) -> parsed.put(name, new ParsedArgument<>(0, 0, value)));

        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);
        CommandContext<CommandSourceStack> context = new CommandContext<>(
                source, "input", parsed, null, null, List.of(), StringRange.at(0), null, null, false
        );
        return new CommandDispatch<>(this.plugin, context, FlagContext.empty(), flags);
    }

    private CommandDispatch<Plugin> dispatchWith(Map<String, Object> arguments) {
        return this.dispatchWith(arguments, List.of());
    }

    // ------------------------------------------------------------------
    // Presence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hasArgument distinguishes present from absent regardless of type")
    void testHasArgument() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("name", "value", "count", 7));

        assertTrue(dispatch.hasArgument("name"));
        assertTrue(dispatch.hasArgument("count"));
        assertFalse(dispatch.hasArgument("missing"));
    }

    @Test
    @DisplayName("getArgument returns the value for the correct type")
    void testGetArgument() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("name", "value", "count", 7));

        assertEquals("value", dispatch.getArgument("name", String.class));
        assertEquals(7, dispatch.getArgument("count", Integer.class));
    }

    // ------------------------------------------------------------------
    // A3 -- absence vs type mismatch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getOptionalArgument returns empty for an absent argument")
    void testOptionalArgumentAbsent() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("name", "value"));
        assertEquals(Optional.empty(), dispatch.getOptionalArgument("missing", String.class));
    }

    @Test
    @DisplayName("getOptionalArgument returns the value when present")
    void testOptionalArgumentPresent() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("name", "value"));
        assertEquals(Optional.of("value"), dispatch.getOptionalArgument("name", String.class));
    }

    @Test
    @DisplayName("getOptionalArgument propagates a genuine type mismatch instead of hiding it")
    void testOptionalArgumentTypeMismatchThrows() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("count", 7));

        assertThrows(IllegalArgumentException.class,
                () -> dispatch.getOptionalArgument("count", String.class));
    }

    @Test
    @DisplayName("getArgument with a default returns the default only when absent")
    void testGetArgumentWithDefaultAbsent() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("name", "value"));

        assertEquals("fallback", dispatch.getArgument("missing", String.class, "fallback"));
        assertEquals("value", dispatch.getArgument("name", String.class, "fallback"));
    }

    @Test
    @DisplayName("getArgument with a default propagates a genuine type mismatch")
    void testGetArgumentWithDefaultTypeMismatchThrows() {
        CommandDispatch<Plugin> dispatch = this.dispatchWith(Map.of("count", 7));

        assertThrows(IllegalArgumentException.class,
                () -> dispatch.getArgument("count", String.class, "fallback"));
    }

    // ------------------------------------------------------------------
    // Flag-like values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An argument whose value is a registered flag token reads as absent")
    void testFlagLikeValueTreatedAsAbsent() {
        List<Flag<?>> flags = List.of(Flags.boolFlag("verbose").alias("v"));

        CommandDispatch<Plugin> longForm = this.dispatchWith(Map.of("arg", "--verbose"), flags);
        assertEquals(Optional.empty(), longForm.getOptionalArgument("arg", String.class));
        assertEquals("fallback", longForm.getArgument("arg", String.class, "fallback"));

        CommandDispatch<Plugin> shortForm = this.dispatchWith(Map.of("arg", "-v"), flags);
        assertEquals(Optional.empty(), shortForm.getOptionalArgument("arg", String.class));

        CommandDispatch<Plugin> ordinary = this.dispatchWith(Map.of("arg", "verbose"), flags);
        assertEquals(Optional.of("verbose"), ordinary.getOptionalArgument("arg", String.class),
                "a bare word matching a flag name without dashes is a normal value");
    }

    // ------------------------------------------------------------------
    // Sender helpers
    // ------------------------------------------------------------------

    /** Builds a dispatch with no arguments and the given sender. */
    private CommandDispatch<Plugin> dispatchFrom(CommandSender sender) {
        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);

        CommandContext<CommandSourceStack> context = new CommandContext<>(
                source, "input", new HashMap<>(), null, null, List.of(), StringRange.at(0), null, null, false
        );
        return new CommandDispatch<>(this.plugin, context, FlagContext.empty(), List.of());
    }

    @Test
    @DisplayName("getSenderAsPlayer returns null for a non-player sender")
    void testSenderAsPlayerForConsole() {
        CommandSender console = Mockito.mock(CommandSender.class);
        CommandDispatch<Plugin> dispatch = this.dispatchFrom(console);

        assertNull(dispatch.getSenderAsPlayer());
        assertSame(console, dispatch.getSender());
    }

    @Test
    @DisplayName("getSenderAsPlayer returns the sender when it is a player")
    void testSenderAsPlayerForPlayer() {
        Player player = Mockito.mock(Player.class);
        assertSame(player, this.dispatchFrom(player).getSenderAsPlayer());
    }

    @Test
    @DisplayName("getExecutor and getLocation delegate to the command source")
    void testExecutorAndLocation() {
        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);
        CommandSender sender = Mockito.mock(CommandSender.class);
        Entity executor = Mockito.mock(Entity.class);
        Location location = new Location(null, 1, 2, 3);

        when(source.getSender()).thenReturn(sender);
        when(source.getExecutor()).thenReturn(executor);
        when(source.getLocation()).thenReturn(location);

        CommandContext<CommandSourceStack> context = new CommandContext<>(
                source, "input", new HashMap<>(), null, null, List.of(), StringRange.at(0), null, null, false
        );
        CommandDispatch<Plugin> dispatch =
                new CommandDispatch<>(this.plugin, context, FlagContext.empty(), List.of());

        assertSame(executor, dispatch.getExecutor());
        assertSame(location, dispatch.getLocation());
        assertSame(sender, dispatch.getSender());
        assertNotSame(dispatch.getExecutor(), dispatch.getSender(),
                "executor and sender are distinct concepts");
    }

    @Test
    @DisplayName("getExecutor returns null for a source with no entity")
    void testExecutorForConsole() {
        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(Mockito.mock(CommandSender.class));
        when(source.getExecutor()).thenReturn(null);

        CommandContext<CommandSourceStack> context = new CommandContext<>(
                source, "input", new HashMap<>(), null, null, List.of(), StringRange.at(0), null, null, false
        );
        assertNull(new CommandDispatch<>(this.plugin, context, FlagContext.empty(), List.of()).getExecutor());
    }

    @Test
    @DisplayName("getSenderAsPlayer is about the sender, resolvePlayer is about an argument")
    void testSenderAndArgumentAccessorsAreIndependent() {
        Player sender = Mockito.mock(Player.class);
        CommandDispatch<Plugin> dispatch = this.dispatchFrom(sender);

        assertSame(sender, dispatch.getSenderAsPlayer());
        assertEquals(Optional.empty(), dispatch.resolvePlayer("target"));
    }

    // ------------------------------------------------------------------
    // Deprecated aliases
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    @DisplayName("Deprecated accessors still delegate to their replacements")
    void testDeprecatedAliasesDelegate() {
        Player player = Mockito.mock(Player.class);
        CommandDispatch<Plugin> dispatch = this.dispatchFrom(player);

        assertSame(dispatch.getSenderAsPlayer(), dispatch.getPlayer());
        assertEquals(dispatch.resolvePlayer("x"), dispatch.getOptionalPlayer("x"));
        assertEquals(dispatch.resolvePlayers("x"), dispatch.getOptionalPlayers("x"));
        assertEquals(dispatch.resolveEntity("x"), dispatch.getOptionalEntity("x"));
        assertEquals(dispatch.resolveEntities("x"), dispatch.getOptionalEntities("x"));
        assertEquals(dispatch.resolvePlayerProfiles("x"), dispatch.getOptionalPlayerProfiles("x"));
        assertEquals(dispatch.resolveBlockPosition("x"), dispatch.getOptionalBlockPosition("x"));
        assertEquals(dispatch.resolveFinePosition("x"), dispatch.getOptionalFinePosition("x"));
    }
}
