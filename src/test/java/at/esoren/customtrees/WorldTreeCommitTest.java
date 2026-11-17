package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldTreeCommitTest {
    @Test void validationIsBudgetedAndDoesNotWriteOrConsumeItems() throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)) {
            Fixture f=new Fixture();
            bukkit.when(()->Bukkit.createBlockData(Material.OAK_LOG)).thenReturn(f.data);
            Map<StructurePos,Material> plan=new LinkedHashMap<>();
            for(int i=0;i<600;i++)plan.put(new StructurePos(i,100,0),Material.OAK_LOG);
            Object job=f.job(plan);
            f.invoke("validate",job);
            verify(f.world,times(512)).getBlockAt(anyInt(),anyInt(),anyInt());
            verify(f.state,never()).update(anyBoolean(),anyBoolean());
            verify(f.player,never()).getInventory();
        }
    }

    @Test void cancelledProtectionEventLeavesFormationAndCatalystUntouched() throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)) {
            Fixture f=new Fixture();
            bukkit.when(()->Bukkit.createBlockData(Material.OAK_LOG)).thenReturn(f.data);
            PluginManager manager=mock(PluginManager.class);bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            doAnswer(call->{((StructureGrowEvent)call.getArgument(0)).setCancelled(true);return null;}).when(manager).callEvent(any(StructureGrowEvent.class));
            when(f.block.getType()).thenReturn(Material.OAK_SAPLING);
            Block soil=mock(Block.class);when(soil.getType()).thenReturn(Material.DIRT);
            when(f.world.getBlockAt(anyInt(),eq(63),anyInt())).thenReturn(soil);
            Map<StructurePos,Material> plan=new LinkedHashMap<>();
            for(StructurePos p:WorldTreeGrowth.members(f.formation))plan.put(p,Material.OAK_LOG);
            f.invoke("validate",f.job(plan));
            verify(manager).callEvent(any(StructureGrowEvent.class));
            verify(f.state,never()).update(anyBoolean(),anyBoolean());
            verify(f.player,never()).getInventory();
        }
    }

    @Test void commitSkipsConcurrentPlayerEditsAndUnloadedChunks() throws Exception {
        Fixture f=new Fixture();
        BlockData original=mock(BlockData.class);
        Object job=f.job(Map.of(new StructurePos(0,64,0),Material.OAK_LOG));
        field(job,"accepted",List.of(f.state));field(job,"committing",true);
        field(job,"originals",new HashMap<>(Map.of(new StructurePos(0,64,0),original)));
        // Current data differs from the validated snapshot.
        f.invoke("commit",job);verify(f.state,never()).update(anyBoolean(),anyBoolean());
        when(f.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
        Object second=f.job(Map.of());field(second,"accepted",List.of(f.state));field(second,"committing",true);
        clearInvocations(f.world);
        f.invoke("commit",second);
        verify(f.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        verify(f.world,never()).getChunkAt(anyInt(),anyInt());
    }

    @Test void buildingsWaterAndBedrockInTheWayNeverAbortTheyAreLeftOut() throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)) {
            Fixture f=new Fixture();
            bukkit.when(()->Bukkit.createBlockData(any(Material.class))).thenReturn(f.data);
            PluginManager manager=mock(PluginManager.class);bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            List<StructureGrowEvent> fired=new ArrayList<>();
            doAnswer(call->{StructureGrowEvent e=call.getArgument(0);fired.add(e);e.setCancelled(true);return null;}).when(manager).callEvent(any(StructureGrowEvent.class));
            Map<String,Block> blocks=new HashMap<>();
            when(f.world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
                int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
                return blocks.computeIfAbsent(x+":"+y+":"+z,k->{
                    Material type=y==64?Material.OAK_SAPLING:y==63?Material.DIRT:switch(x) {
                        case 100->Material.CHEST; case 101->Material.BEDROCK; case 102->Material.LAVA; case 103->Material.OAK_PLANKS;
                        case 104->Material.WATER; case 105->Material.SPRUCE_LOG; case 106->Material.GLASS;
                        case 107->Material.GRASS_BLOCK; case 108->Material.SAND; default->Material.AIR; };
                    Block block=mock(Block.class);when(block.getType()).thenReturn(type);
                    BlockData data=mock(BlockData.class);when(data.clone()).thenReturn(data);when(block.getBlockData()).thenReturn(data);
                    BlockState state=mock(BlockState.class);when(state.getWorld()).thenReturn(f.world);
                    when(state.getX()).thenReturn(x);when(state.getY()).thenReturn(y);when(state.getZ()).thenReturn(z);
                    when(block.getState()).thenReturn(state);return block;
                });
            });
            Map<StructurePos,Material> plan=new LinkedHashMap<>();
            for(StructurePos p:WorldTreeGrowth.members(f.formation))plan.put(p,Material.OAK_LOG);
            for(int x=100;x<=110;x++)plan.put(new StructurePos(x,80,0),Material.OAK_LEAVES);
            Object job=f.job(plan);
            f.invoke("validate",job);
            assertEquals(1,fired.size(),"growth reaches the protection event despite obstacles");
            // 100 members + grass, sand and two air blocks are placed; chest, bedrock, lava, planks, water, log, glass left out.
            assertEquals(104,fired.get(0).getBlocks().size());
            var skipped=job.getClass().getDeclaredField("skippedPlan");skipped.setAccessible(true);
            assertEquals(7,skipped.getInt(job));
        }
    }

    @Test void unloadedFootprintIsLoadedAsynchronouslyHeldByTicketsAndReleasedAfterwards() throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)) {
            Fixture f=new Fixture();
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(f.world.getChunkAtAsync(anyInt(),anyInt(),eq(true))).thenAnswer(call->CompletableFuture.completedFuture(mock(Chunk.class)));
            var chunks=WorldTreeGrowth.footprintChunks(f.formation,160,44);
            Object job=f.job(Map.of());field(f.service,"job",job);
            f.service.loadChunks((WorldTreeGrowth.Job)job,chunks);
            for(long[] c:chunks) {
                verify(f.world).getChunkAtAsync((int)c[0],(int)c[1],true);
                verify(f.world).addPluginChunkTicket((int)c[0],(int)c[1],f.plugin);
            }
            verify(f.world,never()).getChunkAt(anyInt(),anyInt());
            verify(scheduler).runTaskAsynchronously(eq(f.plugin),any(Runnable.class));
            f.service.close();
            for(long[] c:chunks) verify(f.world).removePluginChunkTicket((int)c[0],(int)c[1],f.plugin);
        }
    }

    @Test void failedChunkLoadingMessagesThePlayerAndHoldsNothing() throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)) {
            Fixture f=new Fixture();
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(f.player.isOnline()).thenReturn(true);
            when(f.world.getChunkAtAsync(anyInt(),anyInt(),eq(true))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("io")));
            Object job=f.job(Map.of());field(f.service,"job",job);
            f.service.loadChunks((WorldTreeGrowth.Job)job,List.of(new long[]{0,0},new long[]{1,0}));
            verify(f.plugin).message(f.player,"world-tree.load-failed");
            verify(f.world,never()).addPluginChunkTicket(anyInt(),anyInt(),any());
            verify(f.player,never()).getInventory();
            var current=WorldTreeGrowth.class.getDeclaredField("job");current.setAccessible(true);
            assertNull(current.get(f.service),"job released for the next growth");
        }
    }

    static void field(Object object,String name,Object value)throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    static final class Fixture {
        final CustomTreesPlugin plugin=TestCore.plugin();
        final World world=mock(World.class);final Player player=mock(Player.class);
        final Block block=mock(Block.class);final BlockState state=mock(BlockState.class);final BlockData data=mock(BlockData.class);
        final WorldTreeGrowth service=new WorldTreeGrowth(plugin);
        final WorldTreeGrowth.Formation formation=new WorldTreeGrowth.Formation(world,new StructurePos(0,64,0),Material.OAK_SAPLING);
        Fixture() {
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("CustomTreesTest"));
            when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            var border=mock(WorldBorder.class);when(world.getWorldBorder()).thenReturn(border);when(border.isInside(any())).thenReturn(true);
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);
            Material air=Material.AIR;
            when(block.getType()).thenReturn(air);when(block.getBlockData()).thenReturn(data);when(data.clone()).thenReturn(data);
            when(block.getState()).thenReturn(state);when(state.getWorld()).thenReturn(world);when(state.getY()).thenReturn(64);
        }
        Object job(Map<StructurePos,Material> plan)throws Exception {
            Class<?> type=Class.forName("at.esoren.customtrees.WorldTreeGrowth$Job");
            var ctor=type.getDeclaredConstructor(WorldTreeGrowth.Formation.class,Player.class,WorldTreeSettings.class);ctor.setAccessible(true);
            Object result=ctor.newInstance(formation,player,WorldTreeSettings.load(new YamlConfiguration()));
            field(result,"plan",plan);field(result,"iterator",plan.entrySet().iterator());return result;
        }
        void invoke(String name,Object job)throws Exception {
            var method=WorldTreeGrowth.class.getDeclaredMethod(name,job.getClass());method.setAccessible(true);method.invoke(service,job);
        }
    }
}
