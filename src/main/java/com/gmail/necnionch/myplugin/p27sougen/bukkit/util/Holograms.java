package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

@SuppressWarnings("UnusedReturnValue")
public class Holograms {

    private static boolean HAS_CLASS;

    static {
        HAS_CLASS = true;
        try {
            Class.forName("eu.decentsoftware.holograms.api.DHAPI");
        } catch (ClassNotFoundException e) {
            HAS_CLASS = false;
        }
    }

    private Holograms() {}


    public static boolean available() {
        return HAS_CLASS && Bukkit.getServer().getPluginManager().isPluginEnabled("DecentHolograms");
    }

    public static boolean showHologram(Player player, String hologramId) {
        if (!available()) return false;

        Hologram hologram = DHAPI.getHologram(hologramId);
        if (hologram == null) return false;

        hologram.removeHidePlayer(player);
        return true;
    }

    public static boolean hideHologram(Player player, String hologramId) {
        if (!available()) return false;

        Hologram hologram = DHAPI.getHologram(hologramId);
        if (hologram == null) return false;

        hologram.setHidePlayer(player);
        return true;
    }

}
