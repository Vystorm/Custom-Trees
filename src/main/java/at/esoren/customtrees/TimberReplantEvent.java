package at.esoren.customtrees;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Place event the timber fires for every sapling it replants after a fell, so claim plugins can refuse it and loggers
 * such as CoreProtect record it. The sapling is already set; a refusal takes the whole replanting back.
 */
public final class TimberReplantEvent extends BlockPlaceEvent {
    public TimberReplantEvent(Block block, BlockState replaced, ItemStack sapling, Player player) {
        super(block, replaced, block.getRelative(BlockFace.DOWN), sapling, player, true, EquipmentSlot.HAND);
    }
}
