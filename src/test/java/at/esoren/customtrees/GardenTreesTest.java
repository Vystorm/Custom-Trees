package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class GardenTreesTest {
    @TempDir Path directory;

    @Test
    void regrowthSkipsDisconnectedOrUnauthorizedOwnerBeforeReadingBlocks() throws Exception {
        for (boolean connected : List.of(false, true)) {
            try (Fixture f = new Fixture(connected, !connected)) {
                f.runTick();

                verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
                verify(f.fireGuard, never()).mark(any(Block.class));
                assertEquals(f.dueTime, f.reload().trees().getFirst().leaves().getFirst().dueMillis());
            }
        }
    }

    @Test
    void regrowthRetiresAnOccupiedLeafWithoutReplacingIt() throws Exception {
        try (Fixture f = new Fixture(true, true)) {
            StructurePos leafPos = new StructurePos(1, 83, 0);
            f.types.put(leafPos, Material.STONE);
            f.runTick();

            Block leaf = f.block(1, 83, 0);
            assertEquals(Material.STONE, leaf.getType());
            verify(leaf, never()).getState();
            verify(f.fireGuard, never()).mark(any(Block.class));
            assertEquals(0, f.reload().trees().getFirst().leaves().getFirst().dueMillis());
        }
    }

    @Test
    void regrowthNeverReadsAnUnloadedLeafChunk() throws Exception {
        try (Fixture f = new Fixture(true, true, 16)) {
            when(f.world.isChunkLoaded(1, 0)).thenReturn(false);

            f.runTick();

            verify(f.world, never()).getBlockAt(16, 83, 0);
            verify(f.fireGuard, never()).mark(any(Block.class));
            assertEquals(f.dueTime, f.reload().trees().getFirst().leaves().getFirst().dueMillis());
        }
    }

    @Test
    void onlyTreesWithADueLeafAreChecked() {
        GardenState.Pos pos = new GardenState.Pos(1, 64, 1);
        String data = "minecraft:oak_leaves";
        UUID id = UUID.randomUUID();
        java.util.function.Function<List<GardenState.Leaf>, GardenState.Tree> tree =
                leaves -> new GardenState.Tree(id, id, id, pos, "OAK_LOG", leaves);
        assertFalse(GardenTrees.hasDueLeaf(tree.apply(List.of()), 1000));
        assertFalse(GardenTrees.hasDueLeaf(tree.apply(List.of(new GardenState.Leaf(pos, data, 0))), 1000), "0 means not pruned");
        assertFalse(GardenTrees.hasDueLeaf(tree.apply(List.of(new GardenState.Leaf(pos, data, 1001))), 1000), "not yet due");
        assertTrue(GardenTrees.hasDueLeaf(tree.apply(List.of(new GardenState.Leaf(pos, data, 0), new GardenState.Leaf(new GardenState.Pos(2, 64, 1), data, 1000))), 1000));
    }

    private final class Fixture implements AutoCloseable {
        final CustomTreesPlugin plugin = TestCore.plugin();
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final PluginManager pluginManager = mock(PluginManager.class);
        final World world = mock(World.class);
        final org.bukkit.entity.Player owner;
        final UUID ownerId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        final UUID worldId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        final UUID treeId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        final TreeFireGuard fireGuard = mock(TreeFireGuard.class);
        final long dueTime = 1_700_000_000_000L;
        final int leafX;
        final AtomicReference<Runnable> tick = new AtomicReference<>();
        final Map<StructurePos, Block> blocks = new HashMap<>();
        final Map<StructurePos, Material> types = new HashMap<>();
        final MockedStatic<Bukkit> bukkitApi = mockStatic(Bukkit.class);
        final GardenTrees gardens;

        Fixture(boolean online, boolean permitted) throws Exception {
            this(online, permitted, 1);
        }

        Fixture(boolean online, boolean permitted, int leafX) throws Exception {
            this.leafX = leafX;
            owner = mock(org.bukkit.entity.Player.class);
            when(owner.getUniqueId()).thenReturn(ownerId);
            when(owner.isOnline()).thenReturn(online);
            when(owner.hasPermission("customtrees.garden")).thenReturn(permitted);
            when(plugin.getDataFolder()).thenReturn(directory.toFile());
            when(plugin.fireGuard()).thenReturn(fireGuard);
            when(world.getUID()).thenReturn(worldId);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(384);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                    .thenAnswer(call -> block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L)))
                    .thenAnswer(call -> { tick.set(call.getArgument(1)); return mock(BukkitTask.class); });
            bukkitApi.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkitApi.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkitApi.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkitApi.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(online ? owner : null);

            GardenState store = new GardenState(directory.resolve("garden-trees.properties"));
            store.put(new GardenState.Tree(treeId, ownerId, worldId, new GardenState.Pos(0, 64, 0), "CHERRY_LOG",
                    List.of(new GardenState.Leaf(new GardenState.Pos(leafX, 83, 0), "minecraft:cherry_leaves", dueTime))));
            gardens = new GardenTrees(plugin);
        }

        Block block(int x, int y, int z) {
            StructurePos pos = new StructurePos(x, y, z);
            return blocks.computeIfAbsent(pos, ignored -> {
                Block block = mock(Block.class);
                when(block.getWorld()).thenReturn(world);
                when(block.getX()).thenReturn(x);
                when(block.getY()).thenReturn(y);
                when(block.getZ()).thenReturn(z);
                when(block.getType()).thenAnswer(call -> types.getOrDefault(pos,
                        x == 0 && y == 64 ? Material.CHERRY_LOG : Material.AIR));
                BlockState state = mock(BlockState.class);
                when(state.getType()).thenReturn(Material.CHERRY_LEAVES);
                when(state.update(true, false)).thenReturn(true);
                when(block.getState()).thenReturn(state);
                return block;
            });
        }

        void runTick() { tick.get().run(); }

        GardenState reload() { return new GardenState(directory.resolve("garden-trees.properties")); }

        @Override public void close() { bukkitApi.close(); }
    }

}
