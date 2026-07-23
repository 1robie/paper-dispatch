package fr.robie.paperdispatch.argument;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EnumArgumentTest {

    enum TestMode {
        FAST,
        SLOW,
        MEDIUM
    }

    @Test
    @DisplayName("EnumArgument should convert valid case-insensitive string to enum constant")
    void testConvertSuccess() throws Exception {
        EnumArgument<TestMode> arg = new EnumArgument<>(TestMode.class);

        assertEquals(TestMode.FAST, arg.convert("fast"));
        assertEquals(TestMode.FAST, arg.convert("FAST"));
        assertEquals(TestMode.SLOW, arg.convert("SlOw"));
    }

    @Test
    @DisplayName("EnumArgument should throw CommandSyntaxException on invalid input")
    void testConvertFailure() {
        EnumArgument<TestMode> arg = new EnumArgument<>(TestMode.class, input -> net.kyori.adventure.text.Component.text("Invalid: " + input));

        assertThrows(CommandSyntaxException.class, () -> arg.convert("invalid_mode"));
    }

    @Test
    @DisplayName("EnumArgument suggest should list matching enum options")
    void testSuggestions() throws Exception {
        EnumArgument<TestMode> arg = new EnumArgument<>(TestMode.class);
        SuggestionsBuilder builder = new SuggestionsBuilder("color ", 6);

        CommandContext<?> mockContext = Mockito.mock(CommandContext.class);
        CompletableFuture<Suggestions> future = arg.listSuggestions(mockContext, builder);

        Suggestions suggestions = future.get();
        assertEquals(3, suggestions.getList().size());
    }
}
