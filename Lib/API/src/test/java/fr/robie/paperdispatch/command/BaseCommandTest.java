package fr.robie.paperdispatch.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseCommandTest {

    @Test
    @DisplayName("BaseCommand builder should correctly construct BaseCommand properties")
    void testBaseCommandBuilder() {
        PluginMock mockPlugin = MockBukkit.createMockPlugin();

        BaseCommand<PluginMock> command = BaseCommand.builder(mockPlugin, "testcmd")
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
