package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HerbariumPanelTest {
    private final UUID target = UUID.randomUUID();
    private final Herbarium data = new Herbarium();
    private final HerbariumPanel.Actions actions = mock(HerbariumPanel.Actions.class);

    private Panel panel(String selected, boolean planting) {
        return HerbariumPanel.panel(PanelTestSupport.context(null),
                HerbariumPanel.overview(data, target, "Alex", planting, selected), actions, (p, key) -> {});
    }

    @Test void givenAnotherPlayersCollection_whenRendered_thenTheirProgressAndSelectedFormsAreShown() {
        data.record(target, "Alex", Herbarium.Plant.BIRCH, Herbarium.Stage.MEDIUM);
        data.record(UUID.randomUUID(), "Other", Herbarium.Plant.OAK, Herbarium.Stage.GIANT);
        var doc = PanelTesting.render(panel("birch", true));
        assertTrue(doc.toString().contains("Herbarium of Alex"));
        assertEquals(1, PanelTesting.find(doc, "collection-progress").get("value").getAsInt());
        assertEquals(Herbarium.TOTAL, PanelTesting.find(doc, "collection-progress").get("max").getAsInt());
        assertEquals("birch", PanelTesting.find(doc, "list-trees").get("selected").getAsString());
        assertTrue(PanelTesting.plain(PanelTesting.find(doc, "stage-birch-medium").get("text")).contains("✔"));
        assertTrue(PanelTesting.plain(PanelTesting.find(doc, "stage-birch-single").get("text")).contains("✘"));
        assertTrue(PanelTesting.ids(doc).containsAll(java.util.List.of("plants", "list-trees", "list-fungi", "next-birch", "top", "planting")));
        assertNull(PanelTesting.find(doc, "detail-oak"));
        assertNull(PanelTesting.find(PanelTesting.render(panel(null, false)), "planting"));
    }

    @Test void givenCompletedCollection_whenRendered_thenFinalRankAndFullProgressAreShown() {
        for (var plant : Herbarium.Plant.available()) for (var stage : plant.stages()) data.record(target, "Alex", plant, stage);
        var doc = PanelTesting.render(panel("red_mushroom", false));
        assertEquals("fungi", PanelTesting.find(doc, "plants").get("selected").getAsString());
        assertEquals("Every form found!", PanelTesting.plain(PanelTesting.find(doc, "next-rank").get("text")));
        assertEquals("Keeper of Yggdrasil", PanelTesting.plain(PanelTesting.find(doc, "rank").get("text")));
        assertEquals(Herbarium.TOTAL, PanelTesting.find(doc, "collection-progress").get("value").getAsInt());
    }

    @SuppressWarnings("unchecked")
    @Test void givenTwoViewers_whenSelectingAndQuitting_thenSelectionIsValidatedIsolatedAndForgotten() throws Exception {
        var feature = mock(HerbariumFeature.class);
        when(feature.data()).thenReturn(data);
        var helper = mock(PlantingHelper.class);
        var screen = new HerbariumPanel(feature, helper, actions);
        Player first = mock(Player.class), second = mock(Player.class);
        when(first.getUniqueId()).thenReturn(UUID.randomUUID());
        when(second.getUniqueId()).thenReturn(UUID.randomUUID());
        when(actions.check(first)).thenReturn(Result.ok());
        PanelAction action = mock(PanelAction.class);
        when(action.player()).thenReturn(first);
        Ui.Change<String> select = (Ui.Change<String>) PanelTestSupport.handler(screen.render(PanelTestSupport.context(first), target, "Alex"), "list-trees");
        assertFalse(select.handle(action, "not-a-plant").success());
        assertTrue(select.handle(action, "birch").success());
        verify(action).refresh();
        assertNotNull(PanelTesting.find(PanelTesting.render(screen.render(PanelTestSupport.context(first), target, "Alex")), "detail-birch"));
        assertNotNull(PanelTesting.find(PanelTesting.render(screen.render(PanelTestSupport.context(second), target, "Alex")), "detail-oak"));
        when(actions.check(first)).thenReturn(Result.error("denied"));
        assertFalse(select.handle(action, "spruce").success());
        verify(action, times(1)).refresh();
        screen.forget(first.getUniqueId());
        assertNotNull(PanelTesting.find(PanelTesting.render(screen.render(PanelTestSupport.context(first), target, "Alex")), "detail-oak"));
    }

    @SuppressWarnings("unchecked")
    @Test void givenTaskButton_whenClicked_thenClosesBeforeRequestingTheViewersOwnTask() throws Exception {
        Player viewer = mock(Player.class);
        PanelAction action = mock(PanelAction.class);
        when(action.player()).thenReturn(viewer);
        when(actions.check(viewer)).thenReturn(Result.ok());
        var click = (Ui.Click) PanelTestSupport.handler(panel("birch", false), "next-birch");
        assertTrue(click.handle(action).success());
        verify(actions, never()).suggest(any(), any());
        ArgumentCaptor<Consumer<Player>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(action).closeAndThen(callback.capture());
        callback.getValue().accept(viewer);
        verify(actions).suggest(viewer, "birch");
    }

    @Test void givenSevenLanguages_whenRendered_thenAllDocumentsValidateWithTranslatedLabels() {
        for (String language : PanelTestSupport.LANGUAGES) {
            var ctx = PanelTestSupport.context(null, language);
            var doc = PanelTesting.render(HerbariumPanel.panel(ctx,
                    HerbariumPanel.overview(data, target, "Alex", true, null), actions, (p, key) -> {}));
            assertEquals(ctx.text("panel.next-task"), PanelTesting.plain(PanelTesting.find(doc, "next-oak").get("label")));
            assertFalse(doc.toString().contains("panel."), language);
        }
    }
}
