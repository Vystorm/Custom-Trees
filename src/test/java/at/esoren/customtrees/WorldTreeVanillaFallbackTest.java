package at.esoren.customtrees;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Smaller fungus squares must not block vanilla growth that CustomTrees does not perform itself. */
class WorldTreeVanillaFallbackTest {
    @Test void onlyCompleteTenByTenFormationsReserveNonPlayerGrowth() {
        WorldTreeGrowth growth = new WorldTreeGrowth(plugin());
        for (Material plant : List.of(Material.RED_MUSHROOM, Material.BROWN_MUSHROOM, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS)) {
            for (int width : List.of(1, 2, 3, 5)) {
                assertFalse(growth.interceptNatural(fixture(plant, width, Material.STONE).getBlockAt(0, 64, 0)), plant + " " + width);
            }
            assertTrue(growth.interceptNatural(fixture(plant, 10, Material.STONE).getBlockAt(0, 64, 0)), plant + " 10");
        }
    }

    @Test void smallFungusWithoutValidSoilOrBoneMealFallsBackToVanilla() {
        WorldTreeGrowth growth = new WorldTreeGrowth(plugin());
        World world = fixture(Material.RED_MUSHROOM, 1, Material.STONE);
        PlayerInteractEvent boneMeal = click(world.getBlockAt(0, 64, 0), Material.BONE_MEAL);
        assertFalse(growth.interact(boneMeal));
        verify(boneMeal, never()).setCancelled(anyBoolean());
        PlayerInteractEvent coal = click(fixture(Material.CRIMSON_FUNGUS, 3, Material.CRIMSON_NYLIUM).getBlockAt(1, 64, 1), Material.COAL);
        assertFalse(growth.interact(coal));
        verify(coal, never()).setCancelled(anyBoolean());
    }

    @Test void tenByTenFormationStillReportsMissingSoil() {
        WorldTreeGrowth growth = new WorldTreeGrowth(plugin());
        PlayerInteractEvent event = click(fixture(Material.RED_MUSHROOM, 10, Material.STONE).getBlockAt(0, 64, 0), Material.BONE_MEAL);
        assertTrue(growth.interact(event));
        verify(event).setCancelled(true);
    }

    private static CustomTreesPlugin plugin() {
        CustomTreesPlugin plugin = TestCore.plugin();
        WorldTreeSettings settings = WorldTreeSettings.load(new YamlConfiguration());
        when(plugin.worldTreeSettings()).thenReturn(settings);
        TreeSettings trees = mock(TreeSettings.class);
        when(trees.enabledWorlds()).thenReturn(Set.of());
        when(plugin.treeSettings()).thenReturn(trees);
        return plugin;
    }

    private static PlayerInteractEvent click(Block block, Material held) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Player player = mock(Player.class);
        when(player.hasPermission("customtrees.grow")).thenReturn(true);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(held);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    private static World fixture(Material plant, int width, Material soil) {
        World world = mock(World.class);
        Map<String, Block> blocks = new HashMap<>();
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            return blocks.computeIfAbsent(x + ":" + y + ":" + z, key -> {
                Block block = mock(Block.class);
                when(block.getWorld()).thenReturn(world);
                when(block.getX()).thenReturn(x);
                when(block.getY()).thenReturn(y);
                when(block.getZ()).thenReturn(z);
                boolean inside = x >= 0 && x < width && z >= 0 && z < width;
                when(block.getType()).thenReturn(inside && y == 64 ? plant : inside && y == 63 ? soil : Material.AIR);
                return block;
            });
        });
        return world;
    }
}
