package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;

import at.esoren.customtrees.TimberScanner.Cell;
import at.esoren.customtrees.TimberScanner.Verdict;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TimberScannerTest {
    /** A small world: everything inside the box is air unless set; grass at y = 63 and below. */
    static final class World implements TimberScanner.View {
        final Map<Long, Cell> cells = new HashMap<>();
        int minX = -40, maxX = 40, minZ = -40, maxZ = 40;

        @Override public Cell at(int x, int y, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return null;
            Cell cell = cells.get(TimberScanner.pack(x, y, z));
            if (cell != null) return cell;
            return Cell.of(y <= 63 ? Material.GRASS_BLOCK : Material.AIR);
        }

        World set(int x, int y, int z, Cell cell) { cells.put(TimberScanner.pack(x, y, z), cell); return this; }
        World log(int x, int y, int z) { return set(x, y, z, new Cell(Material.OAK_LOG, Axis.Y, false, 0, false)); }
        World leaf(int x, int y, int z, int distance) { return set(x, y, z, new Cell(Material.OAK_LEAVES, null, false, distance, false)); }

        /** Vanilla-like oak: trunk at (x, 64..64+height-1), a leaf blob around the top with correct distances. */
        World oak(int x, int z, int height) {
            for (int y = 64; y < 64 + height; y++) log(x, y, z);
            int top = 64 + height - 1;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 1; dy++) {
                if (dx == 0 && dz == 0 && dy <= 0) continue;
                int distance = Math.abs(dx) + Math.abs(dz) + Math.max(0, dy);
                if (distance > 4) continue;
                leaf(x + dx, top + dy, z + dz, Math.max(1, distance));
            }
            return this;
        }
    }

    static final TimberScanner.Limits LIMITS = new TimberScanner.Limits(10_000, 32, 64, 4, true);

    @Test void naturalOakIsATree() {
        World world = new World().oak(0, 0, 5);
        var result = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(5, result.logs().size());
        assertTrue(result.crown().size() > 20, "crown " + result.crown().size());
        assertEquals(64, result.cutY());
        assertEquals(TimberOptions.SizeClass.SMALL, result.size());
        assertEquals(0.5, result.pivotX(), 1e-9);
        assertEquals("OAK", result.family());
    }

    @Test void choppingHighOnTheTrunkStillFellsTheWholeTreeWithFirstLog() {
        World world = new World().oak(0, 0, 6);
        var result = TimberScanner.scan(world, 0, 67, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(64, result.cutY());
        assertEquals(6, result.logs().size());
    }

    @Test void percentCutLeavesTheStump() {
        World world = new World().oak(0, 0, 6);
        var result = TimberScanner.scan(world, 0, 66, 0, TimberOptions.Trigger.PERCENT, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(66, result.cutY());
        assertEquals(4, result.logs().size(), "logs 66..69 fall, 64 and 65 stay");
        assertFalse(result.logs().contains(TimberScanner.pack(0, 65, 0)));
    }

    @Test void logsNextToPlanksAreAPlayerBuild() {
        World world = new World().oak(0, 0, 5).set(1, 65, 0, Cell.of(Material.OAK_PLANKS));
        assertEquals(Verdict.FOREIGN_BLOCK, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
        World torch = new World().oak(0, 0, 5).set(0, 66, 1, Cell.of(Material.WALL_TORCH));
        assertEquals(Verdict.FOREIGN_BLOCK, TimberScanner.scan(torch, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
    }

    @Test void logPillarWithoutNaturalLeavesIsNoTree() {
        World world = new World();
        for (int y = 64; y < 70; y++) world.log(0, y, 0);
        assertEquals(Verdict.NO_CROWN, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
        // Player-placed leaves are persistent and do not count either.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) world.set(dx, 69, dz, new Cell(Material.OAK_LEAVES, null, true, 1, false));
        assertEquals(Verdict.NO_CROWN, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
    }

    @Test void customTreesMarkedLeavesCountEvenWhenPersistent() {
        World world = new World();
        for (int y = 64; y < 70; y++) world.log(0, y, 0);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (dx != 0 || dz != 0) world.set(dx, 69, dz, new Cell(Material.OAK_LEAVES, null, true, 1, true));
        var result = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(8, result.markedBlocks());
    }

    @Test void lyingTrunkIsABuild() {
        World world = new World().oak(0, 0, 5);
        for (int y = 64; y < 69; y++) world.set(0, y, 0, new Cell(Material.OAK_LOG, Axis.X, false, 0, false));
        assertEquals(Verdict.LYING_TRUNK, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
    }

    @Test void floatingTrunkIsNotGrounded() {
        World world = new World().oak(0, 0, 5);
        for (int y = 64; y < 69; y++) world.cells.remove(TimberScanner.pack(0, y, 0));
        for (int y = 64; y < 69; y++) world.log(0, y + 10, 0);
        // Air below the lowest log: no ground anywhere.
        for (int y = 0; y < 3; y++) world.leaf(0, 79 + y, 1, 1);
        world.leaf(1, 78, 0, 1); world.leaf(-1, 78, 0, 1); world.leaf(0, 78, -1, 1); world.leaf(0, 79, 0, 1);
        assertEquals(Verdict.NOT_GROUNDED, TimberScanner.scan(world, 0, 76, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
        var relaxed = new TimberScanner.Limits(10_000, 32, 64, 4, false);
        assertEquals(Verdict.TREE, TimberScanner.scan(world, 0, 76, 0, TimberOptions.Trigger.FIRST_LOG, relaxed).verdict());
    }

    @Test void strippedLogsAreNotTrees() {
        World world = new World().oak(0, 0, 5).set(0, 64, 0, new Cell(Material.STRIPPED_OAK_LOG, Axis.Y, false, 0, false));
        assertEquals(Verdict.NOT_A_LOG, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
        // A stripped log inside the trunk is not natural next to the others.
        World inside = new World().oak(0, 0, 6).set(0, 66, 0, new Cell(Material.STRIPPED_OAK_LOG, Axis.Y, false, 0, false));
        assertEquals(Verdict.FOREIGN_BLOCK, TimberScanner.scan(inside, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
    }

    @Test void leavesCloserToANeighbourTreeStay() {
        World world = new World().oak(0, 0, 5).oak(6, 0, 5);
        // The shared middle column x = 3 belongs to whichever trunk is nearer; give x = 4 leaves to the right tree.
        var left = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, left.verdict());
        assertEquals(5, left.logs().size());
        assertFalse(left.crown().contains(TimberScanner.pack(4, 68, 0)), "leaf at distance 2 from the right trunk");
        assertTrue(left.crown().contains(TimberScanner.pack(2, 68, 0)));
    }

    @Test void giantTrunkCrossSectionSetsTheSize() {
        World world = new World();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 64; y < 80; y++) world.log(dx, y, dz);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1) world.leaf(dx, 79, dz, Math.max(Math.abs(dx), Math.abs(dz)) - 1);
        var result = TimberScanner.scan(world, 1, 64, 1, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(9, result.slice().size());
        assertEquals(TimberOptions.SizeClass.GIANT, result.size());
        assertEquals(0.5, result.pivotX(), 1e-9);
    }

    @Test void branchChopFindsTheTrunkBase() {
        World world = new World().oak(0, 0, 6);
        world.set(1, 68, 0, new Cell(Material.OAK_LOG, Axis.X, false, 0, false));
        world.set(2, 68, 0, new Cell(Material.OAK_LOG, Axis.X, false, 0, false));
        var result = TimberScanner.scan(world, 2, 68, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(64, result.cutY());
        assertEquals(8, result.logs().size());
    }

    @Test void buriedRootsStayExposedPropRootsFall() {
        World world = new World().oak(0, 0, 6);
        world.log(1, 63, 0);           // root in the ground, no open face -> stays
        world.set(1, 64, 0, TimberScanner.Cell.of(Material.DIRT));
        world.log(-2, 64, 0);          // prop root standing in air next to the trunk base
        world.log(-1, 65, 0);
        var result = TimberScanner.scan(world, 0, 66, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertFalse(result.logs().contains(TimberScanner.pack(1, 63, 0)));
        assertTrue(result.logs().contains(TimberScanner.pack(-1, 65, 0)));
        assertTrue(result.logs().contains(TimberScanner.pack(-2, 64, 0)));
    }

    @Test void limitsAreRespected() {
        World world = new World().oak(0, 0, 30);
        var small = new TimberScanner.Limits(10, 32, 64, 4, true);
        assertEquals(Verdict.TOO_LARGE, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, small).verdict());
        var low = new TimberScanner.Limits(10_000, 32, 10, 4, true);
        assertEquals(Verdict.TOO_LARGE, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, low).verdict());
    }

    /** Acacia-like: straight trunk, then a diagonal branch reaching {@code reach} blocks sideways with a leaf pad. */
    static World acacia(World world, int reach) {
        for (int y = 64; y < 68; y++) world.set(0, y, 0, new Cell(Material.ACACIA_LOG, Axis.Y, false, 0, false));
        int top = 67;
        for (int i = 1; i <= reach; i++) world.set(i, top + Math.min(i, 4), 0, new Cell(Material.ACACIA_LOG, Axis.Y, false, 0, false));
        int tipY = top + Math.min(reach, 4);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int distance = Math.max(1, Math.abs(dx) + Math.abs(dz));
            if (distance <= 3) world.set(reach + dx, tipY + 1, dz, new Cell(Material.ACACIA_LEAVES, null, false, distance, false));
        }
        world.set(0, top + 1, 0, new Cell(Material.ACACIA_LEAVES, null, false, 1, false));
        return world;
    }

    @Test void wideAcaciaBeyondTheFirstScanRadiusAsksForAWiderViewNotTooLarge() {
        World world = acacia(new World(), 15);
        // First, cheap scan with radius 12 (configured maximum 64): the far branch is known but outside that radius.
        var firstPass = new TimberScanner.Limits(10_000, 12, 64, 4, true, 64);
        assertEquals(Verdict.OUTSIDE_VIEW, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, firstPass).verdict());
        // With the full radius the same acacia is a normal tree.
        var full = new TimberScanner.Limits(10_000, 64, 64, 4, true, 64);
        var result = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, full);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals("ACACIA", result.family());
    }

    @Test void everyVanillaWoodTypeIsRecognisedAsATree() {
        String[] woods = {"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "PALE_OAK", "AZALEA"};
        int checked = 0;
        for (String wood : woods) {
            Material log = Material.matchMaterial(wood.equals("AZALEA") ? "OAK_LOG" : wood + "_LOG");
            Material leaves = Material.matchMaterial(wood + "_LEAVES");
            if (log == null || leaves == null) continue; // not in this API version
            World world = new World();
            for (int y = 64; y < 69; y++) world.set(0, y, 0, new Cell(log, Axis.Y, false, 0, false));
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dz == 0 && dy <= 0) continue;
                int distance = Math.abs(dx) + Math.abs(dz) + Math.max(0, dy);
                if (distance <= 4) world.set(dx, 68 + dy, dz, new Cell(leaves, null, false, Math.max(1, distance), false));
            }
            var result = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
            assertEquals(Verdict.TREE, result.verdict(), wood + " with " + leaves);
            checked++;
        }
        assertTrue(checked >= 8, "only " + checked + " wood types available");
    }

    @Test void treeWiderThanTheConfiguredMaximumIsStillTooLarge() {
        World world = acacia(new World(), 15);
        var capped = new TimberScanner.Limits(10_000, 12, 64, 4, true, 12);
        assertEquals(Verdict.TOO_LARGE, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, capped).verdict());
    }

    @Test void unknownBlocksNextToTheTreeAskForAWiderView() {
        World world = new World().oak(0, 0, 5);
        world.maxX = 0;
        world.log(1, 66, 0);
        assertEquals(Verdict.OUTSIDE_VIEW, TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS).verdict());
    }

    @Test void vinesAndCocoaGoDownWithTheTree() {
        World world = new World().oak(0, 0, 5);
        world.set(0, 65, 1, Cell.of(Material.COCOA));
        world.set(1, 66, 0, Cell.of(Material.VINE)).set(1, 65, 0, Cell.of(Material.VINE));
        var result = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertTrue(result.attachments().contains(TimberScanner.pack(0, 65, 1)));
        assertTrue(result.attachments().contains(TimberScanner.pack(1, 65, 0)));
    }

    @Test void netherFungusUsesWartBlocksAsCrown() {
        World world = new World();
        for (int y = 64; y < 70; y++) world.set(0, y, 0, new Cell(Material.CRIMSON_STEM, Axis.Y, false, 0, false));
        world.set(0, 63, 0, Cell.of(Material.CRIMSON_NYLIUM));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) world.set(dx, 70, dz, Cell.of(Material.NETHER_WART_BLOCK));
        var result = TimberScanner.scan(world, 0, 64, 0, TimberOptions.Trigger.FIRST_LOG, LIMITS);
        assertEquals(Verdict.TREE, result.verdict());
        assertEquals(9, result.crown().size());
    }

    @Test void packingRoundTrips() {
        for (int[] p : new int[][] {{0, 0, 0}, {-1, -64, -1}, {30_000_000, 319, -30_000_000}, {12345, -2048, -54321}}) {
            long packed = TimberScanner.pack(p[0], p[1], p[2]);
            assertEquals(p[0], TimberScanner.x(packed));
            assertEquals(p[1], TimberScanner.y(packed));
            assertEquals(p[2], TimberScanner.z(packed));
        }
    }
}
