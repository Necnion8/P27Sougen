package com.gmail.necnionch.myplugin.p27sougen.bukkit;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenSchematic;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

public final class SougenPlugin extends JavaPlugin implements Listener {

    private final SougenManager manager = new SougenManager(getLogger());

    @Override
    public void onEnable() {
        manager.setup(this);
        addSougenSchematics();
    }

    @Override
    public void onDisable() {
        manager.cleanup();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        if (args.length >= 1 && "set".equalsIgnoreCase(args[0])) {
            Player player = (Player) sender;
            String mapId = (args.length >= 2) ? args[1] : "test";
            manager.addPlayer(player, mapId);

        } else if (args.length == 1 && "unset".equalsIgnoreCase(args[0])) {
            Player player = (Player) sender;
            manager.removePlayer(player);
        }

        return true;
    }

    private void addSougenSchematics() {
        Path schemFilePath = Path.of("./plugins/WorldEdit/schematics/sougen.schem");
        SougenSchematic schematic;
        try {
            schematic = SougenSchematic.fromSchematicFile(schemFilePath);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to load schematic: " + schemFilePath, e);
            return;
        }

        BlockPosition position = new BlockPosition(120, 63, 23);
        World world = getServer().getWorld("lobby");
        if (world == null) {
            getLogger().warning("Not exists world: " + "lobby");
            return;
        }

        manager.addMap("test", schematic, world.getUID(), position);
    }

}
