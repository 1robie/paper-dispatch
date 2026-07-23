package fr.robie.paperdispatch.flag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
