package fr.robie.paperdispatch.flag;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.AxisSet;
import io.papermc.paper.command.brigadier.argument.predicate.ItemStackPredicate;
import io.papermc.paper.command.brigadier.argument.range.DoubleRangeProvider;
import io.papermc.paper.command.brigadier.argument.range.IntegerRangeProvider;
import io.papermc.paper.command.brigadier.argument.resolvers.AngleResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.RotationResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.entity.LookAnchor;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.jetbrains.annotations.NotNull;

/**
 * Entry-point factory for creating {@link Flag} instances. Every public method
 * returns a fully-configured flag that can be further customised via its
 * fluent API ({@link Flag#alias(String...)}, {@link Flag#defaultTo(Object)},
 * {@link Flag#description(String)}, {@link Flag#suggests(String...)}).
 * <p>
 * Brigadier primitives are covered directly:
 * <ul>
 *   <li>{@link #boolFlag}, {@link #intFlag}, {@link #longFlag}, {@link #floatFlag},
 *       {@link #doubleFlag}</li>
 *   <li>{@link #stringFlag}, {@link #quotedStringFlag}, {@link #greedyStringFlag}</li>
 * </ul>
 * Paper/Minecraft argument types are also available:
 * <ul>
 *   <li>{@link #worldFlag}, {@link #gameModeFlag}, {@link #uuidFlag}, {@link #timeFlag},
 *       {@link #namespacedKeyFlag}, {@link #keyFlag}</li>
 *   <li>{@link #itemStackFlag}, {@link #blockStateFlag}, {@link #itemPredicateFlag}</li>
 *   <li>{@link #entityFlag}, {@link #entitiesFlag}, {@link #playerFlag},
 *       {@link #playersFlag}, {@link #playerProfilesFlag}</li>
 *   <li>{@link #blockPositionFlag}, {@link #finePositionFlag}, {@link #rotationFlag},
 *       {@link #angleFlag}</li>
 *   <li>{@link #componentFlag}, {@link #styleFlag}, {@link #namedColorFlag},
 *       {@link #hexColorFlag}</li>
 *   <li>{@link #heightMapFlag}, {@link #entityAnchorFlag}, {@link #templateMirrorFlag},
 *       {@link #templateRotationFlag}</li>
 *   <li>{@link #objectiveCriteriaFlag}, {@link #scoreboardDisplaySlotFlag}</li>
 *   <li>{@link #integerRangeFlag}, {@link #doubleRangeFlag}, {@link #axesFlag}</li>
 * </ul>
 * For any argument type not listed above, use the generic
 * {@link #argFlag(String, ArgumentType)}.
 */
public final class Flags {

    @SuppressWarnings("unused")
    private Flags() {
    }

    /**
     * Creates a boolean (switch) flag.
     *
     * @param name the flag name
     * @return a new bool flag
     */
    @NotNull
    public static BoolFlag boolFlag(@NotNull String name) {
        return new BoolFlag(name);
    }

    /**
     * Creates an integer flag with no range restriction.
     *
     * @param name the flag name
     * @return a new integer flag
     */
    @NotNull
    public static ValueFlag<Integer> intFlag(@NotNull String name) {
        return new ValueFlag<>(name, IntegerArgumentType.integer());
    }

    /**
     * Creates an integer flag constrained to {@code [min, max]}.
     *
     * @param name the flag name
     * @param min  the minimum value (inclusive)
     * @param max  the maximum value (inclusive)
     * @return a new integer flag
     */
    @NotNull
    public static ValueFlag<Integer> intFlag(@NotNull String name, int min, int max) {
        return new ValueFlag<>(name, IntegerArgumentType.integer(min, max));
    }

    /**
     * Creates a double-precision flag with no range restriction.
     *
     * @param name the flag name
     * @return a new double flag
     */
    @NotNull
    public static ValueFlag<Double> doubleFlag(@NotNull String name) {
        return new ValueFlag<>(name, DoubleArgumentType.doubleArg());
    }

    /**
     * Creates a double-precision flag constrained to {@code [min, max]}.
     *
     * @param name the flag name
     * @param min  the minimum value (inclusive)
     * @param max  the maximum value (inclusive)
     * @return a new double flag
     */
    @NotNull
    public static ValueFlag<Double> doubleFlag(@NotNull String name, double min, double max) {
        return new ValueFlag<>(name, DoubleArgumentType.doubleArg(min, max));
    }

    /**
     * Creates a single-precision float flag with no range restriction.
     *
     * @param name the flag name
     * @return a new float flag
     */
    @NotNull
    public static ValueFlag<Float> floatFlag(@NotNull String name) {
        return new ValueFlag<>(name, FloatArgumentType.floatArg());
    }

    /**
     * Creates a single-precision float flag constrained to {@code [min, max]}.
     *
     * @param name the flag name
     * @param min  the minimum value (inclusive)
     * @param max  the maximum value (inclusive)
     * @return a new float flag
     */
    @NotNull
    public static ValueFlag<Float> floatFlag(@NotNull String name, float min, float max) {
        return new ValueFlag<>(name, FloatArgumentType.floatArg(min, max));
    }

    /**
     * Creates a long integer flag with no range restriction.
     *
     * @param name the flag name
     * @return a new long flag
     */
    @NotNull
    public static ValueFlag<Long> longFlag(@NotNull String name) {
        return new ValueFlag<>(name, LongArgumentType.longArg());
    }

    /**
     * Creates a long integer flag constrained to {@code [min, max]}.
     *
     * @param name the flag name
     * @param min  the minimum value (inclusive)
     * @param max  the maximum value (inclusive)
     * @return a new long flag
     */
    @NotNull
    public static ValueFlag<Long> longFlag(@NotNull String name, long min, long max) {
        return new ValueFlag<>(name, LongArgumentType.longArg(min, max));
    }

    /**
     * Creates a string flag accepting a single word (no spaces).
     *
     * @param name the flag name
     * @return a new string flag
     */
    @NotNull
    public static ValueFlag<String> stringFlag(@NotNull String name) {
        return new ValueFlag<>(name, StringArgumentType.word());
    }

    /**
     * Creates a string flag accepting a quoted string (supports spaces).
     *
     * @param name the flag name
     * @return a new quoted-string flag
     */
    @NotNull
    public static ValueFlag<String> quotedStringFlag(@NotNull String name) {
        return new ValueFlag<>(name, StringArgumentType.string());
    }

    /**
     * Creates a string flag that consumes the remainder of the input.
     *
     * @param name the flag name
     * @return a new greedy-string flag
     */
    @NotNull
    public static ValueFlag<String> greedyStringFlag(@NotNull String name) {
        return new ValueFlag<>(name, StringArgumentType.greedyString());
    }

    /**
     * Creates a {@link World} flag.
     *
     * @param name the flag name
     * @return a new world flag
     */
    @NotNull
    public static ValueFlag<World> worldFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.world());
    }

    /**
     * Creates a {@link GameMode} flag.
     *
     * @param name the flag name
     * @return a new game-mode flag
     */
    @NotNull
    public static ValueFlag<GameMode> gameModeFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.gameMode());
    }

    /**
     * Creates a {@link UUID} flag.
     *
     * @param name the flag name
     * @return a new UUID flag
     */
    @NotNull
    public static ValueFlag<UUID> uuidFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.uuid());
    }

    /**
     * Creates a time flag (in ticks).
     *
     * @param name the flag name
     * @return a new time flag
     */
    @NotNull
    public static ValueFlag<Integer> timeFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.time());
    }

    /**
     * Creates a time flag with a minimum value (in ticks).
     *
     * @param name    the flag name
     * @param minTime the minimum time in ticks
     * @return a new time flag
     */
    @NotNull
    public static ValueFlag<Integer> timeFlag(@NotNull String name, int minTime) {
        return new ValueFlag<>(name, ArgumentTypes.time(minTime));
    }

    /**
     * Creates a {@link NamespacedKey} flag.
     *
     * @param name the flag name
     * @return a new namespaced-key flag
     */
    @NotNull
    public static ValueFlag<NamespacedKey> namespacedKeyFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.namespacedKey());
    }

    /**
     * Creates an Adventure {@link Key} flag.
     *
     * @param name the flag name
     * @return a new key flag
     */
    @NotNull
    public static ValueFlag<Key> keyFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.key());
    }

    /**
     * Creates an {@link ItemStack} flag with rich parsing (material + NBT).
     *
     * @param name the flag name
     * @return a new item-stack flag
     */
    @NotNull
    public static ValueFlag<ItemStack> itemStackFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.itemStack());
    }

    /**
     * Creates a {@link BlockState} flag.
     *
     * @param name the flag name
     * @return a new block-state flag
     */
    @NotNull
    public static ValueFlag<BlockState> blockStateFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.blockState());
    }

    /**
     * Creates a {@link HeightMap} flag.
     *
     * @param name the flag name
     * @return a new height-map flag
     */
    @NotNull
    public static ValueFlag<HeightMap> heightMapFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.heightMap());
    }

    /**
     * Creates a {@link LookAnchor} flag.
     *
     * @param name the flag name
     * @return a new entity-anchor flag
     */
    @NotNull
    public static ValueFlag<LookAnchor> entityAnchorFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.entityAnchor());
    }

    /**
     * Creates a {@link Mirror} flag.
     *
     * @param name the flag name
     * @return a new template-mirror flag
     */
    @NotNull
    public static ValueFlag<Mirror> templateMirrorFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.templateMirror());
    }

    /**
     * Creates a {@link StructureRotation} flag.
     *
     * @param name the flag name
     * @return a new template-rotation flag
     */
    @NotNull
    public static ValueFlag<StructureRotation> templateRotationFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.templateRotation());
    }

    /**
     * Creates a {@link Criteria} flag.
     *
     * @param name the flag name
     * @return a new objective-criteria flag
     */
    @NotNull
    public static ValueFlag<Criteria> objectiveCriteriaFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.objectiveCriteria());
    }

    /**
     * Creates a {@link DisplaySlot} flag.
     *
     * @param name the flag name
     * @return a new scoreboard-display-slot flag
     */
    @NotNull
    public static ValueFlag<DisplaySlot> scoreboardDisplaySlotFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.scoreboardDisplaySlot());
    }

    /**
     * Creates a {@link NamedTextColor} flag.
     *
     * @param name the flag name
     * @return a new named-color flag
     */
    @NotNull
    public static ValueFlag<NamedTextColor> namedColorFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.namedColor());
    }

    /**
     * Creates a hex {@link TextColor} flag.
     *
     * @param name the flag name
     * @return a new hex-color flag
     */
    @NotNull
    public static ValueFlag<TextColor> hexColorFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.hexColor());
    }

    /**
     * Creates a single-entity selector flag ({@code @e} / {@code @p} / UUID).
     *
     * @param name the flag name
     * @return a new entity flag
     */
    @NotNull
    public static ValueFlag<EntitySelectorArgumentResolver> entityFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.entity());
    }

    /**
     * Creates a multi-entity selector flag.
     *
     * @param name the flag name
     * @return a new entities flag
     */
    @NotNull
    public static ValueFlag<EntitySelectorArgumentResolver> entitiesFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.entities());
    }

    /**
     * Creates a single-player selector flag.
     *
     * @param name the flag name
     * @return a new player flag
     */
    @NotNull
    public static ValueFlag<PlayerSelectorArgumentResolver> playerFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.player());
    }

    /**
     * Creates a multi-player selector flag.
     *
     * @param name the flag name
     * @return a new players flag
     */
    @NotNull
    public static ValueFlag<PlayerSelectorArgumentResolver> playersFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.players());
    }

    /**
     * Creates a player-profile-list flag.
     *
     * @param name the flag name
     * @return a new player-profiles flag
     */
    @NotNull
    public static ValueFlag<PlayerProfileListResolver> playerProfilesFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.playerProfiles());
    }

    /**
     * Creates a block-position flag (integer coordinates).
     *
     * @param name the flag name
     * @return a new block-position flag
     */
    @NotNull
    public static ValueFlag<BlockPositionResolver> blockPositionFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.blockPosition());
    }

    /**
     * Creates a fine-position flag (decimal coordinates).
     *
     * @param name the flag name
     * @return a new fine-position flag
     */
    @NotNull
    public static ValueFlag<FinePositionResolver> finePositionFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.finePosition());
    }

    /**
     * Creates a fine-position flag with optional integer centering.
     *
     * @param name           the flag name
     * @param centerIntegers if {@code true}, whole numbers are centered (+0.5)
     * @return a new fine-position flag
     */
    @NotNull
    public static ValueFlag<FinePositionResolver> finePositionFlag(@NotNull String name, boolean centerIntegers) {
        return new ValueFlag<>(name, ArgumentTypes.finePosition(centerIntegers));
    }

    /**
     * Creates a rotation flag.
     *
     * @param name the flag name
     * @return a new rotation flag
     */
    @NotNull
    public static ValueFlag<RotationResolver> rotationFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.rotation());
    }

    /**
     * Creates an angle flag.
     *
     * @param name the flag name
     * @return a new angle flag
     */
    @NotNull
    public static ValueFlag<AngleResolver> angleFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.angle());
    }

    /**
     * Creates an item-predicate flag.
     *
     * @param name the flag name
     * @return a new item-predicate flag
     */
    @NotNull
    public static ValueFlag<ItemStackPredicate> itemPredicateFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.itemPredicate());
    }

    /**
     * Creates an integer-range flag (e.g. {@code ..5} or {@code 1..}).
     *
     * @param name the flag name
     * @return a new integer-range flag
     */
    @NotNull
    public static ValueFlag<IntegerRangeProvider> integerRangeFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.integerRange());
    }

    /**
     * Creates a double-range flag (e.g. {@code ..5.0} or {@code 1.5..}).
     *
     * @param name the flag name
     * @return a new double-range flag
     */
    @NotNull
    public static ValueFlag<DoubleRangeProvider> doubleRangeFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.doubleRange());
    }

    /**
     * Creates an Adventure {@link Component} flag.
     *
     * @param name the flag name
     * @return a new component flag
     */
    @NotNull
    public static ValueFlag<Component> componentFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.component());
    }

    /**
     * Creates an Adventure {@link Style} flag.
     *
     * @param name the flag name
     * @return a new style flag
     */
    @NotNull
    public static ValueFlag<Style> styleFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.style());
    }

    /**
     * Creates an axes-set flag (e.g. {@code xz}).
     *
     * @param name the flag name
     * @return a new axes flag
     */
    @NotNull
    public static ValueFlag<AxisSet> axesFlag(@NotNull String name) {
        return new ValueFlag<>(name, ArgumentTypes.axes());
    }

    /**
     * Generic factory for argument types not covered by dedicated methods.
     *
     * @param name         the flag name
     * @param argumentType the Brigadier argument type
     * @return a new value flag
     * @param <T> the value type
     */
    @NotNull
    public static <T> ValueFlag<T> argFlag(@NotNull String name, @NotNull ArgumentType<T> argumentType) {
        return new ValueFlag<>(name, argumentType);
    }
}
