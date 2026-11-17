package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionShape;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class TimberLogicTest {
    private static YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                TimberLogicTest.class.getClassLoader().getResourceAsStream(name), name), StandardCharsets.UTF_8));
    }

    // ---- options ----

    @Test void bundledConfigMatchesTheDefaults() {
        assertEquals(TimberOptions.DEFAULTS, TimberOptions.read(resource("config.yml")));
        assertEquals(TimberOptions.DEFAULTS, TimberOptions.read(new YamlConfiguration()));
    }

    @Test void brokenValuesFallBackOrClamp() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("timber.mode", "sideways");
        yaml.set("timber.trigger", "percent");
        yaml.set("timber.trunk-percent", 500);
        yaml.set("timber.sneaking", true);
        yaml.set("timber.tree-types.world", "disabled");
        yaml.set("timber.tool.durability-per-log", -3.0);
        yaml.set("timber.other-timber-plugins", "run-both");
        TimberOptions options = TimberOptions.read(yaml);
        assertEquals(TimberOptions.Mode.FALL, options.mode());
        assertEquals(TimberOptions.Trigger.PERCENT, options.trigger());
        assertEquals(100, options.trunkPercent());
        assertEquals(TimberOptions.Sneaking.STANDING, options.sneaking());
        assertEquals(TimberOptions.TypeMode.DISABLED, options.treeTypes().get(TimberOptions.SizeClass.WORLD));
        assertEquals(0.0, options.durabilityPerLog());
        assertFalse(options.pauseForOtherTimber());
    }

    @Test void modePrecedence() {
        TimberOptions options = TimberOptions.DEFAULTS;
        assertEquals(TimberOptions.Mode.FALL, options.effectiveMode(TimberOptions.SizeClass.SMALL, TimberOptions.PlayerMode.SERVER));
        assertEquals(TimberOptions.Mode.DROP, options.effectiveMode(TimberOptions.SizeClass.SMALL, TimberOptions.PlayerMode.DROP));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("timber.tree-types.world", "drop");
        yaml.set("timber.tree-types.giant", "disabled");
        yaml.set("timber.player-choice", false);
        TimberOptions forced = TimberOptions.read(yaml);
        assertEquals(TimberOptions.Mode.DROP, forced.effectiveMode(TimberOptions.SizeClass.WORLD, TimberOptions.PlayerMode.FALL));
        assertNull(forced.effectiveMode(TimberOptions.SizeClass.GIANT, TimberOptions.PlayerMode.FALL));
        assertEquals(TimberOptions.Mode.FALL, forced.effectiveMode(TimberOptions.SizeClass.SMALL, TimberOptions.PlayerMode.DROP),
                "player choice ignored when the server does not allow it");
    }

    @Test void sneakingRuleAndWorlds() {
        assertTrue(TimberOptions.Sneaking.STANDING.allows(false));
        assertFalse(TimberOptions.Sneaking.STANDING.allows(true));
        assertTrue(TimberOptions.Sneaking.SNEAKING.allows(true));
        assertTrue(TimberOptions.Sneaking.ANY.allows(true));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("timber.worlds", List.of("world", "Farm"));
        TimberOptions options = TimberOptions.read(yaml);
        assertTrue(options.worldAllowed("farm"));
        assertFalse(options.worldAllowed("world_nether"));
        assertTrue(TimberOptions.DEFAULTS.worldAllowed("anything"));
    }

    @Test void sizeClasses() {
        assertEquals(TimberOptions.SizeClass.SMALL, TimberOptions.SizeClass.of(4, 300));
        assertEquals(TimberOptions.SizeClass.GIANT, TimberOptions.SizeClass.of(9, 3000));
        assertEquals(TimberOptions.SizeClass.COLOSSAL, TimberOptions.SizeClass.of(21, 9000));
        assertEquals(TimberOptions.SizeClass.WORLD, TimberOptions.SizeClass.of(80, 9000));
        assertEquals(TimberOptions.SizeClass.WORLD, TimberOptions.SizeClass.of(9, 50_000));
    }

    // ---- percent trigger ----

    @Test void requiredCutsRoundUp() {
        assertEquals(1, TimberOptions.requiredCuts(1, 80));
        assertEquals(4, TimberOptions.requiredCuts(4, 80));
        assertEquals(8, TimberOptions.requiredCuts(9, 80));
        assertEquals(7, TimberOptions.requiredCuts(8, 80));
        assertEquals(9, TimberOptions.requiredCuts(9, 100));
        assertEquals(1, TimberOptions.requiredCuts(9, 1));
        assertEquals(4, TimberOptions.requiredCuts(8, 50));
    }

    private static TimberScanner.Result plan(int sliceSize) {
        List<Long> slice = new ArrayList<>();
        for (int i = 0; i < sliceSize; i++) slice.add(TimberScanner.pack(i % 3, 70, i / 3));
        return new TimberScanner.Result(TimberScanner.Verdict.TREE, "OAK", slice, List.of(), List.of(), slice, 70, 1.5, 1.5, 0,
                TimberOptions.SizeClass.GIANT);
    }

    @Test void percentTriggerCountsEveryCutOnce() {
        ChopProgress progress = new ChopProgress();
        UUID world = UUID.randomUUID();
        TimberScanner.Result plan = plan(9);
        ChopProgress.Cut cut = progress.start(world, plan, plan.slice().get(0), 80, 0, 60_000);
        assertEquals(8, cut.required);
        assertEquals(1, cut.chopped());
        for (int i = 1; i < 7; i++) {
            ChopProgress.Cut found = progress.find(world, plan.slice().get(i), 1000);
            assertSame(cut, found);
            progress.chop(found, plan.slice().get(i), 1000, 60_000);
            assertFalse(cut.ready());
        }
        progress.chop(cut, plan.slice().get(1), 1000, 60_000);   // same log again
        assertEquals(7, cut.chopped());
        progress.chop(progress.find(world, plan.slice().get(7), 1000), plan.slice().get(7), 1000, 60_000);
        assertTrue(cut.ready());
        assertNull(progress.find(world, plan.slice().get(8), 1000), "a felled cut is forgotten");
        assertNull(progress.find(UUID.randomUUID(), plan.slice().get(0), 1000));
    }

    @Test void percentRecordsExpireAndAreBounded() {
        ChopProgress progress = new ChopProgress();
        UUID world = UUID.randomUUID();
        TimberScanner.Result plan = plan(9);
        progress.start(world, plan, plan.slice().get(0), 80, 0, 1000);
        assertNotNull(progress.find(world, plan.slice().get(1), 500));
        assertNull(progress.find(world, plan.slice().get(1), 5000));
        for (int i = 0; i < ChopProgress.MAX_RECORDS + 10; i++) {
            long position = TimberScanner.pack(i * 10, 70, 0);
            var single = new TimberScanner.Result(TimberScanner.Verdict.TREE, "OAK", List.of(position), List.of(), List.of(),
                    List.of(position, TimberScanner.pack(i * 10 + 1, 70, 0)), 70, 0, 0, 0, TimberOptions.SizeClass.SMALL);
            progress.start(world, single, position, 100, 0, 60_000);
        }
        assertEquals(ChopProgress.MAX_RECORDS, progress.size());
    }

    // ---- fall direction maths ----

    @Test void yawToDirection() {
        double[] south = TimberMath.direction(0), west = TimberMath.direction(90), north = TimberMath.direction(180);
        assertArrayEquals(new double[] {0, 1}, south, 1e-9);
        assertArrayEquals(new double[] {-1, 0}, west, 1e-9);
        assertArrayEquals(new double[] {0, -1}, north, 1e-9);
    }

    @Test void treeFallsAwayFromThePlayer() {
        // Player south of the trunk (z = 5) looking north: the tree falls north (-z).
        double[] direction = TimberMath.fallDirection(0.5, 5.5, 180f, 0.5, 0.5);
        assertTrue(direction[1] < -0.9, "falls north: " + direction[1]);
        // Inside the trunk column the view decides.
        assertArrayEquals(TimberMath.direction(90), TimberMath.fallDirection(0.5, 0.6, 90f, 0.5, 0.5), 1e-9);
    }

    @Test void rotationTipsTheTopIntoTheFallDirection() {
        double[] direction = {1, 0};
        double[] axis = TimberMath.axis(direction);
        double[] top = TimberMath.rotate(new double[] {0, 10, 0}, axis, Math.toRadians(90));
        assertArrayEquals(new double[] {10, 0, 0}, top, 1e-9);
        double[] half = TimberMath.rotate(new double[] {0, 10, 0}, axis, Math.toRadians(45));
        assertEquals(10 / Math.sqrt(2), half[0], 1e-9);
        assertEquals(10 / Math.sqrt(2), half[1], 1e-9);
    }

    @Test void displayQuaternionMatchesTheRotation() {
        double[] axis = TimberMath.axis(TimberMath.direction(37));
        float[] q = TimberMath.quaternion(axis, Math.toRadians(63));
        Vector3f rotated = new Vector3f(1, 7, -2).rotate(new Quaternionf(q[0], q[1], q[2], q[3]));
        double[] expected = TimberMath.rotate(new double[] {1, 7, -2}, axis, Math.toRadians(63));
        assertEquals(expected[0], rotated.x, 1e-4);
        assertEquals(expected[1], rotated.y, 1e-4);
        assertEquals(expected[2], rotated.z, 1e-4);
    }

    @Test void obstaclesStopTheArc() {
        List<double[]> trunk = new ArrayList<>();
        for (int y = 0; y < 10; y++) trunk.add(new double[] {0, y + 0.5, 0});
        double[] pivot = {0.5, 64, 0.5};
        double[] axis = TimberMath.axis(new double[] {1, 0});
        assertEquals(90, TimberMath.stopAngle(trunk, pivot, axis, 90, 5, (x, y, z) -> y < 64), 1e-9);
        // A wall 4 blocks east, 6 high stops the fall early.
        double stopped = TimberMath.stopAngle(trunk, pivot, axis, 90, 5, (x, y, z) -> y < 64 || (x == 4 && y < 70));
        assertTrue(stopped > 0 && stopped < 60, "stopped at " + stopped);
        assertEquals(0, TimberMath.stopAngle(trunk, pivot, axis, 90, 5, (x, y, z) -> true), 1e-9);
    }

    @Test void fallTimingAccelerates() {
        assertEquals(40, TimberMath.fallTicks(90, 40));
        assertTrue(TimberMath.fallTicks(30, 40) < 40);
        assertEquals(0, TimberMath.angleAt(0, 40, 90), 1e-9);
        assertEquals(22.5, TimberMath.angleAt(20, 40, 90), 1e-9);
        assertEquals(90, TimberMath.angleAt(40, 40, 90), 1e-9);
    }

    @Test void proxyKeepsSmallTreesAndMergesHugeOnes() {
        List<int[]> blocks = new ArrayList<>();
        for (int y = 0; y < 5; y++) blocks.add(new int[] {0, y, 0, 0});
        assertEquals(5, TimberMath.proxy(blocks, 400).size());
        List<int[]> huge = new ArrayList<>();
        for (int x = 0; x < 40; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 10; z++) huge.add(new int[] {x, y, z, 1});
        huge.add(new int[] {0, 0, 0, 0});
        List<TimberMath.Voxel> voxels = TimberMath.proxy(huge, 400);
        assertTrue(voxels.size() <= 400, "voxels " + voxels.size());
        assertTrue(voxels.getFirst().size() > 1);
        // The cube at the origin shows the log (priority 0), not a leaf.
        TimberMath.Voxel origin = voxels.stream().filter(v -> v.x() == 0 && v.y() == 0 && v.z() == 0).findFirst().orElseThrow();
        assertEquals(0, huge.get(origin.source())[3]);
    }

    // ---- drops and durability ----

    @Test void durabilityPerLogAndUnbreaking() {
        assertEquals(0, TimberDrops.damage(10, 0));
        assertEquals(10, TimberDrops.damage(10, 1.0));
        assertEquals(5, TimberDrops.damage(10, 0.5));
        assertEquals(4, TimberDrops.damage(7, 0.5));
        assertEquals(10, TimberDrops.expectedDamage(10, 0));
        assertEquals(3, TimberDrops.expectedDamage(10, 3));
        assertTrue(TimberDrops.toolSurvives(250, 0, 100, 0, false));
        assertFalse(TimberDrops.toolSurvives(250, 200, 60, 0, false));
        assertTrue(TimberDrops.toolSurvives(250, 200, 60, 3, false), "Unbreaking III: 15 expected");
        assertTrue(TimberDrops.toolSurvives(250, 249, 500, 0, true), "unbreakable");
        assertTrue(TimberDrops.toolSurvives(0, 0, 500, 0, false), "no durability");
    }

    record Stack(String item, int amount, int max) {}

    @Test void dropsMergeIntoFullStacks() {
        List<Stack> drops = new ArrayList<>();
        for (int i = 0; i < 100; i++) drops.add(new Stack("oak_log", 1, 64));
        drops.add(new Stack("apple", 2, 64));
        drops.add(new Stack("stick", 0, 64));
        drops.add(new Stack("sapling", 3, 64));
        drops.add(new Stack("apple", 1, 64));
        List<Stack> merged = TimberDrops.merge(drops, (a, b) -> a.item().equals(b.item()), Stack::amount, Stack::max,
                (s, n) -> new Stack(s.item(), n, s.max()));
        assertEquals(List.of(new Stack("oak_log", 64, 64), new Stack("oak_log", 36, 64), new Stack("apple", 3, 64),
                new Stack("sapling", 3, 64)), merged);
    }

    // ---- guards ----

    @Test void concurrencyCaps() {
        TimberGuards.Limits limits = new TimberGuards.Limits();
        UUID world = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertTrue(limits.tryAcquire(world, a, 2, 1));
        assertFalse(limits.tryAcquire(world, a, 2, 1), "per player");
        assertTrue(limits.tryAcquire(world, b, 2, 1));
        assertFalse(limits.tryAcquire(world, c, 2, 1), "per world");
        limits.release(world, a);
        assertTrue(limits.tryAcquire(world, c, 2, 1));
        assertEquals(2, limits.active());
    }

    private static Region region(String kind, int priority, Map<String, Boolean> flags) {
        return new Region(kind, kind + "-1", "", priority, new RegionShape.Box(null, -10, -64, -10, 10, 320, 10),
                Set.of(), Map.of(), flags);
    }

    @Test void coreRegionsProtectSpawnAndFlagsDecide() {
        List<String> kinds = TimberOptions.DEFAULT_REGION_KINDS;
        assertFalse(TimberGuards.regionDenies(List.of(), kinds));
        assertTrue(TimberGuards.regionDenies(List.of(region("spawn", 1000, Map.of())), kinds));
        assertFalse(TimberGuards.regionDenies(List.of(region("city", 400, Map.of())), kinds), "cities decide via the event");
        assertFalse(TimberGuards.regionDenies(List.of(region("spawn", 1000, Map.of("timber", true))), kinds));
        assertTrue(TimberGuards.regionDenies(List.of(region("city", 400, Map.of("timber", false))), kinds));
        assertTrue(TimberGuards.regionDenies(List.of(region("city", 400, Map.of()), region("arena", 800, Map.of())), kinds));
    }

    @Test void originRules() {
        assertTrue(TimberGuards.originAccepted(0, TimberGuards.History.UNKNOWN, true, false));
        assertFalse(TimberGuards.originAccepted(0, TimberGuards.History.PLAYER_PLACED, true, false));
        assertTrue(TimberGuards.originAccepted(0, TimberGuards.History.PLAYER_PLACED, false, false));
        assertTrue(TimberGuards.originAccepted(5, TimberGuards.History.PLAYER_PLACED, true, false), "CustomTrees grew it");
        assertFalse(TimberGuards.originAccepted(0, TimberGuards.History.UNKNOWN, true, true));
        assertTrue(TimberGuards.originAccepted(0, TimberGuards.History.NATURAL, true, true));
        assertTrue(TimberGuards.originAccepted(3, TimberGuards.History.UNKNOWN, true, true));
        assertTrue(TimberGuards.playerUser("Esoren"));
        assertFalse(TimberGuards.playerUser("#tree"));
        assertFalse(TimberGuards.playerUser("CustomTrees"));
        assertFalse(TimberGuards.playerUser(""));
    }

    @Test void cleanupRemovesOnlyTimberDisplays() {
        Entity ours = mock(Entity.class), other = mock(Entity.class);
        when(ours.getScoreboardTags()).thenReturn(Set.of(TimberGuards.DISPLAY_TAG));
        when(other.getScoreboardTags()).thenReturn(Set.of("something_else"));
        assertEquals(1, TimberGuards.cleanup(List.of(ours, other)));
        verify(ours).remove();
        verify(other, never()).remove();
    }

    @Test void timberBlocksClassify() {
        assertEquals("OAK", TimberBlocks.family(org.bukkit.Material.OAK_WOOD));
        assertEquals("DARK_OAK", TimberBlocks.family(org.bukkit.Material.DARK_OAK_LOG));
        assertEquals("CRIMSON", TimberBlocks.family(org.bukkit.Material.CRIMSON_HYPHAE));
        assertEquals("MUSHROOM", TimberBlocks.family(org.bukkit.Material.MUSHROOM_STEM));
        assertNull(TimberBlocks.family(org.bukkit.Material.STRIPPED_OAK_LOG));
        assertNull(TimberBlocks.family(org.bukkit.Material.OAK_PLANKS));
        assertTrue(TimberBlocks.axe(org.bukkit.Material.DIAMOND_AXE));
        assertFalse(TimberBlocks.axe(org.bukkit.Material.DIAMOND_PICKAXE));
        assertFalse(TimberBlocks.natural(org.bukkit.Material.GLASS));
        assertFalse(TimberBlocks.natural(org.bukkit.Material.COBBLESTONE));
        assertFalse(TimberBlocks.natural(org.bukkit.Material.WHITE_GLAZED_TERRACOTTA));
        assertTrue(TimberBlocks.natural(org.bukkit.Material.BEE_NEST));
        assertTrue(TimberBlocks.ground(org.bukkit.Material.PODZOL));
    }

    @Test void choiceKeysAreEditorEntriesWithLabels() {
        YamlConfiguration en = resource("lang/en.yml"), de = resource("lang/de.yml"), config = resource("config.yml");
        List<String> paths = TreesConfigEditor.ENTRIES.stream().map(TreesConfigEditor.Entry::path).toList();
        for (var choice : TreesConfigEditor.CHOICES.entrySet()) {
            assertTrue(paths.contains(choice.getKey()), choice.getKey());
            assertTrue(choice.getValue().contains(config.getString(choice.getKey())), "default of " + choice.getKey());
            for (String value : choice.getValue()) {
                assertTrue(en.isString(TreesConfigEditor.choiceKey(value)), value);
                assertTrue(de.isString(TreesConfigEditor.choiceKey(value)), value);
                assertFalse(List.of("on", "off", "yes", "no", "true", "false").contains(value), "YAML boolean: " + value);
            }
        }
    }
}
