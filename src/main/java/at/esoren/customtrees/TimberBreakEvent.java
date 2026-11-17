package at.esoren.customtrees;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Break event the timber fires for every block of a tree before removing it, so claim and protection plugins
 * (CityClaim, Estates, WorldGuard, ...) can refuse single blocks and loggers such as CoreProtect record the removal.
 * The timber removes the block itself afterwards; it drops nothing when a listener turned the drops off.
 */
public final class TimberBreakEvent extends BlockBreakEvent {
    public TimberBreakEvent(Block block, Player player) {
        super(block, player);
        setExpToDrop(0);
    }
}
