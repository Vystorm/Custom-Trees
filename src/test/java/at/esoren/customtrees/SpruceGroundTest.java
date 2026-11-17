package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpruceGroundTest {
    private static final StructurePos BASE = new StructurePos(8, 64, 8);

    @Test void plansSoilAndColdSnowWithoutChangingSource() {
        Source source = new Source();
        BufferedTreeAccess plan = new BufferedTreeAccess(source, Set.of());
        decorate(plan, new YamlConfiguration(), 0.0);
        assertTrue(plan.changes().values().stream().anyMatch(p -> p.material() == Material.PODZOL));
        assertEquals(Material.SNOW, plan.getType(8, 64, 8));
        assertEquals(Material.GRASS_BLOCK, source.type(8, 63, 8));
        assertEquals(Material.AIR, source.type(8, 64, 8));
    }

    @Test void preservesStructuresPlantsAndExistingSnow() {
        Source source = new Source();
        source.blocks.put(new StructurePos(8, 63, 8), Material.STONE_BRICKS);
        source.blocks.put(new StructurePos(9, 64, 8), Material.TORCH);
        source.blocks.put(new StructurePos(8, 64, 9), Material.SNOW);
        BufferedTreeAccess plan = new BufferedTreeAccess(source, Set.of());
        decorate(plan, new YamlConfiguration(), 0.0);
        assertEquals(Material.STONE_BRICKS, plan.getType(8, 63, 8));
        assertEquals(Material.GRASS_BLOCK, plan.getType(9, 63, 8));
        assertEquals(Material.TORCH, plan.getType(9, 64, 8));
        assertFalse(plan.changes().containsKey(new StructurePos(8, 64, 9)));
    }

    @Test void warmSnowIsOptInAndEachFeatureCanBeDisabled() {
        YamlConfiguration config = new YamlConfiguration();
        BufferedTreeAccess warm = new BufferedTreeAccess(new Source(), Set.of());
        decorate(warm, config, 0.8);
        assertEquals(Material.AIR, warm.getType(8, 64, 8));
        config.set("settings.spruce-snow-cold-only", false);
        config.set("settings.spruce-ground", false);
        BufferedTreeAccess snowy = new BufferedTreeAccess(new Source(), Set.of());
        decorate(snowy, config, 0.8);
        assertEquals(Material.SNOW, snowy.getType(8, 64, 8));
        assertEquals(Material.GRASS_BLOCK, snowy.getType(8, 63, 8));
        config.set("settings.spruce-snow", false);
        BufferedTreeAccess disabled = new BufferedTreeAccess(new Source(), Set.of());
        decorate(disabled, config, 0.0);
        assertTrue(disabled.changes().isEmpty());
    }

    @Test void doesNotReadUnloadedChunksOrWidenOrdinaryTreeWrites() {
        Source source = new Source();
        BufferedTreeAccess plan = new BufferedTreeAccess(source, Set.of());
        plan.setType(8, 63, 8, Material.SPRUCE_LOG);
        assertTrue(plan.changes().isEmpty());
        SpruceGround.decorate(plan, new StructurePos(0, 64, 0), 8, 1,
                TreeSettings.load(new YamlConfiguration()), (x, y, z) -> 0);
        assertTrue(plan.changes().keySet().stream().allMatch(p -> p.x() >= 0 && p.z() >= 0));
    }

    private static void decorate(BufferedTreeAccess plan, YamlConfiguration config, double temperature) {
        SpruceGround.decorate(plan, BASE, 4, 12, TreeSettings.load(config), (x, y, z) -> temperature);
    }

    private static final class Source implements BufferedTreeAccess.BlockSource {
        final Map<StructurePos, Material> blocks = new HashMap<>();
        public Material type(int x, int y, int z) {
            assertTrue(loaded(x >> 4, z >> 4), "unloaded chunk read");
            return blocks.getOrDefault(new StructurePos(x, y, z),
                    y == 63 ? Material.GRASS_BLOCK : y < 63 ? Material.STONE : Material.AIR);
        }
        public boolean persistentLeaves(int x, int y, int z) { return false; }
        public boolean loaded(int x, int z) { return x == 0 && z == 0; }
        public int minHeight() { return -64; }
        public int maxHeight() { return 320; }
        public int light(int x, int y, int z) { return 15; }
    }
}
