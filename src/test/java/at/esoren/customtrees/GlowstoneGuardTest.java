package at.esoren.customtrees;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalMatchers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlowstoneGuardTest {
    private final GlowstoneGuard guard = new GlowstoneGuard();
    private final PersistentDataContainer data = mock(PersistentDataContainer.class);
    private final Chunk chunk = mock(Chunk.class);
    /** The guard's break and place handlers, called the way Vystorm Core's stage dispatcher calls them. */
    private final EventStageTesting stages = EventStageTesting.detached();

    GlowstoneGuardTest() {
        Plugin trees = EventStageTesting.plugin("CustomTrees");
        guard.handleBlockEvents(trees, stages.stages());
        guard.handleWorldEvents(trees, stages.stages());
        when(chunk.getPersistentDataContainer()).thenReturn(data);
    }

    private Block block(int x, int y, int z, Material type) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(type);
        when(block.getChunk()).thenReturn(chunk);
        return block;
    }

    private void stored(int... packed) {
        when(data.get(any(), eq(PersistentDataType.INTEGER_ARRAY))).thenReturn(packed);
    }

    @Test
    void packingIsUniqueWithinAChunk() {
        assertNotEquals(GlowstoneGuard.pack(1, 70, 2), GlowstoneGuard.pack(2, 70, 1));
        assertNotEquals(GlowstoneGuard.pack(1, -64, 2), GlowstoneGuard.pack(1, 64, 2));
        assertEquals(GlowstoneGuard.pack(-15, 70, 17), GlowstoneGuard.pack(1, 70, 1));
    }

    @Test
    void addDeduplicatesAndRemoveDropsOnlyTheMatch() {
        int[] tracked = GlowstoneGuard.add(new int[] {1, 2}, List.of(2, 3));
        assertArrayEquals(new int[] {1, 2, 3}, tracked);
        assertArrayEquals(new int[] {1, 3}, GlowstoneGuard.remove(tracked, value -> value == 2));
    }

    @Test
    void breakingTreeGlowstoneDropsNothingAndClearsTheMarker() {
        Block block = block(4, 80, 9, Material.GLOWSTONE);
        stored(GlowstoneGuard.pack(4, 80, 9), GlowstoneGuard.pack(5, 80, 9));
        BlockBreakEvent event = stages.fire(new BlockBreakEvent(block, mock(Player.class)));
        assertFalse(event.isDropItems());
        verify(data).set(any(), eq(PersistentDataType.INTEGER_ARRAY),
                AdditionalMatchers.aryEq(new int[] {GlowstoneGuard.pack(5, 80, 9)}));
    }

    @Test
    void aDeniedBreakOfTreeGlowstoneKeepsItsDropsSettingAndItsMarker() {
        Block block = block(4, 80, 9, Material.GLOWSTONE);
        stored(GlowstoneGuard.pack(4, 80, 9), GlowstoneGuard.pack(5, 80, 9));
        BlockBreakEvent event = new BlockBreakEvent(block, mock(Player.class));
        event.setCancelled(true);
        stages.fire(event);
        assertTrue(event.isDropItems());
        verify(data, never()).set(any(), eq(PersistentDataType.INTEGER_ARRAY), any(int[].class));
        verify(data, never()).remove(any());
    }

    @Test
    void ordinaryGlowstoneKeepsItsDrops() {
        Block block = block(4, 80, 9, Material.GLOWSTONE);
        stored(GlowstoneGuard.pack(5, 80, 9));
        BlockBreakEvent event = stages.fire(new BlockBreakEvent(block, mock(Player.class)));
        assertTrue(event.isDropItems());
    }

    @Test
    void explosionsRemoveTreeGlowstoneWithoutDrops() {
        Block tree = block(1, 90, 1, Material.GLOWSTONE);
        Block other = block(2, 90, 1, Material.GLOWSTONE);
        stored(GlowstoneGuard.pack(1, 90, 1));
        List<Block> blocks = new ArrayList<>(List.of(tree, other));
        BlockExplodeEvent event = mock(BlockExplodeEvent.class);
        when(event.blockList()).thenReturn(blocks);
        stages.fire(event);
        assertEquals(List.of(other), blocks);
        verify(tree).setType(Material.AIR, false);
    }

    @Test
    void pistonsCannotMoveTreeGlowstone() {
        Block tree = block(1, 90, 1, Material.GLOWSTONE);
        stored(GlowstoneGuard.pack(1, 90, 1));
        BlockPistonExtendEvent event = mock(BlockPistonExtendEvent.class);
        when(event.getBlocks()).thenReturn(List.of(tree));
        stages.fire(event);
        verify(event).setCancelled(true);
    }
}
