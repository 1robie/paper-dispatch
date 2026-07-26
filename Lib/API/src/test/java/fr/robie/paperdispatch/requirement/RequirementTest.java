package fr.robie.paperdispatch.requirement;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class RequirementTest {

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
    @DisplayName("PermissionRequirement should return true if sender has permission")
    void testPermissionRequirement() {
        PermissionRequirement<Plugin> requirement = new PermissionRequirement<>("admin.use");

        CommandSourceStack mockSource = Mockito.mock(CommandSourceStack.class);
        CommandSender mockSender = Mockito.mock(CommandSender.class);

        when(mockSource.getSender()).thenReturn(mockSender);
        when(mockSender.hasPermission("admin.use")).thenReturn(true);
        when(mockSender.hasPermission("other.perm")).thenReturn(false);

        assertTrue(requirement.isMet(this.plugin, mockSource));

        PermissionRequirement<Plugin> otherReq = new PermissionRequirement<>("other.perm");
        assertFalse(otherReq.isMet(this.plugin, mockSource));
    }

    @Test
    @DisplayName("PlayerOnlyRequirement should only allow Player instances")
    void testPlayerOnlyRequirement() {
        PlayerOnlyRequirement<Plugin> requirement = new PlayerOnlyRequirement<>();

        CommandSourceStack mockSource1 = Mockito.mock(CommandSourceStack.class);
        CommandSourceStack mockSource2 = Mockito.mock(CommandSourceStack.class);

        Player mockPlayer = Mockito.mock(Player.class);
        CommandSender mockConsoleSender = Mockito.mock(CommandSender.class);

        when(mockSource1.getSender()).thenReturn(mockPlayer);
        when(mockSource2.getSender()).thenReturn(mockConsoleSender);

        assertTrue(requirement.isMet(this.plugin, mockSource1));
        assertFalse(requirement.isMet(this.plugin, mockSource2));
    }
}
