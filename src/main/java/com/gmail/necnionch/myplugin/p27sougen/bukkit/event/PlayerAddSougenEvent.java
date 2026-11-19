package com.gmail.necnionch.myplugin.p27sougen.bukkit.event;

import com.gmail.necnionch.myplugin.p27sougen.bukkit.sougen.SougenMap;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlayerAddSougenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String mapId;
    private final @Nullable SougenMap map;

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }


    public PlayerAddSougenEvent(Player player, String mapId, @Nullable SougenMap map) {
        this.player = player;
        this.mapId = mapId;
        this.map = map;
    }

    public Player getPlayer() {
        return player;
    }

    public String getMapId() {
        return mapId;
    }

    public @Nullable SougenMap getMap() {
        return map;
    }

}
