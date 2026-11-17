package at.esoren.customtrees;

import Vystorm.vystorm_core.compat.ServerCompat;
import java.util.Arrays;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperCompatTest {
    @Test void missingMaterialsAreSkipped() {
        assertNull(PaperCompat.material("CUSTOMTREES_NONEXISTENT_BLOCK"));
        assertEquals(Set.of(Material.OAK_LOG), PaperCompat.materials(Material.OAK_LOG, null));
        assertEquals(Material.OAK_LOG, PaperCompat.material("OAK_LOG"));
    }

    @Test void treeTypesUseDetectedValueOrFallback() {
        assertEquals(TreeType.BIRCH, PaperCompat.treeType("BIRCH", TreeType.TREE));
        assertEquals(TreeType.DARK_OAK, PaperCompat.treeType("NONEXISTENT", TreeType.DARK_OAK));
    }

    @Test void legacyBlocksUseOrdinaryBlockData() {
        Material material = mock(Material.class);
        BlockData data = mock(BlockData.class);
        when(material.createBlockData()).thenReturn(data);
        assertSame(data, PaperCompat.selectBlocks(false).hangingMoss(material, true));
    }

    @Test void adapterSelectionUsesApiPresence() {
        assertEquals(ServerCompat.hasClass("org.bukkit.block.data.type.HangingMoss"),
                PaperCompat.blocks().getClass().getSimpleName().equals("ModernBlocks"));
    }

    @Test void collectionAndLootOnlyContainAvailableSpecies() {
        boolean pale = Material.matchMaterial("PALE_OAK_SAPLING") != null;
        assertEquals(pale, Arrays.asList(Herbarium.Plant.available()).contains(Herbarium.Plant.PALE_OAK));
        assertEquals(pale ? 83 : 76, Herbarium.TOTAL);
        assertEquals(pale, Herbarium.ENTRIES.contains("pale_oak:single"));
        assertEquals(pale, TreeShaking.lootTables().containsKey("customtrees:shaking/pale_oak"));
        assertNull(Herbarium.Plant.of(null));
    }

    @Test void portableClassesInitializeOnBothApis() throws Exception {
        for (String name : new String[]{"WorldTreeGrowth", "TreeFireGuard", "GeneratedTreeScanner"})
            Class.forName("at.esoren.customtrees." + name);
        assertTrue(GeneratedTreeScanner.SOILS.contains(Material.GRASS_BLOCK));
        assertEquals(WorldTreeGrowth.Fit.SKIP,
                WorldTreeGrowth.fit(Material.CHEST, false, false, Material.OAK_SAPLING, false, false));
    }

    @Test void unavailableCollectionEntriesSurviveSaving() {
        java.util.UUID player = java.util.UUID.randomUUID();
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        String path = "players." + player + ".found";
        yaml.set(path, java.util.List.of("pale_oak:single", "oak:single"));
        Herbarium herbarium = Herbarium.fromYaml(yaml);
        assertEquals(Material.matchMaterial("PALE_OAK_SAPLING") == null ? 1 : 2, herbarium.found(player));
        assertEquals(yaml.getStringList(path), herbarium.toYaml().getStringList(path));
        if (Material.matchMaterial("PALE_OAK_SAPLING") == null)
            assertNull(herbarium.next(player, Herbarium.Plant.PALE_OAK));
    }

    @Test void hangingMossAdapterPreservesTip() throws Exception {
        if (!ServerCompat.hasClass("org.bukkit.block.data.type.HangingMoss")) return;
        Class<?> mossType = Class.forName("org.bukkit.block.data.type.HangingMoss");
        BlockData moss = (BlockData) mock(mossType);
        Material material = mock(Material.class);
        when(material.createBlockData()).thenReturn(moss);
        assertSame(moss, PaperCompat.blocks().hangingMoss(material, true));
        mossType.getMethod("setTip", boolean.class).invoke(verify(moss), true);
    }

    @Test void inventoryViewAccessWorksAcrossClassAndInterfaceApis() {
        var view = mock(org.bukkit.inventory.InventoryView.class);
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        // Stub through the compatibility bridge so this test's bytecode also works on either API.
        when(Vystorm.vystorm_core.compat.InventoryCompat.top(view)).thenReturn(inventory);
        assertSame(inventory, Vystorm.vystorm_core.compat.InventoryCompat.top(view));
    }
}
