package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GardenStateTest {
    @TempDir Path directory;

    @Test
    void roundTripsImmutableTreeAndPrunedDueTime() throws Exception {
        Path file = directory.resolve("garden.properties");
        GardenState first = new GardenState(file);
        GardenState.Tree tree = sampleTree();
        first.put(tree);

        GardenState loaded = new GardenState(file);
        assertTrue(loaded.healthy());
        assertEquals(List.of(tree), loaded.trees());
        assertThrows(UnsupportedOperationException.class, () -> loaded.trees().clear());

        GardenState.Tree pruned = new GardenState.Tree(tree.id(), tree.owner(), tree.world(), tree.root(),
                tree.rootMaterial(), List.of(new GardenState.Leaf(tree.leaves().getFirst().pos(),
                        tree.leaves().getFirst().blockData(), 1_798_765_432_123L)));
        loaded.put(pruned);
        assertEquals(pruned, new GardenState(file).trees().getFirst());
    }

    @Test
    void malformedExistingFileIsReadOnlyAndNeverRewritten() throws Exception {
        Path file = directory.resolve("garden.properties");
        byte[] original = "schema=1\ntree.count=0\nextra=refuse\n".getBytes();
        Files.write(file, original);
        GardenState state = new GardenState(file);

        assertFalse(state.healthy());
        assertTrue(state.trees().isEmpty());
        assertThrows(IOException.class, () -> state.put(sampleTree()));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void rejectsDuplicateIdsMalformedNumbersAndDuplicateLeaves() throws Exception {
        Path file = directory.resolve("garden.properties");
        String uuid = "11111111-1111-4111-8111-111111111111";
        String base = "schema=1\ntree.count=1\ntree.0.id=" + uuid + "\ntree.0.owner=" + uuid
                + "\ntree.0.world=" + uuid + "\ntree.0.root.x=01\ntree.0.root.y=64\ntree.0.root.z=0"
                + "\ntree.0.root.material=OAK_LOG\ntree.0.leaf.count=0\n";
        Files.writeString(file, base);
        assertFalse(new GardenState(file).healthy());

        Files.writeString(file, base.replace("root.x=01", "root.x=1")
                .replace("tree.0.leaf.count=0", "tree.0.leaf.count=0\ntree.1.id=" + uuid));
        assertFalse(new GardenState(file).healthy());

        String normalized = base.replace("root.x=01", "root.x=1");
        String secondTree = normalized.substring(normalized.indexOf("tree.0.id="));
        String duplicateIds = normalized.replace("tree.count=1", "tree.count=2")
                + secondTree.replace("tree.0.", "tree.1.");
        Files.writeString(file, duplicateIds);
        assertFalse(new GardenState(file).healthy());

        Files.writeString(file, base.replace("root.x=01", "root.x=1") + "schema=1\n");
        assertFalse(new GardenState(file).healthy());
    }

    @Test
    void enforcesValueAndCollectionBounds() {
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Pos(30_000_001, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Pos(0, 2048, 0));
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Leaf(
                new GardenState.Pos(0, 0, 0), " ", 0));
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Leaf(
                new GardenState.Pos(0, 0, 0), "minecraft:oak_leaves", -1));
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Tree(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new GardenState.Pos(0, 0, 0), "oak_log", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Tree(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new GardenState.Pos(0, 0, 0), "OAK_LOG", java.util.Collections.nCopies(513,
                        new GardenState.Leaf(new GardenState.Pos(0, 0, 0), "minecraft:oak_leaves", 0))));
        assertThrows(IllegalArgumentException.class, () -> new GardenState.Tree(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new GardenState.Pos(0, 0, 0), "OAK_LOG", List.of(
                        new GardenState.Leaf(new GardenState.Pos(0, 1, 0), "minecraft:oak_leaves", 0),
                        new GardenState.Leaf(new GardenState.Pos(0, 1, 0), "minecraft:oak_leaves", 0))));
    }

    @Test
    void raisedTreeLimitKeepsMoreThan128TreesAndLoadsThemAgain() throws Exception {
        Path file = directory.resolve("garden.properties");
        GardenState state = new GardenState(file);
        for (int i = 0; i < 200; i++) state.put(smallTree(i), 300);
        assertEquals(200, state.size());
        GardenState loaded = new GardenState(file);
        assertTrue(loaded.healthy());
        assertEquals(200, loaded.size());
        // the default limit refuses a new tree but keeps the loaded ones
        assertThrows(GardenState.FullException.class, () -> loaded.put(smallTree(500)));
        assertTrue(loaded.healthy());
        assertEquals(200, new GardenState(file).size());
    }

    @Test
    void treeLimitIsClampedAndDefaultIsTheOld128() throws Exception {
        assertEquals(16, GardenState.clampMaxTrees(0));
        assertEquals(1024, GardenState.clampMaxTrees(1_000_000));
        GardenState state = new GardenState(directory.resolve("garden.properties"));
        for (int i = 0; i < 128; i++) state.put(smallTree(i));
        assertThrows(GardenState.FullException.class, () -> state.put(smallTree(128)));
        assertTrue(state.healthy());
    }

    @Test
    void fileSizeLimitRefusesTheNewTreeAndKeepsGardensWorking() throws Exception {
        // Before 1.47.2 the 8 MB limit switched pruning and regrowth off for the rest of the run.
        Path file = directory.resolve("garden.properties");
        GardenState state = new GardenState(file);
        int stored = 0;
        GardenState.FullException refused = null;
        for (int i = 0; i < 200 && refused == null; i++) {
            try { state.put(bigTree(i), 1024); stored++; } catch (GardenState.FullException full) { refused = full; }
        }
        assertTrue(refused != null, "a full 8 MB file must be reached");
        assertTrue(state.healthy());
        assertEquals(stored, state.size());
        assertEquals(stored, new GardenState(file).size());
        assertTrue(Files.size(file) <= GardenState.MAX_FILE_BYTES);
        state.remove(state.trees().getFirst().id());
        assertEquals(stored - 1, new GardenState(file).size(), "writes still work after a refused tree");
    }

    private static GardenState.Tree smallTree(int index) {
        return new GardenState.Tree(new UUID(1, index), new UUID(2, 1), new UUID(3, 1), new GardenState.Pos(index, 70, 0),
                "OAK_LOG", List.of(new GardenState.Leaf(new GardenState.Pos(index, 75, 0), "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", 0)));
    }

    private static GardenState.Tree bigTree(int index) {
        List<GardenState.Leaf> leaves = new java.util.ArrayList<>();
        for (int i = 0; i < GardenState.MAX_LEAF_ROWS; i++)
            leaves.add(new GardenState.Leaf(new GardenState.Pos(index * 100 + i % 10, 80 + i / 100, i / 10 % 10),
                    "minecraft:dark_oak_leaves[distance=1,persistent=false,waterlogged=false]", 0));
        return new GardenState.Tree(new UUID(1, index), new UUID(2, 1), new UUID(3, 1), new GardenState.Pos(index * 100, 70, 0),
                "DARK_OAK_LOG", leaves);
    }

    private static GardenState.Tree sampleTree() {
        return new GardenState.Tree(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                new GardenState.Pos(100, 72, -200), "CHERRY_LOG",
                List.of(new GardenState.Leaf(new GardenState.Pos(101, 83, -199),
                        "minecraft:cherry_leaves[persistent=false,distance=1]", 0)));
    }

}
