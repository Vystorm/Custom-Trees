package at.esoren.customtrees;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Tracks chunks that were generated during this server run and have not been
 * touched by anyone yet. The new-chunk pass may only change blocks inside such
 * chunks: everything in them is still naturally generated. A chunk stops being
 * fresh as soon as a block is placed or broken in it, when it unloads, when
 * players spent more than {@link #MAX_INHABITED_TICKS} ticks near it, or after
 * {@link #WINDOW_TICKS}. Main thread only.
 */
final class FreshChunks {
    /** How long after generation a chunk may still be rewritten by the pass. */
    static final long WINDOW_TICKS = 20L * 60L;
    /** Inhabited time (player presence) after which a chunk is no longer treated as untouched. */
    static final long MAX_INHABITED_TICKS = 20L * 10L;

    private final Map<ChunkKey, Long> generatedAt = new HashMap<>();
    private final Map<ChunkKey, Set<Long>> passBlocks = new HashMap<>();

    void generated(ChunkKey key, long tick) {
        generatedAt.put(key, tick);
    }

    /** Called when a chunk unloads or anybody changes a block in it. */
    void forget(ChunkKey key) {
        generatedAt.remove(key);
        passBlocks.remove(key);
    }

    boolean isFresh(ChunkKey key, long tick, long inhabitedTicks) {
        Long generated = generatedAt.get(key);
        if (generated == null) {
            return false;
        }
        if (tick - generated > WINDOW_TICKS || inhabitedTicks > MAX_INHABITED_TICKS) {
            forget(key);
            return false;
        }
        return true;
    }

    boolean isTracked(ChunkKey key) {
        return generatedAt.containsKey(key);
    }

    /** Remembers a block written by the pass so later passes of neighbour chunks keep it. */
    void recordPassBlock(ChunkKey key, int x, int y, int z) {
        if (generatedAt.containsKey(key)) {
            passBlocks.computeIfAbsent(key, ignored -> new HashSet<>()).add(pack(x, y, z));
        }
    }

    boolean isPassBlock(ChunkKey key, int x, int y, int z) {
        Set<Long> blocks = passBlocks.get(key);
        return blocks != null && blocks.contains(pack(x, y, z));
    }

    void expire(long tick) {
        Iterator<Map.Entry<ChunkKey, Long>> iterator = generatedAt.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, Long> entry = iterator.next();
            if (tick - entry.getValue() > WINDOW_TICKS) {
                passBlocks.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    int size() {
        return generatedAt.size();
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }
}
