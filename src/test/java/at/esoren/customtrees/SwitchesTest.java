package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Every feature has a switch (1.47.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys, and the placeholders resolve and are empty while their feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("herbarium_found", "herbarium_total", "herbarium_rank", "herbarium_rank_id",
            "herbarium_next_needed", "herbarium_top", "herbarium_keepers", "breed_cooldown", "trees_grown", "giant_grown", "colossal_grown",
            "shrubs_grown", "hedges_grown");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    private static final Path OLD = Path.of("src/test/resources/config-1.46.yml");
    @TempDir Path dir;

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchHasItsDefaultAndAComment() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertEquals(feature.defaultOn, config.getBoolean(feature.path), feature.path);
            String parent = feature.path.substring(0, feature.path.lastIndexOf('.'));
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchAndPlaceholderHasADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            assertTrue(lang.getString("feature-needs", "").contains("{switch}"), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
    }

    @Test void givenAConfigFrom1_46_whenRead_thenEveryFeatureKeepsItsState() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(OLD.toFile());
        assertEquals(Set.of(Feature.MIXED_HEDGES, Feature.FIRE_TRUNKS, Feature.FIRE_CROWNS), Feature.Switches.read(old).off(),
                "only the three that were off by default stay off");
        assertEquals(Feature.Switches.read(null), Feature.Switches.read(old));
        assertSame(old.getBoolean("settings.glowstone-growth"), true);
    }

    @Test void givenAConfigFrom1_46_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        Path data = Files.createDirectories(dir.resolve("CustomTrees"));
        Files.writeString(data.resolve("config.yml"), Files.readString(OLD).replace("  coal-growth: true", "  coal-growth: false"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("CustomTrees");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(CONFIG));
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile()));

        assertTrue(ConfigEditor.mergeDefaults(plugin) >= 7);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        for (Feature feature : Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        Set<Feature> expected = EnumSet.of(Feature.MIXED_HEDGES, Feature.FIRE_TRUNKS, Feature.FIRE_CROWNS, Feature.COAL);
        assertEquals(expected, Feature.Switches.read(merged).off(), "an existing value is never changed");
        String text = Files.readString(data.resolve("config.yml"));
        assertTrue(text.contains("Garden trees: the owner trims"), "the comment of a new section comes along");
        assertTrue(text.contains("Use the biome-profiles section below"), "the comment of a new key in an old section comes along");
    }

    // ------------------------------------------------------------------ the switches reach the features

    @Test void givenTheMasterSwitchOff_whenTheFeaturesReadTheConfig_thenEveryFeatureIsOff() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        Feature.Switches off = Feature.Switches.without(Feature.TREES);
        for (Feature feature : Feature.values()) if (feature != Feature.TREES) assertFalse(off.on(feature), feature.name());
        YamlConfiguration effective = CustomTreesPlugin.effective(config, off);
        TreeSettings settings = TreeSettings.load(effective);
        assertFalse(settings.customPlayerGrownTrees());
        assertFalse(settings.replaceGeneratedTrees());
        assertFalse(settings.giantSaplings());
        assertFalse(settings.colossalSaplings());
        assertFalse(settings.connectedShrubsAndHedges());
        assertFalse(settings.glowstoneGrowth());
        assertFalse(settings.coalGrowth());
        assertFalse(settings.spruceGround());
        assertFalse(WorldTreeSettings.load(effective).enabled());
        assertFalse(TreeShaking.Options.read(effective).enabled());
        assertFalse(Herbarium.Options.read(effective).enabled());
        assertFalse(PlantingStencil.Options.read(effective).enabled());
        assertFalse(TimberOptions.read(effective).enabled());
        assertFalse(ForestRegrowth.Options.read(effective).enabled());
        assertTrue(config.getBoolean("settings.glowstone-growth"), "the file itself is not changed");
    }

    @Test void givenEverythingOn_whenTheFeaturesReadTheConfig_thenEverythingBehavesAsBefore() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        YamlConfiguration effective = CustomTreesPlugin.effective(config, Feature.Switches.read(config));
        assertEquals(TreeSettings.load(config), TreeSettings.load(effective));
        assertEquals(TimberOptions.read(config), TimberOptions.read(effective));
        assertTrue(TreeShaking.Options.read(effective).enabled());
        assertTrue(Herbarium.Options.read(effective).enabled());
    }

    @Test void givenOneSwitchOff_whenRead_thenOnlyItAndWhatNeedsItAreOff() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        YamlConfiguration noTimber = CustomTreesPlugin.effective(config, Feature.Switches.without(Feature.TIMBER));
        assertFalse(TimberOptions.read(noTimber).enabled());
        assertFalse(TimberOptions.read(noTimber).replant(), "replanting needs timber");
        assertFalse(ForestRegrowth.Options.read(noTimber).enabled(), "regrowth needs timber");
        assertTrue(TreeShaking.Options.read(noTimber).enabled());
        YamlConfiguration noApples = CustomTreesPlugin.effective(config, Feature.Switches.without(Feature.GOLDEN_APPLES));
        assertTrue(TreeShaking.Options.read(noApples).enabled());
        assertFalse(TreeShaking.Options.read(noApples).goldenApples());
        YamlConfiguration noGiants = CustomTreesPlugin.effective(config, Feature.Switches.without(Feature.GIANT));
        assertFalse(TreeSettings.load(noGiants).giantSaplings());
        assertFalse(TreeSettings.load(noGiants).naturalGiantGrowth());
        assertTrue(TreeSettings.load(noGiants).colossalSaplings());
    }

    // ------------------------------------------------------------------ commands

    @Test void givenASwitchOff_whenItsCommandRuns_thenTheSharedMessageOrTheNeededSwitchAnswers() throws Exception {
        for (Feature feature : List.of(Feature.GUIDE, Feature.CROWN_CHOICE, Feature.BREEDING, Feature.GARDEN, Feature.PLANTING, Feature.HERBARIUM)) {
            CustomTreesPlugin plugin = plugin(feature);
            Player player = player();
            assertTrue(plugin.off(player, feature), feature.name());
            verify(langOf(plugin)).send(player, "feature-off");
        }
        CustomTreesPlugin noTrees = plugin(Feature.TREES);
        Player player = player();
        assertTrue(noTrees.off(player, Feature.HERBARIUM));
        verify(langOf(noTrees)).send(player, "feature-needs", "switch", "trees.enabled");
        CustomTreesPlugin noTimber = plugin(Feature.TIMBER);
        Player other = player();
        assertTrue(noTimber.off(other, Feature.REGROWTH));
        verify(langOf(noTimber)).send(other, "feature-needs", "switch", "timber.enabled");
    }

    @Test void givenEverythingOn_whenACommandRuns_thenNoOffMessage() throws Exception {
        CustomTreesPlugin plugin = plugin();
        for (Feature feature : Feature.values()) if (feature.defaultOn) assertFalse(plugin.off(player(), feature), feature.name());
        verify(langOf(plugin), never()).send(any(), anyString());
    }

    @Test void givenBreedingOff_whenAPlayerBreeds_thenTheSharedMessage() throws Exception {
        CustomTreesPlugin plugin = plugin(Feature.BREEDING);
        Player player = player();
        new SaplingBreeding(plugin).breed(player);
        verify(langOf(plugin)).send(player, "feature-off");
    }

    @Test void givenASwitchOff_whenTheCommandTreeIsAsked_thenItsSubcommandsAreHiddenAndReturnWhenItIsOnAgain() throws Exception {
        CustomTreesPlugin plugin = plugin();
        CommandSpec spec = CustomTreesPlugin.commandSpec(plugin);
        for (CommandSpec child : spec.children().values()) assertTrue(child.visibleTo(null), child.name());
        field(plugin, "switches", Feature.Switches.without(Feature.BREEDING, Feature.HERBARIUM));
        assertFalse(spec.children().get("breed").visibleTo(null));
        assertFalse(spec.children().get("herbarium").visibleTo(null));
        assertFalse(spec.children().get("next").visibleTo(null));
        assertTrue(spec.children().get("plant").visibleTo(null));
        field(plugin, "switches", Feature.Switches.without(Feature.TREES));
        for (String sub : List.of("guide", "sapling", "breed", "prune", "plant", "herbarium", "next")) assertFalse(spec.children().get(sub).visibleTo(null), sub);
        assertTrue(spec.children().get("status").visibleTo(null), "status and reload stay");
        assertTrue(spec.children().get("reload").visibleTo(null));
        field(plugin, "switches", Feature.Switches.without());
        for (CommandSpec child : spec.children().values()) assertTrue(child.visibleTo(null), "on again: " + child.name());
    }

    @Test void givenASwitchChangedBySettings_whenTheSwitchesApply_thenCommandListsAreRefreshedWithoutRegisteringAgain() throws Exception {
        CustomTreesPlugin plugin = plugin(Feature.GUIDE);
        Vystorm.vystorm_core.API core = mock(Vystorm.vystorm_core.API.class);
        Vystorm.vystorm_core._uiMethods.commands.CommandInterface commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        field(plugin, "core", core);
        field(plugin, "commandSwitches", Feature.Switches.without());
        java.lang.reflect.Method apply = CustomTreesPlugin.class.getDeclaredMethod("applySwitches");
        apply.setAccessible(true);
        apply.invoke(plugin);
        verify(commands, times(1)).refresh();
        apply.invoke(plugin);
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenTheNamespace_whenResolved_thenValuesAndEmptyWhileOff() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        OfflinePlayer viewer = PlaceholderTesting.viewer("Steve");
        Herbarium herbarium = mock(Herbarium.class);
        when(herbarium.found(viewer.getUniqueId())).thenReturn(12);
        when(herbarium.keepers()).thenReturn(1);
        when(herbarium.top(anyInt())).thenReturn(List.of(new Herbarium.Standing(UUID.randomUUID(), "Alex", 83),
                new Herbarium.Standing(viewer.getUniqueId(), "Steve", 12)));
        TreesPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("CustomTrees"), new TreesPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return !off.contains(feature); }
            @Override public Herbarium herbarium() { return herbarium; }
            @Override public String rankName(Player player, Herbarium.Rank rank) { return rank.id(); }
            @Override public long breedCooldown(Player player) { return 7; }
            @Override public long[] grown() { return new long[] {5, 4, 3, 2, 1}; }
        }, key -> key);

        assertEquals("12", testing.resolve(viewer, "%vystorm_trees_herbarium_found%"));
        assertEquals(String.valueOf(Herbarium.TOTAL), testing.resolve(viewer, "{trees:herbarium_total}"));
        assertEquals(Herbarium.Rank.of(12).id(), testing.resolve(viewer, "%vystorm_trees_herbarium_rank_id%"));
        assertEquals("Alex", testing.resolve(viewer, "%vystorm_trees_herbarium_top_1_name%"));
        assertEquals("12", testing.resolve(viewer, "{trees:herbarium_top:2:found}"));
        assertEquals("1", testing.resolve(null, "%vystorm_trees_herbarium_keepers%"));
        assertEquals("5", testing.resolve(null, "%vystorm_trees_trees_grown%"));
        assertEquals("1", testing.resolve(null, "%vystorm_trees_hedges_grown%"));
        assertEquals(String.valueOf(Math.max(0, Herbarium.Rank.of(12).next().needed() - 12)), testing.resolve(viewer, "%vystorm_trees_herbarium_next_needed%"));

        off.add(Feature.HERBARIUM);
        off.add(Feature.GIANT);
        assertEquals("", testing.resolve(viewer, "%vystorm_trees_herbarium_found%"));
        assertEquals("", testing.resolve(viewer, "%vystorm_trees_herbarium_top_2_name%"), "a place not asked before (values are cached for a second)");
        assertEquals("", testing.resolve(null, "%vystorm_trees_giant_grown%"));
        assertEquals("5", testing.resolve(null, "%vystorm_trees_trees_grown%"));
        assertEquals(0, TreesPlaceholders.secondsLeft(1000, 2000));
        assertEquals(2, TreesPlaceholders.secondsLeft(3500, 2000));
    }

    // ------------------------------------------------------------------ helpers

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenReturn(true);
        return player;
    }

    /** A plugin object without a server: lang and the switches set, real methods run. */
    private static CustomTreesPlugin plugin(Feature... off) throws Exception {
        CustomTreesPlugin plugin = mock(CustomTreesPlugin.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        field(plugin, "lang", mock(Lang.class));
        field(plugin, "switches", Feature.Switches.without(off));
        return plugin;
    }

    private static Lang langOf(CustomTreesPlugin plugin) throws Exception {
        Field field = CustomTreesPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = CustomTreesPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T any() { return Mockito.any(); }
    private static int anyInt() { return Mockito.anyInt(); }
}
