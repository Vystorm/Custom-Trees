package at.esoren.customtrees;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.particleHandler.ParticleInterface;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.*;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import Vystorm.vystorm_core._coreMethods.inventoryEventHandler.InventoryEventInterface;
import Vystorm.vystorm_core._coreMethods.playerHandler.PlayerHandlerInterface;
import Vystorm.vystorm_core.region.RegionService;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoreIntegrationTest {
    @Test
    void glowstoneUsesCoreXpAndPropagatesReplacementGeneratorFailure() {
        try (Fixture f = new Fixture()) {
            when(f.players.withdrawExperience(f.player, 100)).thenReturn(true);
            PlayerInteractEvent event = f.click(Material.GLOWSTONE_DUST);
            f.stages.fire(event);
            verify(f.players).withdrawExperience(f.player, 100);
            verify(f.player, never()).setExperienceLevelAndProgress(anyInt());
            verify(f.catalyst).setAmount(1);
            f.assertUseReported(0);
            assertEquals(Event.Result.DENY, event.useItemInHand());
            assertNotNull(f.scheduled.get());

            // An override installed before the queued task runs must be used.
            StructureGeneratorInterface replacement = mock(StructureGeneratorInterface.class);
            TreeGenerator builder = mock(TreeGenerator.class);
            when(replacement.tree(any(), any(), eq(TreeSpecies.OAK))).thenReturn(builder);
            when(f.api.structureGenerator()).thenReturn(replacement);
            IllegalStateException failure = new IllegalStateException("test generator failed");
            doThrow(failure).when(builder).buildNormal(any());
            assertSame(failure, assertThrows(IllegalStateException.class, () -> f.scheduled.get().run()));
            verify(builder).buildNormal(new StructurePos(0, 64, 0));
            verifyNoInteractions(f.particles);
        }
    }

    @Test
    void insufficientCoreXpConsumesNoItemAndSchedulesNothing() {
        try (Fixture f = new Fixture()) {
            f.stages.fire(f.click(Material.GLOWSTONE_DUST));
            verify(f.players).withdrawExperience(f.player, 100);
            verify(f.catalyst, never()).setAmount(anyInt());
            verifyNoInteractions(f.inventoryEvents);
            assertNull(f.scheduled.get());
            verify(f.plugin).actionBar(f.player, "growth.glowstone-missing-xp", "xp", 100);
        }
    }

    @Test
    void creativeGrowthUsesNeitherXpNorCatalyst() {
        try (Fixture f = new Fixture()) {
            when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            f.stages.fire(f.click(Material.GLOWSTONE_DUST));
            verifyNoInteractions(f.players);
            verify(f.catalyst, never()).setAmount(anyInt());
            verifyNoInteractions(f.inventoryEvents);
            assertNotNull(f.scheduled.get());
        }
    }

    @Test
    void bonemealConsumesOneItemWithoutXp() {
        try (Fixture f = new Fixture()) {
            f.stages.fire(f.click(Material.BONE_MEAL));
            verifyNoInteractions(f.players);
            verify(f.catalyst).setAmount(1);
            f.assertUseReported(0);
            assertNotNull(f.scheduled.get());
        }
    }

    @Test
    void coalUseReportsTheExactOffhandCatalyst() {
        try (Fixture f = new Fixture()) {
            f.stages.fire(f.click(Material.COAL, EquipmentSlot.OFF_HAND));
            verify(f.offhandCatalyst).setAmount(4);
            f.assertUseReported(40);
        }
    }

    @Test
    void coalCostFirstClickPreviewsWithoutConsumingOrPlanningAndSecondClickChargesExactHandStack() {
        try (Fixture f = new Fixture()) {
            f.setCoalCost(3);
            f.builds(smallTree());

            PlayerInteractEvent preview = f.click(Material.COAL, EquipmentSlot.OFF_HAND);
            f.stages.fire(preview);

            assertEquals(Event.Result.DENY, preview.useItemInHand());
            ArgumentCaptor<Object[]> previewValues = ArgumentCaptor.forClass(Object[].class);
            verify(f.plugin).message(eq(f.player), eq("growth.coal-cost-preview"), previewValues.capture());
            assertEquals(3, previewValues.getValue()[1]);
            verify(f.offhandCatalyst, never()).setAmount(anyInt());
            verify(f.catalyst, never()).setAmount(anyInt());
            assertTrue(f.tasks.isEmpty());
            verify(f.pluginManager, never()).callEvent(any(StructureGrowEvent.class));

            f.stages.fire(f.click(Material.COAL, EquipmentSlot.OFF_HAND));
            verify(f.offhandCatalyst).setAmount(2);
            verify(f.catalyst, never()).setAmount(anyInt());
            f.assertUseReported(40);
            verify(f.plugin).message(eq(f.player), eq("growth.coal-cost-confirm"), any(Object[].class));
            f.runTasks();
            assertFalse(f.updated.isEmpty());
            assertEquals(1, f.growEvents.size());
        }
    }

    @Test
    void cancelledThreeCoalGrowthRefundsWholeMetadataPreservingOffhandStack() {
        try (Fixture f = new Fixture()) {
            f.setCoalCost(3);
            f.builds(smallTree());
            f.protection = event -> event.setCancelled(true);
            ItemMeta metadata = mock(ItemMeta.class);
            when(f.offhandCatalyst.getItemMeta()).thenReturn(metadata);
            when(f.offhandBeforeUse.getItemMeta()).thenReturn(metadata);
            when(f.offhandRefundItem.getItemMeta()).thenReturn(metadata);

            f.stages.fire(f.click(Material.COAL, EquipmentSlot.OFF_HAND));
            f.stages.fire(f.click(Material.COAL, EquipmentSlot.OFF_HAND));
            f.runTasks();

            verify(f.offhandCatalyst).setAmount(2);
            verify(f.inventory).addItem(f.offhandRefundItem);
            verify(f.offhandRefundItem).setAmount(3);
            assertSame(metadata, f.offhandRefundItem.getItemMeta());
            verify(f.catalyst, never()).setAmount(anyInt());
            assertTrue(f.updated.isEmpty());
        }
    }

    @Test
    void insufficientCoalNeverConsumesOrSchedulesGrowth() {
        try (Fixture f = new Fixture()) {
            f.setCoalCost(3);
            when(f.catalyst.getAmount()).thenReturn(2);
            f.stages.fire(f.click(Material.COAL));
            f.stages.fire(f.click(Material.COAL));

            verify(f.plugin).message(eq(f.player), eq("growth.coal-missing"), any(Object[].class));
            verify(f.catalyst, never()).setAmount(anyInt());
            verify(f.offhandCatalyst, never()).setAmount(anyInt());
            assertTrue(f.tasks.isEmpty());
            verify(f.pluginManager, never()).callEvent(any(StructureGrowEvent.class));
        }
    }

    @Test
    void coalCostChangeBetweenClicksRequiresANewConfirmation() {
        try (Fixture f = new Fixture()) {
            f.setCoalCost(3);
            f.stages.fire(f.click(Material.COAL));
            f.setCoalCost(4);
            f.stages.fire(f.click(Material.COAL));

            ArgumentCaptor<Object[]> previewValues = ArgumentCaptor.forClass(Object[].class);
            verify(f.plugin, times(2)).message(eq(f.player), eq("growth.coal-cost-preview"), previewValues.capture());
            assertEquals(3, previewValues.getAllValues().getFirst()[1]);
            assertEquals(4, previewValues.getAllValues().getLast()[1]);
            verify(f.catalyst, never()).setAmount(anyInt());
            assertTrue(f.tasks.isEmpty());
            verify(f.pluginManager, never()).callEvent(any(StructureGrowEvent.class));
        }
    }

    @Test
    void emptyCatalystReportsNoUse() {
        try (Fixture f = new Fixture()) {
            when(f.catalyst.getAmount()).thenReturn(0);
            f.stages.fire(f.click(Material.BONE_MEAL));
            verify(f.catalyst, never()).setAmount(anyInt());
            verifyNoInteractions(f.inventoryEvents);
        }
    }

    @Test
    void missingPermissionLeavesClickAndResourcesUntouched() {
        try (Fixture f = new Fixture()) {
            when(f.player.hasPermission("customtrees.grow")).thenReturn(false);
            PlayerInteractEvent event = f.click(Material.GLOWSTONE_DUST);
            Event.Result original = event.useItemInHand();
            f.stages.fire(event);
            assertEquals(original, event.useItemInHand());
            verifyNoInteractions(f.players);
            verify(f.catalyst, never()).setAmount(anyInt());
            verifyNoInteractions(f.inventoryEvents);
            assertNull(f.scheduled.get());
        }
    }

    private static StructurePos at(int x, int y, int z) {
        return new StructurePos(x, y, z);
    }

    private static Map<StructurePos, Material> smallTree() {
        Map<StructurePos, Material> tree = new LinkedHashMap<>();
        tree.put(at(0, 64, 0), Material.OAK_LOG);
        tree.put(at(0, 65, 0), Material.OAK_LOG);
        tree.put(at(0, 66, 0), Material.OAK_LOG);
        tree.put(at(1, 66, 0), Material.OAK_LEAVES);
        tree.put(at(-1, 66, 0), Material.OAK_LEAVES);
        return tree;
    }

    @Test
    void growthIsOfferedToProtectionPluginsAndOnlyAllowedBlocksAreApplied() {
        try (Fixture f = new Fixture()) {
            f.builds(smallTree());
            // A claim plugin removes the block that lies in a foreign claim.
            f.protection = event -> event.getBlocks().removeIf(state -> state.getX() == 1);
            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();

            assertEquals(1, f.growEvents.size());
            StructureGrowEvent event = f.growEvents.getFirst();
            assertSame(f.player, event.getPlayer());
            assertEquals(0, event.getLocation().getBlockX());
            assertEquals(64, event.getLocation().getBlockY());
            assertEquals(Set.of(at(0, 64, 0), at(0, 65, 0), at(0, 66, 0), at(-1, 66, 0)), f.updated.keySet());
            assertEquals(Material.OAK_LOG, f.types.get(at(0, 64, 0)));
            verify(f.inventory, never()).addItem(any(ItemStack[].class));
            assertEquals(1, f.listener.regularTreesGrown());
            assertEquals(1L, f.listener.speciesTreesGrown().get("oak"));
        }
    }

    @Test
    void cancelledGrowthChangesNothingAndRefundsCatalystAndXp() {
        try (Fixture f = new Fixture()) {
            when(f.players.withdrawExperience(f.player, 100)).thenReturn(true);
            f.builds(smallTree());
            f.protection = event -> event.setCancelled(true);
            f.stages.fire(f.click(Material.GLOWSTONE_DUST));
            f.runTasks();

            assertTrue(f.updated.isEmpty());
            assertEquals(Material.OAK_SAPLING, f.types.get(at(0, 64, 0)));
            f.assertRefunded(100);
            assertEquals(0L, f.listener.speciesTreesGrown().get("oak"));
        }
    }

    @Test
    void cancellingSpruceGrowthAlsoCancelsForestFloorAndSnow() {
        try (Fixture f = new Fixture()) {
            for (int x = -5; x <= 6; x++) for (int z = -5; z <= 6; z++)
                f.types.put(at(x, 63, z), Material.GRASS_BLOCK);
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++)
                f.types.put(at(x, 64, z), Material.SPRUCE_SAPLING);
            doAnswer(call -> {
                f.access.get().setType(0, 64, 0, Material.SPRUCE_LOG);
                f.access.get().setType(0, 65, 0, Material.SPRUCE_LOG);
                return null;
            }).when(f.builder).buildMedium(any());
            f.protection = event -> event.setCancelled(true);
            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();
            assertEquals(1, f.growEvents.size());
            var proposed = f.growEvents.getFirst().getBlocks();
            assertTrue(proposed.stream().anyMatch(block -> block.getType() == Material.PODZOL));
            assertTrue(proposed.stream().anyMatch(block -> block.getType() == Material.SNOW));
            assertTrue(f.updated.isEmpty());
            assertEquals(Material.SPRUCE_SAPLING, f.types.get(at(0, 64, 0)));
            assertEquals(Material.GRASS_BLOCK, f.types.get(at(2, 63, 2)));
        }
    }

    @Test
    void cancelledTaggedGrowthRegistersNoGardenFireMarkersOrSaplingConsumption() {
        try (Fixture f = new Fixture()) {
            TreeFireGuard fireGuard = mock(TreeFireGuard.class);
            GrowthPlanting plantings = mock(GrowthPlanting.class);
            GardenTrees gardens = mock(GardenTrees.class);
            when(f.plugin.fireGuard()).thenReturn(fireGuard);
            when(f.plugin.plantings()).thenReturn(plantings);
            when(f.plugin.gardens()).thenReturn(gardens);
            doAnswer(call -> {
                f.access.set(call.getArgument(0));
                return f.builder;
            }).when(f.structures).tree(any(), any(), eq(TreeSpecies.OAK), any(TreeGrowthProfile.class));
            when(plantings.at(any(Block.class))).thenReturn(new GrowthPlanting.Planted(f.playerId,
                    new GrowthSapling.Spec(TreeSpecies.OAK, GrowthSapling.CrownForm.RANDOM,
                            GrowthSapling.GrowthClass.NORMAL)));
            f.builds(smallTree());
            f.protection = event -> event.setCancelled(true);

            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();

            assertTrue(f.updated.isEmpty());
            verify(fireGuard, never()).markAll(anyCollection());
            verify(gardens, never()).register(any(), anyList());
            verify(plantings, never()).remove(any(Block.class));
            verify(plantings).at(any(Block.class));
        }
    }

    @Test
    void taggedSizeMismatchRefundsCoalAndGlowstoneExperienceWithoutBuilding() {
        try (Fixture f = new Fixture()) {
            GrowthPlanting plantings = mock(GrowthPlanting.class);
            when(f.plugin.plantings()).thenReturn(plantings);
            when(plantings.at(any(Block.class))).thenReturn(new GrowthPlanting.Planted(f.playerId,
                    new GrowthSapling.Spec(TreeSpecies.OAK, GrowthSapling.CrownForm.RANDOM,
                            GrowthSapling.GrowthClass.MEDIUM)));
            f.setCoalCost(1);
            f.builds(smallTree());

            f.stages.fire(f.click(Material.COAL));
            f.stages.fire(f.click(Material.COAL));
            f.runTasks();

            verify(f.builder, never()).buildNormal(any());
            f.assertRefunded(0);
            assertTrue(f.updated.isEmpty());
        }
        try (Fixture f = new Fixture()) {
            GrowthPlanting plantings = mock(GrowthPlanting.class);
            when(f.plugin.plantings()).thenReturn(plantings);
            when(plantings.at(any(Block.class))).thenReturn(new GrowthPlanting.Planted(f.playerId,
                    new GrowthSapling.Spec(TreeSpecies.OAK, GrowthSapling.CrownForm.RANDOM,
                            GrowthSapling.GrowthClass.MEDIUM)));
            when(f.players.withdrawExperience(f.player, 100)).thenReturn(true);
            f.builds(smallTree());

            f.stages.fire(f.click(Material.GLOWSTONE_DUST));
            f.runTasks();

            verify(f.builder, never()).buildNormal(any());
            f.assertRefunded(100);
            assertTrue(f.updated.isEmpty());
        }
    }

    @Test
    void growthNeverReplacesForeignLogsOrPlayerPlacedLeaves() {
        try (Fixture f = new Fixture()) {
            f.types.put(at(0, 65, 0), Material.SPRUCE_LOG);
            f.types.put(at(1, 66, 0), Material.OAK_LEAVES);
            f.persistentLeaves.add(at(1, 66, 0));
            f.types.put(at(-1, 66, 0), Material.BIRCH_LEAVES);
            f.builds(smallTree());
            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();

            assertFalse(f.updated.containsKey(at(0, 65, 0)));
            assertFalse(f.updated.containsKey(at(1, 66, 0)));
            assertEquals(Material.SPRUCE_LOG, f.types.get(at(0, 65, 0)));
            // Natural (non-persistent) leaves may be overgrown.
            assertEquals(Material.OAK_LEAVES, f.updated.get(at(-1, 66, 0)));
        }
    }

    @Test
    void growthNeverReadsOrWritesUnloadedChunks() {
        try (Fixture f = new Fixture()) {
            f.unloadedChunks.add(Fixture.chunk(-1, 0));
            f.builds(smallTree());
            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();

            verify(f.world, never()).getBlockAt(-1, 66, 0);
            assertFalse(f.updated.containsKey(at(-1, 66, 0)));
            assertTrue(f.updated.containsKey(at(1, 66, 0)));
        }
    }

    @Test
    void secondClickWhileGrowthIsPendingChargesNothing() {
        try (Fixture f = new Fixture()) {
            when(f.players.withdrawExperience(f.player, 100)).thenReturn(true);
            f.stages.fire(f.click(Material.GLOWSTONE_DUST));
            PlayerInteractEvent second = f.click(Material.GLOWSTONE_DUST);
            f.stages.fire(second);

            verify(f.players, times(1)).withdrawExperience(f.player, 100);
            verify(f.catalyst, times(1)).setAmount(1);
            assertEquals(1, f.tasks.size());
            assertEquals(Event.Result.DENY, second.useItemInHand());
        }
    }

    @Test
    void growthAbortedBeforeItRunsRefundsTheCatalyst() {
        try (Fixture f = new Fixture()) {
            f.stages.fire(f.click(Material.COAL));
            f.types.put(at(0, 64, 0), Material.AIR); // sapling broken in the meantime
            f.runTasks();

            verify(f.builder, never()).buildNormal(any());
            f.assertRefunded(0);
        }
    }

    @Test
    void disconnectBeforeQueuedGrowthReleasesItsCatalystReservationAndRefunds() {
        try (Fixture f = new Fixture()) {
            f.builds(smallTree());
            f.stages.fire(f.click(Material.COAL));
            f.listener.onPlayerQuit(f.player);
            f.runTasks();

            assertTrue(f.updated.isEmpty());
            verify(f.builder, never()).buildNormal(any());
            f.assertRefunded(0);
        }
    }

    @Test
    void blockedClearanceRejectsWithoutCharging() {
        try (Fixture f = new Fixture()) {
            f.types.put(at(0, 70, 0), Material.STONE);
            PlayerInteractEvent event = f.click(Material.BONE_MEAL);
            f.stages.fire(event);

            assertEquals(Event.Result.DENY, event.useItemInHand());
            verify(f.catalyst, never()).setAmount(anyInt());
            assertTrue(f.tasks.isEmpty());
            verify(f.plugin).actionBar(f.player, "growth.no-room");
        }
    }

    @Test
    void sneakingWithBothHandsEmptyPreviewsWithoutGrowingOrCharging() {
        try (Fixture f = new Fixture(); MockedStatic<RegionService> regions = mockStatic(RegionService.class)) {
            RegionService service = mock(RegionService.class);
            regions.when(RegionService::get).thenReturn(service);
            when(service.intersecting(any(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
            ItemStack emptyHand = mock(ItemStack.class);
            when(emptyHand.getType()).thenReturn(Material.AIR);
            when(emptyHand.getAmount()).thenReturn(1);
            when(f.player.isSneaking()).thenReturn(true);
            when(f.inventory.getItemInOffHand()).thenReturn(emptyHand);
            PlayerInteractEvent event = new PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, emptyHand,
                    f.block(0, 64, 0), BlockFace.UP, EquipmentSlot.HAND);

            f.stages.fire(event);

            assertTrue(event.isCancelled());
            assertTrue(f.tasks.isEmpty());
            verify(f.catalyst, never()).setAmount(anyInt());
            verifyNoInteractions(f.players);
            verify(f.pluginManager, never()).callEvent(any(StructureGrowEvent.class));
            verify(f.player, atMost(200)).spawnParticle(any(Particle.class), anyDouble(), anyDouble(), anyDouble(),
                    anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        }
    }

    @Test
    void previewNeverReadsIntoAnUnloadedChunk() {
        try (Fixture f = new Fixture(); MockedStatic<RegionService> regions = mockStatic(RegionService.class)) {
            RegionService service = mock(RegionService.class);
            regions.when(RegionService::get).thenReturn(service);
            when(service.intersecting(any(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
            f.types.put(at(15, 64, 0), Material.OAK_SAPLING);
            f.unloadedChunks.add(Fixture.chunk(1, 0));
            ItemStack emptyHand = mock(ItemStack.class);
            when(emptyHand.getType()).thenReturn(Material.AIR);
            when(emptyHand.getAmount()).thenReturn(1);
            when(f.player.isSneaking()).thenReturn(true);
            when(f.inventory.getItemInOffHand()).thenReturn(emptyHand);
            f.stages.fire(new PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, emptyHand,
                    f.block(15, 64, 0), BlockFace.UP, EquipmentSlot.HAND));

            verify(f.world, never()).getBlockAt(16, 64, 0);
            verify(f.world, never()).getBlockAt(16, 65, 0);
        }
    }

    @Test
    void clearanceRespectsWorldTopAndCoreHeightBonus() {
        try (Fixture f = new Fixture()) {
            GiantSaplingListener.SaplingPlan plan =
                    new GiantSaplingListener.SaplingPlan(f.world, 0, 64, 0, TreeSpecies.OAK, 1);
            assertTrue(f.listener.canFit(plan));
            when(f.structures.heightBonus(TreeSpecies.OAK, 1)).thenReturn(400);
            assertFalse(f.listener.canFit(plan), "top of the world reached");
            when(f.structures.heightBonus(TreeSpecies.OAK, 1)).thenReturn(10);
            f.types.put(at(0, 64 + TreeSpecies.OAK.maxHeight + 10, 0), Material.GLASS);
            assertFalse(f.listener.canFit(plan), "bonus height must be clear as well");
            f.types.put(at(0, 64 + TreeSpecies.OAK.maxHeight + 10, 0), Material.BIRCH_LEAVES);
            assertTrue(f.listener.canFit(plan), "leaves and logs do not block");
        }
    }

    private static void saplings(Fixture f, int originX, int originZ, int width) {
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < width; dz++) f.types.put(at(originX + dx, 64, originZ + dz), Material.OAK_SAPLING);
    }

    @Test
    void formationsAreFoundFromAnyMember() {
        try (Fixture f = new Fixture()) {
            saplings(f, -1, -1, 2);
            GiantSaplingListener.SaplingPlan square = GiantSaplingListener.findTwoByTwo(f.block(0, 64, 0), TreeSpecies.OAK);
            assertNotNull(square);
            assertEquals(2, square.width());
            assertNull(GiantSaplingListener.findGrid(f.block(0, 64, 0), TreeSpecies.OAK));

            saplings(f, -2, -2, 3);
            GiantSaplingListener.GiantGrid grid = GiantSaplingListener.findGrid(f.block(-1, 64, -1), TreeSpecies.OAK);
            assertNotNull(grid);
            assertEquals(List.of(-2, -2), List.of(grid.x(), grid.z()));
            assertNull(GiantSaplingListener.findColossalGrid(f.block(0, 64, 0), TreeSpecies.OAK));

            saplings(f, -4, -4, 5);
            GiantSaplingListener.ColossalGrid colossal =
                    GiantSaplingListener.findColossalGrid(f.block(0, 64, 0), TreeSpecies.OAK);
            assertNotNull(colossal);
            assertEquals(List.of(-4, -4), List.of(colossal.x(), colossal.z()));
            assertNull(GiantSaplingListener.findGrid(f.block(0, 64, 0), TreeSpecies.BIRCH));
        }
    }

    @Test
    void connectedSaplingsFollowDiagonalsAndBranchesUpToTheLimit() {
        try (Fixture f = new Fixture()) {
            // (0,0) - (1,1) diagonal - (2,1) - branch (2,2) and (3,0)
            for (int[] p : new int[][] {{1, 1}, {2, 1}, {2, 2}, {3, 0}}) f.types.put(at(p[0], 64, p[1]), Material.OAK_SAPLING);
            f.types.put(at(5, 64, 5), Material.OAK_SAPLING); // not connected
            GiantSaplingListener.ConnectedPlan plan =
                    GiantSaplingListener.findConnectedPlan(f.block(0, 64, 0), TreeSpecies.OAK);
            assertNotNull(plan);
            assertEquals(5, plan.members().size());
            assertFalse(plan.members().contains(at(5, 64, 5)));

            for (int x = 10; x < 10 + 130; x++) f.types.put(at(x, 64, 20), Material.OAK_SAPLING);
            assertNull(GiantSaplingListener.findConnectedPlan(f.block(10, 64, 20), TreeSpecies.OAK),
                    "more than 128 connected saplings are not a hedge");
            assertNull(GiantSaplingListener.findConnectedPlan(f.block(5, 64, 5), TreeSpecies.OAK),
                    "a single sapling is no group");
        }
    }

    @Test
    void mixedHedgesKeepEachSpeciesMaterialAndRevalidateEveryMember() {
        try (Fixture f = new Fixture()) {
            for (int x = 0; x < 5; x++) {
                f.types.put(at(x, 64, 0), x % 2 == 0 ? Material.OAK_SAPLING : Material.BIRCH_SAPLING);
            }
            assertNull(GiantSaplingListener.findConnectedPlan(f.block(0, 64, 0), TreeSpecies.OAK),
                    "the legacy finder still treats another species as a hard boundary");

            GiantSaplingListener.ConnectedPlan plan = GiantSaplingListener.findConnectedPlan(
                    f.block(0, 64, 0), TreeSpecies.OAK, true);
            assertNotNull(plan);
            assertEquals(5, plan.members().size());
            assertTrue(plan.mixed());
            assertEquals(TreeSpecies.OAK, plan.memberSpecies().get(at(0, 64, 0)));
            assertEquals(TreeSpecies.BIRCH, plan.memberSpecies().get(at(1, 64, 0)));
            assertEquals(java.util.Set.of(TreeSpecies.OAK, TreeSpecies.BIRCH),
                    GiantSaplingListener.mixedHedgeSegments(plan).stream()
                            .map(GiantSaplingListener.HedgeSegment::species).collect(java.util.stream.Collectors.toSet()));
            assertTrue(GiantSaplingListener.matches(plan));

            f.types.put(at(1, 64, 0), Material.SPRUCE_SAPLING);
            assertFalse(GiantSaplingListener.matches(plan),
                    "a species change after planning must abort before event or placement");
        }
    }

    @Test
    void largestFormationWinsOnClick() {
        try (Fixture f = new Fixture()) {
            saplings(f, -4, -4, 5);
            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();
            verify(f.builder).buildColossal(eq(at(-4, 64, -4)), anyInt(), anyInt(), anyInt());
            verify(f.builder, never()).buildGiant(any(), anyInt(), anyInt(), anyInt());
            verify(f.builder, never()).buildNormal(any());
        }
    }

    @Test
    void ownGrowEventsAreIgnoredByTheVanillaHandler() {
        try (Fixture f = new Fixture()) {
            f.builds(smallTree());
            f.protection = event -> f.listener.onVanillaTreeAttempt(event);
            f.stages.fire(f.click(Material.BONE_MEAL));
            f.runTasks();
            assertFalse(f.growEvents.getFirst().isCancelled());
            assertTrue(f.tasks.isEmpty(), "no second growth may be scheduled");
            assertEquals(Material.OAK_LOG, f.types.get(at(0, 64, 0)));
        }
    }

    @Test
    void protectedTrunksAreLoggedAfterSmoothTimberHandledTheGrowEvent() {
        try (Fixture f = new Fixture()) {
            when(f.players.withdrawExperience(f.player, 100)).thenReturn(true);
            f.builds(smallTree());
            f.stages.fire(f.click(Material.GLOWSTONE_DUST));
            f.runTasks();
            verify(f.scheduler).runTaskLater(eq(f.plugin), any(Runnable.class),
                    eq(GiantSaplingListener.COREPROTECT_LOG_DELAY_TICKS));
            assertTrue(GiantSaplingListener.COREPROTECT_LOG_DELAY_TICKS > 5);
        }
    }

    @Test
    void coalTreesStayUnloggedSoSmoothTimberMayFellThem() {
        try (Fixture f = new Fixture()) {
            f.builds(smallTree());
            f.stages.fire(f.click(Material.COAL));
            f.runTasks();
            assertFalse(f.updated.isEmpty());
            verify(f.scheduler, never()).runTaskLater(any(), any(Runnable.class), anyLong());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CustomTreesPlugin plugin = TestCore.plugin();
        final API api = TestCore.api();
        final PlayerHandlerInterface players = mock(PlayerHandlerInterface.class);
        final InventoryEventInterface inventoryEvents = mock(InventoryEventInterface.class);
        final ParticleInterface particles = mock(ParticleInterface.class);
        final StructureGeneratorInterface structures = mock(StructureGeneratorInterface.class);
        final TreeGenerator builder = mock(TreeGenerator.class);
        final AtomicReference<StructureAccess> access = new AtomicReference<>();
        final Player player = mock(Player.class);
        final UUID playerId = UUID.randomUUID();
        final ItemStack catalyst = mock(ItemStack.class);
        final ItemStack beforeUse = mock(ItemStack.class);
        final ItemStack refundItem = mock(ItemStack.class);
        final ItemStack offhandCatalyst = mock(ItemStack.class);
        final ItemStack offhandBeforeUse = mock(ItemStack.class);
        final ItemStack offhandRefundItem = mock(ItemStack.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final World world = mock(World.class);
        final PluginManager pluginManager = mock(PluginManager.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Map<StructurePos, Block> blocks = new HashMap<>();
        final Map<StructurePos, Material> types = new HashMap<>();
        final Set<StructurePos> persistentLeaves = new HashSet<>();
        final Set<Long> unloadedChunks = new HashSet<>();
        final Map<StructurePos, Material> updated = new LinkedHashMap<>();
        final List<StructureGrowEvent> growEvents = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        final List<StructurePos> effects = new ArrayList<>();
        final AtomicReference<Runnable> scheduled = new AtomicReference<>();
        final YamlConfiguration config = new YamlConfiguration();
        final AtomicReference<TreeSettings> settings = new AtomicReference<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        java.util.function.Consumer<StructureGrowEvent> protection = event -> { };
        final GiantSaplingListener listener;
        /** The click handler the way Vystorm Core's stage dispatcher calls it. */
        final Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting stages = Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting.detached();

        Fixture() {
            types.put(new StructurePos(0, 64, 0), Material.OAK_SAPLING);
            when(plugin.core()).thenReturn(api);
            when(api.player()).thenReturn(players);
            when(api.inventoryEvents()).thenReturn(inventoryEvents);
            when(api.particle()).thenReturn(particles);
            when(api.structureGenerator()).thenReturn(structures);
            when(structures.tree(any(), any(), any())).thenAnswer(call -> {
                access.set(call.getArgument(0));
                return builder;
            });
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.hasPermission("customtrees.grow")).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(player.isOnline()).thenReturn(true);
            when(inventory.getItemInMainHand()).thenReturn(catalyst);
            when(inventory.getItemInOffHand()).thenReturn(offhandCatalyst);
            when(inventory.getHeldItemSlot()).thenReturn(0);
            when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            when(catalyst.getAmount()).thenReturn(2);
            when(catalyst.clone()).thenReturn(beforeUse);
            when(beforeUse.getAmount()).thenReturn(2);
            when(beforeUse.clone()).thenReturn(refundItem);
            when(offhandCatalyst.getAmount()).thenReturn(5);
            when(offhandCatalyst.clone()).thenReturn(offhandBeforeUse);
            when(offhandBeforeUse.getAmount()).thenReturn(5);
            when(offhandBeforeUse.clone()).thenReturn(offhandRefundItem);
            when(world.getName()).thenReturn("world");
            when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(384);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call ->
                    !unloadedChunks.contains(chunk(call.getArgument(0), call.getArgument(1))));
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                    block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            doAnswer(call -> {
                if (call.getArgument(0) instanceof StructureGrowEvent event) {
                    growEvents.add(event);
                    protection.accept(event);
                }
                return null;
            }).when(pluginManager).callEvent(any());
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                scheduled.set(call.getArgument(1));
                tasks.add(call.getArgument(1));
                return null;
            });
            settings.set(TreeSettings.load(config));
            listener = new GiantSaplingListener(plugin, settings::get);
            listener.handleInteract(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting.plugin("CustomTrees"), stages.stages());
            listener.effects = (world, x, y, z, count, pitch) -> effects.add(new StructurePos((int) x, y, (int) z));
        }

        static long chunk(int chunkX, int chunkZ) {
            return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
        }

        /** Lets the mocked generator place the given blocks when buildNormal runs. */
        void builds(Map<StructurePos, Material> placements) {
            doAnswer(call -> {
                placements.forEach((pos, type) -> access.get().setType(pos.x(), pos.y(), pos.z(), type));
                return null;
            }).when(builder).buildNormal(any());
        }

        Block block(int x, int y, int z) {
            return blocks.computeIfAbsent(new StructurePos(x, y, z), pos -> {
                Block block = mock(Block.class);
                when(block.getWorld()).thenReturn(world);
                when(block.getX()).thenReturn(x);
                when(block.getY()).thenReturn(y);
                when(block.getZ()).thenReturn(z);
                when(block.getType()).thenAnswer(call -> types.getOrDefault(pos, Material.AIR));
                when(block.getBlockData()).thenAnswer(call -> {
                    if (!persistentLeaves.contains(pos)) return null;
                    org.bukkit.block.data.type.Leaves leaves = mock(org.bukkit.block.data.type.Leaves.class);
                    when(leaves.isPersistent()).thenReturn(true);
                    return leaves;
                });
                when(block.getState()).thenAnswer(call -> state(pos));
                when(block.getRelative(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(
                        x + (int) call.getArgument(0), y + (int) call.getArgument(1), z + (int) call.getArgument(2)));
                return block;
            });
        }

        BlockState state(StructurePos pos) {
            BlockState state = mock(BlockState.class);
            AtomicReference<Material> planned = new AtomicReference<>();
            when(state.getType()).thenAnswer(call -> planned.get());
            when(state.getWorld()).thenReturn(world);
            when(state.getX()).thenReturn(pos.x());
            when(state.getY()).thenReturn(pos.y());
            when(state.getZ()).thenReturn(pos.z());
            doAnswer(call -> { planned.set(call.getArgument(0)); return null; }).when(state).setType(any());
            when(state.update(true, false)).thenAnswer(call -> {
                updated.put(pos, planned.get());
                types.put(pos, planned.get());
                return true;
            });
            return state;
        }

        void runTasks() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }

        PlayerInteractEvent click(Material type) {
            return click(type, EquipmentSlot.HAND);
        }

        PlayerInteractEvent click(Material type, EquipmentSlot hand) {
            ItemStack held = hand == EquipmentSlot.OFF_HAND ? offhandCatalyst : catalyst;
            ItemStack before = hand == EquipmentSlot.OFF_HAND ? offhandBeforeUse : beforeUse;
            when(held.getType()).thenReturn(type);
            when(before.getType()).thenReturn(type);
            return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, held,
                    block(0, 64, 0), BlockFace.UP, hand);
        }

        void setCoalCost(int cost) {
            config.set("settings.coal-costs.normal", cost);
            settings.set(TreeSettings.load(config));
        }

        void assertUseReported(int slot) {
            ArgumentCaptor<BooleanSupplier> guard = ArgumentCaptor.forClass(BooleanSupplier.class);
            ItemStack used = slot == 40 ? offhandBeforeUse : beforeUse;
            verify(inventoryEvents, times(1)).observeUse(eq(player), eq(slot), same(used), guard.capture());
            assertTrue(guard.getValue().getAsBoolean());
        }

        void assertRefunded(int experience) {
            verify(refundItem).setAmount(1);
            verify(inventory).addItem(refundItem);
            if (experience > 0) verify(players).depositExperience(player, experience);
            else verify(players, never()).depositExperience(any(), anyInt());
        }

        @Override
        public void close() { bukkit.close(); }
    }
}
