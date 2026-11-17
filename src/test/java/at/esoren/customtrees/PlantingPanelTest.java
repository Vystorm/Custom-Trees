package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PlantingPanelTest {
    private final PlantingPanel.Actions actions = mock(PlantingPanel.Actions.class);
    private final PlantingStencil.Pattern square = new PlantingStencil.Pattern(3, false);
    private final PlantingStencil.Pattern hedge = new PlantingStencil.Pattern(8, true);

    @Test void givenAllowedFormations_whenRendered_thenTabsCountsActiveSelectionAndNavigationAreShown() {
        var doc = PanelTesting.render(PlantingPanel.panel(PanelTestSupport.context(null),
                new PlantingPanel.Overview(List.of(square, hedge), hedge, true), actions));
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("formations", "arm-3x3", "arm-hedge8", "active-hedge8", "off", "herbarium")));
        assertEquals("hedges", PanelTesting.find(doc, "formations").get("selected").getAsString());
        assertTrue(doc.toString().contains("Uses 9 plants of one kind"));
        assertTrue(doc.toString().contains("Uses 8 plants of one kind"));
        assertTrue(doc.toString().contains("Grows: 3×3 giant"));
        assertNull(PanelTesting.find(doc, "arm-10x10"));
    }

    @Test void givenNoHedgesOrHerbarium_whenRendered_thenUnavailableChoicesAreOmitted() {
        var doc = PanelTesting.render(PlantingPanel.panel(PanelTestSupport.context(null),
                new PlantingPanel.Overview(List.of(square), null, false), actions));
        assertNull(PanelTesting.find(doc, "herbarium"));
        assertFalse(doc.toString().contains("Hedge rows"));
        assertFalse(doc.toString().contains("active-"));
        assertEquals("No planting pattern selected.", PanelTesting.plain(PanelTesting.find(doc, "selection").get("text")));
    }

    @Test void givenSelectionButton_whenActionSucceedsOrFails_thenOnlySuccessfulChangesRefresh() throws Exception {
        Player player = mock(Player.class);
        PanelAction action = mock(PanelAction.class);
        when(action.player()).thenReturn(player);
        var panel = PlantingPanel.panel(PanelTestSupport.context(player), new PlantingPanel.Overview(List.of(square), null, false), actions);
        var arm = (Ui.Click) PanelTestSupport.handler(panel, "arm-3x3");
        when(actions.arm(player, square)).thenReturn(Result.error("disabled"), Result.ok());
        assertFalse(arm.handle(action).success());
        verify(action, never()).refresh();
        assertTrue(arm.handle(action).success());
        verify(action).refresh();
        when(actions.disarm(player)).thenReturn(Result.ok());
        assertTrue(((Ui.Click) PanelTestSupport.handler(panel, "off")).handle(action).success());
        verify(actions).disarm(player);
        verify(action, times(2)).refresh();
    }

    @Test void givenSevenLanguages_whenRendered_thenAllDocumentsValidateWithTranslatedLabels() {
        for (String language : PanelTestSupport.LANGUAGES) {
            var ctx = PanelTestSupport.context(null, language);
            var doc = PanelTesting.render(PlantingPanel.panel(ctx, new PlantingPanel.Overview(List.of(square, hedge), square, true), actions));
            assertEquals(ctx.text("panel.active"), PanelTesting.plain(PanelTesting.find(doc, "active-3x3").get("text")));
            assertFalse(doc.toString().contains("panel."), language);
        }
    }
}
