package at.esoren.customtrees;

import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.StructurePos;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.WorldTreePlan;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldTreeGrowthTest {
    @Test void largestCompleteFungusSquareWinsAtEverySizeAndCorner() {
        for(Material plant:List.of(Material.RED_MUSHROOM,Material.BROWN_MUSHROOM,Material.CRIMSON_FUNGUS,Material.WARPED_FUNGUS))
            for(int width:List.of(1,2,3,5,10)) {
                World world=fixture(plant,width);
                for(int x:List.of(0,width-1))for(int z:List.of(0,width-1)) {
                    var formation=WorldTreeGrowth.find(world.getBlockAt(x,64,z));
                    assertNotNull(formation);assertEquals(width,formation.width());
                    assertEquals(width*width,WorldTreeGrowth.members(formation).size());
                }
            }
        assertNull(WorldTreeGrowth.find(fixture(Material.OAK_SAPLING,3).getBlockAt(0,64,0)));
    }
    @Test void detectsCompleteFormationFromEveryCornerForTreesMushroomsAndNetherFungi() {
        for(Material plant:List.of(Material.OAK_SAPLING,Material.RED_MUSHROOM,Material.BROWN_MUSHROOM,Material.CRIMSON_FUNGUS,Material.WARPED_FUNGUS)) {
            World world=fixture(plant);
            for(int x:List.of(0,9))for(int z:List.of(0,9)) {
                var result=WorldTreeGrowth.find(world.getBlockAt(x,64,z));
                assertNotNull(result);assertEquals(100,WorldTreeGrowth.members(result).size());
                assertEquals(0,result.origin().x());assertEquals(0,result.origin().z());assertEquals(plant,result.plant());
            }
        }
    }
    @Test void rejectsMixedIncompleteAndUnloadedFormationsWithoutLoadingChunks() {
        World world=fixture(Material.WARPED_FUNGUS);
        Block member=world.getBlockAt(0,64,0);
        Block missing=mock(Block.class);when(missing.getType()).thenReturn(Material.CRIMSON_FUNGUS);
        when(world.getBlockAt(9,64,9)).thenReturn(missing);
        assertNull(WorldTreeGrowth.find(member,10));
        world=fixture(Material.OAK_SAPLING);member=world.getBlockAt(0,64,0);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
        clearInvocations(world);
        assertNull(WorldTreeGrowth.find(member,10));
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
    @Test void defaultsAndInvalidConfigRespectSizeAndTickLimits() {
        var config=new YamlConfiguration();var settings=WorldTreeSettings.load(config);
        assertEquals(160,settings.height(Material.OAK_SAPLING,World.Environment.NORMAL));
        assertEquals(44,settings.radius(Material.OAK_SAPLING));
        config.set("world-trees.tree-radius",48);assertEquals(48,WorldTreeSettings.load(config).radius(Material.OAK_SAPLING));
        config.set("world-trees.tree-radius",49);assertThrows(TreeSettingsException.class,()->WorldTreeSettings.load(config));
        config.set("world-trees.tree-radius",44);
        assertEquals(80,settings.height(Material.RED_MUSHROOM,World.Environment.NORMAL));
        assertEquals(48,settings.height(Material.CRIMSON_FUNGUS,World.Environment.NETHER));
        assertEquals(512,settings.blocksPerTick());
        for(Object bad:List.of(0,4097,512.5,"512")) {
            config.set("world-trees.blocks-per-tick",bad);
            assertThrows(TreeSettingsException.class,()->WorldTreeSettings.load(config));
        }
    }
    @Test void hangingVinesInheritTheFaceOfTheVineAttachedAboveThem() {
        Map<StructurePos,Material> plan=new HashMap<>();
        plan.put(new StructurePos(0,10,0),Material.JUNGLE_LEAVES);
        for(int y=10;y>=5;y--)plan.put(new StructurePos(1,y,0),Material.VINE);
        for(int y=10;y>=5;y--)assertEquals(Set.of(org.bukkit.block.BlockFace.WEST),WorldTreeGrowth.vineFaces(plan,new StructurePos(1,y,0)));
        plan.put(new StructurePos(5,20,5),Material.JUNGLE_LEAVES);plan.put(new StructurePos(5,19,5),Material.VINE);plan.put(new StructurePos(5,18,5),Material.VINE);
        assertEquals(Set.of(org.bukkit.block.BlockFace.UP),WorldTreeGrowth.vineFaces(plan,new StructurePos(5,19,5)));
        assertTrue(WorldTreeGrowth.vineFaces(plan,new StructurePos(5,18,5)).isEmpty(),"unsupported vines are left out");
    }
    private static WorldTreeGrowth.Fit fit(Material old) { return WorldTreeGrowth.fit(old,false,false,Material.OAK_SAPLING,false,false); }
    @Test void obstaclesAreNeverBlockingOnlyPlacedOrSkipped() {
        assertEquals(List.of(WorldTreeGrowth.Fit.PLACE,WorldTreeGrowth.Fit.SKIP),List.of(WorldTreeGrowth.Fit.values()));
        for(Material any:Material.values()) if(!any.isLegacy())
            for(boolean root:new boolean[]{false,true}) for(boolean stone:new boolean[]{false,true})
                assertNotNull(WorldTreeGrowth.fit(any,false,false,Material.OAK_SAPLING,root,stone),any.toString());
        assertEquals(WorldTreeGrowth.Fit.PLACE,WorldTreeGrowth.fit(Material.OAK_SAPLING,false,true,Material.OAK_SAPLING,false,false));
    }
    @Test void plantsCoverLeavesAndSoftNaturalGroundAreReplaced() {
        for(Material soft:List.of(Material.AIR,Material.CAVE_AIR,Material.SHORT_GRASS,Material.TALL_GRASS,Material.FERN,Material.POPPY,
                Material.BIRCH_LEAVES,Material.SPRUCE_SAPLING,Material.MANGROVE_PROPAGULE,Material.SNOW,Material.SNOW_BLOCK,Material.POWDER_SNOW,
                Material.VINE,Material.MOSS_BLOCK,Material.MOSS_CARPET,Material.DIRT,Material.GRASS_BLOCK,Material.COARSE_DIRT,
                Material.ROOTED_DIRT,Material.PODZOL,Material.MYCELIUM,Material.DIRT_PATH,Material.SAND,Material.RED_SAND,Material.GRAVEL,
                Material.CLAY,Material.MUD,Material.MUDDY_MANGROVE_ROOTS))
            assertEquals(WorldTreeGrowth.Fit.PLACE,fit(soft),soft.toString());
    }
    @Test void buildingsValuablesOtherTreesFluidsAndHardTerrainAreSkipped() {
        for(Material keep:List.of(Material.FARMLAND,Material.ICE,Material.PACKED_ICE,Material.STONE,Material.DEEPSLATE,Material.SANDSTONE,
                Material.IRON_ORE,Material.DEEPSLATE_DIAMOND_ORE,Material.OAK_PLANKS,Material.COBBLESTONE,Material.STONE_BRICKS,Material.GLASS,
                Material.CHEST,Material.BARREL,Material.FURNACE,Material.RED_BED,Material.OAK_SIGN,Material.RAIL,Material.REDSTONE_WIRE,
                Material.BEDROCK,Material.BARRIER,Material.NETHER_PORTAL,Material.END_PORTAL_FRAME,Material.SPRUCE_LOG,Material.OAK_WOOD,
                Material.WATER,Material.LAVA,Material.SEAGRASS,Material.KELP_PLANT,Material.WHEAT))
            assertEquals(WorldTreeGrowth.Fit.SKIP,fit(keep),keep.toString());
        // Waterlogged leaves keep their water for crowns.
        assertEquals(WorldTreeGrowth.Fit.SKIP,WorldTreeGrowth.fit(Material.MANGROVE_LEAVES,true,false,Material.OAK_SAPLING,false,false));
        assertTrue(WorldTreeGrowth.woodLike(Material.OAK_WOOD));assertTrue(WorldTreeGrowth.woodLike(Material.MANGROVE_ROOTS));
        assertFalse(WorldTreeGrowth.woodLike(Material.OAK_LEAVES));
    }
    @Test void replaceStoneOptionReplacesNaturalStoneButNeverOres() {
        for(Material stone:List.of(Material.STONE,Material.DEEPSLATE,Material.ANDESITE,Material.DIORITE,Material.GRANITE,Material.TUFF,
                Material.TERRACOTTA,Material.ORANGE_TERRACOTTA,Material.SANDSTONE,Material.RED_SANDSTONE))
            assertEquals(WorldTreeGrowth.Fit.PLACE,WorldTreeGrowth.fit(stone,false,false,Material.OAK_SAPLING,false,true),stone.toString());
        for(Material keep:List.of(Material.IRON_ORE,Material.DEEPSLATE_COAL_ORE,Material.ANCIENT_DEBRIS,Material.COBBLESTONE,Material.STONE_BRICKS,Material.BEDROCK))
            assertEquals(WorldTreeGrowth.Fit.SKIP,WorldTreeGrowth.fit(keep,false,false,Material.OAK_SAPLING,true,true),keep.toString());
        var config=new YamlConfiguration();
        assertFalse(WorldTreeSettings.load(config).replaceStone());
        config.set("world-trees.replace-stone",true);assertTrue(WorldTreeSettings.load(config).replaceStone());
    }
    @Test void rootsAndTrunkBaseGrowThroughWaterButCrownsDoNot() {
        for(Material water:List.of(Material.WATER,Material.SEAGRASS,Material.TALL_SEAGRASS,Material.KELP,Material.KELP_PLANT,Material.BUBBLE_COLUMN)) {
            assertEquals(WorldTreeGrowth.Fit.PLACE,WorldTreeGrowth.fit(water,false,false,Material.MANGROVE_PROPAGULE,true,false),water.toString());
            assertEquals(WorldTreeGrowth.Fit.SKIP,WorldTreeGrowth.fit(water,false,false,Material.MANGROVE_PROPAGULE,false,false),water.toString());
        }
        assertEquals(WorldTreeGrowth.Fit.PLACE,WorldTreeGrowth.fit(Material.MANGROVE_LEAVES,true,false,Material.MANGROVE_PROPAGULE,true,false));
        assertEquals(WorldTreeGrowth.Fit.SKIP,WorldTreeGrowth.fit(Material.LAVA,false,false,Material.OAK_SAPLING,true,false));
        assertEquals(WorldTreeGrowth.Fit.SKIP,WorldTreeGrowth.fit(Material.CHEST,true,false,Material.OAK_SAPLING,true,false));
        assertTrue(WorldTreeGrowth.rootBlock(new StructurePos(0,50,0),Material.OAK_LOG,64));
        assertTrue(WorldTreeGrowth.rootBlock(new StructurePos(0,65,0),Material.MANGROVE_ROOTS,64));
        assertFalse(WorldTreeGrowth.rootBlock(new StructurePos(0,66,0),Material.OAK_LOG,64));
        assertFalse(WorldTreeGrowth.rootBlock(new StructurePos(0,60,0),Material.OAK_LEAVES,64));
    }
    @Test void skipLimitDefaultsToNeverRefusingAndIsConfigurable() {
        var config=new YamlConfiguration();
        assertEquals(100,WorldTreeSettings.load(config).maxSkippedPercent());
        config.set("world-trees.max-skipped-percent",30);assertEquals(30,WorldTreeSettings.load(config).maxSkippedPercent());
        config.set("world-trees.max-skipped-percent",101);assertThrows(TreeSettingsException.class,()->WorldTreeSettings.load(config));
    }
    @Test void tooTallTreesShrinkToFitTheBuildLimitAndRefuseOnlyBelowTheMinimum() {
        World world=fixture(Material.OAK_SAPLING);when(world.getMaxHeight()).thenReturn(320);when(world.getMinHeight()).thenReturn(-64);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        assertEquals(new WorldTreeGrowth.Size(160,44,false),WorldTreeGrowth.fit(formation(world,64),160,44));
        assertEquals(new WorldTreeGrowth.Size(120,33,true),WorldTreeGrowth.fit(formation(world,200),160,44));
        assertEquals(new WorldTreeGrowth.Size(32,12,true),WorldTreeGrowth.fit(formation(world,288),160,44),"radius keeps Core's minimum");
        assertNull(WorldTreeGrowth.fit(formation(world,289),160,44));
        // Underground roots: shrink until the root depth fits above the world bottom where possible.
        var low=WorldTreeGrowth.fit(formation(world,-54),160,44);
        assertTrue(low.shrunk() && low.height()<160,low.toString());
        assertTrue(WorldTreePlan.depth(Material.OAK_SAPLING,low.height(),low.radius())<=10,low.toString());
        var bottom=WorldTreeGrowth.fit(formation(world,-62),160,44);
        assertEquals(32,bottom.height(),"roots below the world bottom are left out instead of refusing");
        // Nether roof below the build limit.
        when(world.getEnvironment()).thenReturn(World.Environment.NETHER);when(world.getMaxHeight()).thenReturn(256);
        when(world.getMinHeight()).thenReturn(0);when(world.getLogicalHeight()).thenReturn(128);
        var nether=new WorldTreeGrowth.Formation(world,new StructurePos(0,90,0),Material.CRIMSON_FUNGUS);
        assertEquals(new WorldTreeGrowth.Size(38,14,true),WorldTreeGrowth.fit(nether,48,18));
        assertNull(WorldTreeGrowth.fit(new WorldTreeGrowth.Formation(world,new StructurePos(0,100,0),Material.CRIMSON_FUNGUS),48,18));
        assertEquals(28,WorldTreeGrowth.topLimit(world,100)-100);
        assertEquals(256,WorldTreeGrowth.topLimit(world,130));
    }
    @Test void footprintChunksCoverThePlanAndStayBelowTheCap() {
        World world=fixture(Material.OAK_SAPLING);
        var chunks=WorldTreeGrowth.footprintChunks(formation(world,64),160,44);
        int extent=WorldTreePlan.footprint(Material.OAK_SAPLING,160,44);
        int side=((5+extent)>>4)-((5-extent)>>4)+1;
        assertEquals(side*side,chunks.size());
        assertTrue(chunks.stream().anyMatch(c->c[0]==(5-extent)>>4 && c[1]==(5-extent)>>4));
        assertTrue(chunks.stream().anyMatch(c->c[0]==(5+extent)>>4 && c[1]==(5+extent)>>4));
        assertTrue(WorldTreeGrowth.footprintChunks(formation(world,64),192,48).size()<=WorldTreeGrowth.MAX_CHUNKS);
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
    private static WorldTreeGrowth.Formation formation(World world,int y) {
        return new WorldTreeGrowth.Formation(world,new StructurePos(0,y,0),Material.OAK_SAPLING);
    }
    private static World fixture(Material plant) {
        return fixture(plant,10);
    }
    private static World fixture(Material plant,int width) {
        World world=mock(World.class);Map<String,Block> blocks=new HashMap<>();
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return blocks.computeIfAbsent(x+":"+y+":"+z,key->{
                Block block=mock(Block.class);when(block.getWorld()).thenReturn(world);
                when(block.getX()).thenReturn(x);when(block.getY()).thenReturn(y);when(block.getZ()).thenReturn(z);
                when(block.getType()).thenReturn(y==64 && x>=0 && x<width && z>=0 && z<width?plant:Material.AIR);
                return block;
            });
        });
        return world;
    }
}
