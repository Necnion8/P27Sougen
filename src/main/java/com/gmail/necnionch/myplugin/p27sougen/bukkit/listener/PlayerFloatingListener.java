package com.gmail.necnionch.myplugin.p27sougen.bukkit.listener;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.event.PlayerEnterSougenEvent;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.event.PlayerLeaveSougenEvent;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenManager;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import com.gmail.necnionch.myplugin.p27sougen.bukkit.util.FloatingKickController;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

public class PlayerFloatingListener implements Listener {

    private final SougenManager sougen;
    private final FloatingKickController controller;
    private final Set<Player> acceptFloating = new HashSet<>();

    public static Runnable register(Plugin plugin, SougenManager sougen, FloatingKickController controller) {
        PlayerFloatingListener listener = new PlayerFloatingListener(sougen, controller);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, listener::onTime, 0, 20);
        return () -> {
            task.cancel();
            HandlerList.unregisterAll(listener);
            listener.acceptFloating.clear();
        };
    }

    private PlayerFloatingListener(SougenManager sougen, FloatingKickController controller) {
        this.sougen = sougen;
        this.controller = controller;
    }

    private void onTime() {
        for (Player player : acceptFloating) {
            //noinspection deprecation
            if (player.isOnGround() || player.getAllowFlight()) continue;
            controller.resetFlyingTicks(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnter(PlayerEnterSougenEvent event) {
        acceptFloating.add(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeave(PlayerLeaveSougenEvent event) {
        acceptFloating.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        acceptFloating.remove(event.getPlayer());
        SougenMap map = sougen.getPlayerMap(event.getPlayer());
        if (map != null && event.getPlayer().getWorld().getUID().equals(map.destWorldId())) {
            acceptFloating.add(event.getPlayer());
        }
    }

}
