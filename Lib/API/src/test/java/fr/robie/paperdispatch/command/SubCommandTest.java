package fr.robie.paperdispatch.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.robie.paperdispatch.flag.Flag;
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
    @DisplayName("Only the last required argument may execute")
    void testIncompleteRequiredChainIsNotExecutable() {
        SubCommand<Plugin> cmd = SubCommand.builder(this.plugin, "cmd")
                .addRequiredArgument("a", StringArgumentType.word())
                .addRequiredArgument("b", StringArgumentType.word())
                .addRequiredArgument("c", StringArgumentType.word())
                .build();

        CommandNode<CommandSourceStack> a = cmd.build().getChild("a");
        CommandNode<CommandSourceStack> b = a.getChild("b");
        CommandNode<CommandSourceStack> c = b.getChild("c");

        assertNull(a.getCommand(), "/cmd <a> is incomplete and must not run");
        assertNull(b.getCommand(), "/cmd <a> <b> is incomplete and must not run");
        assertNotNull(c.getCommand(), "the full chain must still run");
    }

    @Test
    @DisplayName("A single required argument stays executable")
    void testSingleRequiredArgumentStaysExecutable() {
        SubCommand<Plugin> cmd = SubCommand.builder(this.plugin, "cmd")
                .addRequiredArgument("only", StringArgumentType.word())
                .build();

        assertNotNull(cmd.build().getChild("only").getCommand(),
                "one required argument is a complete chain");
    }

    @Test
    @DisplayName("Optional arguments after a required chain stay executable")
    void testOptionalsAfterRequiredChainStayExecutable() {
        SubCommand<Plugin> cmd = SubCommand.builder(this.plugin, "cmd")
                .addRequiredArgument("a", StringArgumentType.word())
                .addRequiredArgument("b", StringArgumentType.word())
                .addOptionalArgument("opt", StringArgumentType.word())
                .build();

        CommandNode<CommandSourceStack> a = cmd.build().getChild("a");
        CommandNode<CommandSourceStack> b = a.getChild("b");

        assertNull(a.getCommand(), "still incomplete with an optional trailing the chain");
        assertNotNull(b.getCommand(), "the required chain is complete here");
        assertNotNull(b.getChild("opt").getCommand(), "optional arguments remain executable");
    }

    @Test
    @DisplayName("Sub-command aliases get the same restriction as the primary name")
    void testAliasNodesGetTheSameRestriction() {
        SubCommand<Plugin> child = SubCommand.builder(this.plugin, "child")
                .alias("c")
                .addRequiredArgument("a", StringArgumentType.word())
                .addRequiredArgument("b", StringArgumentType.word())
                .build();
        BaseCommand<Plugin> parent = BaseCommand.builder(this.plugin, "parent")
                .addSubCommand(child)
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();

        LiteralCommandNode<CommandSourceStack> node = parent.build();

        for (String label : List.of("child", "c")) {
            CommandNode<CommandSourceStack> a = node.getChild(label).getChild("a");
            assertNotNull(a, label + " should expose the argument chain");
            assertNull(a.getCommand(), "/parent " + label + " <a> is incomplete and must not run");
            assertNotNull(a.getChild("b").getCommand(), "the full chain must run via " + label);
        }
    }

    @Test
    @DisplayName("Flags are not offered on an incomplete required chain")
    void testFlagsSkipIncompleteRequiredArguments() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addRequiredArgument("a", StringArgumentType.word());
                this.addRequiredArgument("b", StringArgumentType.word());
                this.addFlag(Flags.boolFlag("verbose"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        CommandNode<CommandSourceStack> a = cmd.build().getChild("a");

        assertNull(a.getChild("--verbose"), "an incomplete chain must not offer flags");
        assertNotNull(a.getChild("b").getChild("--verbose"), "the complete chain still offers them");
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
    @DisplayName("A single-character flag name is matched in the form the tree registers")
    void testSingleCharacterFlagNameIsRecognised() {
        // flagTokens() registers a one-letter name as "-v", so matches() must recognise "-v".
        // Comparing against "--" + name made the flag invisible to FlagAwareStringType, and a
        // preceding string argument then swallowed the token: "/cmd -v" parsed as text="-v".
        Flag<Boolean> shortName = Flags.boolFlag("v");
        assertTrue(shortName.matches("-v"), "a one-letter flag is typed as -v");
        assertFalse(shortName.matches("--v"), "no --v node is ever registered");

        Flag<Boolean> longName = Flags.boolFlag("verbose");
        assertTrue(longName.matches("--verbose"));
        assertFalse(longName.matches("-verbose"));
    }

    @Test
    @DisplayName("A string argument must not swallow a single-character flag token")
    void testStringArgumentRejectsShortFlagToken() {
        SubCommand<Plugin> cmd = new SubCommand<>(this.plugin, "cmd") {
            {
                this.addRequiredArgument("text", StringArgumentType.word());
                this.addFlag(Flags.boolFlag("v"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(cmd.build());
        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);

        ParseResults<CommandSourceStack> parsed = dispatcher.parse("cmd -v", source);
        assertFalse(parsed.getContext().getArguments().containsKey("text"),
                "-v is a flag token, not a value for the required argument");
        assertEquals("-v", parsed.getReader().getRemaining(),
                "the token should be left unconsumed so Brigadier reports the missing argument");
    }

    @Test
    @DisplayName("Nested sub-command aliases are reachable through a parent alias")
    void testNestedAliasesReachableThroughParentAlias() {
        SubCommand<Plugin> grand = SubCommand.builder(this.plugin, "grand").alias("g").build();
        SubCommand<Plugin> child = SubCommand.builder(this.plugin, "child").alias("c")
                .addSubCommand(grand).build();
        BaseCommand<Plugin> parent = BaseCommand.builder(this.plugin, "parent")
                .addSubCommand(child)
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();

        LiteralCommandNode<CommandSourceStack> node = parent.build();

        // An alias should behave exactly like the primary name at every depth.
        for (String childLabel : List.of("child", "c")) {
            CommandNode<CommandSourceStack> childNode = node.getChild(childLabel);
            assertNotNull(childNode, childLabel + " must exist");
            assertNotNull(childNode.getChild("grand"), "/parent " + childLabel + " grand");
            assertNotNull(childNode.getChild("g"), "/parent " + childLabel + " g");
        }
    }

    @Test
    @DisplayName("Flags sit before a greedy argument, so both orderings parse")
    void testFlagsPrecedeGreedyArgument() {
        SubCommand<Plugin> say = new SubCommand<>(this.plugin, "say") {
            {
                this.addFlag(Flags.boolFlag("loud"));
                this.addRequiredArgument("msg", StringArgumentType.greedyString());
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(say.build());
        CommandSourceStack source = Mockito.mock(CommandSourceStack.class);

        // A greedy string eats the rest of the line, so a flag placed after it could never be
        // matched. Putting the flags first is the only layout where both forms work.
        ParseResults<CommandSourceStack> flagged = dispatcher.parse("say --loud hello there", source);
        assertEquals("hello there", flagged.getContext().getArguments().get("msg").getResult(),
                "the greedy argument should start after the flag");
        assertTrue(flagged.getContext().getNodes().stream()
                        .anyMatch(n -> n.getNode().getName().equals("--loud")),
                "the flag should be part of the parsed path");

        ParseResults<CommandSourceStack> plain = dispatcher.parse("say hello there", source);
        assertEquals("hello there", plain.getContext().getArguments().get("msg").getResult(),
                "the command must still work with no flags at all");
    }

    @Test
    @DisplayName("A greedy argument alone is not executable until supplied")
    void testGreedyArgumentStillRequired() {
        SubCommand<Plugin> say = new SubCommand<>(this.plugin, "say") {
            {
                this.addFlag(Flags.boolFlag("loud"));
                this.addRequiredArgument("msg", StringArgumentType.greedyString());
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        };

        LiteralCommandNode<CommandSourceStack> node = say.build();
        assertNull(node.getCommand(), "/say with no message is incomplete");
        assertNull(node.getChild("--loud").getCommand(), "/say --loud with no message is incomplete");
        assertNotNull(node.getChild("--loud").getChild("msg"), "the flag must continue into the argument");
        assertNotNull(node.getChild("msg"), "the argument must also hang off the literal");
    }

    @Test
    @DisplayName("A greedy positional with flags no longer warns, since ordering handles it")
    void testGreedyPositionalIsNotWarnedAbout() {
        List<String> logs = this.captureLogs();

        new SubCommand<>(this.plugin, "say") {
            {
                this.addRequiredArgument("msg", StringArgumentType.greedyString());
                this.addFlag(Flags.boolFlag("verbose"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        }.build();

        assertTrue(logs.stream().noneMatch(m -> m.contains("greedy string")),
                "ordering resolves this case, so warning would be noise: " + logs);
    }

    @Test
    @DisplayName("A greedy value flag alongside other flags is still reported")
    void testGreedyValueFlagWithOtherFlagsWarns() {
        List<String> logs = this.captureLogs();

        new SubCommand<>(this.plugin, "say") {
            {
                this.addFlag(Flags.greedyStringFlag("message"));
                this.addFlag(Flags.boolFlag("verbose"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        }.build();

        // Reordering cannot save this one: the greedy value sits inside the flag subtree, and
        // flags may be given in any order, so some ordering always shadows the rest.
        assertTrue(logs.stream().anyMatch(m -> m.contains("greedy string") && m.contains("--message")),
                "expected a greedy value-flag warning, got: " + logs);
    }

    @Test
    @DisplayName("A lone greedy value flag stays quiet")
    void testLoneGreedyValueFlagIsQuiet() {
        List<String> logs = this.captureLogs();

        new SubCommand<>(this.plugin, "tell") {
            {
                this.addFlag(Flags.greedyStringFlag("message"));
            }

            @Override
            protected CommandResultType perform(CommandDispatch<Plugin> dispatch) {
                return CommandResultType.SUCCESS;
            }
        }.build();

        assertTrue(logs.stream().noneMatch(m -> m.contains("greedy string")),
                "nothing to shadow with a single flag: " + logs);
    }

    @Test
    @DisplayName("Labels that could never be typed are rejected")
    void testUnusableLabelsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SubCommand.builder(this.plugin, "two words").build(),
                "a literal containing a space can never match");
        assertThrows(IllegalArgumentException.class,
                () -> SubCommand.builder(this.plugin, "ok").alias("").build(),
                "an empty alias is a dead node");
        assertThrows(IllegalArgumentException.class,
                () -> SubCommand.builder(this.plugin, "ok").alias("has space").build(),
                "an alias containing a space can never match");
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
