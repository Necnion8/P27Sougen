package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;

import java.util.UUID;

public record SougenMap(
        String id,
        SougenSchematic schematic,
        UUID destWorldId,
        BlockPosition destPosition
) {
}
