package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.event.MVPortalEvent;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("UnusedReturnValue")
public class Portals {

    private static final Set<String> BLOCKED_NAMES = new HashSet<>();

    private Portals() {}

    public static boolean isHooked() {
        return Bukkit.getServer().getPluginManager().isPluginEnabled("Multiverse-Portals");
    }

    public static void setup(JavaPlugin plugin) {
        Bukkit.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPortal(MVPortalEvent event) {
                Player player = event.getTeleportee();
                MVPortal portal = event.getSendingPortal();
                if (BLOCKED_NAMES.contains(portal.getName())) {
                    event.setCancelled(true);
                    player.sendMessage("cancelled");
                }

            }
        }, plugin);

    }

    public static Set<String> blockedNames() {
        return BLOCKED_NAMES;
    }


}
