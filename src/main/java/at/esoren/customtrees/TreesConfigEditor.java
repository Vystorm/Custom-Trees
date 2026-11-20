package at.esoren.customtrees;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core.lang.Lang;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Makes the whole {@code config.yml} editable in Core's admin config editor (Core 0.27.0+, {@code config-editor}).
 * Labels reuse the texts of the settings section ({@code settings.<name>.label/description}), bounds follow it with
 * step 1 so an untouched save never rounds a stored value.
 */
final class TreesConfigEditor {
    /** A labelled key; {@code min} is NaN when the key has no slider range. */
    record Entry(String path, double min, double max, double step) {
        boolean ranged() { return !Double.isNaN(min); }
    }

    private static final List<String> TREE_TYPE_CHOICES = List.of("server-mode", "fall", "drop", "disabled");

    static final List<Entry> ENTRIES = List.of(
            label("spruce-ground"), label("spruce-snow"), label("spruce-snow-cold-only"),
            label("custom-player-grown-trees"),
            label("connected-shrubs-and-hedges"),
            label("replace-generated-trees"),
            range("replacement-chance", 0.0, 1.0, 0.01),
            range("generated-tree-scan-depth", TreeSettings.MIN_SCAN_DEPTH, TreeSettings.MAX_SCAN_DEPTH, 1),
            label("new-chunk-post-processing"),
            range("new-chunk-post-process-delay-ticks", 1, 40, 1),
            label("giant-3x3-saplings"),
            label("natural-3x3-growth"),
            label("bonemeal-3x3-growth"),
            range("giant-min-height", 16, 96, 1),
            range("giant-max-height", 16, 96, 1),
            range("giant-canopy-radius", 5, 20, 1),
            label("colossal-5x5-saplings"),
            label("natural-5x5-growth"),
            label("bonemeal-5x5-growth"),
            range("colossal-min-height", 32, 120, 1),
            range("colossal-max-height", 32, 120, 1),
            range("colossal-canopy-radius", 10, 28, 1),
            label("glowstone-growth"),
            range("glowstone-xp-cost", 0, TreeSettings.MAX_GLOWSTONE_XP_COST, 1),
            range("glowstone-leaves-per-block", 16, 1024, 1),
            range("glowstone-max-blocks", 1, 128, 1),
            range("glowstone-dark-light-level", 0, 15, 1),
            range("glowstone-growth-cooldown-seconds", 0, TreeSettings.MAX_CATALYST_COOLDOWN_SECONDS, 1),
            range("glowstone-growth-daily-limit", 0, TreeSettings.MAX_CATALYST_DAILY_LIMIT, 1),
            label("coal-growth"),
            range("coal-growth-cooldown-seconds", 0, TreeSettings.MAX_CATALYST_COOLDOWN_SECONDS, 1),
            range("coal-growth-daily-limit", 0, TreeSettings.MAX_CATALYST_DAILY_LIMIT, 1),
            range("coal-costs.normal", 1, 64, 1),
            range("coal-costs.medium", 1, 64, 1),
            range("coal-costs.giant", 1, 64, 1),
            range("coal-costs.colossal", 1, 64, 1),
            range("coal-costs.hedge", 1, 64, 1),
            label("fire-protect-trunks"),
            label("fire-protect-crowns"),
            range("garden-regrowth-seconds", 10, 86_400, 1),
            range("garden-max-trees", GardenState.MIN_TREES, GardenState.HARD_MAX_TREES, 1),
            new Entry("world-trees.enabled", Double.NaN, Double.NaN, Double.NaN),
            new Entry("world-trees.tree-height",32,192,1),
            new Entry("world-trees.mushroom-height",32,192,1),
            new Entry("world-trees.nether-height",32,192,1),
            new Entry("world-trees.tree-radius",12,48,1),
            new Entry("world-trees.mushroom-radius",12,40,1),
            new Entry("world-trees.nether-radius",12,40,1),
            new Entry("world-trees.blocks-per-tick",64,4096,1),
            new Entry("world-trees.replace-stone", Double.NaN, Double.NaN, Double.NaN),
            new Entry("world-trees.max-skipped-percent",0,100,1),
            new Entry("world-trees.fungi.1x1.mushroom-height",6,192,1),
            new Entry("world-trees.fungi.1x1.mushroom-radius",2,40,1),
            new Entry("world-trees.fungi.1x1.nether-height",6,192,1),
            new Entry("world-trees.fungi.1x1.nether-radius",2,40,1),
            new Entry("world-trees.fungi.2x2.mushroom-height",6,192,1),
            new Entry("world-trees.fungi.2x2.mushroom-radius",2,40,1),
            new Entry("world-trees.fungi.2x2.nether-height",6,192,1),
            new Entry("world-trees.fungi.2x2.nether-radius",2,40,1),
            new Entry("world-trees.fungi.3x3.mushroom-height",6,192,1),
            new Entry("world-trees.fungi.3x3.mushroom-radius",2,40,1),
            new Entry("world-trees.fungi.3x3.nether-height",6,192,1),
            new Entry("world-trees.fungi.3x3.nether-radius",2,40,1),
            new Entry("world-trees.fungi.5x5.mushroom-height",6,192,1),
            new Entry("world-trees.fungi.5x5.mushroom-radius",2,40,1),
            new Entry("world-trees.fungi.5x5.nether-height",6,192,1),
            new Entry("world-trees.fungi.5x5.nether-radius",2,40,1),
            new Entry("shaking.enabled", Double.NaN, Double.NaN, Double.NaN),
            new Entry("shaking.cooldown-seconds", 0, 86_400, 1),
            new Entry("shaking.max-rolls", 1, 32, 1),
            new Entry("shaking.min-leaves", 1, 1000, 1),
            new Entry("shaking.golden-apples", Double.NaN, Double.NaN, Double.NaN),
            new Entry("herbarium.enabled", Double.NaN, Double.NaN, Double.NaN),
            new Entry("herbarium.announce-ranks", Double.NaN, Double.NaN, Double.NaN),
            new Entry("planting-helper.enabled", Double.NaN, Double.NaN, Double.NaN),
            new Entry("planting-helper.max-size", 2, 10, 1),
            new Entry("planting-helper.max-hedge-length", 0, 32, 1),
            new Entry("planting-helper.keep-active", Double.NaN, Double.NaN, Double.NaN),
            new Entry("planting-helper.timeout-seconds", 10, 3600, 1),
            new Entry("timber.enabled", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.mode", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.trigger", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.trunk-percent", 1, 100, 1),
            new Entry("timber.sneaking", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.require-axe", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.worlds", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.player-choice", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.tree-types.small", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.tree-types.giant", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.tree-types.colossal", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.tree-types.world", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.tool.durability-per-log", 0, 10, 0.1),
            new Entry("timber.tool.spare-tool", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.drops.leaves", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.drops.into-inventory", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.replant", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.detection.max-blocks", 16, 250000, 1),
            new Entry("timber.detection.max-radius", 4, 96, 1),
            new Entry("timber.detection.max-height", 8, 384, 1),
            new Entry("timber.detection.min-leaves", 0, 1000, 1),
            new Entry("timber.detection.natural-ground", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.detection.coreprotect-check", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.detection.only-generated-trees", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.fall.max-displays", 16, 2000, 1),
            new Entry("timber.fall.fall-ticks", 10, 200, 1),
            new Entry("timber.performance.blocks-per-tick", 64, 20000, 1),
            new Entry("timber.performance.checks-per-tick", 64, 20000, 1),
            new Entry("timber.performance.falls-per-world", 1, 64, 1),
            new Entry("timber.performance.falls-per-player", 1, 16, 1),
            new Entry("timber.other-timber-plugins", Double.NaN, Double.NaN, Double.NaN),
            new Entry("timber.percent-memory-seconds", 10, 86400, 1),
            new Entry("timber.protected-region-kinds", Double.NaN, Double.NaN, Double.NaN));

    /** Fixed choices of string keys; option labels are {@code timber.choice.<value>} in lang/*.yml. */
    static final java.util.Map<String, List<String>> CHOICES = java.util.Map.of(
            "timber.mode", List.of("fall", "drop"),
            "timber.trigger", List.of("first-log", "percent"),
            "timber.sneaking", List.of("standing", "sneaking", "any"),
            "timber.tree-types.small", TREE_TYPE_CHOICES,
            "timber.tree-types.giant", TREE_TYPE_CHOICES,
            "timber.tree-types.colossal", TREE_TYPE_CHOICES,
            "timber.tree-types.world", TREE_TYPE_CHOICES,
            "timber.drops.leaves", List.of("vanilla", "none"),
            "timber.other-timber-plugins", List.of("pause", "run-both"));

    static String choiceKey(String value) { return "timber.choice." + value; }

    private TreesConfigEditor() {}

    private static Entry range(String name, double min, double max, double step) { return new Entry("settings." + name, min, max, step); }
    private static Entry label(String name) { return new Entry("settings." + name, Double.NaN, Double.NaN, Double.NaN); }

    static String labelKey(String path) { return path + ".label"; }
    static String descriptionKey(String path) { return path + ".description"; }

    /** {@code reload} is the same path as {@code /customtrees reload} (validated; on an error the old settings stay). */
    static void register(ConfigEditorInterface config, Plugin plugin, Lang lang, Runnable reload) {
        config.register(plugin, "config.yml", reload);
        config.registerAll(plugin, reload);
        for (Entry entry : ENTRIES) {
            config.label(plugin, entry.path(), lang.ref(labelKey(entry.path())), lang.ref(descriptionKey(entry.path())));
            if (entry.ranged()) config.range(plugin, entry.path(), entry.min(), entry.max(), entry.step());
        }
        try {
            for (var choice : CHOICES.entrySet()) {
                java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
                for (String value : choice.getValue()) labels.put(value, lang.ref(choiceKey(value)));
                config.choices(plugin, choice.getKey(), labels);
            }
        } catch (RuntimeException failure) {
            // Choices not registered: the values stay plain text fields.
        }
    }
}
