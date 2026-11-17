package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TimberReplantTest {
    private static long p(int x, int y, int z) { return TimberScanner.pack(x, y, z); }

    @Test void everyWoodGetsItsSapling() {
        assertEquals(Material.OAK_SAPLING, TimberReplant.sapling(Material.OAK_LOG));
        assertEquals(Material.DARK_OAK_SAPLING, TimberReplant.sapling(Material.DARK_OAK_WOOD));
        assertEquals(Material.PALE_OAK_SAPLING, TimberReplant.sapling(Material.STRIPPED_PALE_OAK_LOG));
        assertEquals(Material.CHERRY_SAPLING, TimberReplant.sapling(Material.CHERRY_LOG));
        assertEquals(Material.MANGROVE_PROPAGULE, TimberReplant.sapling(Material.MANGROVE_LOG));
        assertEquals(Material.CRIMSON_FUNGUS, TimberReplant.sapling(Material.CRIMSON_STEM));
        assertEquals(Material.WARPED_FUNGUS, TimberReplant.sapling(Material.WARPED_HYPHAE));
        assertNull(TimberReplant.sapling(Material.MUSHROOM_STEM));
        assertNull(TimberReplant.sapling(Material.OAK_PLANKS));
        assertNull(TimberReplant.sapling(null));
    }

    @Test void singleTrunkGetsOneSpotAtTheBottom() {
        assertEquals(List.of(p(5, 64, 5)), TimberReplant.spots(List.of(p(5, 66, 5), p(5, 64, 5), p(5, 65, 5), p(6, 67, 5))));
    }

    @Test void twoByTwoTrunkGetsFourSpots() {
        List<Long> trunk = List.of(p(0, 70, 0), p(1, 70, 0), p(0, 70, 1), p(1, 70, 1), p(0, 71, 0), p(1, 71, 1));
        assertEquals(4, TimberReplant.spots(trunk).size());
        assertTrue(TimberReplant.spots(trunk).contains(p(1, 70, 1)));
    }

    @Test void oddBottomsGetNothing() {
        assertTrue(TimberReplant.spots(List.of()).isEmpty());
        assertTrue(TimberReplant.spots(List.of(p(0, 64, 0), p(1, 64, 0))).isEmpty(), "two logs");
        assertTrue(TimberReplant.spots(List.of(p(0, 64, 0), p(1, 64, 0), p(2, 64, 0), p(3, 64, 0))).isEmpty(), "a row of four");
        assertTrue(TimberReplant.spots(List.of(p(0, 64, 0), p(0, 64, 1), p(0, 64, 2), p(1, 64, 0), p(1, 64, 1))).isEmpty(), "hedge");
    }

    @Test void replantIsOnByDefaultAndCanBeSwitchedOff() {
        assertTrue(TimberOptions.DEFAULTS.replant());
        YamlConfiguration config = new YamlConfiguration();
        config.set("timber.replant", false);
        assertFalse(TimberOptions.read(config).replant());
        config.set("timber.replant", true);
        assertTrue(TimberOptions.read(config).replant());
    }
}
