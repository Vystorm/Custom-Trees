package at.esoren.customtrees;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Every feature of the plugin with its on/off key in config.yml. A missing key keeps today's default, so a config
 * written before a key existed behaves as before. The description shown in {@code /vadmin features} is the language
 * key {@code switches.<id>} ({@link #description()}). Keys that existed before 1.47.0 keep their names; the nine
 * growth booleans under {@code settings.} and the other older keys are declared as features, not renamed.
 */
enum Feature {
    /** Master switch: saplings, mushrooms and fungi grow as in vanilla and no CustomTrees feature acts. */
    TREES("trees.enabled", true, null),
    CUSTOM_GROWTH("settings.custom-player-grown-trees", true, null),
    SHRUBS_HEDGES("settings.connected-shrubs-and-hedges", true, null),
    MIXED_HEDGES("settings.mixed-hedges", false, SHRUBS_HEDGES),
    GENERATED("settings.replace-generated-trees", true, null),
    GIANT("settings.giant-3x3-saplings", true, null),
    GIANT_NATURAL("settings.natural-3x3-growth", true, GIANT),
    GIANT_BONEMEAL("settings.bonemeal-3x3-growth", true, GIANT),
    COLOSSAL("settings.colossal-5x5-saplings", true, null),
    COLOSSAL_NATURAL("settings.natural-5x5-growth", true, COLOSSAL),
    COLOSSAL_BONEMEAL("settings.bonemeal-5x5-growth", true, COLOSSAL),
    GLOWSTONE("settings.glowstone-growth", true, null),
    COAL("settings.coal-growth", true, null),
    SPRUCE_GROUND("settings.spruce-ground", true, null),
    SPRUCE_SNOW("settings.spruce-snow", true, null),
    FIRE_TRUNKS("settings.fire-protect-trunks", false, null),
    FIRE_CROWNS("settings.fire-protect-crowns", false, null),
    BIOME_PROFILES("settings.biome-profiles", true, null),
    WORLD_TREES("world-trees.enabled", true, null),
    SHAKING("shaking.enabled", true, null),
    GOLDEN_APPLES("shaking.golden-apples", true, SHAKING),
    HERBARIUM("herbarium.enabled", true, null),
    PLANTING("planting-helper.enabled", true, null),
    TIMBER("timber.enabled", true, null),
    TIMBER_REPLANT("timber.replant", true, TIMBER),
    TIMBER_PLAYER_CHOICE("timber.player-choice", true, TIMBER),
    REGROWTH("regrowth.enabled", true, TIMBER),
    FRUIT("fruit-trees.enabled", true, null),
    BREEDING("breeding.enabled", true, null),
    CROWN_CHOICE("crown-choice.enabled", true, null),
    GARDEN("garden.enabled", true, null),
    PREVIEW("preview.enabled", true, null),
    GUIDE("guide.enabled", true, null),
    /** Read once when the plugin starts. */
    TUTORIAL("tutorial.enabled", true, null);

    final String path;
    /** The value a missing key has (today's behaviour). */
    final boolean defaultOn;
    /** The switch this one needs besides the master switch, or null. */
    final Feature needs;

    Feature(String path, boolean defaultOn, Feature needs) {
        this.path = path;
        this.defaultOn = defaultOn;
        this.needs = needs;
    }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Language key of the readable name in {@code /vadmin features} and in "This needs ..." (1.47.1). */
    String label() { return "switch-names." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /** The switches as read from a config; a missing key keeps its default. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            for (Feature feature : values()) {
                boolean on = config == null ? feature.defaultOn : config.getBoolean(feature.path, feature.defaultOn);
                if (!on) switches.off.add(feature);
            }
            return switches;
        }

        /** Every switch at its default, except the given ones, which are off. */
        static Switches without(Feature... off) {
            Switches switches = read(null);
            switches.off.addAll(List.of(off));
            return switches;
        }

        /** On when its own switch, the switch it needs and trees.enabled are on. */
        boolean on(Feature feature) {
            if (off.contains(feature)) return false;
            if (feature != TREES && off.contains(TREES)) return false;
            return feature.needs == null || on(feature.needs);
        }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}
