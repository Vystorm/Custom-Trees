package at.esoren.customtrees;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FruitTreesTest {
    @Test
    void givenGrowthTime_whenRipen_thenOneMoreUpToMax() {
        assertEquals(0, FruitTrees.ripen(0, 1000, 999, 100, 6));
        assertEquals(1, FruitTrees.ripen(0, 1000, 1000, 100, 6));
        assertEquals(6, FruitTrees.ripen(6, 0, 5000, 100, 6));
        assertEquals(3, FruitTrees.ripen(9, 0, 5000, 100, 3), "a lowered max caps the stored fruit");
    }
}
