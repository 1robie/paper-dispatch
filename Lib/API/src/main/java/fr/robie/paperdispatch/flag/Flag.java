package fr.robie.paperdispatch.flag;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Abstract base for a command-line flag, analogous to GNU-style {@code --option}.
 * <p>
 * A flag has a canonical name ({@code --name}), optional aliases (e.g. {@code -n}),
 * an optional default value, and an optional {@link com.mojang.brigadier.suggestion.SuggestionProvider}.
 * Subclasses determine whether the flag is boolean ({@link BoolFlag}) or carries a
 * typed value ({@link ValueFlag}).
 *
 * @param <T> the type of the flag's value (use {@link Boolean} for boolean flags)
 */
public abstract class Flag<T> {

    private final String name;
    private final List<String> aliases = new ArrayList<>();
    private String description = "";
    @Nullable
    private T defaultValue = null;
    private boolean hasDefault = false;
    @Nullable
    private SuggestionProvider<CommandSourceStack> suggestionProvider = null;

    /**
     * @param name the flag name (used as {@code --name} in command input)
     */
    protected Flag(@NotNull String name) {
        Preconditions.checkNotNull(name, "Flag name cannot be null");
        Preconditions.checkArgument(!name.isEmpty(), "Flag name cannot be empty");
        this.name = name;
    }

    /**
     * @return the canonical flag name
     */
    @NotNull
    public String getName() {
        return this.name;
    }

    /**
     * @return an unmodifiable list of alias strings (dashes stripped)
     */
    @NotNull
    public List<String> getAliases() {
        return Collections.unmodifiableList(this.aliases);
    }

    /**
     * @return the description text (may be empty)
     */
    @NotNull
    public String getDescription() {
        return this.description;
    }

    /**
     * @return the default value, or {@code null} if none was set
     */
    @Nullable
    public T getDefaultValue() {
        return this.defaultValue;
    }

    /**
     * @return {@code true} if a default value has been set via {@link #defaultTo(Object)}
     */
    public boolean hasDefaultValue() {
        return this.hasDefault;
    }

    /**
     * @return the Brigadier argument type, or {@code null} for boolean flags
     */
    @Nullable
    public abstract ArgumentType<?> getArgumentType();

    /**
     * @return {@code true} if this is a boolean (switch) flag
     */
    public abstract boolean isBoolFlag();

    /**
     * Returns the value type class for this flag.
     *
     * @return {@link Boolean Boolean.class} for bool flags, {@link Object Object.class} otherwise
     * @deprecated the return type is imprecise; use the generic type parameter {@code T} instead
     */
    @NotNull
    @Deprecated
    @SuppressWarnings("unchecked")
    public Class<T> getValueType() {
        if (this.isBoolFlag()) {
            return (Class<T>) Boolean.class;
        }
        return (Class<T>) Object.class;
    }

    /**
     * Adds one or more aliases for this flag. Leading dashes are stripped
     * automatically; an alias cannot match the flag's own name.
     *
     * @param aliases the alias strings (e.g. {@code "v"}, {@code "verbose"})
     * @return this instance for chaining
     * @throws IllegalArgumentException if an alias is invalid or duplicates an existing one
     */
    @NotNull
    public Flag<T> alias(@NotNull String... aliases) {
        Preconditions.checkNotNull(aliases, "Aliases array cannot be null");
        for (String alias : aliases) {
            Preconditions.checkNotNull(alias, "Alias entry cannot be null");
            String stripped = alias.replaceAll("^-+", "");
            if (stripped.isEmpty()) {
                throw new IllegalArgumentException("Alias cannot consist only of dashes");
            }
            if (stripped.equals(this.name)) {
                throw new IllegalArgumentException("Alias '" + alias + "' cannot match the flag name");
            }
            if (this.aliases.contains(stripped)) {
                throw new IllegalArgumentException("Duplicate alias '" + alias + "'");
            }
            this.aliases.add(stripped);
        }
        return this;
    }

    /**
     * Sets the description text for this flag.
     *
     * @param description the description
     * @return this instance for chaining
     */
    @NotNull
    public Flag<T> description(@NotNull String description) {
        Preconditions.checkNotNull(description, "Description cannot be null");
        this.description = description;
        return this;
    }

    /**
     * Sets the default value for this flag. The flag will report as present
     * via {@link #hasDefaultValue()} and its value will be available even if
     * not explicitly provided in the command input.
     *
     * @param defaultValue the default value (may be {@code null})
     * @return this instance for chaining
     */
    @NotNull
    public Flag<T> defaultTo(@Nullable T defaultValue) {
        this.defaultValue = defaultValue;
        this.hasDefault = true;
        return this;
    }

    /**
     * Suggests a fixed, static list of values for this flag's value slot - e.g.
     * {@code Flags.intFlag("count").suggests("1", "16", "64")} to mimic vanilla
     * Minecraft's stack-size hints. Values are shown as-is regardless of the
     * flag's underlying type (Brigadier suggestions are always text).
     *
     * @param values the values to suggest
     * @return this instance for chaining
     */
    @NotNull
    public Flag<T> suggests(@NotNull String... values) {
        Preconditions.checkNotNull(values, "Suggestion values array cannot be null");
        Preconditions.checkArgument(values.length > 0, "At least one suggestion value is required");
        for (String value : values) {
            Preconditions.checkNotNull(value, "Suggestion value entry cannot be null");
        }
        this.suggestionProvider = (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String value : values) {
                if (remaining.isEmpty() || value.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
        return this;
    }

    /**
     * Suggests a fixed list of values, each with a hover tooltip — the same treatment vanilla
     * gives entity selectors and registry keys.
     *
     * <pre>{@code
     * Flags.stringFlag("mode").suggests(new LinkedHashMap<>() {{
     *     put("fast", Component.text("Skips validation"));
     *     put("safe", Component.text("Checks every entry"));
     * }});
     * }</pre>
     *
     * <p>Suggestions are emitted in the map's iteration order, so pass a {@link LinkedHashMap}
     * if that order matters to you. Values are filtered by the text typed so far.
     *
     * @param valuesWithTooltips value to tooltip mappings
     * @return this instance for chaining
     */
    @NotNull
    public Flag<T> suggests(@NotNull Map<String, Component> valuesWithTooltips) {
        Preconditions.checkNotNull(valuesWithTooltips, "Suggestion map cannot be null");
        Preconditions.checkArgument(!valuesWithTooltips.isEmpty(), "At least one suggestion value is required");
        valuesWithTooltips.forEach((value, tooltip) -> {
            Preconditions.checkNotNull(value, "Suggestion value cannot be null");
            Preconditions.checkNotNull(tooltip, "Suggestion tooltip cannot be null");
        });

        Map<String, Component> copy = new LinkedHashMap<>(valuesWithTooltips);
        this.suggestionProvider = (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            copy.forEach((value, tooltip) -> {
                if (remaining.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(value, MessageComponentSerializer.message().serialize(tooltip));
                }
            });
            return builder.buildFuture();
        };
        return this;
    }

    /**
     * Suggests values dynamically (e.g. online player names, registry keys) via a
     * standard Brigadier {@link SuggestionProvider}.
     *
     * @param provider the suggestion provider
     * @return this instance for chaining
     */
    @NotNull
    public Flag<T> suggests(@NotNull SuggestionProvider<CommandSourceStack> provider) {
        Preconditions.checkNotNull(provider, "Suggestion provider cannot be null");
        this.suggestionProvider = provider;
        return this;
    }

    /**
     * @return {@code true} if a suggestion provider has been set
     */
    public boolean hasSuggestions() {
        return this.suggestionProvider != null;
    }

    /**
     * @return the suggestion provider, or {@code null} if none was set
     */
    @Nullable
    public SuggestionProvider<CommandSourceStack> getSuggestionProvider() {
        return this.suggestionProvider;
    }

    /**
     * Converts an alias string into a flag token: single-character aliases
     * become {@code -x}, multi-character become {@code --xxx}.
     *
     * @param alias the alias string
     * @return the flag token
     */
    @NotNull
    public static String toFlagToken(@NotNull String alias) {
        Preconditions.checkNotNull(alias, "Alias cannot be null");
        return alias.length() == 1 ? "-" + alias : "--" + alias;
    }

    /**
     * Checks whether the given input string matches this flag's name or any
     * of its aliases (in {@code --name} or {@code -x} form).
     *
     * @param input the input to match (e.g. {@code "--verbose"})
     * @return {@code true} if this flag matches
     */
    public boolean matches(@NotNull String input) {
        Preconditions.checkNotNull(input, "Input cannot be null");
        if (input.equals("--" + this.name)) return true;
        for (String alias : this.aliases) {
            if (input.equals(toFlagToken(alias))) return true;
        }
        return false;
    }
}
