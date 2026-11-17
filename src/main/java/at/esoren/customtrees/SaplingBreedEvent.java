package at.esoren.customtrees;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;

/** Cancellable grafting action, before either parent sapling is consumed. Item views are defensive copies. */
public final class SaplingBreedEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS=new HandlerList();
    private final Player player;
    private final ItemStack first,second,offspring;
    private boolean cancelled;
    public SaplingBreedEvent(Player player,ItemStack first,ItemStack second,ItemStack offspring) {
        this.player=player;this.first=first.clone();this.second=second.clone();this.offspring=offspring.clone();
    }
    public Player getPlayer(){return player;}
    public ItemStack getFirstParent(){return first.clone();}
    public ItemStack getSecondParent(){return second.clone();}
    public ItemStack getOffspring(){return offspring.clone();}
    public boolean isCancelled(){return cancelled;}
    public void setCancelled(boolean value){cancelled=value;}
    public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
