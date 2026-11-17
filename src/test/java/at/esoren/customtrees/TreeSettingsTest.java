package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TreeSettingsTest {
    @Test
    void nonFiniteReplacementChancesAreRejected() {
        for (double chance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.set("settings.replacement-chance", chance);
            org.junit.jupiter.api.Assertions.assertThrows(TreeSettingsException.class, () -> TreeSettings.load(yaml));
        }
    }

    @Test
    void bundledConfigurationLoads() {
        YamlConfiguration yaml;
        try (var stream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (stream == null) {
                throw new AssertionError("config.yml missing");
            }
            yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        TreeSettings settings = TreeSettings.load(yaml);
        assertTrue(settings.customPlayerGrownTrees());
        assertTrue(settings.replaceGeneratedTrees());
        assertTrue(settings.postProcessNewChunks());
        assertEquals(2, settings.postProcessDelayTicks());
        assertTrue(settings.giantSaplings());
        assertTrue(settings.connectedShrubsAndHedges());
        assertFalse(settings.mixedHedges(), "mixed hedges stay off unless explicitly enabled");
        assertEquals(36, settings.giantMinHeight());
        assertEquals(48, settings.giantMaxHeight());
        assertTrue(settings.colossalSaplings());
        assertEquals(52, settings.colossalMinHeight());
        assertEquals(68, settings.colossalMaxHeight());
        assertEquals(17, settings.colossalCanopyRadius());
        assertTrue(settings.glowstoneGrowth());
        assertEquals(100, settings.glowstoneXpCost());
        assertEquals(80, settings.glowstoneLeavesPerBlock());
        assertEquals(32, settings.glowstoneMaxBlocks());
        assertEquals(7, settings.glowstoneDarkLightLevel());
        assertEquals(0, settings.glowstoneGrowthCooldownSeconds());
        assertEquals(0, settings.glowstoneGrowthDailyLimit());
        assertTrue(settings.coalGrowth());
        assertEquals(0, settings.coalGrowthCooldownSeconds());
        assertEquals(0, settings.coalGrowthDailyLimit());
        assertTrue(settings.enables("world", World.Environment.NORMAL));
    }

    @Test
    void allConfiguredSpeciesMapBothWays() {
        for (TreeSpecies species : TreeSpecies.values()) {
            assertEquals(species, TreeSpecies.fromSapling(species.sapling));
            assertEquals(species, TreeSpecies.fromLog(species.log));
        }
        assertEquals(null, TreeSpecies.fromSapling(Material.STONE));
    }

    @Test
    void outOfRangeScanDepthAndXpCostAreRejected() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("settings.generated-tree-scan-depth", 500);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> TreeSettings.load(yaml));
        yaml.set("settings.generated-tree-scan-depth", 80);
        yaml.set("settings.glowstone-xp-cost", TreeSettings.MAX_GLOWSTONE_XP_COST + 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> TreeSettings.load(yaml));
        yaml.set("settings.glowstone-xp-cost", TreeSettings.MAX_GLOWSTONE_XP_COST);
        assertEquals(TreeSettings.MAX_GLOWSTONE_XP_COST, TreeSettings.load(yaml).glowstoneXpCost());
    }

    @Test
    void catalystCooldownsAndDailyLimitsAreValidatedIndependently() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("settings.glowstone-growth-cooldown-seconds", TreeSettings.MAX_CATALYST_COOLDOWN_SECONDS);
        yaml.set("settings.coal-growth-daily-limit", TreeSettings.MAX_CATALYST_DAILY_LIMIT);
        TreeSettings settings = TreeSettings.load(yaml);
        assertEquals(TreeSettings.MAX_CATALYST_COOLDOWN_SECONDS, settings.glowstoneGrowthCooldownSeconds());
        assertEquals(TreeSettings.MAX_CATALYST_DAILY_LIMIT, settings.coalGrowthDailyLimit());
        yaml.set("settings.glowstone-growth-cooldown-seconds", -1);
        org.junit.jupiter.api.Assertions.assertThrows(TreeSettingsException.class, () -> TreeSettings.load(yaml));
        yaml.set("settings.glowstone-growth-cooldown-seconds", 0);
        yaml.set("settings.coal-growth-daily-limit", TreeSettings.MAX_CATALYST_DAILY_LIMIT + 1);
        org.junit.jupiter.api.Assertions.assertThrows(TreeSettingsException.class, () -> TreeSettings.load(yaml));
    }
}
