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

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("UnusedReturnValue")
public class Portals {

    private static final Multimap<Player, String> BLOCKED_NAMES = ArrayListMultimap.create();
    private static boolean HAS_LISTENER;

    private Portals() {}

    public static boolean available() {
        return HAS_LISTENER;
    }

    public static @Nullable Runnable register(JavaPlugin plugin) {
        PluginManager mgr = Bukkit.getServer().getPluginManager();
        Set<Listener> listeners = new HashSet<>();

        if (mgr.isPluginEnabled("Multiverse-Portals")) {
            listeners.add(new Listener() {
                @EventHandler
                public void onPortal(MVPortalEvent event) {
                    Player player = event.getTeleportee();
                    MVPortal portal = event.getSendingPortal();
                    if (BLOCKED_NAMES.get(player).contains(portal.getName())) {
                        event.setCancelled(true);
                    }
                }
            });
        }

        if (mgr.isPluginEnabled("BungeePortals2")) {
            listeners.add(new Listener() {
                @EventHandler
                public void onPortal(BungeePortalEvent event) {
                    Player player = event.getPlayer();
                    Portal portal = event.getPortal();
                    if (BLOCKED_NAMES.get(player).contains(portal.getName())) {
                        event.setCancelled(true);
                    }
                }
            });
        }

        HAS_LISTENER = false;
        if (listeners.isEmpty()) return null;

        HAS_LISTENER = true;
        listeners.forEach(listener -> mgr.registerEvents(listener, plugin));
        return () -> listeners.forEach(HandlerList::unregisterAll);
    }

    public static Multimap<Player, String> blockedNames() {
        return BLOCKED_NAMES;
    }

}
