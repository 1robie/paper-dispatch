package fr.robie.paperdispatch.flag;

import com.mojang.brigadier.arguments.ArgumentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A flag that acts as a boolean switch — present or absent, no associated value.
 * <p>
 * Created via {@link Flags#boolFlag(String)}. Always defaults to {@code false};
 * presence in the command input sets the value to {@code true}.
 */
public class BoolFlag extends Flag<Boolean> {

    /**
     * @param name the flag name (used as {@code --name} in command input)
     */
    public BoolFlag(@NotNull String name) {
        super(name);
        this.defaultTo(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBoolFlag() {
        return true;
    }

    /**
     * Boolean flags carry no argument value, so this always returns {@code null}.
     *
     * @return {@code null}
     */
    @Nullable
    @Override
    public ArgumentType<?> getArgumentType() {
        return null;
    }
}
