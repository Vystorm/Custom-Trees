package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.stream.IntStream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BiomeGrowthProfilesTest {
    private static boolean known(String key) {
        return key.equals("minecraft:plains") || key.equals("minecraft:meadow")
                || key.matches("minecraft:test_[0-9]+");
    }

    @Test
    void emptyProfilesStayEmptyAndConfiguredProfilesAreImmutableLookups() {
        assertTrue(BiomeGrowthProfiles.load(new YamlConfiguration(), BiomeGrowthProfilesTest::known).isEmpty());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("biome-profiles.meadow.biomes", java.util.List.of("minecraft:plains", "minecraft:meadow"));
        yaml.set("biome-profiles.meadow.height-scale", 1.2);
        yaml.set("biome-profiles.meadow.crown-scale", 0.8);
        yaml.set("biome-profiles.meadow.crown-form", "OPEN_GROWN");
        yaml.set("biome-profiles.meadow.replacement-chance", 0.65);
        BiomeGrowthProfiles profiles = BiomeGrowthProfiles.load(yaml, BiomeGrowthProfilesTest::known);
        assertEquals(2, profiles.byBiome().size());
        assertEquals(new BiomeGrowthProfiles.Profile(1.2, 0.8,
                BiomeGrowthProfiles.CrownForm.OPEN_GROWN, 0.65), profiles.forBiome("minecraft:plains"));
        assertNull(profiles.forBiome("minecraft:forest"));
        assertThrows(UnsupportedOperationException.class, () -> profiles.byBiome().clear());
    }

    @Test
    void invalidBiomeAssignmentsAndValuesRejectTheWholeMap() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("biome-profiles.one.biomes", java.util.List.of("minecraft:plains"));
        yaml.set("biome-profiles.two.biomes", java.util.List.of("minecraft:plains"));
        final YamlConfiguration duplicate = yaml;
        assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(duplicate, BiomeGrowthProfilesTest::known), "duplicate membership");

        yaml = new YamlConfiguration();
        yaml.set("biome-profiles.one.biomes", java.util.List.of("minecraft:unknown"));
        final YamlConfiguration unknown = yaml;
        assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(unknown, BiomeGrowthProfilesTest::known), "unknown biome");

        yaml = new YamlConfiguration();
        yaml.set("biome-profiles.one.biomes", java.util.List.of("minecraft:plains"));
        yaml.set("biome-profiles.one.height-scale", Double.NaN);
        final YamlConfiguration nonFinite = yaml;
        assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(nonFinite, BiomeGrowthProfilesTest::known), "non-finite scale");

        yaml = new YamlConfiguration();
        yaml.set("biome-profiles.one.biomes", java.util.List.of("minecraft:plains"));
        yaml.set("biome-profiles.one.crown-form", "MAGIC");
        final YamlConfiguration badForm = yaml;
        assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(badForm, BiomeGrowthProfilesTest::known), "unknown form");

        YamlConfiguration outOfRange = new YamlConfiguration();
        outOfRange.set("biome-profiles.one.biomes", java.util.List.of("minecraft:plains"));
        outOfRange.set("biome-profiles.one.crown-scale", 1.51);
        assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(outOfRange, BiomeGrowthProfilesTest::known), "scale above API range");

        YamlConfiguration badChance = new YamlConfiguration();
        badChance.set("biome-profiles.one.biomes", java.util.List.of("minecraft:plains"));
        badChance.set("biome-profiles.one.replacement-chance", -0.01);
        assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(badChance, BiomeGrowthProfilesTest::known), "chance outside 0..1");
    }

    @Test
    void groupAndBiomeAssignmentBoundsAreEnforced() {
        YamlConfiguration tooManyGroups = new YamlConfiguration();
        IntStream.range(0, BiomeGrowthProfiles.MAX_GROUPS + 1).forEach(i -> {
            tooManyGroups.set("biome-profiles.group_" + i + ".biomes", java.util.List.of("minecraft:plains"));
        });
        TreeSettingsException groupLimit = assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(tooManyGroups, BiomeGrowthProfilesTest::known));
        assertEquals("validation.biome-profiles-limit", groupLimit.languageKey());
        assertArrayEquals(new Object[] {"max", BiomeGrowthProfiles.MAX_GROUPS}, groupLimit.arguments());

        YamlConfiguration tooManyBiomes = new YamlConfiguration();
        IntStream.range(0, BiomeGrowthProfiles.MAX_BIOMES + 1).forEach(i -> {
            int group = i / 26;
            java.util.List<String> keys = new java.util.ArrayList<>(
                    tooManyBiomes.getStringList("biome-profiles.group_" + group + ".biomes"));
            keys.add("minecraft:test_" + i);
            tooManyBiomes.set("biome-profiles.group_" + group + ".biomes", keys);
        });
        TreeSettingsException biomeLimit = assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(tooManyBiomes, BiomeGrowthProfilesTest::known));
        assertEquals("validation.biome-profiles-biome-limit", biomeLimit.languageKey());
        assertArrayEquals(new Object[] {"max", BiomeGrowthProfiles.MAX_BIOMES}, biomeLimit.arguments());
    }

    @Test
    void localizedValidationArgumentsAreNamedForEveryProfilePlaceholder() {
        YamlConfiguration badNumber = profile("height-scale", "large");
        assertValidation(badNumber, "validation.biome-profile-number", "id", "meadow", "field", "height-scale");

        YamlConfiguration badScale = profile("crown-scale", 2.0);
        assertValidation(badScale, "validation.biome-profile-scale", "id", "meadow", "field", "crown-scale",
                "min", 0.5, "max", 1.5);

        YamlConfiguration badForm = profile("crown-form", "MAGIC");
        assertValidation(badForm, "validation.biome-profile-form", "id", "meadow", "form", "MAGIC");

        YamlConfiguration badChance = profile("replacement-chance", 2.0);
        assertValidation(badChance, "validation.biome-profile-chance", "id", "meadow", "min", 0, "max", 1);

        YamlConfiguration badBiomeKey = profile("biomes", java.util.List.of("Invalid"));
        assertValidation(badBiomeKey, "validation.biome-profile-biome-key", "id", "meadow", "biome", "Invalid");

        YamlConfiguration unknownBiome = profile("biomes", java.util.List.of("minecraft:unknown"));
        assertValidation(unknownBiome, "validation.biome-profile-unknown-biome",
                "id", "meadow", "biome", "minecraft:unknown");

        YamlConfiguration duplicateBiome = new YamlConfiguration();
        duplicateBiome.set("biome-profiles.first.biomes", java.util.List.of("minecraft:plains"));
        duplicateBiome.set("biome-profiles.second.biomes", java.util.List.of("minecraft:plains"));
        assertValidation(duplicateBiome, "validation.biome-profile-duplicate-biome", "biome", "minecraft:plains");
    }

    private static YamlConfiguration profile(String field, Object value) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("biome-profiles.meadow.biomes", java.util.List.of("minecraft:plains"));
        yaml.set("biome-profiles.meadow." + field, value);
        return yaml;
    }

    private static void assertValidation(YamlConfiguration yaml, String key, Object... arguments) {
        TreeSettingsException error = assertThrows(TreeSettingsException.class,
                () -> BiomeGrowthProfiles.load(yaml, BiomeGrowthProfilesTest::known));
        assertEquals(key, error.languageKey());
        assertArrayEquals(arguments, error.arguments());
    }
}
