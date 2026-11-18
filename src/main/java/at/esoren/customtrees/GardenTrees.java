package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.StructureGrowEvent;
import java.util.*;

/** Only newly registered profile trees; no world scanning and no reconstruction of arbitrary builds. */
final class GardenTrees implements Listener {
    private final CustomTreesPlugin plugin;
    private final GardenState store;
    private boolean ownBreak;
    GardenTrees(CustomTreesPlugin plugin) {
        this.plugin=plugin; store=new GardenState(plugin.getDataFolder().toPath().resolve("garden-trees.properties"));
        if (!store.healthy()) plugin.getLogger().warning("Garden tree records are invalid; pruning and regrowth are disabled, file preserved.");
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
    }
    void register(UUID owner,List<Block> committed) {
        if (!store.healthy() || store.trees().size()>=128) return;
        Block root=committed.stream().filter(b->TreeSpecies.isLog(b.getType())).findFirst().orElse(null);
        if(root==null) return;
        List<GardenState.Leaf> leaves=committed.stream().filter(b->TreeSpecies.isLeaves(b.getType()))
                .distinct().limit(512).map(b->new GardenState.Leaf(pos(b),b.getBlockData().getAsString(),0)).toList();
        if(leaves.isEmpty()) return;
        try { store.put(new GardenState.Tree(UUID.randomUUID(),owner,root.getWorld().getUID(),pos(root),root.getType().name(),leaves)); }
        catch(Exception failure) { report(failure); }
    }
    void prune(Player player) {
        if(!player.hasPermission("customtrees.garden") || !store.healthy()) { plugin.message(player,"garden.unavailable"); return; }
        Block target=lookedAt(player); if(target==null) { plugin.message(player,"garden.no-tree"); return; }
        GardenState.Tree tree=store.trees().stream().filter(t->t.world().equals(target.getWorld().getUID()) && t.owner().equals(player.getUniqueId())
                && t.leaves().stream().anyMatch(l->l.pos().equals(pos(target)))).findFirst().orElse(null);
        if(tree==null || !rootPresent(tree,target.getWorld())) { plugin.message(player,"garden.no-tree"); return; }
        Set<GardenState.Pos> selected=new HashSet<>();
        for(GardenState.Leaf leaf:tree.leaves()) {
            if(selected.size()>=16) break;
            GardenState.Pos p=leaf.pos();
            if(leaf.dueMillis()!=0 || Math.abs(p.x()-target.getX())>1 || Math.abs(p.y()-target.getY())>1 || Math.abs(p.z()-target.getZ())>1) continue;
            Block block=loaded(target.getWorld(),p); if(block==null || !block.getBlockData().getAsString().equals(leaf.blockData()) || !may(player,block,"BREAK")) continue;
            BlockBreakEvent event=new BlockBreakEvent(block,player); event.setDropItems(false); event.setExpToDrop(0);
            ownBreak=true;
            try { Bukkit.getPluginManager().callEvent(event); } finally { ownBreak=false; }
            if(!event.isCancelled() && block.getBlockData().getAsString().equals(leaf.blockData())) selected.add(p);
        }
        if(selected.isEmpty()) { plugin.message(player,"garden.denied"); return; }
        long due=System.currentTimeMillis()+Math.clamp(plugin.getConfig().getLong("settings.garden-regrowth-seconds",300),10,86400)*1000;
        List<GardenState.Leaf> changed=tree.leaves().stream().map(l->selected.contains(l.pos())?new GardenState.Leaf(l.pos(),l.blockData(),due):l).toList();
        try { store.put(copy(tree,changed)); } catch(Exception failure) { report(failure); plugin.message(player,"garden.unavailable"); return; }
        int count=0;
        for(GardenState.Leaf leaf:changed) if(selected.contains(leaf.pos())) {
            Block block=loaded(target.getWorld(),leaf.pos());
            if(block!=null && block.getBlockData().getAsString().equals(leaf.blockData())) { plugin.fireGuard().unmark(block); block.setType(Material.AIR,false); count++; }
        }
        plugin.message(player,"garden.pruned","count",count,"seconds",(due-System.currentTimeMillis())/1000);
    }
    private void tick() {
        if(!store.healthy() || !CustomTreesPlugin.enabled(plugin, Feature.GARDEN)) return; // garden.enabled off: pruned leaves wait
        int budget=16; long now=System.currentTimeMillis();
        for(GardenState.Tree tree:store.trees()) {
            if(budget<=0) break;
            if(!hasDueLeaf(tree,now)) continue; // nothing to regrow: skip the player, permission and block lookups
            World world=Bukkit.getWorld(tree.world()); Player owner=Bukkit.getPlayer(tree.owner());
            if(world==null || owner==null || !owner.isOnline() || !owner.hasPermission("customtrees.garden") || !rootPresent(tree,world)) continue;
            List<GardenState.Leaf> changed=new ArrayList<>(tree.leaves()); boolean dirty=false;
            for(int index=0; index<changed.size() && budget>0; index++) {
                GardenState.Leaf leaf=changed.get(index); if(leaf.dueMillis()==0 || leaf.dueMillis()>now) continue;
                Block block=loaded(world,leaf.pos()); if(block==null) continue;
                budget--;
                // Retry denied/cancelled rows later so they cannot consume every tick's budget forever.
                changed.set(index,new GardenState.Leaf(leaf.pos(),leaf.blockData(),now+60000)); dirty=true;
                // Any later build permanently removes this position from regrowth.
                if(!air(block.getType())) { changed.set(index,new GardenState.Leaf(leaf.pos(),leaf.blockData(),0)); dirty=true; continue; }
                if(!may(owner,block,"BUILD")) continue;
                BlockState state=block.getState();
                try { state.setBlockData(Bukkit.createBlockData(leaf.blockData())); } catch(RuntimeException invalid) { continue; }
                if(!TreeSpecies.isLeaves(state.getType())) continue;
                List<BlockState> states=new ArrayList<>(List.of(state));
                StructureGrowEvent event=new StructureGrowEvent(block.getLocation(),TreeType.TREE,false,owner,states);
                Bukkit.getPluginManager().callEvent(event);
                // Do not accept injected positions or a changed material from event plugins.
                boolean retained=event.getBlocks().stream().anyMatch(s->s.getWorld().equals(world) && s.getX()==block.getX() && s.getY()==block.getY() && s.getZ()==block.getZ() && s.getBlockData().getAsString().equals(leaf.blockData()));
                if(event.isCancelled() || !retained || !air(block.getType()) || !may(owner,block,"BUILD")) continue;
                if(state.update(true,false)) { plugin.fireGuard().mark(block); changed.set(index,new GardenState.Leaf(leaf.pos(),leaf.blockData(),0)); dirty=true; }
            }
            if(dirty) try { store.put(copy(tree,changed)); } catch(Exception failure) { report(failure); return; }
        }
    }
    private boolean may(Player player,Block block,String right) {
        // Core structure/spawn protection is conservative; standard claim plugins additionally receive the events.
        var shapes=ProtectedRegions.within(plugin.core().area().regions(), block.getWorld().getUID(),block.getX(),block.getZ(),block.getX(),block.getZ());
        if(ProtectedRegions.contains(shapes,block.getWorld().getUID(),block.getX(),block.getY(),block.getZ())) return false;
        var city=Bukkit.getPluginManager().getPlugin("VystormCityClaim");
        if(city==null) return true;
        if(!city.isEnabled()) return false;
        try {
            for(Class<?> api:Bukkit.getServicesManager().getKnownServices()) if(api.getName().equals("at.esoren.stadtclaim.VystormCityApi")) {
                Object provider=Bukkit.getServicesManager().load(api);
                return Boolean.TRUE.equals(api.getMethod("allowed",UUID.class,Location.class,String.class).invoke(provider,player.getUniqueId(),block.getLocation(),right));
            }
        } catch(ReflectiveOperationException | RuntimeException failure) { return false; }
        return false;
    }
    static Block lookedAt(Player player) {
        Location eye=player.getEyeLocation(); var dir=eye.getDirection();
        for(double d=0;d<=6;d+=0.2) { Location at=eye.clone().add(dir.clone().multiply(d)); World world=at.getWorld();
            if(!world.isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4)) return null;
            Block block=world.getBlockAt(at.getBlockX(),at.getBlockY(),at.getBlockZ()); if(!air(block.getType())) return block;
        } return null;
    }
    /** True when at least one pruned leaf of the tree is due for regrowth; cheap check before any world or player lookup. */
    static boolean hasDueLeaf(GardenState.Tree tree,long now) {
        for(GardenState.Leaf leaf:tree.leaves()) if(leaf.dueMillis()!=0 && leaf.dueMillis()<=now) return true;
        return false;
    }
    private boolean rootPresent(GardenState.Tree tree,World world) { Block root=loaded(world,tree.root()); return root!=null && root.getType().name().equals(tree.rootMaterial()); }
    private static Block loaded(World world,GardenState.Pos p) { return p.y()<world.getMinHeight() || p.y()>=world.getMaxHeight() || !world.isChunkLoaded(p.x()>>4,p.z()>>4)?null:world.getBlockAt(p.x(),p.y(),p.z()); }
    private static boolean air(Material material) { return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR; }
    private static GardenState.Pos pos(Block block) { return new GardenState.Pos(block.getX(),block.getY(),block.getZ()); }
    private static GardenState.Tree copy(GardenState.Tree tree,List<GardenState.Leaf> leaves) { return new GardenState.Tree(tree.id(),tree.owner(),tree.world(),tree.root(),tree.rootMaterial(),leaves); }
    private void forget(Collection<Block> blocks) {
        if(!store.healthy() || ownBreak) return;
        for(GardenState.Tree tree:store.trees()) {
            Set<GardenState.Pos> affected=new HashSet<>(); for(Block b:blocks) if(tree.world().equals(b.getWorld().getUID())) affected.add(pos(b));
            try {
                if(affected.contains(tree.root())) { store.remove(tree.id()); continue; }
                List<GardenState.Leaf> leaves=tree.leaves().stream().filter(l->!affected.contains(l.pos())).toList();
                if(leaves.size()!=tree.leaves().size()) { if(leaves.isEmpty()) store.remove(tree.id()); else store.put(copy(tree,leaves)); }
            } catch(Exception failure) { report(failure); return; }
        }
    }
    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * Both only forget stored garden trees: OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::broken);
        stages.handle(owner, BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, this::placed);
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent e) { forget(List.of(e.getBlock())); }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void placed(BlockPlaceEvent e) { forget(List.of(e.getBlock())); }
    /** Fire, explosions, pistons and entity block changes: forget what is gone. OBSERVE, never a cancelled event. */
    void handleWorldEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, BlockBurnEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (BlockBurnEvent e) -> burn(e));
        stages.handle(owner, EntityChangeBlockEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (EntityChangeBlockEvent e) -> entityChange(e));
        stages.handle(owner, BlockExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (BlockExplodeEvent e) -> explode(e));
        stages.handle(owner, EntityExplodeEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (EntityExplodeEvent e) -> explode(e));
        stages.handle(owner, BlockPistonExtendEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (BlockPistonExtendEvent e) -> piston(e));
        stages.handle(owner, BlockPistonRetractEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE, (BlockPistonRetractEvent e) -> piston(e));
    }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void burn(BlockBurnEvent e) { forget(List.of(e.getBlock())); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void decay(LeavesDecayEvent e) { forget(List.of(e.getBlock())); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void entityChange(EntityChangeBlockEvent e) { forget(List.of(e.getBlock())); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void explode(BlockExplodeEvent e) { forget(e.blockList()); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void explode(EntityExplodeEvent e) { forget(e.blockList()); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void piston(BlockPistonExtendEvent e) { forget(e.getBlocks()); }
    /* Stage OBSERVE, registered in handleWorldEvents */ public void piston(BlockPistonRetractEvent e) { forget(e.getBlocks()); }
    private void report(Exception failure) { plugin.getLogger().log(java.util.logging.Level.WARNING,"Garden tree persistence failed; world edits are halted if the store is unhealthy.",failure); }
}
