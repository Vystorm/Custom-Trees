package at.esoren.customtrees;

import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable coal prices selected by the formation being grown. */
record CoalGrowthCosts(int normal, int medium, int giant, int colossal, int hedge) {
    static final int MIN_COST = 1;
    static final int MAX_COST = 64;

    enum GrowthClass { NORMAL, MEDIUM, GIANT, COLOSSAL, HEDGE }

    CoalGrowthCosts {
        validate("normal", normal);
        validate("medium", medium);
        validate("giant", giant);
        validate("colossal", colossal);
        validate("hedge", hedge);
    }

    static CoalGrowthCosts defaults() {
        return new CoalGrowthCosts(1, 1, 1, 1, 1);
    }

    static CoalGrowthCosts load(FileConfiguration config) {
        if (config.contains("settings.coal-costs")
                && config.getConfigurationSection("settings.coal-costs") == null) {
            throw new TreeSettingsException("validation.coal-costs-section");
        }
        return new CoalGrowthCosts(
                read(config, "normal"), read(config, "medium"), read(config, "giant"),
                read(config, "colossal"), read(config, "hedge"));
    }

    int cost(GrowthClass growthClass) {
        return switch (Objects.requireNonNull(growthClass, "growthClass")) {
            case NORMAL -> normal;
            case MEDIUM -> medium;
            case GIANT -> giant;
            case COLOSSAL -> colossal;
            case HEDGE -> hedge;
        };
    }

    private static int read(FileConfiguration config, String growthClass) {
        String path = "settings.coal-costs." + growthClass;
        Object raw = config.get(path);
        if (raw == null) return 1;
        if (!(raw instanceof Number number)) {
            throw invalid(growthClass);
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < MIN_COST || value > MAX_COST) {
            throw invalid(growthClass);
        }
        return (int) value;
    }

    private static void validate(String growthClass, int value) {
        if (value < MIN_COST || value > MAX_COST) throw invalid(growthClass);
    }

    private static TreeSettingsException invalid(String growthClass) {
        return new TreeSettingsException("validation.coal-growth-cost",
                "growthClass", growthClass, "min", MIN_COST, "max", MAX_COST);
    }
}
