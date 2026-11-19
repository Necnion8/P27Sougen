package com.gmail.necnionch.myplugin.p27sougen.bukkit.config;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.BlockPosition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SougenConfig extends BukkitConfiguration {

    private @Nullable String world;
    private @Nullable BlockPosition center;
    private final Map<String, SougenMap.Config> maps = new HashMap<>();
    private final Set<String> hologramIds = new HashSet<>();
    private final Set<String> portalIds = new HashSet<>();

    public SougenConfig(Plugin plugin) {
        super(plugin);
    }

    @Override
    protected boolean onLoaded() {
        world = null;
        center = null;
        maps.clear();
        hologramIds.clear();
        portalIds.clear();

        Path mapsPath = dataFolder.resolve("maps");
        try {
            Files.createDirectories(mapsPath);
        } catch (IOException ignored) {
        }

        String tmp = config.getString("location", "0,60,0,lobby");
        String[] split = tmp.split(",", 4);
        int centerX = 0, centerY = 60, centerZ = 0;
        world = (4 <= split.length) ? split[3] : "lobby";
        if (3 <= split.length) {
            try {
                centerX = Integer.parseInt(split[0]);
                centerY = Integer.parseInt(split[1]);
                centerZ = Integer.parseInt(split[2]);
            } catch (NumberFormatException e) {
                log.severe("Invalid location coordinates: " + tmp);
            }
        }
        center =  new BlockPosition(centerX, centerY, centerZ);

        ConfigurationSection mapsSection = config.getConfigurationSection("maps");
        if (mapsSection != null) {
            for (String mapId : mapsSection.getKeys(false)) {
                ConfigurationSection section = mapsSection.getConfigurationSection(mapId);
                if (section == null) continue;

                String name = section.getString("name");
                List<String> holograms = section.getStringList("holograms");
                List<String> portals = section.getStringList("portals");
                String file = section.getString("file");
                if (file == null || file.isEmpty()) {
                    file = mapId + ".schem";
                }
                Path schematicFile = mapsPath.resolve(file);

                maps.put(mapId, new SougenMap.Config(mapId, name, schematicFile, holograms, portals));
                hologramIds.addAll(holograms);
                portalIds.addAll(portals);
            }
        }
        return true;
    }

    public @Nullable String getWorld() {
        return world;
    }

    public BlockPosition getCenter() {
        return center != null ? center : new BlockPosition(0, 60, 0);
    }

    public Map<String, SougenMap.Config> getMaps() {
        return Collections.unmodifiableMap(maps);
    }

    public Set<String> getAllHologramIds() {
        return Collections.unmodifiableSet(hologramIds);
    }

    public Set<String> getAllPortalIds() {
        return Collections.unmodifiableSet(portalIds);
    }

}
