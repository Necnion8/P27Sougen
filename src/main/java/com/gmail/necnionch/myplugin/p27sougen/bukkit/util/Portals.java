package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import com.gmail.necnionch.myplugin.bungeeportals2.bukkit.events.BungeePortalEvent;
import com.gmail.necnionch.myplugin.bungeeportals2.bukkit.portal.Portal;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.mvplugins.multiverse.portals.MVPortal;
import org.mvplugins.multiverse.portals.event.MVPortalEvent;

@SuppressWarnings("UnusedReturnValue")
public class Portals {

    private static final Multimap<Player, String> BLOCKED_NAMES = ArrayListMultimap.create();
    private static @Nullable Listener MVP_LISTENER;
    private static @Nullable Listener BP2_LISTENER;

    private Portals() {}

    public static boolean available() {
        return MVP_LISTENER != null || BP2_LISTENER != null;
    }

    public static boolean register(JavaPlugin plugin) {
        unregister();
        PluginManager mgr = Bukkit.getServer().getPluginManager();

        if (mgr.isPluginEnabled("Multiverse-Portals")) {
            MVP_LISTENER = new Listener() {
                @EventHandler
                public void onPortal(MVPortalEvent event) {
                    Player player = event.getTeleportee();
                    MVPortal portal = event.getSendingPortal();
                    if (BLOCKED_NAMES.get(player).contains(portal.getName())) {
                        event.setCancelled(true);
                    }
                }
            };
            mgr.registerEvents(MVP_LISTENER, plugin);
        }

        if (mgr.isPluginEnabled("BungeePortals2")) {
            BP2_LISTENER = new Listener() {
                @EventHandler
                public void onPortal(BungeePortalEvent event) {
                    Player player = event.getPlayer();
                    Portal portal = event.getPortal();
                    if (BLOCKED_NAMES.get(player).contains(portal.getName())) {
                        event.setCancelled(true);
                    }
                }
            };
            mgr.registerEvents(BP2_LISTENER, plugin);
        }

        return available();
    }

    public static void unregister() {
        if (MVP_LISTENER != null) {
            HandlerList.unregisterAll(MVP_LISTENER);
            MVP_LISTENER = null;
        }
        if (BP2_LISTENER != null) {
            HandlerList.unregisterAll(BP2_LISTENER);
            BP2_LISTENER = null;
        }
    }

    public static Multimap<Player, String> blockedNames() {
        return BLOCKED_NAMES;
    }

}
