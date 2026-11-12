package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import java.util.Map;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public record BlockPosition(int x, int y, int z) {

    public static BlockPosition of(com.comphenix.protocol.wrappers.BlockPosition bp) {
        return new BlockPosition(bp.getX(), bp.getY(), bp.getZ());
    }

    public com.comphenix.protocol.wrappers.BlockPosition toProtocolLib() {
        return new com.comphenix.protocol.wrappers.BlockPosition(x, y, z);
    }

    @SuppressWarnings("UnstableApiUsage")
    public static BlockPosition of(io.papermc.paper.math.Position p) {
        return new BlockPosition(p.blockX(), p.blockY(), p.blockZ());
    }

    @SuppressWarnings("UnstableApiUsage")
    public io.papermc.paper.math.BlockPosition toPaper() {
        return io.papermc.paper.math.Position.block(x, y, z);
    }

    @SuppressWarnings("UnstableApiUsage")
    public static <V> Map<io.papermc.paper.math.BlockPosition, V> mapToPaper(Map<BlockPosition, V> map) {
        return map
                .entrySet()
                .stream()
                .collect(Collectors.toMap(e -> e.getKey().toPaper(), Map.Entry::getValue));
    }

}
