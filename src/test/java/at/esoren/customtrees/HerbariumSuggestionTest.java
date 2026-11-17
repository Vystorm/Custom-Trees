package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HerbariumSuggestionTest {
    private final UUID player = UUID.randomUUID();

    @Test void prefersSmallPlantingsAndNeverChangesTheCollection() {
        Herbarium data = new Herbarium();
        assertEquals(new Herbarium.Suggestion(Herbarium.Plant.OAK, Herbarium.Stage.SINGLE), data.next(player, null));
        assertFalse(data.dirty());
        assertEquals(0, data.collectors());
        data.record(player, "Alex", Herbarium.Plant.OAK, Herbarium.Stage.SINGLE);
        data.record(player, "Alex", Herbarium.Plant.OAK, Herbarium.Stage.LUMINOUS);
        data.saved();
        assertEquals(Herbarium.Plant.BIRCH, data.next(player, null).plant());
        assertEquals(Herbarium.Stage.MEDIUM, data.next(player, Herbarium.Plant.OAK).stage());
        assertFalse(data.dirty());
        assertEquals(Herbarium.Stage.SINGLE, data.next(UUID.randomUUID(), Herbarium.Plant.OAK).stage());
    }

    @Test void walksAll83FormsAndRecognizesCompletedSelections() {
        Herbarium data = new Herbarium();
        int count = 0;
        Herbarium.Suggestion next;
        while ((next = data.next(player, null)) != null) {
            assertTrue(data.record(player, "Alex", next.plant(), next.stage()).added());
            assertTrue(++count <= 83);
        }
        assertEquals(83, count);
        for (Herbarium.Plant plant : Herbarium.Plant.values()) assertNull(data.next(player, plant));
        assertNull(Herbarium.fromYaml(data.toYaml()).next(player, null));
    }

    @Test void recipesCoverEveryValidFormAndPlantCount() {
        Lang lang = LangFilesTest.bundle();
        for (Herbarium.Plant plant : Herbarium.Plant.values()) {
            for (Herbarium.Stage stage : plant.stages()) {
                Herbarium.Suggestion next = new Herbarium.Suggestion(plant, stage);
                for (String code : new String[]{"en", "de", "zh_cn"})
                    assertNotNull(lang.raw(code, "herbarium.recipe." + next.recipe()));
                if (plant.fungus() || stage == Herbarium.Stage.WORLD) assertEquals("explicit", next.recipe());
                if (stage == Herbarium.Stage.WORLD || stage == Herbarium.Stage.FUNGUS_10) assertEquals(100, next.plants());
            }
        }
        assertEquals(4, new Herbarium.Suggestion(Herbarium.Plant.OAK, Herbarium.Stage.HEDGE).plants());
        assertEquals("hedge", new Herbarium.Suggestion(Herbarium.Plant.OAK, Herbarium.Stage.HEDGE).recipe());
        assertEquals("luminous", new Herbarium.Suggestion(Herbarium.Plant.OAK, Herbarium.Stage.LUMINOUS).recipe());
        assertEquals(9, new Herbarium.Suggestion(Herbarium.Plant.OAK, Herbarium.Stage.GIANT).plants());
        assertEquals(25, new Herbarium.Suggestion(Herbarium.Plant.CRIMSON_FUNGUS, Herbarium.Stage.FUNGUS_5).plants());
    }

    @Test void adviceUsesOwnCollectionAndCaseInsensitiveFilter(@TempDir Path directory) {
        CustomTreesPlugin plugin = TestCore.plugin();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Lang lang = mock(Lang.class);
        HerbariumFeature feature = new HerbariumFeature(plugin, lang);
        Player viewer = mock(Player.class);
        when(viewer.hasPermission("customtrees.herbarium")).thenReturn(true);
        when(viewer.getUniqueId()).thenReturn(player);
        for (Herbarium.Stage stage : Herbarium.Plant.CHERRY.stages())
            feature.data().record(UUID.randomUUID(), "Other", Herbarium.Plant.CHERRY, stage);
        feature.suggest(viewer, "CHERRY");
        verify(lang).send(viewer, "herbarium.recipe.sapling", "count", 1, "width", 1);
        assertEquals(0, feature.data().found(player));
        for (Herbarium.Stage stage : Herbarium.Plant.CHERRY.stages())
            feature.data().record(player, "Alex", Herbarium.Plant.CHERRY, stage);
        feature.suggest(viewer, "cherry");
        verify(lang).send(viewer, "herbarium.next-complete");
    }

    @Test void adviceHonorsPermissionDisabledStateAndInvalidPlant(@TempDir Path directory) {
        CustomTreesPlugin plugin = TestCore.plugin();
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        Lang lang = mock(Lang.class);
        HerbariumFeature feature = new HerbariumFeature(plugin, lang);
        Player viewer = mock(Player.class);
        feature.suggest(viewer, null);
        verify(lang).send(viewer, "command.no-permission");
        when(viewer.hasPermission("customtrees.herbarium")).thenReturn(true);
        YamlConfiguration config = new YamlConfiguration();
        config.set("herbarium.enabled", false);
        feature.apply(Herbarium.Options.read(config));
        feature.suggest(viewer, null);
        verify(lang).send(viewer, "herbarium.disabled");
        feature.apply(Herbarium.Options.read(new YamlConfiguration()));
        feature.suggest(viewer, "invalid");
        verify(lang).send(viewer, "herbarium.next-unknown", "plant", "invalid");
        assertEquals(0, feature.data().collectors());
    }
}
