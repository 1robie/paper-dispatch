package fr.robie.paperdispatch.flag;

import com.google.common.base.Preconditions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Immutable snapshot of the flag values parsed during a command invocation.
 * <p>
 * Provides typed accessors ({@link #getValue}, {@link #getOptionalValue}),
 * presence checks ({@link #hasFlag}), and the full set of explicitly-provided
 * flags ({@link #getPresentFlags}). A shared empty instance is available via
 * {@link #empty()}.
 */
public class FlagContext {

    private static final FlagContext EMPTY = new FlagContext();

    private final Map<String, Object> values;
    private final Set<String> presentFlags;

    private FlagContext() {
        this.values = Map.of();
        this.presentFlags = Collections.emptySet();
    }

    /**
     * Creates a context with all provided values as explicitly-present flags.
     *
     * @param values flag-name to value mappings
     */
    public FlagContext(@NotNull Map<String, Object> values) {
        this(Preconditions.checkNotNull(values, "Values map cannot be null"), values.keySet());
    }

    /**
     * @param values        flag-name to value mappings
     * @param presentFlags  the subset of flag names that were explicitly provided
     *                      in the command input (as opposed to falling back to defaults)
     */
    public FlagContext(@NotNull Map<String, Object> values, @NotNull Set<String> presentFlags) {
        Preconditions.checkNotNull(values, "Values map cannot be null");
        Preconditions.checkNotNull(presentFlags, "Present flags set cannot be null");
        this.values = new HashMap<>(values);
        this.presentFlags = new HashSet<>(presentFlags);
    }

    /**
     * Returns a shared empty instance.
     *
     * @return empty flag context
     */
    public static FlagContext empty() {
        return EMPTY;
    }

    /**
     * Checks whether a flag was explicitly provided in the command input.
     *
     * @param name the flag name
     * @return {@code true} if the flag was present
     */
    public boolean hasFlag(@NotNull String name) {
        Preconditions.checkNotNull(name, "Flag name cannot be null");
        return this.presentFlags.contains(name);
    }

    /**
     * Gets the value of a flag, throwing if it was not provided and has no default.
     *
     * @param name the flag name
     * @param type the expected value type
     * @return the flag value
     * @throws NoSuchElementException if the flag was neither present nor has a default
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public <T> T getValue(@NotNull String name, @NotNull Class<T> type) {
        Preconditions.checkNotNull(name, "Flag name cannot be null");
        Preconditions.checkNotNull(type, "Value type cannot be null");
        Object value = this.values.get(name);
        if (value == null) {
            throw new NoSuchElementException("Flag '" + name + "' is not present");
        }
        return type.cast(value);
    }

    /**
     * Gets the value of a flag, returning {@code fallback} if it was not provided
     * and has no default.
     *
     * @param name     the flag name
     * @param type     the expected value type
     * @param fallback the value to return if the flag is absent
     * @return the flag value or {@code fallback}
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public <T> T getValue(@NotNull String name, @NotNull Class<T> type, @NotNull T fallback) {
        Preconditions.checkNotNull(name, "Flag name cannot be null");
        Preconditions.checkNotNull(type, "Value type cannot be null");
        Preconditions.checkNotNull(fallback, "Fallback value cannot be null");
        Object value = this.values.get(name);
        if (value == null) {
            return fallback;
        }
        return type.cast(value);
    }

    /**
     * Gets the value of a flag as an {@link Optional}, empty if the flag was
     * neither provided nor has a default.
     *
     * @param name the flag name
     * @param type the expected value type
     * @return optional flag value
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptionalValue(@NotNull String name, @NotNull Class<T> type) {
        Preconditions.checkNotNull(name, "Flag name cannot be null");
        Preconditions.checkNotNull(type, "Value type cannot be null");
        if (!this.values.containsKey(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(type.cast(this.values.get(name)));
    }

    /**
     * Returns the set of flag names that were explicitly provided in the command input.
     *
     * @return unmodifiable set of present flag names
     */
    @NotNull
    public Set<String> getPresentFlags() {
        return Collections.unmodifiableSet(this.presentFlags);
    }

    /**
     * Returns all flag name-to-value mappings (including defaults).
     *
     * @return unmodifiable map of all flag values
     */
    @NotNull
    public Map<String, Object> getAllValues() {
        return Collections.unmodifiableMap(this.values);
    }
}
