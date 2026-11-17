package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class TreeFireGuardTest {
    @Test
    void markersPreserveExpectedMaterialAndTrunkAndCrownToggles() {
        Fixture fixture = new Fixture();
        AtomicBoolean protectTrunks = new AtomicBoolean(true);
        AtomicBoolean protectCrowns = new AtomicBoolean(false);
        TreeFireGuard guard = fixture.guard(protectTrunks::get, protectCrowns::get);
        Block log = fixture.block(3, 72, 4, Material.OAK_LOG);
        Block leaves = fixture.block(4, 72, 4, Material.OAK_LEAVES);
        guard.mark(log);
        guard.mark(leaves);

        BlockBurnEvent logBurn = new BlockBurnEvent(log);
        fire(guard, logBurn);
        assertTrue(logBurn.isCancelled());
        BlockBurnEvent leavesBurn = new BlockBurnEvent(leaves);
        fire(guard, leavesBurn);
        assertFalse(leavesBurn.isCancelled());
        guard.mark(leaves); // the unprotected leaves burned, which removed their marker (OBSERVE)

        protectTrunks.set(false);
        protectCrowns.set(true);
        assertFalse(newBurn(guard, log).isCancelled());
        assertTrue(newBurn(guard, leaves).isCancelled());

        // A stale log marker must not protect a different material at the same position.
        Block changed = fixture.block(3, 72, 4, Material.OAK_LEAVES);
        assertFalse(newBurn(guard, changed).isCancelled());
    }

    @Test
    void igniteAndSpreadRequireTheirReportedSourceToBeAMarkedTreeBlock() {
        Fixture fixture = new Fixture();
        TreeFireGuard guard = fixture.guard(() -> true, () -> true);
        Block marked = fixture.block(1, 80, 2, Material.BIRCH_LOG);
        Block fireTarget = fixture.block(2, 80, 2, Material.FIRE);
        guard.mark(marked);

        BlockIgniteEvent ignite = new BlockIgniteEvent(fireTarget,
                BlockIgniteEvent.IgniteCause.SPREAD, marked);
        fire(guard, ignite);
        assertTrue(ignite.isCancelled());

        BlockSpreadEvent spread = mock(BlockSpreadEvent.class);
        when(spread.getSource()).thenReturn(marked);
        fire(guard, spread);
        verify(spread).setCancelled(true);

        Block unrelatedSource = fixture.block(7, 80, 2, Material.FIRE);
        when(spread.getSource()).thenReturn(unrelatedSource);
        fire(guard, spread);
        verify(spread, times(1)).setCancelled(true); // the unrelated source is not cancelled
    }

    @Test
    void malformedOrUnknownMarkersFailClosedAndRemainUntouched() {
        Fixture fixture = new Fixture();
        TreeFireGuard guard = fixture.guard(() -> true, () -> true);
        Block log = fixture.block(0, 64, 0, Material.OAK_LOG);
        fixture.returned = new int[] {0x43544647, 1, 1, TreeFireGuard.pack(0, 64, 0), Integer.MAX_VALUE};
        assertFalse(newBurn(guard, log).isCancelled());
        guard.mark(log);
        guard.unmark(log);
        verify(fixture.data, never()).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY), any(int[].class));
        verify(fixture.data, never()).remove(any(NamespacedKey.class));
    }

    @Test
    void capIsPerChunkAndInvalidYDoesNotWrap() {
        Fixture fixture = new Fixture();
        TreeFireGuard guard = fixture.guard(() -> true, () -> true);
        int[] full = new int[3 + TreeFireGuard.MAX_MARKERS_PER_CHUNK * 2];
        full[0] = 0x43544647;
        full[1] = 1;
        full[2] = TreeFireGuard.MAX_MARKERS_PER_CHUNK;
        for (int i = 0; i < TreeFireGuard.MAX_MARKERS_PER_CHUNK; i++) {
            full[3 + i * 2] = TreeFireGuard.pack(i & 15, -2048 + i / 256, (i >> 4) & 15);
            full[4 + i * 2] = 1; // ACACIA_LOG
        }
        fixture.returned = full;
        guard.mark(fixture.block(0, 100, 0, Material.OAK_LOG));
        verify(fixture.data, never()).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY), any(int[].class));
        assertThrows(IllegalArgumentException.class, () -> TreeFireGuard.pack(0, -2049, 0));
        assertThrows(IllegalArgumentException.class, () -> TreeFireGuard.pack(0, 2048, 0));
        assertNotEquals(TreeFireGuard.pack(1, 64, 2), TreeFireGuard.pack(2, 64, 1));
    }

    @Test
    void markerOperationsNeverFetchAnUnloadedChunk() {
        Fixture fixture = new Fixture();
        TreeFireGuard guard = fixture.guard(() -> true, () -> true);
        Block block = fixture.block(1, 64, 1, Material.OAK_LOG);
        when(fixture.world.isChunkLoaded(0, 0)).thenReturn(false);
        guard.mark(block);
        guard.unmark(block);
        verify(block, never()).getChunk();
        verify(fixture.data, never()).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY), any(int[].class));
    }

    @Test
    void breakPlaceDecayEntityAndPistonCleanMarkersWithoutBlockingMovement() {
        Fixture fixture = new Fixture();
        TreeFireGuard guard = fixture.guard(() -> true, () -> true);
        Block log = fixture.block(5, 70, 6, Material.OAK_LOG);
        guard.mark(log);
        // break and place run the way Vystorm Core's stage dispatcher calls them: OBSERVE, not for a denied action
        EventStageTesting stages = stages(guard);
        BlockBreakEvent denied = new BlockBreakEvent(log, mock(Player.class));
        denied.setCancelled(true);
        stages.fire(denied);
        verify(fixture.data, never()).remove(any(NamespacedKey.class));
        stages.fire(new BlockBreakEvent(log, mock(Player.class)));
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(log);
        LeavesDecayEvent decay = new LeavesDecayEvent(log);
        guard.onLeavesDecay(decay);
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(log);
        BlockPlaceEvent placed = mock(BlockPlaceEvent.class);
        when(placed.getBlock()).thenReturn(log);
        stages.fire(placed);
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(log);
        BlockExplodeEvent explosion = mock(BlockExplodeEvent.class);
        List<Block> affected = new java.util.ArrayList<>(List.of(log));
        when(explosion.blockList()).thenReturn(affected);
        fire(guard, explosion);
        assertTrue(affected.contains(log));
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(log);
        BlockPistonExtendEvent piston = mock(BlockPistonExtendEvent.class);
        when(piston.getBlocks()).thenReturn(List.of(log));
        fire(guard, piston);
        verify(piston, never()).setCancelled(true);
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(log);
        BlockSpreadEvent ignored = mock(BlockSpreadEvent.class);
        Block fireSource = fixture.block(9, 70, 9, Material.FIRE);
        when(ignored.getSource()).thenReturn(fireSource);
        fire(guard, ignored);
        verify(ignored, never()).setCancelled(true);
    }

    @Test
    void explosionsAndEntityBlockChangesOnlyCleanMarkerStorage() {
        Fixture fixture = new Fixture();
        TreeFireGuard guard = fixture.guard(() -> true, () -> true);
        Block marked = fixture.block(0, 70, 0, Material.OAK_LEAVES);
        guard.mark(marked);

        BlockExplodeEvent explosion = mock(BlockExplodeEvent.class);
        List<Block> affected = new java.util.ArrayList<>(List.of(marked));
        when(explosion.blockList()).thenReturn(affected);
        fire(guard, explosion);
        assertTrue(affected.contains(marked));
        verify(marked, never()).setType(any(Material.class), eq(false));
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(marked);
        EntityExplodeEvent entityExplosion = mock(EntityExplodeEvent.class);
        List<Block> entityAffected = new java.util.ArrayList<>(List.of(marked));
        when(entityExplosion.blockList()).thenReturn(entityAffected);
        fire(guard, entityExplosion);
        assertTrue(entityAffected.contains(marked));
        verify(fixture.data).remove(any(NamespacedKey.class));

        fixture.resetMarkerStore();
        guard.mark(marked);
        EntityChangeBlockEvent change = new EntityChangeBlockEvent(mock(Entity.class), marked,
                mock(BlockData.class));
        fire(guard, change);
        verify(fixture.data).remove(any(NamespacedKey.class));
    }

    /** The guard's handlers the way Vystorm Core's stage dispatcher calls them. */
    private static EventStageTesting stages(TreeFireGuard guard) {
        EventStageTesting stages = EventStageTesting.detached();
        org.bukkit.plugin.Plugin trees = EventStageTesting.plugin("CustomTrees");
        guard.handleBlockEvents(trees, stages.stages());
        guard.handleWorldEvents(trees, stages.stages());
        return stages;
    }

    private static <E extends org.bukkit.event.Event> E fire(TreeFireGuard guard, E event) {
        return stages(guard).fire(event);
    }

    private static BlockBurnEvent newBurn(TreeFireGuard guard, Block block) {
        BlockBurnEvent event = new BlockBurnEvent(block);
        fire(guard, event);
        return event;
    }

    private static final class Fixture {
        final World world = mock(World.class);
        final Chunk chunk = mock(Chunk.class);
        final PersistentDataContainer data = mock(PersistentDataContainer.class);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Map<NamespacedKey, int[]> values = new HashMap<>();
        int[] returned;

        Fixture() {
            when(plugin.getName()).thenReturn("CustomTrees");
            when(chunk.getPersistentDataContainer()).thenReturn(data);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            doAnswer(call -> {
                returned = call.getArgument(2);
                values.put(call.getArgument(0), returned);
                return null;
            }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY), any(int[].class));
            when(data.has(any(NamespacedKey.class))).thenAnswer(call ->
                    values.containsKey(call.getArgument(0)) || returned != null);
            when(data.has(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY))).thenAnswer(call ->
                    values.containsKey(call.getArgument(0)) || returned != null);
            when(data.get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER_ARRAY)))
                    .thenAnswer(call -> returned != null ? returned : values.get(call.getArgument(0)));
            doAnswer(call -> {
                values.remove(call.getArgument(0));
                returned = null;
                return null;
            }).when(data).remove(any(NamespacedKey.class));
        }

        TreeFireGuard guard(java.util.function.BooleanSupplier trunks, java.util.function.BooleanSupplier crowns) {
            return new TreeFireGuard(plugin, trunks, crowns);
        }

        Block block(int x, int y, int z, Material material) {
            Block block = mock(Block.class);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getType()).thenReturn(material);
            when(block.getWorld()).thenReturn(world);
            when(block.getChunk()).thenReturn(chunk);
            return block;
        }

        void resetMarkerStore() {
            clearInvocations(data);
            values.clear();
            returned = null;
        }
    }
}
