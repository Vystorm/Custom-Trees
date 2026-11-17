package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** Item profiles are explicitly transferred to exact chunk-local sapling positions. */
final class GrowthPlanting {
    static final NamespacedKey KEY=new NamespacedKey("customtrees","planted_profiles");
    record Planted(UUID owner,GrowthSapling.Spec spec) {}
    private final CustomTreesPlugin plugin;
    GrowthPlanting(CustomTreesPlugin plugin) { this.plugin=plugin; }
    private Map<Integer,Planted> read(Block block) {
        if(block.getY() < -2048 || block.getY() > 2047) return null;
        if(!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4)) return null;
        try {
            var pdc=block.getChunk().getPersistentDataContainer();
            if(!pdc.has(KEY)) return new LinkedHashMap<>();
            String text=pdc.get(KEY,PersistentDataType.STRING);
            if(text==null || text.length()>65536 || !text.startsWith("1\n")) return null;
            Map<Integer,Planted> rows=new LinkedHashMap<>();
            for(String line:text.substring(2).split("\n")) {
                if(line.isEmpty()) continue;
                String[] v=line.split(";",-1); if(v.length!=5 || rows.size()>=256) return null;
                int pos=Integer.parseInt(v[0]); if(pos<0 || pos>=1<<20 || rows.containsKey(pos)) return null;
                rows.put(pos,new Planted(UUID.fromString(v[1]),new GrowthSapling.Spec(TreeSpecies.valueOf(v[2]),GrowthSapling.CrownForm.valueOf(v[3]),GrowthSapling.GrowthClass.valueOf(v[4]))));
            }
            return rows;
        } catch(RuntimeException malformed) { return null; }
    }
    Planted at(Block block) {
        Map<Integer,Planted> rows=read(block); if(rows==null) return null;
        Planted result=rows.get(TreeFireGuard.pack(block.getX(),block.getY(),block.getZ()));
        return result!=null && result.spec.species().sapling==block.getType()?result:null;
    }
    void remove(Block block) {
        Map<Integer,Planted> rows=read(block);
        if(rows!=null && rows.remove(TreeFireGuard.pack(block.getX(),block.getY(),block.getZ()))!=null) write(block,rows);
    }
    private void write(Block block,Map<Integer,Planted> rows) {
        var pdc=block.getChunk().getPersistentDataContainer(); if(rows.isEmpty()) { pdc.remove(KEY); return; }
        StringBuilder out=new StringBuilder("1\n");
        rows.forEach((pos,p)->out.append(pos).append(';').append(p.owner).append(';').append(p.spec.species()).append(';').append(p.spec.crownForm()).append(';').append(p.spec.growthClass()).append('\n'));
        pdc.set(KEY,PersistentDataType.STRING,out.toString());
    }
    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * A chunk that cannot hold another growth sapling refuses it in ADJUST (a rule of this plugin, not a protection);
     * the stored saplings follow the final outcome in OBSERVE. None of the handlers sees a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ADJUST, this::validatePlace);
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::place);
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::broken);
    }
    /** Stage ADJUST, registered in {@link #handleBlockEvents}. */
    public void validatePlace(BlockPlaceEvent event) {
        if(GrowthSapling.read(event.getItemInHand()).isEmpty()) return;
        Map<Integer,Planted> rows=read(event.getBlock());
        if(rows==null || rows.size()>=256) { event.setCancelled(true); plugin.message(event.getPlayer(),"garden.unavailable"); }
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void place(BlockPlaceEvent event) {
        remove(event.getBlock());
        GrowthSapling.read(event.getItemInHand()).ifPresent(spec->{
            if(event.getBlock().getType()!=spec.species().sapling) return;
            Map<Integer,Planted> rows=read(event.getBlock()); if(rows==null || rows.size()>=256) return;
            rows.put(TreeFireGuard.pack(event.getBlock().getX(),event.getBlock().getY(),event.getBlock().getZ()),new Planted(event.getPlayer().getUniqueId(),spec)); write(event.getBlock(),rows);
        });
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent e) { remove(e.getBlock()); }
    /** Explosions, fire, entity block changes and pistons: forget the planted sapling. OBSERVE, never a cancelled event. */
    void handleWorldEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (BlockExplodeEvent e) -> exploded(e));
        stages.handle(owner, EntityExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (EntityExplodeEvent e) -> exploded(e));
        stages.handle(owner, BlockBurnEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::burned);
        stages.handle(owner, org.bukkit.event.entity.EntityChangeBlockEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::changed);
        stages.handle(owner, BlockPistonExtendEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::extended);
        stages.handle(owner, BlockPistonRetractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::retracted);
    }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void exploded(BlockExplodeEvent e) { e.blockList().forEach(this::remove); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void exploded(EntityExplodeEvent e) { e.blockList().forEach(this::remove); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void burned(BlockBurnEvent e) { remove(e.getBlock()); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void changed(org.bukkit.event.entity.EntityChangeBlockEvent e) { remove(e.getBlock()); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void extended(BlockPistonExtendEvent e) { e.getBlocks().forEach(this::remove); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void retracted(BlockPistonRetractEvent e) { e.getBlocks().forEach(this::remove); }
}
