package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import at.esoren.customtrees.TreeShaking.Species;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TreeShakingTest {
    @Test void logsAndWoodMapToTheirSpecies() {
        assertEquals(Species.OAK, Species.of(Material.OAK_LOG));
        assertEquals(Species.DARK_OAK, Species.of(Material.DARK_OAK_LOG));
        assertEquals(Species.PALE_OAK, Species.of(Material.PALE_OAK_WOOD));
        assertEquals(Species.CHERRY, Species.of(Material.STRIPPED_CHERRY_LOG));
        assertEquals(Species.MANGROVE, Species.of(Material.MANGROVE_LOG));
        assertNull(Species.of(Material.OAK_PLANKS));
        assertNull(Species.of(Material.CRIMSON_STEM));
        assertNull(Species.of(Material.STONE));
    }

    @Test void appleTreesCanDropAGoldenApple() {
        assertTrue(Species.OAK.table().stream().anyMatch(l -> l.item() == Material.GOLDEN_APPLE));
        assertFalse(Species.BIRCH.table().stream().anyMatch(l -> l.item() == Material.GOLDEN_APPLE));
        assertTrue(Species.JUNGLE.table().stream().anyMatch(l -> l.item() == Material.COCOA_BEANS));
    }

    @Test void weightedPickCoversTheWholeRange() {
        List<TreeShaking.Loot> table = List.of(new TreeShaking.Loot(Material.APPLE, 1), new TreeShaking.Loot(Material.STICK, 3));
        assertEquals(Material.APPLE, TreeShaking.pick(table, 0.0));
        assertEquals(Material.APPLE, TreeShaking.pick(table, 0.24));
        assertEquals(Material.STICK, TreeShaking.pick(table, 0.26));
        assertEquals(Material.STICK, TreeShaking.pick(table, 0.999999));
    }

    @Test void biggerCrownsRollMoreOften() {
        assertEquals(1, TreeShaking.rolls(10, 6));
        assertEquals(3, TreeShaking.rolls(120, 6));
        assertEquals(6, TreeShaking.rolls(5000, 6));
    }

    @Test void optionsHaveDefaultsAndBounds() {
        var defaults = TreeShaking.Options.read(new YamlConfiguration());
        assertTrue(defaults.enabled());
        assertEquals(300_000, defaults.cooldownMillis());
        var config = new YamlConfiguration();
        config.set("shaking.max-rolls", 999);
        config.set("shaking.cooldown-seconds", -5);
        var bounded = TreeShaking.Options.read(config);
        assertEquals(32, bounded.maxRolls());
        assertEquals(0, bounded.cooldownMillis());
        assertTrue(defaults.goldenApples());
    }

    @Test void goldenApplesCanBeSwitchedOff() {
        var config = new YamlConfiguration();
        config.set("shaking.golden-apples", false);
        assertFalse(TreeShaking.Options.read(config).goldenApples());
        assertFalse(Species.OAK.table(false).stream().anyMatch(l -> l.item() == Material.GOLDEN_APPLE));
        assertTrue(Species.OAK.table(false).stream().anyMatch(l -> l.item() == Material.APPLE));
        for (double r = 0; r < 1; r += 0.001) assertNotEquals(Material.GOLDEN_APPLE, TreeShaking.pick(Species.DARK_OAK.table(false), r));
    }

    @Test void statusLineShowsSwitchesAndCounters() {
        var options = TreeShaking.Options.read(new YamlConfiguration());
        assertEquals("CustomTrees shaking: enabled=true, cooldown=300s, max-rolls=6, golden-apples=true, shaken=4, items=11, golden-dropped=1, resting-now=2",
                TreeShaking.statusLine(options, 4, 11, 1, 2));
    }
}
