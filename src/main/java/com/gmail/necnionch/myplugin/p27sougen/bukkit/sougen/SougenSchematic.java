package com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen;

import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record SougenSchematic(
        List<BlockPosition> positionList,
        List<BlockData> blockDataList,
        List<WrappedBlockData> packetBlockDataList
) {

    public static SougenSchematic fromSchematicFile(Path schematicPath) throws IOException {
        ClipboardFormat format = ClipboardFormats.findByPath(schematicPath);
        if (format == null)
            throw new IOException("Unsupported schematic file: " + schematicPath);

        try (InputStream is = Files.newInputStream(schematicPath);
             ClipboardReader reader = format.getReader(is)) {

            Clipboard clipboard = reader.read();
            BlockVector3 min = clipboard.getMinimumPoint();
            BlockVector3 max = clipboard.getMaximumPoint();
            BlockVector3 origin = clipboard.getOrigin();

            List<BlockPosition> positionList = new ArrayList<>();
            List<BlockData> blockDataList = new ArrayList<>();
            List<WrappedBlockData> packetBlockDataList = new ArrayList<>();

            for (int x = min.x(); x <= max.x(); x++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    for (int z = min.z(); z <= max.z(); z++) {
                        BlockVector3 vec = BlockVector3.at(x, y, z);
                        BaseBlock baseBlock = clipboard.getFullBlock(vec);

                        if (baseBlock.getBlockType().getMaterial().isAir())
                            continue;

                        positionList.add(new BlockPosition(x - origin.x(), y - origin.y(), z - origin.z()));
                        BlockData blockData = BukkitAdapter.adapt(baseBlock);
                        blockDataList.add(blockData);
                        packetBlockDataList.add(WrappedBlockData.createData(blockData));

                    }
                }
            }

            return new SougenSchematic(positionList, blockDataList, packetBlockDataList);
        }
    }

}
