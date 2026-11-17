package at.esoren.customtrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ExplosionResult;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The handlers of the other shared events (fire, explosions, pistons, entity block changes, clicks), called by Vystorm
 * Core's real stage dispatcher in the order of CustomTreesPlugin.onEnable, together with a second plugin.
 */
class SharedEventStagesTest {
    private final Plugin trees = EventStageTesting.plugin("CustomTrees"), other = EventStageTesting.plugin("AProtectionPlugin");
    private final Block block = mock(Block.class);
    private final List<String> calls = new ArrayList<>();

    // every listener with its real registration and silent handlers
    private final GardenTrees gardens = mock(GardenTrees.class, CALLS_REAL_METHODS);
    private final GlowstoneGuard glowstone = mock(GlowstoneGuard.class, CALLS_REAL_METHODS);
    private final GrowthPlanting plantings = mock(GrowthPlanting.class, CALLS_REAL_METHODS);
    private final TreeFireGuard fireGuard = mock(TreeFireGuard.class, CALLS_REAL_METHODS);
    private final TreeShaking shaking = mock(TreeShaking.class, CALLS_REAL_METHODS);
    private final FruitTrees fruit = mock(FruitTrees.class, CALLS_REAL_METHODS);
    private final GiantSaplingListener saplings = mock(GiantSaplingListener.class, CALLS_REAL_METHODS);

    /** The world handlers in the order of CustomTreesPlugin.onEnable; the guards refuse on demand. */
    private EventStageTesting worldHandlers(boolean refuse) {
        doAnswer(call -> { if (refuse) call.getArgument(0, BlockBurnEvent.class).setCancelled(true); return null; }).when(fireGuard).onBlockBurn(any());
        doAnswer(call -> { if (refuse) call.getArgument(0, BlockIgniteEvent.class).setCancelled(true); return null; }).when(fireGuard).onBlockIgnite(any());
        doNothing().when(fireGuard).onBlockSpread(any());
        doNothing().when(fireGuard).onBlockBurned(any());
        doNothing().when(fireGuard).onBlockExplode(any());
        doNothing().when(fireGuard).onEntityExplode(any());
        doNothing().when(fireGuard).onEntityChangeBlock(any());
        doNothing().when(fireGuard).onPistonExtend(any());
        doNothing().when(fireGuard).onPistonRetract(any());
        doNothing().when(plantings).exploded(any(BlockExplodeEvent.class));
        doNothing().when(plantings).exploded(any(EntityExplodeEvent.class));
        doNothing().when(plantings).burned(any());
        doNothing().when(plantings).changed(any());
        doNothing().when(plantings).extended(any());
        doNothing().when(plantings).retracted(any());
        doNothing().when(gardens).burn(any());
        doNothing().when(gardens).entityChange(any());
        doNothing().when(gardens).explode(any(BlockExplodeEvent.class));
        doNothing().when(gardens).explode(any(EntityExplodeEvent.class));
        doNothing().when(gardens).piston(any(BlockPistonExtendEvent.class));
        doNothing().when(gardens).piston(any(BlockPistonRetractEvent.class));
        doNothing().when(glowstone).onBlockExplode(any());
        doNothing().when(glowstone).onEntityExplode(any());
        doAnswer(call -> { if (refuse) call.getArgument(0, BlockPistonExtendEvent.class).setCancelled(true); return null; }).when(glowstone).onPistonExtend(any());
        doNothing().when(glowstone).onPistonRetract(any());
        doAnswer(call -> { if (refuse) call.getArgument(0, EntityChangeBlockEvent.class).setCancelled(true); return null; }).when(glowstone).onEntityChangeBlock(any());
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
        fireGuard.handleWorldEvents(trees, stages);
        plantings.handleWorldEvents(trees, stages);
        gardens.handleWorldEvents(trees, stages);
        glowstone.handleWorldEvents(trees, stages);
        return harness;
    }

    private BlockExplodeEvent explosion() {
        return new BlockExplodeEvent(block, mock(BlockState.class), new ArrayList<>(List.of(block)), 1f, ExplosionResult.DESTROY);
    }

    @Test
    void givenProtectedTreeBlock_whenItWouldBurn_thenTheBurnIsDeniedAndNoBookkeepingOfCustomTreesRuns() {
        EventStageTesting harness = worldHandlers(true);
        harness.handle(other, BlockBurnEvent.class, EventStage.ACT, event -> calls.add("act"));
        harness.handle(other, BlockBurnEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));

        assertTrue(harness.fire(new BlockBurnEvent(block, null)).isCancelled());
        assertTrue(harness.fire(new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.SPREAD, block)).isCancelled());

        verify(fireGuard, never()).onBlockBurned(any());
        verify(plantings, never()).burned(any());
        verify(gardens, never()).burn(any());
        assertEquals(List.of(), calls, "the features of another plugin do not act on a burn the fire guard denied");
    }

    @Test
    void givenUnprotectedBlock_whenItBurns_thenTheGuardDecidesFirstAndTheBookkeepingFollowsInTheOldOrder() {
        EventStageTesting harness = worldHandlers(false);

        BlockBurnEvent burn = harness.fire(new BlockBurnEvent(block, null));

        assertFalse(burn.isCancelled());
        InOrder order = inOrder(fireGuard, plantings, gardens);
        order.verify(fireGuard).onBlockBurn(burn);     // PROTECT
        order.verify(fireGuard).onBlockBurned(burn);   // OBSERVE, in registration order
        order.verify(plantings).burned(burn);
        order.verify(gardens).burn(burn);
    }

    @Test
    void givenBurnDeniedByAnotherPlugin_whenFired_thenNoHandlerOfCustomTreesRuns() {
        EventStageTesting harness = worldHandlers(false);
        // "AProtectionPlugin" sorts before "CustomTrees" inside PROTECT
        harness.handle(other, BlockBurnEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        harness.fire(new BlockBurnEvent(block, null));

        verify(fireGuard, never()).onBlockBurn(any());
        verify(fireGuard, never()).onBlockBurned(any());
        verify(plantings, never()).burned(any());
        verify(gardens, never()).burn(any());
    }

    @Test
    void givenTreeGlowstone_whenAPistonOrAnEntityWouldMoveIt_thenItIsRefusedAndTheMarkersStay() {
        EventStageTesting harness = worldHandlers(true);

        assertTrue(harness.fire(new BlockPistonExtendEvent(block, List.of(block), BlockFace.UP)).isCancelled());
        assertTrue(harness.fire(new EntityChangeBlockEvent(mock(Entity.class), block, mock(BlockData.class))).isCancelled());

        verify(fireGuard, never()).onPistonExtend(any());
        verify(plantings, never()).extended(any());
        verify(gardens, never()).piston(any(BlockPistonExtendEvent.class));
        verify(fireGuard, never()).onEntityChangeBlock(any());
        verify(plantings, never()).changed(any());
        verify(gardens, never()).entityChange(any());
    }

    @Test
    void givenExplosion_whenFired_thenGlowstoneIsRemovedAfterEveryFeatureAndNotAtAllWhenTheExplosionWasDenied() {
        EventStageTesting harness = worldHandlers(false);
        boolean[] deny = {false};
        harness.handle(other, BlockExplodeEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        harness.handle(other, BlockExplodeEvent.class, EventStage.ACT, event -> calls.add("act"));
        doAnswer(call -> { calls.add("glowstone"); return null; }).when(glowstone).onBlockExplode(any());

        BlockExplodeEvent explosion = harness.fire(explosion());

        assertEquals(List.of("act", "glowstone"), calls);
        InOrder order = inOrder(glowstone, fireGuard, plantings, gardens);
        order.verify(glowstone).onBlockExplode(explosion);   // FINISH
        order.verify(fireGuard).onBlockExplode(explosion);   // OBSERVE, in registration order
        order.verify(plantings).exploded(explosion);
        order.verify(gardens).explode(explosion);

        deny[0] = true;
        BlockExplodeEvent denied = harness.fire(explosion());
        verify(glowstone, never()).onBlockExplode(denied);
        verify(fireGuard, never()).onBlockExplode(denied);
        verify(plantings, never()).exploded(denied);
        verify(gardens, never()).explode(denied);
    }

    /** The click features in the order of CustomTreesPlugin.onEnable. */
    private EventStageTesting clickHandlers() {
        doNothing().when(shaking).shake(any());
        doNothing().when(fruit).mark(any());
        doNothing().when(fruit).harvest(any());
        doNothing().when(saplings).onGrowthCatalyst(any());
        EventStageTesting harness = EventStageTesting.detached();
        shaking.handleInteract(trees, harness.stages());
        fruit.handleInteract(trees, harness.stages());
        saplings.handleInteract(trees, harness.stages());
        return harness;
    }

    private PlayerInteractEvent click() {
        return new PlayerInteractEvent(mock(Player.class), Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP);
    }

    @Test
    void givenAllowedClick_whenFired_thenTheFeaturesActInTheOldOrderAfterProtection() {
        EventStageTesting harness = clickHandlers();
        harness.handle(other, PlayerInteractEvent.class, EventStage.PROTECT, event -> calls.add("protect"));
        doAnswer(call -> { calls.add("shake"); return null; }).when(shaking).shake(any());

        PlayerInteractEvent click = harness.fire(click());

        assertEquals(List.of("protect", "shake"), calls);
        InOrder order = inOrder(shaking, fruit, saplings);
        order.verify(shaking).shake(click);
        order.verify(fruit).mark(click);
        order.verify(fruit).harvest(click);
        order.verify(saplings).onGrowthCatalyst(click);
    }

    @Test
    void givenDeniedClick_whenFired_thenShakingAndGrowingDoNotActAndTheFruitHandlersStillDecideThemselves() {
        EventStageTesting harness = clickHandlers();
        harness.handle(other, PlayerInteractEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        PlayerInteractEvent click = harness.fire(click());

        assertTrue(click.isCancelled());
        verify(shaking, never()).shake(any());
        verify(saplings, never()).onGrowthCatalyst(any());
        // registered with receiveCancelled = true, like their old ignoreCancelled = false
        verify(fruit).mark(click);
        verify(fruit).harvest(click);
    }

    @Test
    void givenFruitMarkedOnASapling_whenTheSameClickReachesTheCatalyst_thenItIsAlreadyConsumed() {
        EventStageTesting harness = clickHandlers();
        doAnswer(call -> { call.getArgument(0, PlayerInteractEvent.class).setCancelled(true); return null; }).when(fruit).mark(any());

        harness.fire(click());

        verify(saplings, never()).onGrowthCatalyst(any());
    }
}
