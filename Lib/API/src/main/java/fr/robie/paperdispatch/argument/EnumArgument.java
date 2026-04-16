package fr.robie.paperdispatch.argument;

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

public class EnumArgument<E extends Enum<E>> implements CustomArgumentType.Converted<Enum<E>, String> {
    private final Class<E> enumClass;
    private final DynamicCommandExceptionType invalidEnumException;


    public EnumArgument(Class<E> enumClass) {
        this(enumClass, input -> Component.text("<red>Invalid value: " + input + "."));
    }

    public EnumArgument(Class<E> enumClass, Function<Object, Component> errorMessageFunction) {
        this.enumClass = enumClass;
        this.invalidEnumException = new DynamicCommandExceptionType(input -> MessageComponentSerializer.message().serialize(errorMessageFunction.apply(input)));
    }

    @Override
    public @NonNull Enum<E> convert(String input) throws CommandSyntaxException {
        try {
            return Enum.valueOf(this.enumClass, input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw this.invalidEnumException.create(input);
        }
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        for (E constant : this.enumClass.getEnumConstants()) {
            String name = constant.toString();

            if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(name);
            }
        }

        return builder.buildFuture();
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }
}
