package at.esoren.customtrees;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForestRegrowthTest {
    @TempDir Path folder;

    private static List<Long> trunk(int side, int y, int height) {
        List<Long> logs = new ArrayList<>();
        for (int dy = 0; dy < height; dy++)
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) logs.add(TimberScanner.pack(10 + x, y + dy, 20 + z));
        return logs;
    }

    @Test
    void givenSquareTrunks_whenSpots_thenTheWholeBottomLayerIsPlanted() {
        assertEquals(1, ForestRegrowth.spots(trunk(1, 64, 6)).size());
        assertEquals(4, ForestRegrowth.spots(trunk(2, 64, 10)).size());
        assertEquals(9, ForestRegrowth.spots(trunk(3, 64, 20)).size());
        assertEquals(25, ForestRegrowth.spots(trunk(5, 64, 30)).size());
        assertTrue(ForestRegrowth.spots(trunk(3, 64, 20)).stream().allMatch(p -> TimberScanner.y(p) == 64));
    }

    @Test
    void givenUnevenBottom_whenSpots_thenOneSaplingNearTheCentre() {
        List<Long> logs = new ArrayList<>(trunk(1, 64, 5));
        logs.add(TimberScanner.pack(11, 64, 20)); // a root sticking out on the lowest layer
        logs.add(TimberScanner.pack(12, 64, 20));
        List<Long> spots = ForestRegrowth.spots(logs);
        assertEquals(1, spots.size());
        assertEquals(11, TimberScanner.x(spots.getFirst()));
    }

    @Test
    void givenWideFlatTrunk_whenSpots_thenNothing() {
        List<Long> logs = new ArrayList<>();
        for (int x = 0; x < 8; x++) logs.add(TimberScanner.pack(x, 70, 0));
        assertTrue(ForestRegrowth.spots(logs).isEmpty());
        assertTrue(ForestRegrowth.spots(List.of()).isEmpty());
    }

    @Test
    void givenPending_whenWrittenAndLoaded_thenItRoundTrips() throws Exception {
        Path file = folder.resolve("regrowth.yml");
        UUID world = UUID.randomUUID();
        var pending = List.of(new ForestRegrowth.Pending(world, ForestRegrowth.spots(trunk(3, 70, 4)), Material.SPRUCE_SAPLING, 12345L));
        ForestRegrowth.write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, file, pending);
        var loaded = ForestRegrowth.load(file);
        assertEquals(1, loaded.size());
        assertEquals(world, loaded.getFirst().world());
        assertEquals(Material.SPRUCE_SAPLING, loaded.getFirst().sapling());
        assertEquals(12345L, loaded.getFirst().due());
        assertEquals(pending.getFirst().spots(), loaded.getFirst().spots());
        ForestRegrowth.write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, file, List.of());
        assertFalse(Files.exists(file));
    }

    @Test
    void givenBrokenEntries_whenLoading_thenTheyAreSkipped() throws Exception {
        Path file = folder.resolve("regrowth.yml");
        Files.writeString(file, "pending:\n- world: not-a-uuid\n  sapling: OAK_SAPLING\n  due: 1\n  spots: ['1']\n- world: " + UUID.randomUUID()
                + "\n  sapling: OAK_SAPLING\n  due: 5\n  spots: ['7']\n");
        assertEquals(1, ForestRegrowth.load(file).size());
    }
}
