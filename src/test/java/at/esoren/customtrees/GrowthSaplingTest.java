package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class GrowthSaplingTest {
    @Test
    void commandCrownAcceptsDocumentedHyphensAndExistingUnderscores() {
        assertEquals(GrowthSapling.CrownForm.OPEN_GROWN, GrowthSapling.CrownForm.parseCommand("open-grown"));
        assertEquals(GrowthSapling.CrownForm.OPEN_GROWN, GrowthSapling.CrownForm.parseCommand("OPEN_GROWN"));
        assertEquals(GrowthSapling.CrownForm.FOREST, GrowthSapling.CrownForm.parseCommand("Forest"));
        assertThrows(IllegalArgumentException.class, () -> GrowthSapling.CrownForm.parseCommand("unknown"));
    }

    private static final GrowthSapling.Spec SPEC = new GrowthSapling.Spec(TreeSpecies.CHERRY,
            GrowthSapling.CrownForm.WINDSWEPT, GrowthSapling.GrowthClass.GIANT);

    @Test
    void writesLocalizedSingleSaplingMetadataAndReadsImmutableSpec() {
        Fixture fixture = new Fixture(Material.CHERRY_SAPLING, 1);
        var lang = LangFilesTest.bundle();
        ItemStack returned = GrowthSapling.write(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, fixture.item, lang, "de", SPEC);

        assertSame(fixture.item, returned);
        assertEquals(1, fixture.values.get(GrowthSapling.VERSION_KEY));
        assertEquals("WINDSWEPT", fixture.values.get(GrowthSapling.CROWN_KEY));
        assertEquals("GIANT", fixture.values.get(GrowthSapling.CLASS_KEY));
        assertEquals(SPEC, GrowthSapling.read(fixture.item).orElseThrow());
        verify(fixture.meta).displayName(eq(lang.component(
                "de", "growth-sapling.name", "species", "Kirsche")));
        verify(fixture.meta).lore(eq(List.of(
                lang.component("de", "growth-sapling.form", "form", "Windgebeugt"),
                lang.component("de", "growth-sapling.size", "growthClass", "Riesig"))));
    }

    @Test
    void readRejectsOrdinaryMaterialsUnknownVersionsAndMalformedEnumTags() {
        Fixture fixture = new Fixture(Material.CHERRY_SAPLING, 1);
        assertFalse(GrowthSapling.read(fixture.item).isPresent());
        fixture.values.put(GrowthSapling.VERSION_KEY, 2);
        fixture.values.put(GrowthSapling.CROWN_KEY, "WINDSWEPT");
        fixture.values.put(GrowthSapling.CLASS_KEY, "GIANT");
        assertFalse(GrowthSapling.read(fixture.item).isPresent());
        fixture.values.put(GrowthSapling.VERSION_KEY, 1);
        fixture.values.put(GrowthSapling.CROWN_KEY, "windswept");
        assertFalse(GrowthSapling.read(fixture.item).isPresent());
        fixture.values.put(GrowthSapling.CROWN_KEY, "WINDSWEPT");
        fixture.values.put(GrowthSapling.CLASS_KEY, "HEDGE");
        assertFalse(GrowthSapling.read(fixture.item).isPresent());

        Fixture ordinary = new Fixture(Material.OAK_LOG, 1);
        ordinary.values.put(GrowthSapling.VERSION_KEY, 1);
        ordinary.values.put(GrowthSapling.CROWN_KEY, "RANDOM");
        ordinary.values.put(GrowthSapling.CLASS_KEY, "NORMAL");
        assertTrue(GrowthSapling.read(ordinary.item).isEmpty());
    }

    @Test
    void readTreatsWrongPersistentDataTypesAsMalformed() {
        Fixture fixture = new Fixture(Material.CHERRY_SAPLING, 1);
        fixture.values.put(GrowthSapling.VERSION_KEY, "not an integer");
        assertTrue(GrowthSapling.read(fixture.item).isEmpty());

        fixture.values.put(GrowthSapling.VERSION_KEY, 1);
        fixture.values.put(GrowthSapling.CROWN_KEY, 42);
        fixture.values.put(GrowthSapling.CLASS_KEY, "GIANT");
        assertTrue(GrowthSapling.read(fixture.item).isEmpty());
    }

    @Test
    void writerRejectsStacksAndSpeciesMismatches() {
        Fixture stacked = new Fixture(Material.CHERRY_SAPLING, 2);
        assertThrows(IllegalArgumentException.class,
                () -> GrowthSapling.write(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, stacked.item, LangFilesTest.bundle(), "en", SPEC));
        Fixture mismatch = new Fixture(Material.OAK_SAPLING, 1);
        assertThrows(IllegalArgumentException.class,
                () -> GrowthSapling.write(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, mismatch.item, LangFilesTest.bundle(), "en", SPEC));
    }

    private static final class Fixture {
        final ItemStack item = mock(ItemStack.class);
        final ItemMeta meta = mock(ItemMeta.class);
        final PersistentDataContainer data = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, Object> values = new HashMap<>();

        Fixture(Material material, int amount) {
            when(item.getType()).thenReturn(material);
            when(item.getAmount()).thenReturn(amount);
            when(item.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(data);
            doAnswer(call -> {
                values.put(call.getArgument(0), call.getArgument(2));
                return null;
            }).when(data).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
            when(data.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                    .thenAnswer(call -> values.get(call.getArgument(0)));
        }
    }
}
