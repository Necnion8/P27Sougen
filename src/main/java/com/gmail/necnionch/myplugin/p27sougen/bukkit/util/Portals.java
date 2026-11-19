package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.event.MVPortalEvent;

@SuppressWarnings("UnusedReturnValue")
public class Portals {

    private static final Multimap<Player, String> BLOCKED_NAMES = ArrayListMultimap.create();
    private static @Nullable Listener LISTENER;

    private Portals() {}

    public static boolean available() {
        return LISTENER != null;
    }

    public static boolean register(JavaPlugin plugin) {
        unregister();
        if (!Bukkit.getServer().getPluginManager().isPluginEnabled("Multiverse-Portals")) return false;

        LISTENER = new Listener() {
            @EventHandler
            public void onPortal(MVPortalEvent event) {
                Player player = event.getTeleportee();
                MVPortal portal = event.getSendingPortal();
                if (BLOCKED_NAMES.get(player).contains(portal.getName())) {
                    event.setCancelled(true);
                }
            }
        };
        Bukkit.getServer().getPluginManager().registerEvents(LISTENER, plugin);
        return true;
    }

    public static void unregister() {
        if (LISTENER != null) {
            HandlerList.unregisterAll(LISTENER);
            LISTENER = null;
        }
    }

    public static Multimap<Player, String> blockedNames() {
        return BLOCKED_NAMES;
    }


}
