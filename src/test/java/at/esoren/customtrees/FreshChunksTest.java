package at.esoren.customtrees;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreshChunksTest {
    private final UUID world = UUID.randomUUID();
    private final ChunkKey key = new ChunkKey(world, 3, -2);

    @Test
    void onlyChunksGeneratedInThisRunAreFresh() {
        FreshChunks fresh = new FreshChunks();
        assertFalse(fresh.isFresh(key, 0, 0));
        fresh.generated(key, 100);
        assertTrue(fresh.isFresh(key, 100 + FreshChunks.WINDOW_TICKS, FreshChunks.MAX_INHABITED_TICKS));
    }

    @Test
    void windowInhabitedTimeAndPlayerChangesEndFreshness() {
        FreshChunks fresh = new FreshChunks();
        fresh.generated(key, 0);
        assertFalse(fresh.isFresh(key, FreshChunks.WINDOW_TICKS + 1, 0));
        assertFalse(fresh.isTracked(key));

        fresh.generated(key, 0);
        assertFalse(fresh.isFresh(key, 1, FreshChunks.MAX_INHABITED_TICKS + 1));

        fresh.generated(key, 0);
        fresh.forget(key);
        assertFalse(fresh.isFresh(key, 1, 0));
    }

    @Test
    void passBlocksAreRememberedPerFreshChunkAndDroppedWithIt() {
        FreshChunks fresh = new FreshChunks();
        fresh.recordPassBlock(key, 50, 70, -30);
        assertFalse(fresh.isPassBlock(key, 50, 70, -30), "untracked chunks keep no pass blocks");
        fresh.generated(key, 0);
        fresh.recordPassBlock(key, 50, 70, -30);
        assertTrue(fresh.isPassBlock(key, 50, 70, -30));
        assertFalse(fresh.isPassBlock(key, 50, 71, -30));
        fresh.expire(FreshChunks.WINDOW_TICKS + 1);
        assertEquals(0, fresh.size());
        assertFalse(fresh.isPassBlock(key, 50, 70, -30));
    }

    @Test
    void packingSeparatesNegativeCoordinates() {
        assertNotEquals(FreshChunks.pack(-1, 64, 0), FreshChunks.pack(0, 64, -1));
        assertNotEquals(FreshChunks.pack(1, -64, 1), FreshChunks.pack(1, 64, 1));
    }
}
