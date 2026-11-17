package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import Vystorm.vystorm_core.lang.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PlantingStatusTest {
    @Test void statusDoesNotConsumeOrRenewSelectionOrReadWorldAndInventory() {
        try (var buttons=mockStatic(Vystorm.vystorm_core._uiMethods.chat.ChatActions.class, call -> Component.empty())) {
            var player=mock(Player.class);
            when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
            when(player.hasPermission("customtrees.plant")).thenReturn(true);
            var lang=mock(Lang.class);
            when(lang.component(eq(player),anyString(),any(Object[].class))).thenReturn(Component.empty());
            var helper=new PlantingHelper(TestCore.plugin(),lang);
            helper.command(player,new String[]{"plant","status"});
            verify(lang).send(player,"planting.status-none");
            helper.arm(player,new PlantingStencil.Pattern(8,true));
            Object before=helper.selection(player);
            assertNotNull(before);
            helper.command(player,new String[]{"plant","status"});
            verify(lang).send(eq(player),eq("planting.status-selection"),any(Object[].class));
            assertSame(before,helper.selection(player));
            verify(player,never()).getInventory();
            verify(player,never()).getWorld();
        }
    }
}
