package at.esoren.customtrees;

import java.util.List;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneratedTreeSkipTest {
    @Test
    void generatorPluginsAreMatchedIgnoringCase() {
        assertTrue(GeneratedTreeListener.skipsGenerator("VystormStrata", List.of("vystormstrata")));
        assertFalse(GeneratedTreeListener.skipsGenerator("Iris", List.of("VystormStrata")));
        assertFalse(GeneratedTreeListener.skipsGenerator("VystormStrata", List.of()));
    }

    @Test
    void worldsWithoutCustomGeneratorAreNotSkipped() {
        World world = mock(World.class);
        when(world.getGenerator()).thenReturn(null);
        assertFalse(GeneratedTreeListener.placesOwnTrees(world, List.of("VystormStrata")));
    }
}
