package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Glowstone grown into a luminous tree is decoration, not a resource: one dust
 * and some XP would otherwise yield up to {@code glowstone-max-blocks} blocks
 * (two to four dust each). The positions are stored in the chunk's persistent
 * data. Breaking such a block drops nothing, explosions remove it without
 * drops and pistons cannot move it (a moved block would lose its marker).
 */
final class GlowstoneGuard {
    private static final int Y_OFFSET = 2048;

    private final NamespacedKey key;

    GlowstoneGuard() {
        this.key = new NamespacedKey("customtrees", "tree_glowstone");
    }

    void track(World world, Collection<StructurePos> positions) {
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (StructurePos position : positions) {
            long chunk = (long) (position.x() >> 4) << 32 | ((position.z() >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(chunk, ignored -> new java.util.ArrayList<>())
                    .add(pack(position.x(), position.y(), position.z()));
        }
        for (Map.Entry<Long, List<Integer>> entry : byChunk.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            PersistentDataContainer data = world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer();
            data.set(key, PersistentDataType.INTEGER_ARRAY,
                    add(data.getOrDefault(key, PersistentDataType.INTEGER_ARRAY, new int[0]), entry.getValue()));
        }
    }

    boolean isTracked(Block block) {
        int[] tracked = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return tracked != null && contains(tracked, pack(block.getX(), block.getY(), block.getZ()));
    }

    /** Packed chunk-local positions ({@link #pack}) of the tree glowstone in this chunk (timber scans). */
    java.util.Set<Integer> trackedPositions(Chunk chunk) {
        int[] tracked = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        if (tracked == null || tracked.length == 0) return java.util.Set.of();
        java.util.Set<Integer> positions = new java.util.HashSet<>();
        for (int value : tracked) positions.add(value);
        return positions;
    }

    private void untrack(Block block) {
        Chunk chunk = block.getChunk();
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        int[] tracked = data.get(key, PersistentDataType.INTEGER_ARRAY);
        if (tracked == null) {
            return;
        }
        int packed = pack(block.getX(), block.getY(), block.getZ());
        int[] remaining = remove(tracked, value -> value == packed);
        if (remaining.length == 0) {
            data.remove(key);
        } else if (remaining.length != tracked.length) {
            data.set(key, PersistentDataType.INTEGER_ARRAY, remaining);
        }
    }

    private boolean isTreeGlowstone(Block block) {
        return block.getType() == Material.GLOWSTONE && isTracked(block);
    }

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * Tree glowstone loses its drops in ADJUST, before any feature looks at the drops; the markers follow the final
     * outcome in OBSERVE. None of the handlers sees a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ADJUST, this::onBreak);
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onBroken);
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::onPlace);
    }

    /** Stage ADJUST, registered in {@link #handleBlockEvents}. */
    public void onBreak(BlockBreakEvent event) {
        if (isTreeGlowstone(event.getBlock())) {
            event.setDropItems(false);
        }
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onBroken(BlockBreakEvent event) {
        if (isTracked(event.getBlock())) {
            untrack(event.getBlock());
        }
    }

    /** A block placed where a stale marker survived (e.g. after WorldEdit) must behave normally. Stage OBSERVE. */
    public void onPlace(BlockPlaceEvent event) {
        if (isTracked(event.getBlock())) {
            untrack(event.getBlock());
        }
    }

    /**
     * Explosions, pistons and entity block changes go through Core's stages too. Tree glowstone may not be pushed
     * (a rule of this plugin: ADJUST). In an explosion or when an entity breaks it, it disappears without drops:
     * FINISH, after every protection has had its say, because the block is removed right here.
     */
    void handleWorldEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.FINISH, this::onBlockExplode);
        stages.handle(owner, EntityExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.FINISH, this::onEntityExplode);
        stages.handle(owner, BlockPistonExtendEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ADJUST, this::onPistonExtend);
        stages.handle(owner, BlockPistonRetractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ADJUST, this::onPistonRetract);
        stages.handle(owner, EntityChangeBlockEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.FINISH, this::onEntityChangeBlock);
    }
    /* Stage FINISH, registered in handleWorldEvents */
    public void onBlockExplode(BlockExplodeEvent event) {
        removeWithoutDrops(event.blockList());
    }

    /* Stage FINISH, registered in handleWorldEvents */
    public void onEntityExplode(EntityExplodeEvent event) {
        removeWithoutDrops(event.blockList());
    }

    private void removeWithoutDrops(List<Block> blocks) {
        blocks.removeIf(block -> {
            if (!isTreeGlowstone(block)) {
                return false;
            }
            untrack(block);
            block.setType(Material.AIR, false);
            return true;
        });
    }

    /* Stage ADJUST, registered in handleWorldEvents */
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isTreeGlowstone)) {
            event.setCancelled(true);
        }
    }

    /* Stage ADJUST, registered in handleWorldEvents */
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isTreeGlowstone)) {
            event.setCancelled(true);
        }
    }

    /** Withers and similar entities would break the block with drops. */
    /* Stage FINISH, registered in handleWorldEvents */
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (event.getTo() != Material.GLOWSTONE && isTreeGlowstone(block)) {
            event.setCancelled(true);
            untrack(block);
            block.setType(Material.AIR, false);
        }
    }

    static int pack(int x, int y, int z) {
        return (x & 15) | (z & 15) << 4 | (y + Y_OFFSET) << 8;
    }

    static boolean contains(int[] tracked, int packed) {
        for (int value : tracked) {
            if (value == packed) {
                return true;
            }
        }
        return false;
    }

    static int[] add(int[] tracked, Collection<Integer> packed) {
        java.util.LinkedHashSet<Integer> result = new java.util.LinkedHashSet<>();
        for (int value : tracked) {
            result.add(value);
        }
        result.addAll(packed);
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    static int[] remove(int[] tracked, IntPredicate drop) {
        return Arrays.stream(tracked).filter(value -> !drop.test(value)).toArray();
    }
}
