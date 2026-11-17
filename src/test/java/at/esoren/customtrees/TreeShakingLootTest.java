package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.loot.LootResult;
import Vystorm.vystorm_core.loot.LootTable;
import Vystorm.vystorm_core.loot.LootTables;
import Vystorm.vystorm_core.loot.RollContext;
import at.esoren.customtrees.TreeShaking.Species;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tree shaking through Vystorm Core loot: own tables per species, old odds, golden-apple switch, override. */
class TreeShakingLootTest {
    private final Plugin trees = plugin("CustomTrees");
    private final Plugin core = plugin("Vystorm_Core");

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }

    @AfterEach void release() {
        LootTables.release(trees);
        LootTables.release(core);
    }

    private void registerAll() {
        TreeShaking.registerLoot(TestCore.LOOT, trees);
        LootTables.replace(core, Map.of(TreeShaking.CATEGORY, new LootTable(1, 1, 1.0, List.of(
                new LootTable.Entry(LootTable.SELF, 1, 1, 0, 0)))));
    }

    @Test void everySpeciesHasAnOwnTable() {
        Map<String, LootTable> tables = TreeShaking.lootTables();
        assertEquals(Species.values().length, tables.size());
        assertTrue(tables.containsKey("customtrees:shaking/oak"));
        assertTrue(tables.containsKey("customtrees:shaking/pale_oak"));
        LootTable oak = tables.get("customtrees:shaking/oak");
        assertEquals(List.of("minecraft:apple", "minecraft:stick", "minecraft:oak_sapling", "", "minecraft:golden_apple"),
                oak.entries().stream().map(LootTable.Entry::item).toList());
        // Old weights 35/35/12/15/0.6 -> ranks with the same odds; the golden apple is rare+.
        assertEquals(List.of(0, 0, 6572, 5715, 9830), oak.entries().stream().map(LootTable.Entry::chestWeight).toList());
        TreeShaking.registerLoot(TestCore.LOOT, trees);
        assertTrue(LootTables.exists("customtrees:shaking/birch"));
    }

    private static Map<String, Integer> shake(TreeShaking.Options options, Species species, int n) {
        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < n; i++) {
            LootResult result = TreeShaking.lootRoll(TestCore.LOOT, options, 1)
                    .context(RollContext.NONE.withSelf(TreeShaking.tableId(species))).seed(i).roll();
            assertTrue(result.problems().isEmpty(), result.problems().toString());
            for (LootResult.Hit hit : result.hits()) hits.merge(hit.item(), hit.amount(), Integer::sum);
        }
        return hits;
    }

    @Test void categoryRollsKeepTheOldOdds() {
        registerAll();
        TreeShaking.Options options = TreeShaking.Options.read(new YamlConfiguration());
        assertEquals(TreeShaking.CATEGORY, TreeShaking.lootSource(TestCore.LOOT, options));
        int n = 40_000;
        Map<String, Integer> hits = shake(options, Species.OAK, n);
        double total = 35 + 35 + 12 + 15 + 0.6;
        assertEquals(35 / total, hits.get("minecraft:apple") / (double) n, 0.01);
        assertEquals(35 / total, hits.get("minecraft:stick") / (double) n, 0.01);
        assertEquals(12 / total, hits.get("minecraft:oak_sapling") / (double) n, 0.01);
        assertEquals(0.6 / total, hits.getOrDefault("minecraft:golden_apple", 0) / (double) n, 0.002);
        assertEquals(15 / total, 1 - hits.values().stream().mapToInt(Integer::intValue).sum() / (double) n, 0.01);
    }

    @Test void goldenApplesOffExcludesThemLikeTheOldTable() {
        registerAll();
        YamlConfiguration config = new YamlConfiguration();
        config.set("shaking.golden-apples", false);
        TreeShaking.Options options = TreeShaking.Options.read(config);
        int n = 20_000;
        Map<String, Integer> hits = shake(options, Species.OAK, n);
        assertFalse(hits.containsKey("minecraft:golden_apple"));
        assertEquals(35 / 97.0, hits.get("minecraft:apple") / (double) n, 0.012);
    }

    @Test void multipleRollsGiveOneDrawEach() {
        registerAll();
        TreeShaking.Options options = TreeShaking.Options.read(new YamlConfiguration());
        LootResult result = TreeShaking.lootRoll(TestCore.LOOT, options, 27)
                .context(RollContext.NONE.withSelf(TreeShaking.tableId(Species.BIRCH))).seed(3).roll();
        assertTrue(result.hits().size() <= 27 && result.hits().size() >= 10, result.hits().toString());
    }

    @Test void configuredLootTableWinsBlankOrUnknownUsesTheCategory() {
        registerAll();
        LootTables.replace(core, Map.of(TreeShaking.CATEGORY, new LootTable(1, 1, 1.0, List.of(
                new LootTable.Entry(LootTable.SELF, 1, 1, 0, 0))), "vystorm_core:test/shake", new LootTable(1, 1, 1.0, List.of(
                new LootTable.Entry("minecraft:diamond", 1, 1, 0, 0)))));
        YamlConfiguration config = new YamlConfiguration();
        config.set("shaking.loot-table", "vystorm_core:test/shake");
        assertEquals("vystorm_core:test/shake", TreeShaking.lootSource(TestCore.LOOT, TreeShaking.Options.read(config)));
        config.set("shaking.loot-table", "vystorm_core:nothing");
        assertEquals(TreeShaking.CATEGORY, TreeShaking.lootSource(TestCore.LOOT, TreeShaking.Options.read(config)));
        assertEquals("", TreeShaking.Options.read(new YamlConfiguration()).lootTable());
    }
}
