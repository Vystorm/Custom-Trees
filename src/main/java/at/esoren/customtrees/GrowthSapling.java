package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;

/** Item metadata for a planted sapling with a bounded, named growth profile. */
final class GrowthSapling {
    private static final int VERSION = 1;
    static final NamespacedKey VERSION_KEY = new NamespacedKey("customtrees", "growth_sapling_version");
    static final NamespacedKey CROWN_KEY = new NamespacedKey("customtrees", "growth_sapling_crown");
    static final NamespacedKey CLASS_KEY = new NamespacedKey("customtrees", "growth_sapling_class");

    enum GrowthClass { NORMAL, MEDIUM, GIANT, COLOSSAL }
    enum CrownForm { RANDOM, OPEN_GROWN, FOREST, WINDSWEPT, ANCIENT;
        static CrownForm parseCommand(String value) {
            return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    record Spec(TreeSpecies species, CrownForm crownForm, GrowthClass growthClass) {
        Spec {
            Objects.requireNonNull(species, "species");
            Objects.requireNonNull(crownForm, "crownForm");
            Objects.requireNonNull(growthClass, "growthClass");
        }
    }

    private GrowthSapling() { }

    /** Creates exactly one tagged sapling. This method does not award or remove inventory items. */
    static ItemStack create(PlatformCompatInterface platform, Lang lang, String language, TreeSpecies species,
            CrownForm crownForm, GrowthClass growthClass) {
        Spec spec = new Spec(species, crownForm, growthClass);
        return write(platform, new ItemStack(species.sapling, 1), lang, language, spec);
    }

    /** Writes metadata onto a single sapling stack; package-private to allow server-free metadata tests. */
    static ItemStack write(PlatformCompatInterface platform, ItemStack item, Lang lang, String language, Spec spec) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(spec, "spec");
        if (item.getAmount() != 1 || TreeSpecies.fromSapling(item.getType()) != spec.species()) {
            throw new IllegalArgumentException("Growth profile must tag exactly one matching sapling");
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Sapling has no item metadata");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(VERSION_KEY, PersistentDataType.INTEGER, VERSION);
        data.set(CROWN_KEY, PersistentDataType.STRING, spec.crownForm().name());
        data.set(CLASS_KEY, PersistentDataType.STRING, spec.growthClass().name());

        String speciesKey = "growth-sapling.species." + spec.species().name().toLowerCase(Locale.ROOT);
        String crownKey = "growth-sapling.crown." + spec.crownForm().name().toLowerCase(Locale.ROOT);
        String classKey = "growth-sapling.class." + spec.growthClass().name().toLowerCase(Locale.ROOT);
        Component name = lang.component(language, "growth-sapling.name",
                "species", lang.plain(language, speciesKey));
        List<Component> lore = List.of(
                lang.component(language, "growth-sapling.form",
                        "form", lang.plain(language, crownKey)),
                lang.component(language, "growth-sapling.size",
                        "growthClass", lang.plain(language, classKey)));
        platform.displayName(meta, name);
        platform.lore(meta, lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Reads only version 1 tags with a real vanilla sapling species and exact enum names. */
    static Optional<Spec> read(ItemStack item) {
        if (item == null) return Optional.empty();
        TreeSpecies species = TreeSpecies.fromSapling(item.getType());
        if (species == null) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Optional.empty();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        try {
            Integer version = data.get(VERSION_KEY, PersistentDataType.INTEGER);
            if (version == null || version != VERSION) return Optional.empty();
            String crownName = data.get(CROWN_KEY, PersistentDataType.STRING);
            String className = data.get(CLASS_KEY, PersistentDataType.STRING);
            if (crownName == null || className == null) return Optional.empty();
            CrownForm crown = CrownForm.valueOf(crownName);
            GrowthClass growthClass = GrowthClass.valueOf(className);
            return Optional.of(new Spec(species, crown, growthClass));
        } catch (RuntimeException malformedData) {
            return Optional.empty();
        }
    }
}
