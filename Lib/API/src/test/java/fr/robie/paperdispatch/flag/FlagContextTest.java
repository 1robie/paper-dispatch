package fr.robie.paperdispatch.flag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FlagContextTest {

    @Test
    @DisplayName("Empty context should return defaults and empty values")
    void testEmptyContext() {
        FlagContext ctx = FlagContext.empty();
        assertFalse(ctx.hasFlag("verbose"));
        assertTrue(ctx.getPresentFlags().isEmpty());
        assertTrue(ctx.getAllValues().isEmpty());
        assertEquals(10, ctx.getValue("count", Integer.class, 10));
        assertEquals(Optional.empty(), ctx.getOptionalValue("count", Integer.class));
        assertThrows(NoSuchElementException.class, () -> ctx.getValue("count", Integer.class));
    }

    @Test
    @DisplayName("FlagContext with explicit values should store and retrieve typed values")
    void testExplicitValues() {
        Map<String, Object> values = Map.of("count", 42, "silent", true);
        Set<String> present = Set.of("count", "silent");

        FlagContext ctx = new FlagContext(values, present);

        assertTrue(ctx.hasFlag("count"));
        assertTrue(ctx.hasFlag("silent"));
        assertFalse(ctx.hasFlag("unknown"));

        assertEquals(42, ctx.getValue("count", Integer.class));
        assertEquals(true, ctx.getValue("silent", Boolean.class));
        assertEquals(42, ctx.getValue("count", Integer.class, 100));

        assertEquals(Optional.of(42), ctx.getOptionalValue("count", Integer.class));
        assertEquals(Optional.empty(), ctx.getOptionalValue("unknown", Integer.class));
    }

    @Test
    @DisplayName("A flag mapped to an explicit null default counts as present in every accessor")
    void testNullValuedFlagIsConsistentlyPresent() {
        Map<String, Object> values = new HashMap<>();
        values.put("nullable", null);

        FlagContext ctx = new FlagContext(values, Set.of("nullable"));

        assertTrue(ctx.hasFlag("nullable"));
        assertNull(ctx.getValue("nullable", String.class),
                "a null-valued flag is present, so getValue must return null rather than throw");
        assertEquals(Optional.empty(), ctx.getOptionalValue("nullable", String.class),
                "Optional cannot hold null; an empty Optional here means 'present but null'");
        assertNull(ctx.getValue("nullable", String.class, "fallback"),
                "the flag is present, so the fallback must not be substituted");

        assertThrows(NoSuchElementException.class, () -> ctx.getValue("absent", String.class));
        assertEquals("fallback", ctx.getValue("absent", String.class, "fallback"));
    }

    @Test
    @DisplayName("FlagContext should correctly handle default values vs present flags")
    void testDefaultValueDifference() {
        Map<String, Object> values = Map.of("count", 5);
        Set<String> present = Set.of(); // count has a default value 5, but was not explicitly provided

        FlagContext ctx = new FlagContext(values, present);

        assertFalse(ctx.hasFlag("count"));
        assertEquals(5, ctx.getValue("count", Integer.class));
        assertEquals(Optional.of(5), ctx.getOptionalValue("count", Integer.class));
    }
}
