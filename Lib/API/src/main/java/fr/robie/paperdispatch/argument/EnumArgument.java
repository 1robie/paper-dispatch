package fr.robie.paperdispatch.argument;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A {@link CustomArgumentType.Converted} that parses a string input into a Java
 * enum constant. Matching is case-insensitive; suggestions are provided for all
 * enum constants via {@link Enum#name()}.
 * <p>
 * Error messages can be customized via the
 * {@link #EnumArgument(Class, Function)} constructor.
 *
 * @param <E> the enum type
 */
public class EnumArgument<E extends Enum<E>> implements CustomArgumentType.Converted<Enum<E>, String> {
    private final Class<E> enumClass;
    private final DynamicCommandExceptionType invalidEnumException;


    /**
     * Creates an enum argument with a default error message.
     *
     * @param enumClass the enum class
     */
    public EnumArgument(Class<E> enumClass) {
        this(Preconditions.checkNotNull(enumClass, "Enum class cannot be null"), input -> Component.text("<red>Invalid value: " + input + "."));
    }

    /**
     * Creates an enum argument with a custom error message.
     *
     * @param enumClass            the enum class
     * @param errorMessageFunction function that produces an error {@link Component}
     *                             from the invalid input
     */
    public EnumArgument(@NonNull Class<E> enumClass, @NonNull Function<Object, Component> errorMessageFunction) {
        Preconditions.checkNotNull(enumClass, "Enum class cannot be null");
        Preconditions.checkNotNull(errorMessageFunction, "Error message function cannot be null");
        this.enumClass = enumClass;
        this.invalidEnumException = new DynamicCommandExceptionType(input -> MessageComponentSerializer.message().serialize(errorMessageFunction.apply(input)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Enum<E> convert(@NonNull String input) throws CommandSyntaxException {
        Preconditions.checkNotNull(input, "Input string cannot be null");
        try {
            return Enum.valueOf(this.enumClass, input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw this.invalidEnumException.create(input);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        for (E constant : this.enumClass.getEnumConstants()) {
            String name = constant.name();

            if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(name);
            }
        }

        return builder.buildFuture();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }
}
