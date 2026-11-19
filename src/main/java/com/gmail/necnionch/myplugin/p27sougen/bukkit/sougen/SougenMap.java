package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public record SougenMap(
        String id,
        SougenSchematic schematic,
        UUID destWorldId,
        BlockPosition destPosition,
        Config config,
        Multimap<ChunkKey, BlockPosition> chunkCache
) {

    public SougenMap(String id, SougenSchematic schematic, UUID destWorldId, BlockPosition destPosition, Config config) {
        this(id, schematic, destWorldId, destPosition, config, createChunkCache(schematic));
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

    public record Config(
            String id,
            @Nullable String name,
            Path filePath,
            List<String> hologramIds,
            List<String> portalIds
    ) {
    }

}
