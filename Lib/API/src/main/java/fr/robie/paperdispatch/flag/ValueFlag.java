package fr.robie.paperdispatch.flag;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.arguments.ArgumentType;
import org.jetbrains.annotations.NotNull;

/**
 * A flag that takes a typed value from the command input, e.g.
 * {@code --count 5} or {@code --world world_nether}.
 * <p>
 * Created via factory methods on {@link Flags}, such as
 * {@link Flags#intFlag(String)}, {@link Flags#worldFlag(String)}, etc.
 *
 * @param <T> the type of the flag's value
 */
public class ValueFlag<T> extends Flag<T> {

    private final ArgumentType<T> argumentType;

    /**
     * @param name         the flag name (used as {@code --name} in command input)
     * @param argumentType the Brigadier argument type for the flag's value
     */
    public ValueFlag(@NotNull String name, @NotNull ArgumentType<T> argumentType) {
        super(name);
        Preconditions.checkNotNull(argumentType, "Flag argument type cannot be null");
        this.argumentType = argumentType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBoolFlag() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public ArgumentType<T> getArgumentType() {
        return this.argumentType;
    }
}
