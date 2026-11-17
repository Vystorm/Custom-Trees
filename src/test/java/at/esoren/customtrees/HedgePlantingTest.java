package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class HedgePlantingTest {
    @Test void reloadAndPermissionChecksInvalidateUnavailableRows() {
        try (var buttons = mockStatic(Vystorm.vystorm_core._uiMethods.chat.ChatActions.class,
                call -> net.kyori.adventure.text.Component.empty())) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
            when(player.hasPermission("customtrees.plant")).thenReturn(true);
            PlantingHelper helper = new PlantingHelper(TestCore.plugin(), LangFilesTest.bundle());
            helper.command(player, new String[]{"plant", "hedge16"});
            assertNotNull(helper.selection(player));
            var config = new YamlConfiguration();
            config.set("planting-helper.max-hedge-length", 8);
            helper.apply(PlantingStencil.Options.read(config));
            assertNull(helper.selection(player));
            helper.command(player, new String[]{"plant", "hedge16"});
            assertNull(helper.selection(player));
            helper.command(player, new String[]{"plant", "hedge8"});
            assertNotNull(helper.selection(player));
            helper.forget(player.getUniqueId());
            when(player.hasPermission("customtrees.plant")).thenReturn(false);
            helper.command(player, new String[]{"plant", "hedge4"});
            assertNull(helper.selection(player));
        }
    }

    @Test void rowsFollowAllFourDirectionsWithoutBecomingSquares() {
        StructurePos origin = new StructurePos(-16, 64, 15);
        for (int[] facing : List.of(new int[]{0, -1}, new int[]{1, 0}, new int[]{0, 1}, new int[]{-1, 0})) {
            for (int length = 4; length <= 32; length++) {
                var cells = new PlantingStencil.Pattern(length, true).cells(origin, facing[0], facing[1]);
                assertEquals(length, cells.size());
                for (int i = 0; i < length; i++) assertEquals(new StructurePos(
                        origin.x() + i * facing[0], 64, origin.z() + i * facing[1]), cells.get(i));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new PlantingStencil.Pattern(4, true).cells(origin, 1, 1));
        assertEquals(9, PlantingStencil.Pattern.parse("3x3").cells(origin, 0, 1).size());
    }

    @Test void parserRejectsUnboundedAndMalformedLengths() {
        assertEquals(new PlantingStencil.Pattern(12, true), PlantingStencil.Pattern.parse("HEDGE12"));
        for (String invalid : List.of("hedge", "hedge3", "hedge33", "hedge-8", "hedge4x4", "hedge999999999999"))
            assertNull(PlantingStencil.Pattern.parse(invalid), invalid);
        assertNull(PlantingStencil.Pattern.parse(null));
    }

    @Test void oldConfigsGetDefaultsAndLimitsCanDisableOnlyRows() {
        var config = new YamlConfiguration();
        assertEquals(16, PlantingStencil.Options.read(config).maxHedgeLength());
        config.set("planting-helper.max-size", 2);
        assertTrue(PlantingStencil.Pattern.parse("hedge16").allowed(PlantingStencil.Options.read(config)));
        assertFalse(PlantingStencil.Pattern.parse("hedge17").allowed(PlantingStencil.Options.read(config)));
        config.set("planting-helper.max-hedge-length", 1000);
        assertEquals(32, PlantingStencil.Options.read(config).maxHedgeLength());
        config.set("planting-helper.max-hedge-length", -1);
        assertEquals(0, PlantingStencil.Options.read(config).maxHedgeLength());
        assertFalse(PlantingStencil.Pattern.parse("hedge4").allowed(PlantingStencil.Options.read(config)));
        assertTrue(PlantingStencil.Pattern.parse("2x2").allowed(PlantingStencil.Options.read(config)));
        config.set("planting-helper.enabled", false);
        assertFalse(PlantingStencil.Pattern.parse("2x2").allowed(PlantingStencil.Options.read(config)));
    }

    @Test void rowsAcceptSaplingsButNeverFungi() {
        var row = PlantingStencil.Pattern.parse("hedge4");
        assertTrue(row.accepts(Material.PALE_OAK_SAPLING));
        assertTrue(row.accepts(Material.MANGROVE_PROPAGULE));
        for (Material material : List.of(Material.RED_MUSHROOM, Material.BROWN_MUSHROOM,
                Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS, Material.OAK_LOG)) assertFalse(row.accepts(material));
    }

    @Test void allDynamicMessagesAreTranslated() {
        var lang = LangFilesTest.bundle();
        for (String language : List.of("en", "de", "zh_cn")) {
            for (String message : List.of("armed", "blocked", "missing", "done")) {
                String key = new PlantingStencil.Pattern(4, true).key(message);
                String text = lang.plain(language, key, "size", 4, "count", 2, "missing", 1);
                assertNotEquals(key, text);
                assertFalse(text.contains("?"), text);
                assertFalse(text.contains("{"), text);
            }
        }
    }

    @Test void successfulRowConsumesOnlyMissingSaplings() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.blocks[1].getType()).thenReturn(Material.OAK_SAPLING);
            f.fill();
            verify(f.stock).setAmount(6);
            verify(f.blocks[1], never()).setBlockData(any(), anyBoolean());
            verify(f.manager, times(2)).callEvent(any(BlockPlaceEvent.class));
        }
    }

    @Test void deniedPlacementRollsBackEveryAddedPlantWithoutCharging() throws Exception {
        try (Fixture f = new Fixture()) {
            int[] seen = {0};
            doAnswer(call -> {
                BlockPlaceEvent event = call.getArgument(0);
                assertSame(f.player, event.getPlayer());
                if (++seen[0] == 2) event.setCancelled(true);
                return null;
            }).when(f.manager).callEvent(any(BlockPlaceEvent.class));
            f.fill();
            verify(f.states[1]).update(true, false);
            verify(f.states[2]).update(true, false);
            verify(f.blocks[3], never()).setBlockData(any(), anyBoolean());
            verify(f.stock, never()).setAmount(anyInt());
        }
    }

    @Test void insufficientInventoryDoesNotPlaceAnything() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.stock.getAmount()).thenReturn(2);
            f.fill();
            verifyNoInteractions(f.manager);
            for (Block block : f.blocks) verify(block, never()).setBlockData(any(), anyBoolean());
        }
    }

    @Test void unloadedRowIsNeverReadOrChanged() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            f.fill();
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            verifyNoInteractions(f.manager);
        }
    }

    @Test void creativeRowConsumesNothing() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            f.fill();
            verify(f.manager, times(3)).callEvent(any(BlockPlaceEvent.class));
            verifyNoInteractions(f.inventory);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final org.mockito.MockedConstruction<ItemStack> items = mockConstruction(ItemStack.class);
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final ItemStack stock = mock(ItemStack.class);
        final PluginManager manager = mock(PluginManager.class);
        final Block[] blocks = new Block[4];
        final BlockState[] states = new BlockState[4];
        final PlantingHelper helper = new PlantingHelper(TestCore.plugin(), LangFilesTest.bundle());

        Fixture() {
            BlockData data = mock(BlockData.class);
            Material empty = mock(Material.class);
            when(empty.isAir()).thenReturn(true);
            bukkit.when(() -> Bukkit.createBlockData(Material.OAK_SAPLING)).thenReturn(data);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            when(data.isSupported(any(Block.class))).thenReturn(true);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getInventory()).thenReturn(inventory);
            when(stock.getType()).thenReturn(Material.OAK_SAPLING);
            when(stock.getAmount()).thenReturn(8);
            when(inventory.getStorageContents()).thenReturn(new ItemStack[]{stock});
            for (int i = 0; i < 4; i++) {
                blocks[i] = mock(Block.class);
                states[i] = mock(BlockState.class);
                when(blocks[i].getType()).thenReturn(i == 0 ? Material.OAK_SAPLING : empty);
                when(blocks[i].getState()).thenReturn(states[i]);
                when(world.getBlockAt(0, 64, i)).thenReturn(blocks[i]);
            }
        }

        void fill() throws Exception {
            Method method = PlantingHelper.class.getDeclaredMethod("fill", Player.class, World.class,
                    StructurePos.class, Material.class, BlockFace.class, PlantingStencil.Pattern.class);
            method.setAccessible(true);
            method.invoke(helper, player, world, new StructurePos(0, 64, 0), Material.OAK_SAPLING,
                    BlockFace.SOUTH, new PlantingStencil.Pattern(4, true));
        }

        @Override public void close() { items.close(); bukkit.close(); }
    }
}
