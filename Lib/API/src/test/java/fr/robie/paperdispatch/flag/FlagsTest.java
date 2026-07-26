package fr.robie.paperdispatch.flag;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlagsTest {

    @Test
    @DisplayName("BoolFlag should default to false and match flag tokens")
    void testBoolFlag() {
        Flag<Boolean> flag = Flags.boolFlag("verbose").alias("v");
        assertEquals("verbose", flag.getName());
        assertTrue(flag.isBoolFlag());
        assertEquals(false, flag.getDefaultValue());
        assertTrue(flag.hasDefaultValue());
        assertEquals(1, flag.getAliases().size());
        assertEquals("v", flag.getAliases().getFirst());

        assertTrue(flag.matches("--verbose"));
        assertTrue(flag.matches("-v"));
        assertFalse(flag.matches("--v"));
        assertFalse(flag.matches("-verbose"));
    }

    @Test
    @DisplayName("ValueFlag should hold argument type and suggestions")
    void testValueFlag() {
        ValueFlag<Integer> flag = Flags.intFlag("count", 1, 100);
        flag.alias("c").defaultTo(10).description("Item count").suggests("1", "16", "64");

        assertEquals("count", flag.getName());
        assertFalse(flag.isBoolFlag());
        assertNotNull(flag.getArgumentType());
        assertEquals(10, flag.getDefaultValue());
        assertEquals("Item count", flag.getDescription());
        assertTrue(flag.hasSuggestions());

        assertTrue(flag.matches("--count"));
        assertTrue(flag.matches("-c"));
    }

    @Test
    @DisplayName("Static suggestions should filter by the typed prefix, case-insensitively")
    void testStaticSuggestionFiltering() throws Exception {
        Flag<String> flag = Flags.stringFlag("mode").suggests("fast", "safe", "FALLBACK");
        assertTrue(flag.hasSuggestions());

        List<String> all = suggestionTexts(flag, "");
        assertEquals(List.of("FALLBACK", "fast", "safe"), all.stream().sorted().toList());

        assertEquals(List.of("FALLBACK", "fast"), suggestionTexts(flag, "fa").stream().sorted().toList());
        assertEquals(List.of("safe"), suggestionTexts(flag, "SA"));
    }

    @Test
    @DisplayName("Tooltip suggestions should preserve insertion order and attach a tooltip")
    void testTooltipSuggestions() throws Exception {
        Map<String, Component> options = new LinkedHashMap<>();
        options.put("fast", Component.text("Skips validation"));
        options.put("safe", Component.text("Checks every entry"));

        Flag<String> flag = Flags.stringFlag("mode").suggests(options);
        assertTrue(flag.hasSuggestions());

        Suggestions suggestions = suggestionsFor(flag, "");
        assertEquals(List.of("fast", "safe"), suggestions.getList().stream().map(Suggestion::getText).toList());
        assertNotNull(suggestions.getList().getFirst().getTooltip(), "each suggestion should carry a tooltip");
    }

    @Test
    @DisplayName("Tooltip suggestions should reject empty or null input")
    void testTooltipSuggestionValidation() {
        BoolFlag flag = Flags.boolFlag("x");

        assertThrows(IllegalArgumentException.class, () -> flag.suggests(Map.of()));
        assertThrows(NullPointerException.class, () -> flag.suggests((Map<String, Component>) null));
    }

    private static Suggestions suggestionsFor(Flag<?> flag, String remaining) throws Exception {
        SuggestionsBuilder builder = new SuggestionsBuilder(remaining, 0);
        return flag.getSuggestionProvider().getSuggestions(null, builder).get();
    }

    private static List<String> suggestionTexts(Flag<?> flag, String remaining) throws Exception {
        return suggestionsFor(flag, remaining).getList().stream().map(Suggestion::getText).toList();
    }

    @Test
    @DisplayName("toFlagToken should format single char as - and multi char as --")
    void testToFlagToken() {
        assertEquals("-v", Flag.toFlagToken("v"));
        assertEquals("--verbose", Flag.toFlagToken("verbose"));
    }

    @Test
    @DisplayName("Flag alias validations should throw IllegalArgumentException on invalid input")
    void testFlagAliasValidation() {
        BoolFlag flag = Flags.boolFlag("test");

        assertThrows(IllegalArgumentException.class, () -> flag.alias("---"));
        assertThrows(IllegalArgumentException.class, () -> flag.alias("test"));
        flag.alias("t");
        assertThrows(IllegalArgumentException.class, () -> flag.alias("t"));
    }
}
