package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructureAccess;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import at.esoren.customtrees.GeneratedTreeScanner.TreeBase;
import at.esoren.customtrees.GeneratedTreeScanner.TreeRemoval;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedTreeScannerTest {
    /** Map-backed world; {@code contains} is limited to the chunks in {@code allowed}. */
    private static final class FakeWorld implements StructureAccess, GeneratedTreeScanner.ChunkReader {
        final Map<StructurePos, Material> blocks = new HashMap<>();
        final java.util.Set<Long> allowed = new java.util.HashSet<>();

        void allow(int chunkX, int chunkZ) {
            allowed.add((long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL));
        }

        void put(int x, int y, int z, Material material) {
            blocks.put(new StructurePos(x, y, z), material);
        }

        Material at(int x, int y, int z) {
            return blocks.getOrDefault(new StructurePos(x, y, z), Material.AIR);
        }

        @Override public Material getType(int x, int y, int z) {
            return contains(x, y, z) ? at(x, y, z) : Material.BARRIER;
        }

        @Override public void setType(int x, int y, int z, Material material) {
            if (contains(x, y, z)) put(x, y, z, material);
        }

        @Override public void setBlockData(int x, int y, int z, BlockData data) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean contains(int x, int y, int z) {
            return y >= 0 && y < 320 && allowed.contains((long) (x >> 4) << 32 | ((z >> 4) & 0xFFFFFFFFL));
        }

        @Override public Material type(int x, int y, int z) {
            return at(x, y, z);
        }

        @Override public int highestY(int x, int z) {
            for (int y = 319; y > 0; y--) if (at(x, y, z) != Material.AIR) return y;
            return 0;
        }
    }

    private static FakeWorld treeAt(int baseX, int baseZ) {
        FakeWorld world = new FakeWorld();
        for (int x = -32; x < 48; x++) for (int z = -32; z < 48; z++) world.put(x, 63, z, Material.GRASS_BLOCK);
        for (int y = 64; y < 72; y++) world.put(baseX, y, baseZ, Material.OAK_LOG);
        for (int x = baseX - 3; x <= baseX + 3; x++)
            for (int z = baseZ - 3; z <= baseZ + 3; z++)
                for (int y = 70; y < 74; y++) if (world.at(x, y, z) == Material.AIR) world.put(x, y, z, Material.OAK_LEAVES);
        return world;
    }

    @Test
    void findsTheTrunkBaseOnSoilOnlyInsideTheChunk() {
        FakeWorld world = treeAt(8, 8);
        // Player-style log wall outside the chunk must never be reported.
        for (int y = 64; y < 67; y++) world.put(20, y, 8, Material.OAK_LOG);
        List<TreeBase> bases = GeneratedTreeScanner.findBases(world, 42L, 0, 0, -64, 320, 80, 1.0);
        assertEquals(List.of(new TreeBase(new StructurePos(8, 64, 8), TreeSpecies.OAK)), bases);
    }

    @Test
    void replacementRollIsDeterministicAndRespectsTheChance() {
        double roll = GeneratedTreeScanner.replacementRoll(1234L, 5, 64, -7);
        assertEquals(roll, GeneratedTreeScanner.replacementRoll(1234L, 5, 64, -7));
        assertTrue(roll >= 0.0 && roll < 1.0);
        assertNotEquals(roll, GeneratedTreeScanner.replacementRoll(1235L, 5, 64, -7));
        FakeWorld world = treeAt(8, 8);
        assertTrue(GeneratedTreeScanner.findBases(world, 42L, 0, 0, -64, 320, 80, 0.0).isEmpty());
    }

    @Test
    void removalNeverTouchesBlocksOutsideTheAllowedChunks() {
        // Tree at the chunk edge; its crown and a neighbouring log wall reach into chunk (1, 0).
        FakeWorld world = treeAt(15, 8);
        for (int y = 64; y < 70; y++) world.put(18, y, 8, Material.SPRUCE_LOG);
        world.put(17, 66, 9, Material.OAK_LEAVES);
        world.allow(0, 0);
        TreeBase base = new TreeBase(new StructurePos(15, 64, 8), TreeSpecies.OAK);
        TreeRemoval removal = GeneratedTreeScanner.measure(world, base, (x, y, z) -> false);
        int removed = GeneratedTreeScanner.remove(world, removal, (x, y, z) -> false);

        assertTrue(removed > 0);
        assertEquals(Material.AIR, world.at(15, 64, 8));
        assertEquals(Material.AIR, world.at(13, 71, 8));
        for (int y = 64; y < 70; y++) assertEquals(Material.SPRUCE_LOG, world.at(18, y, 8));
        assertEquals(Material.OAK_LEAVES, world.at(17, 66, 9));
        assertEquals(Material.OAK_LEAVES, world.at(16, 71, 8));
        assertEquals(Material.GRASS_BLOCK, world.at(15, 63, 8));
    }

    @Test
    void removalKeepsBlocksBuiltByEarlierPasses() {
        FakeWorld world = treeAt(8, 8);
        world.allow(0, 0);
        world.put(2, 66, 2, Material.OAK_LOG);
        TreeBase base = new TreeBase(new StructurePos(8, 64, 8), TreeSpecies.OAK);
        GeneratedTreeScanner.PositionFilter keep = (x, y, z) -> x == 2 && z == 2;
        GeneratedTreeScanner.remove(world, GeneratedTreeScanner.measure(world, base, keep), keep);
        assertEquals(Material.OAK_LOG, world.at(2, 66, 2));
        assertEquals(Material.AIR, world.at(8, 66, 8));
    }

    @Test
    void measurementIsBoundedByTheMaximumRadius() {
        FakeWorld world = treeAt(8, 8);
        world.allow(0, 0);
        world.allow(1, 0);
        world.allow(2, 0);
        for (int x = 8; x < 40; x++) world.put(x, 68, 8, Material.OAK_LOG);
        TreeRemoval removal = GeneratedTreeScanner.measure(world,
                new TreeBase(new StructurePos(8, 64, 8), TreeSpecies.OAK), (x, y, z) -> false);
        assertEquals(GeneratedTreeScanner.MAX_REMOVAL_RADIUS, removal.radius());
    }
}
