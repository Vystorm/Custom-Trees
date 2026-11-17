package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;

import at.esoren.customtrees.Herbarium.Plant;
import at.esoren.customtrees.Herbarium.Rank;
import at.esoren.customtrees.Herbarium.Stage;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HerbariumTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void everyPlantHasItsFormsAndTheTotalAddsUp() {
        assertEquals(9 * 7 + 4 * 5, Herbarium.TOTAL);
        assertEquals(Herbarium.TOTAL, Herbarium.ENTRIES.stream().distinct().count());
        assertEquals("oak:giant", Herbarium.entry(Plant.OAK, Stage.GIANT));
        assertEquals("warped_fungus:10x10", Herbarium.entry(Plant.WARPED_FUNGUS, Stage.FUNGUS_10));
        assertEquals(Plant.MANGROVE, Plant.of(Material.MANGROVE_PROPAGULE));
        assertEquals(Plant.CRIMSON_FUNGUS, Plant.of(Material.CRIMSON_FUNGUS));
        assertNull(Plant.of(Material.AZALEA));
        assertTrue(Plant.BROWN_MUSHROOM.fungus());
        assertFalse(Plant.PALE_OAK.fungus());
    }

    @Test void growthClassesAndFungusWidthsMapToForms() {
        assertEquals(Stage.SINGLE, Stage.of(CoalGrowthCosts.GrowthClass.NORMAL));
        assertEquals(Stage.MEDIUM, Stage.of(CoalGrowthCosts.GrowthClass.MEDIUM));
        assertEquals(Stage.COLOSSAL, Stage.of(CoalGrowthCosts.GrowthClass.COLOSSAL));
        assertEquals(Stage.HEDGE, Stage.of(CoalGrowthCosts.GrowthClass.HEDGE));
        assertEquals(Stage.FUNGUS_5, Stage.fungus(5));
        assertNull(Stage.fungus(4));
    }

    @Test void aFormCountsOnceAndOnlyForMatchingPlants() {
        Herbarium herbarium = new Herbarium();
        var first = herbarium.record(ALICE, "Alice", Plant.OAK, Stage.SINGLE);
        assertTrue(first.added());
        assertEquals(1, first.found());
        assertNull(first.rankUp());
        assertFalse(herbarium.record(ALICE, "Alice", Plant.OAK, Stage.SINGLE).added());
        assertFalse(herbarium.record(ALICE, "Alice", Plant.RED_MUSHROOM, Stage.GIANT).added(), "fungi have no tree forms");
        assertFalse(herbarium.record(ALICE, "Alice", Plant.OAK, Stage.FUNGUS_1).added(), "trees have no fungus sizes");
        assertEquals(1, herbarium.found(ALICE));
        assertEquals(1, herbarium.found(ALICE, Plant.OAK));
        assertTrue(herbarium.has(ALICE, Plant.OAK, Stage.SINGLE));
        assertTrue(herbarium.dirty());
    }

    @Test void ranksRiseWithTheNumberOfForms() {
        assertEquals(Rank.SPROUT, Rank.of(0));
        assertEquals(Rank.GARDENER, Rank.of(5));
        assertEquals(Rank.ELDER, Rank.of(Herbarium.TOTAL - 1));
        assertEquals(Rank.KEEPER, Rank.of(Herbarium.TOTAL));
        assertNull(Rank.KEEPER.next());
        assertEquals(Rank.FORESTER, Rank.GARDENER.next());

        Herbarium herbarium = new Herbarium();
        Rank reached = null;
        int rankUps = 0;
        for (Plant plant : Plant.values()) for (Stage stage : plant.stages()) {
            var recorded = herbarium.record(ALICE, "Alice", plant, stage);
            if (recorded.rankUp() != null) { reached = recorded.rankUp(); rankUps++; }
        }
        assertEquals(Rank.KEEPER, reached);
        assertEquals(Rank.values().length - 1, rankUps);
        assertEquals(1, herbarium.keepers());
    }

    @Test void fileRoundTripKeepsNamesFormsAndUnknownIds() throws Exception {
        Herbarium herbarium = new Herbarium();
        herbarium.record(ALICE, "Alice", Plant.CHERRY, Stage.LUMINOUS);
        herbarium.record(ALICE, "Alice", Plant.WARPED_FUNGUS, Stage.FUNGUS_10);
        herbarium.record(BOB, "Bob", Plant.SPRUCE, Stage.WORLD);
        YamlConfiguration yaml = herbarium.toYaml();
        yaml.set("players." + BOB + ".found", List.of("spruce:world", "baobab:giant"));
        yaml.set("players.not-a-uuid.found", List.of("oak:single"));

        YamlConfiguration reread = new YamlConfiguration();
        reread.loadFromString(yaml.saveToString());
        Herbarium loaded = Herbarium.fromYaml(reread);
        assertFalse(loaded.dirty());
        assertEquals(2, loaded.found(ALICE));
        assertTrue(loaded.has(ALICE, Plant.WARPED_FUNGUS, Stage.FUNGUS_10));
        assertEquals(1, loaded.found(BOB), "unknown ids are kept but not counted");
        assertEquals(BOB, loaded.find("bob"));
        assertEquals(2, loaded.collectors());
        assertTrue(loaded.toYaml().getStringList("players." + BOB + ".found").contains("baobab:giant"));
        assertEquals(0, Herbarium.fromYaml(new YamlConfiguration()).collectors());
    }

    @Test void leaderboardSortsByFormsThenName() {
        Herbarium herbarium = new Herbarium();
        herbarium.record(BOB, "bob", Plant.OAK, Stage.SINGLE);
        herbarium.record(ALICE, "Alice", Plant.OAK, Stage.SINGLE);
        herbarium.record(ALICE, "Alice", Plant.OAK, Stage.MEDIUM);
        UUID carol = UUID.randomUUID();
        herbarium.record(carol, "Carol", Plant.BIRCH, Stage.SINGLE);
        List<Herbarium.Standing> top = herbarium.top(10);
        assertEquals(List.of("Alice", "bob", "Carol"), top.stream().map(Herbarium.Standing::name).toList());
        assertEquals(2, herbarium.top(2).size());
    }

    @Test void optionsReadRankCommandsAndIgnoreUnknownRanks() {
        var defaults = Herbarium.Options.read(new YamlConfiguration());
        assertTrue(defaults.enabled());
        assertTrue(defaults.announceRanks());
        assertTrue(defaults.rankCommands().isEmpty());
        YamlConfiguration config = new YamlConfiguration();
        config.set("herbarium.rank-commands.forester", List.of("/give {player} bone_meal 16", " "));
        config.set("herbarium.rank-commands.emperor", List.of("op {player}"));
        var options = Herbarium.Options.read(config);
        assertEquals(List.of("give {player} bone_meal 16"), options.rankCommands().get(Rank.FORESTER));
        assertEquals(1, options.rankCommands().size());
        assertEquals("CustomTrees herbarium: enabled=true, forms=83, collectors=4, all-forms=1, announce-ranks=true, rank-commands=1",
                HerbariumFeature.statusLine(options, 4, 1));
    }

    @Test void bundledConfigDefaultsMatchTheCode() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new java.io.StringReader(LangFilesTest.resource("config.yml")));
        var options = Herbarium.Options.read(config);
        assertTrue(options.enabled());
        assertTrue(options.rankCommands().isEmpty());
        assertTrue(config.isConfigurationSection("herbarium.rank-commands"));
    }

    @Test void everyRankPlantAndStageHasTextsInBothLanguages() {
        var lang = LangFilesTest.bundle();
        for (String language : List.of("en", "de")) {
            for (Rank rank : Rank.values()) assertNotEquals("herbarium.rank." + rank.id(), lang.plain(language, "herbarium.rank." + rank.id()));
            for (Plant plant : Plant.values()) assertNotEquals("herbarium.plant." + plant.id(), lang.plain(language, "herbarium.plant." + plant.id()));
            for (Stage stage : Stage.values()) assertNotEquals("herbarium.stage." + stage.id, lang.plain(language, "herbarium.stage." + stage.id));
        }
        assertEquals("Herbarium: neue Form Eiche – 3×3-Riese (3/83).", lang.plain("de", "herbarium.new-entry",
                "plant", "Eiche", "stage", "3×3-Riese", "count", 3, "total", 83));
    }
}
