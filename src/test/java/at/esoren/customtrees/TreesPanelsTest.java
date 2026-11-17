package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Panels;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TreesPanelsTest {
    private final CustomTreesPlugin plugin = TestCore.plugin();
    private final HerbariumFeature herbarium = mock(HerbariumFeature.class);
    private final PlantingHelper planting = mock(PlantingHelper.class);
    private final Player viewer = mock(Player.class);
    private final PlantingStencil.Pattern square = new PlantingStencil.Pattern(3, false);

    TreesPanelsTest() {
        when(plugin.lang()).thenReturn(PanelTestSupport.lang());
        when(plugin.herbarium()).thenReturn(herbarium);
        when(plugin.planting()).thenReturn(planting);
        when(plugin.isEnabled()).thenReturn(true);
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.hasPermission(anyString())).thenReturn(true);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.getName()).thenReturn("Viewer");
        when(herbarium.enabled()).thenReturn(true);
        when(herbarium.usable(viewer)).thenReturn(true);
        when(herbarium.data()).thenReturn(new Herbarium());
        when(planting.usable(viewer)).thenReturn(true);
        when(planting.options()).thenReturn(new PlantingStencil.Options(true, 10, false, 300000, 16));
        when(planting.menuPatterns()).thenReturn(List.of(square));
    }

    @Test void givenOpenScreen_whenPermissionOrConfigurationChanges_thenMutationIsRejected() {
        var actions = TreesPanels.plantingActions(plugin);
        assertTrue(actions.arm(viewer, square).success());
        verify(planting).arm(viewer, square);
        when(viewer.hasPermission("customtrees.plant")).thenReturn(false);
        assertFalse(actions.arm(viewer, square).success());
        assertFalse(actions.disarm(viewer).success());
        when(viewer.hasPermission("customtrees.plant")).thenReturn(true);
        when(planting.options()).thenReturn(new PlantingStencil.Options(true, 2, false, 300000, 0));
        assertFalse(actions.arm(viewer, square).success());
        when(planting.options()).thenReturn(new PlantingStencil.Options(false, 10, false, 300000, 16));
        assertFalse(actions.arm(viewer, square).success());
        assertFalse(actions.disarm(viewer).success());
        verify(planting, times(1)).arm(viewer, square);
        verify(planting, never()).disarm(viewer);
    }

    @Test void givenDelayedHandover_whenPermissionIsRevokedOrPlayerDisconnects_thenNoServiceRuns() {
        var actions = TreesPanels.herbariumActions(plugin);
        actions.suggest(viewer, "oak");
        verify(herbarium).suggest(viewer, "oak");
        when(viewer.hasPermission("customtrees.herbarium")).thenReturn(false);
        actions.suggest(viewer, "oak");
        actions.top(viewer);
        actions.planting(viewer);
        verify(herbarium, times(1)).suggest(viewer, "oak");
        verify(herbarium, never()).sendTop(viewer);
        verify(planting, never()).openMenu(viewer);
        when(viewer.hasPermission("customtrees.herbarium")).thenReturn(true);
        when(viewer.isOnline()).thenReturn(false);
        assertFalse(actions.check(viewer).success());
        assertFalse(TreesPanels.plantingActions(plugin).arm(viewer, square).success());
    }

    @Test void givenRegistration_whenNativeOrFallbackOpens_thenTargetIsPreservedAndSelfCanBeRestored() {
        Panels core = mock(Panels.class);
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(core);
            TreesPanels integration = new TreesPanels(plugin);
            integration.register();
            ArgumentCaptor<PanelSpec> herbariumSpec = ArgumentCaptor.forClass(PanelSpec.class);
            ArgumentCaptor<PanelSpec> plantingSpec = ArgumentCaptor.forClass(PanelSpec.class);
            verify(core).register(eq("herbarium"), herbariumSpec.capture());
            verify(core).register(eq("planting"), plantingSpec.capture());
            assertTrue(herbariumSpec.getValue().access().test(viewer));
            when(core.open(viewer, "herbarium")).thenReturn(PanelOpenResult.NATIVE);
            UUID other = UUID.randomUUID();
            assertTrue(integration.openHerbarium(viewer, other, "Alex"));
            var doc = PanelTesting.render(herbariumSpec.getValue().render().apply(PanelTestSupport.context(viewer)));
            assertTrue(doc.toString().contains("Herbarium of Alex"));
            herbariumSpec.getValue().fallback().accept(viewer);
            verify(herbarium).openChest(viewer, other, "Alex");
            integration.openHerbarium(viewer, viewer.getUniqueId(), viewer.getName());
            herbariumSpec.getValue().fallback().accept(viewer);
            verify(herbarium).openChest(viewer, viewer.getUniqueId(), "Viewer");
            when(core.open(viewer, "planting")).thenReturn(PanelOpenResult.FALLBACK, PanelOpenResult.NONE);
            assertTrue(integration.openPlanting(viewer));
            assertFalse(integration.openPlanting(viewer));
            plantingSpec.getValue().fallback().accept(viewer);
            verify(planting).openChest(viewer);
            when(plugin.isEnabled()).thenReturn(false);
            assertFalse(herbariumSpec.getValue().access().test(viewer));
            assertFalse(plantingSpec.getValue().access().test(viewer));
            integration.close();
            factory.verify(() -> Panels.release(plugin));
        }
    }

    @Test void givenFeatureOpeners_whenPanelsAreUnavailable_thenOriginalChestsAreUsed() {
        when(plugin.getDataFolder()).thenReturn(new java.io.File("build/panel-tests"));
        var realHerbarium = spy(new HerbariumFeature(plugin, plugin.lang()));
        var realPlanting = spy(new PlantingHelper(plugin, plugin.lang()));
        doNothing().when(realHerbarium).openChest(any(), any(), any());
        doNothing().when(realPlanting).openChest(any());
        realHerbarium.open(viewer, viewer.getUniqueId(), "Viewer");
        realPlanting.openMenu(viewer);
        verify(realHerbarium).openChest(viewer, viewer.getUniqueId(), "Viewer");
        verify(realPlanting).openChest(viewer);
        when(plugin.openHerbariumPanel(viewer, viewer.getUniqueId(), "Viewer")).thenReturn(true);
        when(plugin.openPlantingPanel(viewer)).thenReturn(true);
        realHerbarium.open(viewer, viewer.getUniqueId(), "Viewer");
        realPlanting.openMenu(viewer);
        verify(realHerbarium, times(1)).openChest(viewer, viewer.getUniqueId(), "Viewer");
        verify(realPlanting, times(1)).openChest(viewer);
    }

    @Test void givenPanelOpenFailure_whenOpening_thenExceptionsAllowChestFallback() throws Exception {
        TreesPanels integration = mock(TreesPanels.class);
        UUID target = viewer.getUniqueId();
        var field = CustomTreesPlugin.class.getDeclaredField("panels");
        field.setAccessible(true);
        field.set(plugin, integration);
        doCallRealMethod().when(plugin).openPlantingPanel(viewer);
        doCallRealMethod().when(plugin).openHerbariumPanel(viewer, target, "Viewer");
        when(integration.openPlanting(viewer)).thenThrow(new IllegalStateException("unavailable"));
        when(integration.openHerbarium(viewer, target, "Viewer")).thenThrow(new IllegalStateException("unavailable"));
        try (var debug = mockStatic(DebugMessages.class)) {
            assertFalse(plugin.openPlantingPanel(viewer));
            assertFalse(plugin.openHerbariumPanel(viewer, target, "Viewer"));
        }
    }
}
