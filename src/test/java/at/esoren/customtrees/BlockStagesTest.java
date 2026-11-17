package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

/**
 * The block break and place handlers of CustomTrees, called by Vystorm Core's real stage dispatcher together with the
 * handlers of a second plugin: nothing of CustomTrees runs for a break or placement that was denied.
 */
class BlockStagesTest {
    private final Plugin trees = EventStageTesting.plugin("CustomTrees"), other = EventStageTesting.plugin("AProtectionPlugin");
    private final Player player = mock(Player.class);
    private final Block block = mock(Block.class);
    private final List<String> calls = new ArrayList<>();
    private boolean deny;

    // every listener with its real registration and silent handlers
    private final GardenTrees gardens = mock(GardenTrees.class, CALLS_REAL_METHODS);
    private final GeneratedTreeListener generated = mock(GeneratedTreeListener.class, CALLS_REAL_METHODS);
    private final GlowstoneGuard glowstone = mock(GlowstoneGuard.class, CALLS_REAL_METHODS);
    private final GrowthPlanting plantings = mock(GrowthPlanting.class, CALLS_REAL_METHODS);
    private final PlantingHelper stencil = mock(PlantingHelper.class, CALLS_REAL_METHODS);
    private final TimberFeature timber = mock(TimberFeature.class, CALLS_REAL_METHODS);
    private final TreeFireGuard fireGuard = mock(TreeFireGuard.class, CALLS_REAL_METHODS);

    private BlockPlaceEvent placing(ItemStack item) {
        return new BlockPlaceEvent(block, mock(BlockState.class), block, item, player, true, EquipmentSlot.HAND);
    }

    /** All listeners in the order of CustomTreesPlugin.onEnable, plus a protection plugin that denies on demand. */
    private EventStageTesting everyListener() {
        doNothing().when(gardens).broken(any());
        doNothing().when(gardens).placed(any());
        doNothing().when(generated).onBlockBreak(any());
        doNothing().when(generated).onBlockPlace(any());
        doNothing().when(glowstone).onBreak(any());
        doNothing().when(glowstone).onBroken(any());
        doNothing().when(glowstone).onPlace(any());
        doNothing().when(plantings).validatePlace(any());
        doNothing().when(plantings).place(any());
        doNothing().when(plantings).broken(any());
        doNothing().when(stencil).placed(any());
        doNothing().when(timber).onBreak(any());
        doNothing().when(fireGuard).onBroken(any());
        doNothing().when(fireGuard).onPlaced(any());
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
            stencil.handleBlockEvents(trees, stages);
            fireGuard.handleBlockEvents(trees, stages);
            plantings.handleBlockEvents(trees, stages);
            gardens.handleBlockEvents(trees, stages);
            generated.handleBlockEvents(trees, stages);
            glowstone.handleBlockEvents(trees, stages);
            timber.handleBlockEvents(trees, stages);
            denyOnDemand(stages);
        return harness;
    }

    private void denyOnDemand(EventStages stages) {
        stages.handle(other, BlockBreakEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny));
        stages.handle(other, BlockPlaceEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny));
    }

    @Test
    void givenDeniedBreakOrPlacement_whenFired_thenNoHandlerOfCustomTreesRuns() {
        EventStageTesting harness = everyListener();
        deny = true;

        assertTrue(harness.fire(new BlockBreakEvent(block, player)).isCancelled());
        assertTrue(harness.fire(placing(mock(ItemStack.class))).isCancelled());

        verify(glowstone, never()).onBreak(any());
        verify(glowstone, never()).onBroken(any());
        verify(glowstone, never()).onPlace(any());
        verify(timber, never()).onBreak(any());
        verify(fireGuard, never()).onBroken(any());
        verify(fireGuard, never()).onPlaced(any());
        verify(gardens, never()).broken(any());
        verify(gardens, never()).placed(any());
        verify(generated, never()).onBlockBreak(any());
        verify(generated, never()).onBlockPlace(any());
        verify(plantings, never()).validatePlace(any());
        verify(plantings, never()).place(any());
        verify(plantings, never()).broken(any());
        verify(stencil, never()).placed(any());
    }

    @Test
    void givenAllowedBreakOrPlacement_whenFired_thenDropsAndRulesComeBeforeTheBookkeepingInTheOldOrder() {
        EventStageTesting harness = everyListener();

        BlockBreakEvent broken = harness.fire(new BlockBreakEvent(block, player));
        BlockPlaceEvent placed = harness.fire(placing(mock(ItemStack.class)));

        InOrder breaking = inOrder(glowstone, fireGuard, plantings, gardens, generated, timber);
        breaking.verify(glowstone).onBreak(broken);        // ADJUST
        breaking.verify(fireGuard).onBroken(broken);       // OBSERVE, in registration order
        breaking.verify(plantings).broken(broken);
        breaking.verify(gardens).broken(broken);
        breaking.verify(generated).onBlockBreak(broken);
        breaking.verify(glowstone).onBroken(broken);
        breaking.verify(timber).onBreak(broken);
        InOrder placing = inOrder(stencil, fireGuard, plantings, gardens, generated, glowstone);
        placing.verify(plantings).validatePlace(placed);   // ADJUST
        placing.verify(stencil).placed(placed);            // OBSERVE, in registration order
        placing.verify(fireGuard).onPlaced(placed);
        placing.verify(plantings).place(placed);
        placing.verify(gardens).placed(placed);
        placing.verify(generated).onBlockPlace(placed);
        placing.verify(glowstone).onPlace(placed);
    }

    @Test
    void givenChunkThatCannotStoreAnotherGrowthSapling_whenOneIsPlaced_thenItIsRefusedAndNeitherRecordedNorSeenByFeatures() {
        CustomTreesPlugin plugin = mock(CustomTreesPlugin.class);
        GrowthPlanting real = spy(new GrowthPlanting(plugin));
        World world = mock(World.class);
        when(block.getWorld()).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); // the chunk data cannot be read
        EventStageTesting harness = EventStageTesting.detached();
        {
            EventStages stages = harness.stages();
            stages.handle(other, BlockPlaceEvent.class, EventStage.ACT, event -> calls.add("act"));
            stages.handle(other, BlockPlaceEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
            real.handleBlockEvents(trees, stages);
        }
        ItemStack sapling = mock(ItemStack.class);

        try (MockedStatic<GrowthSapling> saplings = mockStatic(GrowthSapling.class)) {
            saplings.when(() -> GrowthSapling.read(any())).thenCallRealMethod();
            saplings.when(() -> GrowthSapling.read(sapling)).thenReturn(Optional.of(new GrowthSapling.Spec(TreeSpecies.OAK,
                    GrowthSapling.CrownForm.RANDOM, GrowthSapling.GrowthClass.NORMAL)));

            assertTrue(harness.fire(placing(sapling)).isCancelled());
            verify(plugin).message(player, "garden.unavailable");
            verify(real, never()).place(any());
            assertEquals(List.of(), calls);

            // an ordinary block in the same chunk is not this plugin's business
            ItemStack stone = mock(ItemStack.class);
            when(stone.getType()).thenReturn(Material.STONE);
            assertFalse(harness.fire(placing(stone)).isCancelled());
            verify(real).place(any());
            assertEquals(List.of("act", "observe"), calls);
        }
    }
}
