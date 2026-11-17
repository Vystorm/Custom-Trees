package at.esoren.customtrees;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, bounded biome-to-growth-profile configuration. This class deliberately has no Core 0.26.5 API types. */
record BiomeGrowthProfiles(Map<String, Profile> byBiome) {
    static final int MAX_GROUPS = 32;
    static final int MAX_BIOMES = 128;
    static final BiomeGrowthProfiles EMPTY = new BiomeGrowthProfiles(Map.of());

    BiomeGrowthProfiles {
        byBiome = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(byBiome)));
        if (byBiome.size() > MAX_BIOMES) throw new IllegalArgumentException("Too many biome profile memberships");
    }

    boolean isEmpty() {
        return byBiome.isEmpty();
    }

    Profile forBiome(String biomeKey) {
        return byBiome.get(biomeKey);
    }

    static BiomeGrowthProfiles load(FileConfiguration config) {
        return load(config, BiomeGrowthProfiles::registeredBiome);
    }

    /** Resolver-injected overload keeps validation tests independent of server registries. */
    static BiomeGrowthProfiles load(FileConfiguration config, Predicate<String> knownBiome) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(knownBiome);
        if (!config.contains("biome-profiles")) return EMPTY;
        ConfigurationSection groups = config.getConfigurationSection("biome-profiles");
        if (groups == null) throw invalid("validation.biome-profiles-root");
        Map<String, Object> entries = groups.getValues(false);
        if (entries.size() > MAX_GROUPS) throw invalid("validation.biome-profiles-limit", "max", MAX_GROUPS);
        Map<String, Profile> profiles = new LinkedHashMap<>();
        Set<String> assigned = new LinkedHashSet<>();
        for (String id : entries.keySet()) {
            if (id == null || !id.matches("[a-z][a-z0-9_-]{0,31}")) {
                throw invalid("validation.biome-profile-id", "id", String.valueOf(id));
            }
            ConfigurationSection group = groups.getConfigurationSection(id);
            if (group == null) throw invalid("validation.biome-profile-group", "id", id);
            Object rawBiomes = group.get("biomes");
            if (!(rawBiomes instanceof List<?> biomes) || biomes.isEmpty()) {
                throw invalid("validation.biome-profile-biomes", "id", id);
            }
            double height = decimal(group, "height-scale", 1.0, id);
            double crown = decimal(group, "crown-scale", 1.0, id);
            if (height < 0.5 || height > 1.5) {
                throw invalid("validation.biome-profile-scale", "id", id, "field", "height-scale", "min", 0.5, "max", 1.5);
            }
            if (crown < 0.5 || crown > 1.5) {
                throw invalid("validation.biome-profile-scale", "id", id, "field", "crown-scale", "min", 0.5, "max", 1.5);
            }
            Object rawForm = group.get("crown-form", "RANDOM");
            if (!(rawForm instanceof String formText)) {
                throw invalid("validation.biome-profile-form", "id", id, "form", String.valueOf(rawForm));
            }
            CrownForm form;
            try {
                form = CrownForm.valueOf(formText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw invalid("validation.biome-profile-form", "id", id, "form", formText);
            }
            Double replacementChance = null;
            if (group.contains("replacement-chance")) replacementChance = decimal(group, "replacement-chance", 0, id);
            if (replacementChance != null && (replacementChance < 0 || replacementChance > 1)) {
                throw invalid("validation.biome-profile-chance", "id", id, "min", 0, "max", 1);
            }
            Profile profile = new Profile(height, crown, form, replacementChance);
            for (Object rawBiome : biomes) {
                if (!(rawBiome instanceof String biome) || !biome.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) {
                    throw invalid("validation.biome-profile-biome-key", "id", id, "biome", String.valueOf(rawBiome));
                }
                if (!knownBiome.test(biome)) {
                    throw invalid("validation.biome-profile-unknown-biome", "id", id, "biome", biome);
                }
                if (!assigned.add(biome)) {
                    throw invalid("validation.biome-profile-duplicate-biome", "biome", biome);
                }
                if (assigned.size() > MAX_BIOMES) {
                    throw invalid("validation.biome-profiles-biome-limit", "max", MAX_BIOMES);
                }
                profiles.put(biome, profile);
            }
        }
        return profiles.isEmpty() ? EMPTY : new BiomeGrowthProfiles(profiles);
    }

    private static double decimal(ConfigurationSection section, String path, double fallback, String id) {
        Object value = section.get(path);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw invalid("validation.biome-profile-number", "id", id, "field", path);
        }
        return number.doubleValue();
    }

    private static boolean registeredBiome(String key) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        return parsed != null && Registry.BIOME.get(parsed) != null;
    }

    private static TreeSettingsException invalid(String key, Object... arguments) {
        return new TreeSettingsException(key, arguments);
    }

    enum CrownForm { RANDOM, OPEN_GROWN, FOREST, WINDSWEPT, ANCIENT }

    record Profile(double heightScale, double crownScale, CrownForm crownForm, Double replacementChance) {
    }
}
