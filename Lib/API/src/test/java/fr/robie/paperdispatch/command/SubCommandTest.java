package fr.robie.paperdispatch.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.paperdispatch.flag.Flags;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SubCommand#build()} — the tree the library actually hands to Brigadier.
 * Previously untested, which is how the flag-tree size, the dropped-children bug and the
 * no-op {@code requiresConfirmation} all went unnoticed.
 */
class SubCommandTest {

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

    /** Total node count of a tree, including the root literal. */
    private static int countNodes(CommandNode<CommandSourceStack> node) {
        int total = 1;
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            total += countNodes(child);
        }
        return total;
    }

    private SubCommand<Plugin> flagCommand(String name, int boolFlags) {
        return new SubCommand<>(this.plugin, name) {
            {
                for (int i = 0; i < boolFlags; i++) {
                    this.addFlag(Flags.boolFlag("f" + i));
                }
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };
    }

    // ------------------------------------------------------------------
    // Flag tree size
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Flag tree materialises one node per ordered subset of the flag set")
    void testFlagTreeNodeCounts() {
        assertEquals(1, countNodes(this.flagCommand("c0", 0).build()));
        assertEquals(2, countNodes(this.flagCommand("c1", 1).build()));
        assertEquals(5, countNodes(this.flagCommand("c2", 2).build()));
        assertEquals(16, countNodes(this.flagCommand("c3", 3).build()));
        assertEquals(65, countNodes(this.flagCommand("c4", 4).build()));
        assertEquals(326, countNodes(this.flagCommand("c5", 5).build()));
    }

    // ------------------------------------------------------------------
    // Flag-tree warning threshold
    // ------------------------------------------------------------------

    /** Captures what the plugin logger emits, so the warning itself is assertable. */
    private List<String> captureLogs() {
        List<String> records = new ArrayList<>();
        this.plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return records;
    }

    @Test
    @DisplayName("The flag-tree warning fires past the default threshold and not below it")
    void testFlagWarningDefaultThreshold() {
        List<String> logs = this.captureLogs();

        this.flagCommand("under", SubCommand.DEFAULT_FLAG_COUNT_WARN_THRESHOLD);
        assertTrue(logs.stream().noneMatch(m -> m.contains("Brigadier nodes")),
                "at the threshold the warning must stay quiet: " + logs);

        this.flagCommand("over", SubCommand.DEFAULT_FLAG_COUNT_WARN_THRESHOLD + 1);
        assertTrue(logs.stream().anyMatch(m -> m.contains("Brigadier nodes")),
                "one flag past the threshold must warn: " + logs);
    }

    @Test
    @DisplayName("A raised threshold suppresses the warning")
    void testRaisedThresholdSuppressesWarning() {
        List<String> logs = this.captureLogs();

        SubCommand.builder(this.plugin, "many")
                .flagCountWarnThreshold(8)
                .addFlags(Flags.boolFlag("a"), Flags.boolFlag("b"), Flags.boolFlag("c"),
                        Flags.boolFlag("d"), Flags.boolFlag("e"), Flags.boolFlag("f"))
                .build();

        assertTrue(logs.stream().noneMatch(m -> m.contains("Brigadier nodes")),
                "6 flags under a threshold of 8 must not warn: " + logs);
    }

    @Test
    @DisplayName("FLAG_COUNT_WARN_DISABLED silences the warning entirely")
    void testDisabledThreshold() {
        List<String> logs = this.captureLogs();

        SubCommand.builder(this.plugin, "silent")
                .flagCountWarnThreshold(SubCommand.FLAG_COUNT_WARN_DISABLED)
                .addFlags(Flags.boolFlag("a"), Flags.boolFlag("b"), Flags.boolFlag("c"),
                        Flags.boolFlag("d"), Flags.boolFlag("e"), Flags.boolFlag("f"))
                .build();

        assertTrue(logs.stream().noneMatch(m -> m.contains("Brigadier nodes")), "expected silence: " + logs);
    }

    @Test
    @DisplayName("A lowered threshold warns sooner")
    void testLoweredThresholdWarnsSooner() {
        List<String> logs = this.captureLogs();

        SubCommand.builder(this.plugin, "picky")
                .flagCountWarnThreshold(1)
                .addFlags(Flags.boolFlag("a"), Flags.boolFlag("b"))
                .build();

        assertTrue(logs.stream().anyMatch(m -> m.contains("Brigadier nodes")),
                "2 flags over a threshold of 1 must warn: " + logs);
    }

    @Test
    @DisplayName("The builder applies the threshold before flags, whatever order it is called in")
    void testThresholdIsOrderIndependentOnBuilder() {
        List<String> logs = this.captureLogs();

        SubCommand.builder(this.plugin, "late")
                .addFlags(Flags.boolFlag("a"), Flags.boolFlag("b"), Flags.boolFlag("c"),
                        Flags.boolFlag("d"), Flags.boolFlag("e"))
                .flagCountWarnThreshold(SubCommand.FLAG_COUNT_WARN_DISABLED)
                .build();

        assertTrue(logs.stream().noneMatch(m -> m.contains("Brigadier nodes")),
                "builder ordering must not matter: " + logs);
    }

    @Test
    @DisplayName("A negative threshold is rejected")
    void testNegativeThresholdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SubCommand.builder(this.plugin, "bad").flagCountWarnThreshold(-1));
    }

    @Test
    @DisplayName("The threshold is readable and defaults to the documented constant")
    void testThresholdAccessor() {
        assertEquals(SubCommand.DEFAULT_FLAG_COUNT_WARN_THRESHOLD,
                SubCommand.builder(this.plugin, "a").build().getFlagCountWarnThreshold());
        assertEquals(9,
                SubCommand.builder(this.plugin, "b").flagCountWarnThreshold(9).build().getFlagCountWarnThreshold());
    }

    @Test
    @DisplayName("Flags can be supplied in any order and any subset")
    void testFlagOrderIndependence() {
        LiteralCommandNode<CommandSourceStack> node = this.flagCommand("cmd", 3).build();

        assertNotNull(node.getChild("--f0").getChild("--f2"));
        assertNotNull(node.getChild("--f2").getChild("--f0"));
        assertNotNull(node.getChild("--f1").getCommand());
    }

    @Test
    @DisplayName("Value flags get a typed argument node named with the flag-value prefix")
    void testValueFlagArgumentNode() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addFlag(Flags.intFlag("count"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        CommandNode<CommandSourceStack> flagNode = cmd.build().getChild("--count");
        assertNotNull(flagNode);
        CommandNode<CommandSourceStack> valueNode = flagNode.getChild(cmd.getFlagValuePrefix() + "count");
        assertNotNull(valueNode, "a value flag must expose a real typed argument node");
        assertNotNull(valueNode.getCommand(), "the value node must be executable");
    }

    @Test
    @DisplayName("Single-character flag aliases register as -x, multi-character as --xx")
    void testFlagAliasTokens() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addFlag(Flags.boolFlag("verbose").alias("v", "loud"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        LiteralCommandNode<CommandSourceStack> node = cmd.build();
        assertNotNull(node.getChild("--verbose"));
        assertNotNull(node.getChild("-v"));
        assertNotNull(node.getChild("--loud"));
    }

    // ------------------------------------------------------------------
    // A1 -- pre-built argument builders must keep their children
    // ------------------------------------------------------------------

    @Test
    @DisplayName("String argument wrapped for flag-awareness must keep its pre-attached children")
    void testStringArgumentPreservesChildren() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addRequiredArgument(Commands.argument("s", StringArgumentType.word())
                        .then(Commands.literal("deeper").executes(ctx -> 1)));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        CommandNode<CommandSourceStack> arg = cmd.build().getChild("s");
        assertNotNull(arg);
        assertNotNull(arg.getChild("deeper"),
                "wrapping the type in FlagAwareStringType must not discard the caller's subtree");
    }

    @Test
    @DisplayName("Non-String argument builders keep their children too (control case)")
    void testNonStringArgumentPreservesChildren() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addRequiredArgument(Commands.argument("i", IntegerArgumentType.integer())
                        .then(Commands.literal("deeper").executes(ctx -> 1)));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        assertNotNull(cmd.build().getChild("i").getChild("deeper"));
    }

    @Test
    @DisplayName("Suggestions on a wrapped String argument survive the rewrap")
    void testStringArgumentPreservesSuggestions() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addOptionalArgument(Commands.argument("s", StringArgumentType.word())
                        .suggests((ctx, builder) -> builder.suggest("one").buildFuture()));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        CommandNode<CommandSourceStack> arg = cmd.build().getChild("s");
        assertInstanceOf(com.mojang.brigadier.tree.ArgumentCommandNode.class, arg);
        assertNotNull(((com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, ?>) arg)
                .getCustomSuggestions());
    }

    // ------------------------------------------------------------------
    // A2 -- requiresConfirmation must not depend on requirements being present
    // ------------------------------------------------------------------

    /**
     * MockBukkit does not implement {@code Commands.restricted}, so reaching it throws.
     * That makes "did we call restricted()?" observable. If MockBukkit ever implements it,
     * these assertions should switch to inspecting the resulting predicate instead.
     */
    private boolean reachesRestricted(SubCommand<Plugin> command) {
        try {
            command.build();
            return false;
        } catch (UnimplementedOperationException e) {
            return true;
        }
    }

    @Test
    @DisplayName("requiresConfirmation applies even with no other requirements")
    void testRequiresConfirmationWithoutRequirements() {
        BaseCommand<Plugin> confirming = BaseCommand.builder(this.plugin, "a")
                .requiresConfirmation(true)
                .executes(d -> CommandResultType.SUCCESS)
                .build();

        assertTrue(this.reachesRestricted(confirming),
                "requiresConfirmation(true) must wrap the predicate even when no permission/playerOnly is set");
    }

    @Test
    @DisplayName("requiresConfirmation applies alongside other requirements")
    void testRequiresConfirmationWithRequirements() {
        BaseCommand<Plugin> confirming = BaseCommand.builder(this.plugin, "b")
                .requiresConfirmation(true)
                .permission("x.y")
                .executes(d -> CommandResultType.SUCCESS)
                .build();

        assertTrue(this.reachesRestricted(confirming));
    }

    @Test
    @DisplayName("Commands without requiresConfirmation are never wrapped as restricted")
    void testNoConfirmationIsNotRestricted() {
        BaseCommand<Plugin> plain = BaseCommand.builder(this.plugin, "c")
                .executes(d -> CommandResultType.SUCCESS)
                .build();
        assertFalse(this.reachesRestricted(plain));

        BaseCommand<Plugin> permissioned = BaseCommand.builder(this.plugin, "d")
                .permission("x.y")
                .executes(d -> CommandResultType.SUCCESS)
                .build();
        assertFalse(this.reachesRestricted(permissioned));
    }

    @Test
    @DisplayName("Requirement predicates are ANDed and actually gate the source")
    void testRequirementPredicateGating() {
        BaseCommand<Plugin> cmd = BaseCommand.builder(this.plugin, "gated")
                .permission("allowed.perm")
                .playerOnly()
                .executes(d -> CommandResultType.SUCCESS)
                .build();

        LiteralCommandNode<CommandSourceStack> node = cmd.build();

        CommandSourceStack allowed = Mockito.mock(CommandSourceStack.class);
        Player player = Mockito.mock(Player.class);
        when(allowed.getSender()).thenReturn(player);
        when(player.hasPermission("allowed.perm")).thenReturn(true);

        CommandSourceStack wrongSenderType = Mockito.mock(CommandSourceStack.class);
        CommandSender console = Mockito.mock(CommandSender.class);
        when(wrongSenderType.getSender()).thenReturn(console);
        when(console.hasPermission("allowed.perm")).thenReturn(true);

        CommandSourceStack missingPermission = Mockito.mock(CommandSourceStack.class);
        Player unprivileged = Mockito.mock(Player.class);
        when(missingPermission.getSender()).thenReturn(unprivileged);
        when(unprivileged.hasPermission("allowed.perm")).thenReturn(false);

        assertTrue(node.getRequirement().test(allowed));
        assertFalse(node.getRequirement().test(wrongSenderType), "playerOnly must reject a console sender");
        assertFalse(node.getRequirement().test(missingPermission), "permission must be required too");
    }

    // ------------------------------------------------------------------
    // Argument chaining and sub-commands
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Optional arguments chain sequentially off the required tail")
    void testArgumentChaining() {
        SubCommand<Plugin> cmd = SubCommand.builder(this.plugin, "cmd")
                .addRequiredArgument("req", StringArgumentType.word())
                .addOptionalArgument("opt1", StringArgumentType.word())
                .addOptionalArgument("opt2", StringArgumentType.word())
                .build();

        LiteralCommandNode<CommandSourceStack> node = cmd.build();

        CommandNode<CommandSourceStack> req = node.getChild("req");
        assertNotNull(req);
        assertNotNull(req.getCommand(), "the required argument alone must be executable");

        CommandNode<CommandSourceStack> opt1 = req.getChild("opt1");
        assertNotNull(opt1);
        assertNotNull(opt1.getChild("opt2"), "optional arguments must chain, not sit side by side");
    }

    @Test
    @DisplayName("A command with only optional arguments stays executable at its literal")
    void testOptionalOnlyCommandIsExecutable() {
        SubCommand<Plugin> cmd = SubCommand.builder(this.plugin, "cmd")
                .addOptionalArgument("opt", StringArgumentType.word())
                .build();

        LiteralCommandNode<CommandSourceStack> node = cmd.build();
        assertNotNull(node.getCommand(), "/cmd with no arguments must still run");
        assertNotNull(node.getChild("opt"));
    }

    @Test
    @DisplayName("Sub-command aliases are built into the parent tree")
    void testSubCommandAliasesAreBuilt() {
        SubCommand<Plugin> child = SubCommand.builder(this.plugin, "child").alias("c", "kid").build();
        BaseCommand<Plugin> parent = BaseCommand.builder(this.plugin, "parent")
                .addSubCommand(child)
                .executes(d -> CommandResultType.SUCCESS)
                .build();

        LiteralCommandNode<CommandSourceStack> node = parent.build();
        assertNotNull(node.getChild("child"));
        assertNotNull(node.getChild("c"));
        assertNotNull(node.getChild("kid"));
    }

    @Test
    @DisplayName("Repeated build() on the same instance is idempotent in tree size")
    void testRepeatedBuildIsIdempotent() {
        SubCommand<Plugin> cmd = this.flagCommand("cmd", 3);
        int first = countNodes(cmd.build());
        int second = countNodes(cmd.build());
        int third = countNodes(cmd.build());

        assertEquals(first, second, "argument builders are reused across builds; nodes must merge, not accumulate");
        assertEquals(first, third);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Colliding flag names and aliases are rejected")
    void testFlagCollisionDetection() {
        SubCommand.SubCommandBuilder<Plugin> duplicateName = SubCommand.builder(this.plugin, "x")
                .addFlag(Flags.boolFlag("dup"))
                .addFlag(Flags.boolFlag("dup"));
        assertThrows(IllegalArgumentException.class, duplicateName::build,
                "two flags with the same name must be rejected");

        SubCommand.SubCommandBuilder<Plugin> aliasClash = SubCommand.builder(this.plugin, "y")
                .addFlag(Flags.boolFlag("verbose"))
                .addFlag(Flags.boolFlag("other").alias("verbose"));
        assertThrows(IllegalArgumentException.class, aliasClash::build,
                "an alias colliding with an existing flag name must be rejected");
    }

    @Test
    @DisplayName("Argument names using the reserved flag-value prefix are rejected")
    void testReservedArgumentNameRejected() {
        SubCommand.SubCommandBuilder<Plugin> builder = SubCommand.builder(this.plugin, "cmd")
                .addRequiredArgument("flag$count", StringArgumentType.word());

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    @DisplayName("Empty command names are rejected")
    void testEmptyNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SubCommand.builder(this.plugin, "").build());
    }

    @Test
    @DisplayName("Builder aliases reach the built command")
    void testBuilderAliases() {
        SubCommand<Plugin> cmd = SubCommand.builder(this.plugin, "cmd").alias("a", "b").build();
        assertEquals(List.of("a", "b"), cmd.getAliases().stream().sorted().toList());
    }
}
