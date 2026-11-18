package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.util.UUID;

public record SougenMap(
        String id,
        SougenSchematic schematic,
        UUID destWorldId,
        BlockPosition destPosition,
        Multimap<ChunkKey, BlockPosition> chunkCache
) {

    public SougenMap(String id, SougenSchematic schematic, UUID destWorldId, BlockPosition destPosition) {
        this(id, schematic, destWorldId, destPosition, createChunkCache(schematic));
    }

    private static Multimap<ChunkKey, BlockPosition> createChunkCache(SougenSchematic schematic) {
        Multimap<ChunkKey, BlockPosition> tempCache = ArrayListMultimap.create();
        for (BlockPosition bp : schematic.positionList()) {
            tempCache.put(ChunkKey.of(bp), bp);
        }
        return Multimaps.unmodifiableMultimap(tempCache);
    }

    public record ChunkKey(int x, int z) {
        public static ChunkKey of(BlockPosition bp) {
            return new ChunkKey(bp.x() >> 4, bp.z() >> 4);
        }
    }

}
