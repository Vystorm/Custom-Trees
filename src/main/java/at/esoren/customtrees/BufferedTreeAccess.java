package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

/**
 * Collects the blocks a tree generator wants to change without touching the
 * world. Only unloaded-chunk-free, "free" positions may be planned: air, grass,
 * ferns, vines, natural (non-persistent) leaves and the saplings of the growing
 * formation. Existing logs, player-placed (persistent) leaves and every other
 * block are never replaced. The planned changes are then offered to protection
 * plugins through a {@code StructureGrowEvent} before anything is applied.
 */
final class BufferedTreeAccess implements StructureAccess {
    private static final Set<Material> FREE = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.VINE);

    /** Read-only world view; never loads chunks. */
    interface BlockSource {
        Material type(int x, int y, int z);

        boolean persistentLeaves(int x, int y, int z);

        boolean loaded(int chunkX, int chunkZ);

        int minHeight();

        int maxHeight();

        int light(int x, int y, int z);

        static BlockSource of(World world) {
            return new BlockSource() {
                @Override public Material type(int x, int y, int z) {
                    return world.getBlockAt(x, y, z).getType();
                }

                @Override public boolean persistentLeaves(int x, int y, int z) {
                    return world.getBlockAt(x, y, z).getBlockData() instanceof Leaves leaves && leaves.isPersistent();
                }

                @Override public boolean loaded(int chunkX, int chunkZ) {
                    return world.isChunkLoaded(chunkX, chunkZ);
                }

                @Override public int minHeight() {
                    return world.getMinHeight();
                }

                @Override public int maxHeight() {
                    return world.getMaxHeight();
                }

                @Override public int light(int x, int y, int z) {
                    return world.getBlockAt(x, y, z).getLightLevel();
                }
            };
        }
    }

    /** A planned block: {@code data} is null when only the material was set. */
    record Planned(Material material, BlockData data) {
    }

    private final BlockSource source;
    private final Set<StructurePos> formation;
    private final Map<StructurePos, Planned> planned = new LinkedHashMap<>();
    private final Map<StructurePos, Material> originals = new HashMap<>();
    private final Map<Long, Boolean> loadedChunks = new HashMap<>();

    BufferedTreeAccess(BlockSource source, Set<StructurePos> formation) {
        this.source = source;
        this.formation = Set.copyOf(formation);
    }

    @Override
    public Material getType(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return Material.BARRIER;
        }
        Planned change = planned.get(new StructurePos(x, y, z));
        return change != null ? change.material() : original(x, y, z);
    }

    @Override
    public void setType(int x, int y, int z, Material material) {
        plan(x, y, z, new Planned(material, null));
    }

    @Override
    public void setBlockData(int x, int y, int z, BlockData data) {
        plan(x, y, z, new Planned(data.getMaterial(), data));
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (y < source.minHeight() || y >= source.maxHeight()) {
            return false;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        return loadedChunks.computeIfAbsent((long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL),
                ignored -> source.loaded(chunkX, chunkZ));
    }

    @Override
    public int getLightLevel(int x, int y, int z) {
        return contains(x, y, z) ? source.light(x, y, z) : 0;
    }

    /** True when the block that was there before the growth may be replaced. */
    boolean isWritable(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return false;
        }
        Material original = original(x, y, z);
        if (FREE.contains(original)) {
            return true;
        }
        if (TreeSpecies.fromSapling(original) != null) {
            return formation.contains(new StructurePos(x, y, z));
        }
        return TreeSpecies.isLeaves(original) && !source.persistentLeaves(x, y, z);
    }

    Map<StructurePos, Planned> changes() {
        return Collections.unmodifiableMap(planned);
    }

    /** Narrow exception for surface decoration; ordinary tree writes stay air-only. */
    void planGround(int x, int y, int z, Material material) {
        if (contains(x, y, z) && SpruceGround.isSoil(getType(x, y, z))
                && (material == Material.PODZOL || material == Material.COARSE_DIRT)) {
            planned.put(new StructurePos(x, y, z), new Planned(material, null));
        }
    }

    Material original(StructurePos position) {
        return original(position.x(), position.y(), position.z());
    }

    private Material original(int x, int y, int z) {
        return originals.computeIfAbsent(new StructurePos(x, y, z), ignored -> source.type(x, y, z));
    }

    private void plan(int x, int y, int z, Planned change) {
        if (isWritable(x, y, z)) {
            planned.put(new StructurePos(x, y, z), change);
        }
    }
}
