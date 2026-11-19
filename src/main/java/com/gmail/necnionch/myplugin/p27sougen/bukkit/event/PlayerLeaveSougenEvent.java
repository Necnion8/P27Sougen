package com.gmail.necnionch.myplugin.p27sougen.bukkit.event;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerLeaveSougenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final SougenMap map;

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }


    public PlayerLeaveSougenEvent(Player player, SougenMap map) {
        this.player = player;
        this.map = map;
    }

    public Player getPlayer() {
        return player;
    }

    public SougenMap getMap() {
        return map;
    }

}
