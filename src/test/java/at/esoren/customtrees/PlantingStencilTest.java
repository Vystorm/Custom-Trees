package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PlantingStencilTest {
    private static final StructurePos ORIGIN = new StructurePos(10, 64, 20);

    @Test void sizesParseInEverySpelling() {
        assertEquals(2, PlantingStencil.parseSize("2x2"));
        assertEquals(3, PlantingStencil.parseSize("3×3"));
        assertEquals(10, PlantingStencil.parseSize("10X10"));
        assertEquals(5, PlantingStencil.parseSize("5"));
        assertNull(PlantingStencil.parseSize("4x4"));
        assertNull(PlantingStencil.parseSize("2x3"));
        assertNull(PlantingStencil.parseSize("off"));
        assertNull(PlantingStencil.parseSize(""));
        assertNull(PlantingStencil.parseSize(null));
    }

    @Test void squareStartsAtThePlacedPlantAndGrowsForwardAndRight() {
        // Facing north (-z): right is east (+x).
        List<StructurePos> north = PlantingStencil.cells(ORIGIN, 0, -1, 2);
        assertEquals(List.of(ORIGIN, new StructurePos(11, 64, 20), new StructurePos(10, 64, 19), new StructurePos(11, 64, 19)), north);
        // Facing east (+x): right is south (+z).
        assertTrue(PlantingStencil.cells(ORIGIN, 1, 0, 2).contains(new StructurePos(11, 64, 21)));
        // Facing south (+z): right is west (-x); facing west (-x): right is north (-z).
        assertTrue(PlantingStencil.cells(ORIGIN, 0, 1, 2).contains(new StructurePos(9, 64, 21)));
        assertTrue(PlantingStencil.cells(ORIGIN, -1, 0, 2).contains(new StructurePos(9, 64, 19)));
        assertThrows(IllegalArgumentException.class, () -> PlantingStencil.cells(ORIGIN, 1, 1, 2));
    }

    @Test void everySizeCoversADistinctSquareOnOneLevel() {
        for (int size : PlantingStencil.SIZES) {
            List<StructurePos> cells = PlantingStencil.cells(ORIGIN, 0, 1, size);
            assertEquals(size * size, cells.size());
            assertEquals(size * size, new HashSet<>(cells).size());
            assertTrue(cells.stream().allMatch(cell -> cell.y() == 64));
            assertEquals(size - 1, cells.stream().mapToInt(StructurePos::z).max().orElseThrow() - ORIGIN.z());
        }
    }

    @Test void oneBlockedSpotStopsTheWholeSquare() {
        List<StructurePos> cells = PlantingStencil.cells(ORIGIN, 0, -1, 3);
        StructurePos rock = new StructurePos(11, 64, 19);
        Set<StructurePos> planted = Set.of(ORIGIN, new StructurePos(12, 64, 20));
        var plan = PlantingStencil.plan(cells, cell -> cell.equals(rock) ? PlantingStencil.Cell.BLOCKED
                : planted.contains(cell) ? PlantingStencil.Cell.SAME : PlantingStencil.Cell.FREE);
        assertFalse(plan.ready());
        assertEquals(List.of(rock), plan.blocked());
        assertEquals(2, plan.already());
        assertEquals(6, plan.free().size());
        var clear = PlantingStencil.plan(cells, cell -> planted.contains(cell) ? PlantingStencil.Cell.SAME : PlantingStencil.Cell.FREE);
        assertTrue(clear.ready());
        assertEquals(7, clear.free().size());
    }

    @Test void onlyFormationPlantsAreHandled() {
        assertTrue(PlantingStencil.plantable(Material.OAK_SAPLING));
        assertTrue(PlantingStencil.plantable(Material.MANGROVE_PROPAGULE));
        assertTrue(PlantingStencil.plantable(Material.WARPED_FUNGUS));
        assertTrue(PlantingStencil.plantable(Material.RED_MUSHROOM));
        assertFalse(PlantingStencil.plantable(Material.WHEAT_SEEDS));
        assertFalse(PlantingStencil.plantable(Material.AZALEA));
        assertFalse(PlantingStencil.plantable(Material.OAK_LOG));
    }

    @Test void optionsHaveSafeDefaultsAndBounds() {
        var defaults = PlantingStencil.Options.read(new YamlConfiguration());
        assertTrue(defaults.enabled());
        assertEquals(10, defaults.maxSize());
        assertFalse(defaults.keepActive());
        assertEquals(300_000, defaults.timeoutMillis());
        assertEquals(List.of(2, 3, 5, 10), defaults.sizes());
        YamlConfiguration config = new YamlConfiguration();
        config.set("planting-helper.max-size", 4);
        config.set("planting-helper.timeout-seconds", 1);
        var bounded = PlantingStencil.Options.read(config);
        assertEquals(3, bounded.maxSize());
        assertEquals(List.of(2, 3), bounded.sizes());
        assertEquals(10_000, bounded.timeoutMillis());
        config.set("planting-helper.max-size", 0);
        assertEquals(2, PlantingStencil.Options.read(config).maxSize());
        assertEquals("CustomTrees planting-helper: enabled=true, max-size=10, keep-active=false, squares=3, plants=40, active-now=1",
                PlantingStencil.statusLine(defaults, 3, 40, 1));
    }

    @Test void menuResultsExistForEverySize() {
        var lang = LangFilesTest.bundle();
        for (String language : List.of("en", "de")) for (int size : PlantingStencil.SIZES) {
            String key = "planting.result." + size + "x" + size;
            assertNotEquals(key, lang.plain(language, key));
        }
        assertEquals("Planting helper: you need 3 more of this plant for the 5×5 square.",
                LangFilesTest.bundle().plain("en", "planting.missing", "missing", 3, "size", 5));
        assertEquals(List.of("2x2", "3x3", "5x5", "10x10", "hedge4", "hedge8", "hedge16", "off"), PlantingHelper.sizeNames());
    }
}
