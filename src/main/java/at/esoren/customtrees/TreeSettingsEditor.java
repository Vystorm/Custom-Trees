package at.esoren.customtrees;

import Vystorm.vystorm_core._uiMethods.settings.ConfigSettings;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Server settings section for the Core settings menu. Values are written into config.yml; afterwards
 * the same validation as /customtrees reload runs. Invalid combinations (e.g. min height above max
 * height) are rolled back to the last valid values, so the active settings never break.
 */
final class TreeSettingsEditor {
    static final String SECTION_ID = "server";
    static final String PERMISSION = "customtrees.admin";

    private final Supplier<FileConfiguration> config;
    private final Runnable save;
    private final BiConsumer<TreeSettings, BiomeGrowthProfiles> apply;
    private final Lang lang;
    private final BiConsumer<Player, Component> rejected;
    private Map<String, Object> lastValid = Map.of();
    private Map<String, Object> lastValidProfiles = Map.of();

    TreeSettingsEditor(Supplier<FileConfiguration> config, Runnable save, Consumer<TreeSettings> apply, Lang lang) {
        this(config, save, apply, lang, (player, message) -> Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT.sendMessage(player, message));
    }

    /** @param rejected sends the rollback notice to the editing admin, e.g. with a settings button appended */
    TreeSettingsEditor(Supplier<FileConfiguration> config, Runnable save, Consumer<TreeSettings> apply, Lang lang,
            BiConsumer<Player, Component> rejected) {
        this(config, save, (settings, profiles) -> apply.accept(settings), lang, rejected);
    }

    TreeSettingsEditor(Supplier<FileConfiguration> config, Runnable save,
            BiConsumer<TreeSettings, BiomeGrowthProfiles> apply, Lang lang,
            BiConsumer<Player, Component> rejected) {
        this.config = Objects.requireNonNull(config);
        this.save = Objects.requireNonNull(save);
        this.apply = Objects.requireNonNull(apply);
        this.lang = Objects.requireNonNull(lang);
        this.rejected = Objects.requireNonNull(rejected);
    }

    /** Validates the current configuration and applies it; returns null or the validation error. */
    String load() {
        return load(lang.language(null));
    }

    /** Validates the current configuration and applies it, rendering validation errors in the requested language. */
    String load(String language) {
        try {
            TreeSettings loaded = TreeSettings.load(config.get());
            BiomeGrowthProfiles profiles = BiomeGrowthProfiles.load(config.get());
            apply.accept(loaded, profiles);
            lastValid = snapshot();
            lastValidProfiles = profileSnapshot();
            return null;
        } catch (TreeSettingsException exception) {
            return lang.plain(language, exception.languageKey(), exception.arguments());
        } catch (RuntimeException exception) {
            return exception.getMessage() == null ? exception.toString() : exception.getMessage();
        }
    }

    /** Called after the settings dialog saved config.yml. */
    void afterSave(Player player) {
        String error = load(lang.language(player));
        if (error == null) {
            if (player != null) lang.send(player, "settings.applied");
            return;
        }
        FileConfiguration yaml = config.get();
        yaml.set("settings", null);
        lastValid.forEach((key, value) -> yaml.set("settings." + key, copyValue(value)));
        yaml.set("biome-profiles", null);
        lastValidProfiles.forEach((key, value) -> yaml.set("biome-profiles." + key, copyValue(value)));
        save.run();
        if (player != null) {
            rejected.accept(player, lang.component(player, "settings.rejected", "error", error));
        }
    }

    private Map<String, Object> snapshot() {
        ConfigurationSection section = config.get().getConfigurationSection("settings");
        return snapshotSection(section);
    }

    private Map<String, Object> profileSnapshot() {
        ConfigurationSection section = config.get().getConfigurationSection("biome-profiles");
        return snapshotSection(section);
    }

    private static Map<String, Object> snapshotSection(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        flatten(section, "", result);
        return result;
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, Object> result) {
        for (String key : section.getKeys(false)) {
            ConfigurationSection nested = section.getConfigurationSection(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (nested != null) flatten(nested, path, result);
            else result.put(path, copyValue(section.get(key)));
        }
    }

    private static Object copyValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(copyValue(item));
            return copy;
        }
        return value;
    }

    SettingsSection section() {
        return ConfigSettings.of(config, save)
                .toggle(path("custom-player-grown-trees"), label("custom-player-grown-trees"), description("custom-player-grown-trees"))
                .toggle(path("connected-shrubs-and-hedges"), label("connected-shrubs-and-hedges"), description("connected-shrubs-and-hedges"))
                .toggle(path("replace-generated-trees"), label("replace-generated-trees"), description("replace-generated-trees"))
                .decimal(path("replacement-chance"), label("replacement-chance"), description("replacement-chance"), 0.0, 1.0, 0.05)
                .integer(path("generated-tree-scan-depth"), label("generated-tree-scan-depth"), description("generated-tree-scan-depth"),
                        TreeSettings.MIN_SCAN_DEPTH, TreeSettings.MAX_SCAN_DEPTH, 4)
                .toggle(path("new-chunk-post-processing"), label("new-chunk-post-processing"), description("new-chunk-post-processing"))
                .integer(path("new-chunk-post-process-delay-ticks"), label("new-chunk-post-process-delay-ticks"),
                        description("new-chunk-post-process-delay-ticks"), 1, 40, 1)
                .toggle(path("giant-3x3-saplings"), label("giant-3x3-saplings"), description("giant-3x3-saplings"))
                .toggle(path("natural-3x3-growth"), label("natural-3x3-growth"), description("natural-3x3-growth"))
                .toggle(path("bonemeal-3x3-growth"), label("bonemeal-3x3-growth"), description("bonemeal-3x3-growth"))
                .integer(path("giant-min-height"), label("giant-min-height"), description("giant-min-height"), 16, 96, 1)
                .integer(path("giant-max-height"), label("giant-max-height"), description("giant-max-height"), 16, 96, 1)
                .integer(path("giant-canopy-radius"), label("giant-canopy-radius"), description("giant-canopy-radius"), 5, 20, 1)
                .toggle(path("colossal-5x5-saplings"), label("colossal-5x5-saplings"), description("colossal-5x5-saplings"))
                .toggle(path("natural-5x5-growth"), label("natural-5x5-growth"), description("natural-5x5-growth"))
                .toggle(path("bonemeal-5x5-growth"), label("bonemeal-5x5-growth"), description("bonemeal-5x5-growth"))
                .integer(path("colossal-min-height"), label("colossal-min-height"), description("colossal-min-height"), 32, 120, 1)
                .integer(path("colossal-max-height"), label("colossal-max-height"), description("colossal-max-height"), 32, 120, 1)
                .integer(path("colossal-canopy-radius"), label("colossal-canopy-radius"), description("colossal-canopy-radius"), 10, 28, 1)
                .toggle(path("glowstone-growth"), label("glowstone-growth"), description("glowstone-growth"))
                .integer(path("glowstone-xp-cost"), label("glowstone-xp-cost"), description("glowstone-xp-cost"),
                        0, TreeSettings.MAX_GLOWSTONE_XP_COST, 10)
                .integer(path("glowstone-leaves-per-block"), label("glowstone-leaves-per-block"), description("glowstone-leaves-per-block"), 16, 1024, 8)
                .integer(path("glowstone-max-blocks"), label("glowstone-max-blocks"), description("glowstone-max-blocks"), 1, 128, 1)
                .integer(path("glowstone-dark-light-level"), label("glowstone-dark-light-level"), description("glowstone-dark-light-level"), 0, 15, 1)
                .integer(path("glowstone-growth-cooldown-seconds"), label("glowstone-growth-cooldown-seconds"),
                        description("glowstone-growth-cooldown-seconds"), 0, TreeSettings.MAX_CATALYST_COOLDOWN_SECONDS, 60)
                .integer(path("glowstone-growth-daily-limit"), label("glowstone-growth-daily-limit"),
                        description("glowstone-growth-daily-limit"), 0, TreeSettings.MAX_CATALYST_DAILY_LIMIT, 1)
                .toggle(path("coal-growth"), label("coal-growth"), description("coal-growth"))
                .integer(path("coal-growth-cooldown-seconds"), label("coal-growth-cooldown-seconds"),
                        description("coal-growth-cooldown-seconds"), 0, TreeSettings.MAX_CATALYST_COOLDOWN_SECONDS, 60)
                .integer(path("coal-growth-daily-limit"), label("coal-growth-daily-limit"),
                        description("coal-growth-daily-limit"), 0, TreeSettings.MAX_CATALYST_DAILY_LIMIT, 1)
                .integer(path("coal-costs.normal"), label("coal-costs.normal"), description("coal-costs.normal"), 1, 64, 1)
                .integer(path("coal-costs.medium"), label("coal-costs.medium"), description("coal-costs.medium"), 1, 64, 1)
                .integer(path("coal-costs.giant"), label("coal-costs.giant"), description("coal-costs.giant"), 1, 64, 1)
                .integer(path("coal-costs.colossal"), label("coal-costs.colossal"), description("coal-costs.colossal"), 1, 64, 1)
                .integer(path("coal-costs.hedge"), label("coal-costs.hedge"), description("coal-costs.hedge"), 1, 64, 1)
                .toggle(path("fire-protect-trunks"), label("fire-protect-trunks"), description("fire-protect-trunks"))
                .toggle(path("spruce-ground"), label("spruce-ground"), description("spruce-ground"))
                .toggle(path("spruce-snow"), label("spruce-snow"), description("spruce-snow"))
                .toggle(path("spruce-snow-cold-only"), label("spruce-snow-cold-only"), description("spruce-snow-cold-only"))
                .toggle(path("fire-protect-crowns"), label("fire-protect-crowns"), description("fire-protect-crowns"))
                .integer(path("garden-regrowth-seconds"), label("garden-regrowth-seconds"), description("garden-regrowth-seconds"),
                        10, 86_400, 1)
                .section(SECTION_ID, "CustomTrees", lang.ref("settings.section-description"), PERMISSION, this::afterSave);
    }

    /** Names of all settings in the Core menu (their label/description keys live under settings.&lt;name&gt; in lang/*.yml). */
    static final List<String> SETTING_NAMES = List.of(
            "spruce-ground", "spruce-snow", "spruce-snow-cold-only",
            "custom-player-grown-trees", "connected-shrubs-and-hedges", "replace-generated-trees",
            "replacement-chance", "generated-tree-scan-depth", "new-chunk-post-processing",
            "new-chunk-post-process-delay-ticks", "giant-3x3-saplings", "natural-3x3-growth", "bonemeal-3x3-growth",
            "giant-min-height", "giant-max-height", "giant-canopy-radius", "colossal-5x5-saplings",
            "natural-5x5-growth", "bonemeal-5x5-growth", "colossal-min-height", "colossal-max-height",
            "colossal-canopy-radius", "glowstone-growth", "glowstone-xp-cost", "glowstone-leaves-per-block",
            "glowstone-max-blocks", "glowstone-dark-light-level", "glowstone-growth-cooldown-seconds",
            "glowstone-growth-daily-limit", "coal-growth", "coal-growth-cooldown-seconds",
            "coal-growth-daily-limit", "coal-costs.normal", "coal-costs.medium", "coal-costs.giant",
            "coal-costs.colossal", "coal-costs.hedge", "fire-protect-trunks", "fire-protect-crowns",
            "garden-regrowth-seconds");

    private static String path(String name) {
        return "settings." + name;
    }

    private String label(String name) {
        return lang.ref("settings." + name + ".label");
    }

    private String description(String name) {
        return lang.ref("settings." + name + ".description");
    }
}
