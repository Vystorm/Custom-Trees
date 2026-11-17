package at.esoren.customtrees;

import java.util.UUID;

/** World-scoped chunk coordinate. */
record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    static ChunkKey ofBlock(UUID worldId, int blockX, int blockZ) {
        return new ChunkKey(worldId, blockX >> 4, blockZ >> 4);
    }
}
