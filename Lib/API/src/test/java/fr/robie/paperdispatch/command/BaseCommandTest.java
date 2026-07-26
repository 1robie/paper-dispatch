package fr.robie.paperdispatch.command;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseCommandTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.plugin = PluginMock.builder().withPluginName("TestPlugin").build();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("BaseCommand builder should correctly construct BaseCommand properties")
    void testBaseCommandBuilder() {
        BaseCommand<Plugin> command = BaseCommand.builder(this.plugin, "testcmd")
                .alias("tc", "t")
                .description("Test command description")
                .reloadable(true)
                .executes(dispatch -> CommandResultType.SUCCESS)
                .build();

        assertEquals("testcmd", command.getName());
        assertEquals("Test command description", command.getDescription());
        assertTrue(command.isReloadable());
        assertEquals(2, command.getAliases().size());
        assertTrue(command.getAliases().contains("tc"));
        assertTrue(command.getAliases().contains("t"));
    }
}
