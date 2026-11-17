package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CoalGrowthCostsTest {
    @Test
    void missingSettingsAndMissingClassesDefaultToOneCoal() {
        CoalGrowthCosts absent = CoalGrowthCosts.load(new YamlConfiguration());
        YamlConfiguration partialConfig = new YamlConfiguration();
        partialConfig.set("settings.coal-costs.normal", 3);
        CoalGrowthCosts partial = CoalGrowthCosts.load(partialConfig);

        for (CoalGrowthCosts.GrowthClass growthClass : CoalGrowthCosts.GrowthClass.values()) {
            assertEquals(1, absent.cost(growthClass));
        }
        assertEquals(3, partial.cost(CoalGrowthCosts.GrowthClass.NORMAL));
        assertEquals(1, partial.cost(CoalGrowthCosts.GrowthClass.MEDIUM));
        assertEquals(1, partial.cost(CoalGrowthCosts.GrowthClass.GIANT));
        assertEquals(1, partial.cost(CoalGrowthCosts.GrowthClass.COLOSSAL));
        assertEquals(1, partial.cost(CoalGrowthCosts.GrowthClass.HEDGE));
    }

    @Test
    void classSelectionUsesEachConfiguredFormationCost() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("settings.coal-costs.normal", 1);
        config.set("settings.coal-costs.medium", 2);
        config.set("settings.coal-costs.giant", 9);
        config.set("settings.coal-costs.colossal", 25);
        config.set("settings.coal-costs.hedge", 4);

        CoalGrowthCosts costs = CoalGrowthCosts.load(config);
        assertEquals(1, costs.cost(CoalGrowthCosts.GrowthClass.NORMAL));
        assertEquals(2, costs.cost(CoalGrowthCosts.GrowthClass.MEDIUM));
        assertEquals(9, costs.cost(CoalGrowthCosts.GrowthClass.GIANT));
        assertEquals(25, costs.cost(CoalGrowthCosts.GrowthClass.COLOSSAL));
        assertEquals(4, costs.cost(CoalGrowthCosts.GrowthClass.HEDGE));
        assertEquals(25, TreeSettings.load(config).coalGrowthCosts().colossal());
    }

    @Test
    void rejectsValuesOutsideRangeNonIntegersAndNonFiniteNumbersBeforeSettingsLoadCompletes() {
        for (Object invalid : new Object[] {0, -1, 65, 2.5, Double.NaN,
                Double.POSITIVE_INFINITY, "3", true}) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("settings.coal-costs.medium", invalid);
            assertThrows(IllegalArgumentException.class, () -> TreeSettings.load(config),
                    "expected rejection for " + invalid);
        }

        YamlConfiguration malformedSection = new YamlConfiguration();
        malformedSection.set("settings.coal-costs", "not-a-section");
        assertThrows(IllegalArgumentException.class, () -> TreeSettings.load(malformedSection));
    }
}
