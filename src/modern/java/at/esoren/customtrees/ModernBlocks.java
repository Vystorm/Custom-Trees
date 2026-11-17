package at.esoren.customtrees;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingMoss;

final class ModernBlocks implements PaperCompat.Blocks {
    @Override public BlockData hangingMoss(Material material, boolean tip) {
        HangingMoss moss = (HangingMoss) material.createBlockData();
        moss.setTip(tip);
        return moss;
    }
}
